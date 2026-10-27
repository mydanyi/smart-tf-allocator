package io.github.mydanyi.armadatf.storage

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import io.github.mydanyi.armadatf.domain.Identifiers
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Failure surface of [HandoffWriter]. The [code] is a stable, log-safe token;
 * the message never carries key material.
 */
class HandoffException(val code: String, detail: String? = null) : Exception(detail ?: code)

/**
 * Persists the verified partition handoff from the main app.
 *
 * The writer only ever touches the app-specific external files directory that
 * the platform itself hands out for the card whose filesystem UUID matches the
 * payload's `public_fs_uuid`. It uses plain Android/storage APIs only — no
 * privileged binder, no root, no direct block-device access.
 *
 * Ordering is deliberate: the canonical payload is written to a private
 * internal *pending* file first, so a TF write that fails (missing volume, IO
 * error) can be retried later without repartitioning the card. Nothing marks
 * the Linux side "ready" until [write]/[retry] returns normally.
 */
class HandoffWriter(context: Context) {

    private val appContext: Context = context.applicationContext ?: context

    /**
     * Validates [handoff], records the canonical payload as the private pending
     * copy and then publishes it to the matching public card.
     *
     * @throws HandoffException `handoff_invalid` for a malformed payload,
     * `public_volume_missing` when no mounted, writeable card matches
     * `public_fs_uuid`, and `handoff_write_failed` for any IO/verification
     * failure. A failure leaves the pending copy in place for [retry].
     */
    fun write(handoff: JSONObject): File {
        val validated = validate(handoff)
        val payload = encode(validated.canonical)
        // Persist the private pending copy BEFORE attempting the TF write so a
        // later failure is retryable without repartitioning.
        atomicWrite(pendingFile(), payload)
        return publish(payload, validated.publicFsUuid)
    }

    /**
     * Re-publishes the stored private pending payload to the matching public
     * card. Never repartitions and never re-validates a destination that is not
     * identified by the pending payload's own `public_fs_uuid`.
     *
     * @throws HandoffException `handoff_invalid` when no valid pending copy
     * exists, `public_volume_missing`/`handoff_write_failed` as in [write].
     */
    fun retry(): File {
        val bytes = readPendingBytes()
            ?: throw HandoffException(ERR_HANDOFF_INVALID, "no pending handoff to retry")
        val validated = parsePayload(bytes)
        return publish(bytes, validated.publicFsUuid)
    }

    /**
     * Deletes only our own app-private *pending* copy, leaving the published
     * file on the card untouched.
     *
     * Used for the verified all-Android completion, where a pending payload
     * from an earlier run must never be re-published as a Linux handoff. A
     * missing file already counts as cleared; a failed delete returns `false`
     * so the caller can log it without pretending a Linux handoff is valid.
     */
    fun clearPending(): Boolean {
        val file = pendingFile()
        if (!file.exists()) return true
        return try {
            file.delete()
        } catch (e: SecurityException) {
            false
        }
    }

    /**
     * Returns a copy of the validated private pending payload, or `null` when no
     * pending file exists. A corrupted or oversized pending file throws rather
     * than masquerading as an empty ready payload.
     */
    fun pending(): JSONObject? {
        val bytes = readPendingBytes() ?: return null
        val validated = parsePayload(bytes)
        return JSONObject(validated.canonical.toString())
    }

    // --- publishing ----------------------------------------------------------

    private fun publish(payload: ByteArray, publicFsUuid: String): File {
        val dir = findPublicVolumeDir(publicFsUuid)
        val target = File(dir, FILE_NAME)
        atomicWrite(target, payload)

        // Verify the atomic write landed intact and the destination still maps
        // to the expected public volume before reporting success.
        val readBack = try {
            target.readBytes()
        } catch (e: IOException) {
            throw HandoffException(ERR_WRITE_FAILED, "cannot verify written handoff")
        }
        if (!readBack.contentEquals(payload)) {
            throw HandoffException(ERR_WRITE_FAILED, "written handoff does not match payload")
        }
        if (!volumeUuidMatches(dir, publicFsUuid)) {
            throw HandoffException(ERR_WRITE_FAILED, "public volume changed during write")
        }
        return target
    }

    /**
     * Locates the app-specific files directory on the card whose volume UUID
     * equals [publicFsUuid] (case-insensitive), requiring the directory to be
     * mounted and writeable. Never assumes an index and never falls back to the
     * primary directory, root or `/mnt/media_rw`.
     */
    private fun findPublicVolumeDir(publicFsUuid: String): File {
        val storageManager = appContext.getSystemService(StorageManager::class.java)
            ?: throw HandoffException(ERR_PUBLIC_VOLUME_MISSING, "storage service unavailable")
        val candidates = appContext.getExternalFilesDirs(null) ?: emptyArray()
        for (dir in candidates) {
            if (dir == null) continue
            if (Environment.getExternalStorageState(dir) != Environment.MEDIA_MOUNTED) continue
            if (!dir.canWrite()) continue
            if (!isAppFilesDir(dir)) continue
            val volume = storageManager.getStorageVolume(dir) ?: continue
            val uuid = volume.uuid
            if (uuid.isNullOrEmpty()) continue
            if (uuid.equals(publicFsUuid, ignoreCase = true)) return dir
        }
        throw HandoffException(
            ERR_PUBLIC_VOLUME_MISSING,
            "no mounted, writeable public volume matches public_fs_uuid",
        )
    }

    private fun volumeUuidMatches(dir: File, publicFsUuid: String): Boolean {
        val storageManager = appContext.getSystemService(StorageManager::class.java) ?: return false
        val uuid = storageManager.getStorageVolume(dir)?.uuid ?: return false
        return uuid.isNotEmpty() && uuid.equals(publicFsUuid, ignoreCase = true)
    }

    /** The platform-provided directory must be exactly `.../Android/data/<pkg>/files`. */
    private fun isAppFilesDir(dir: File): Boolean {
        val expected = "/Android/data/${appContext.packageName}/files"
        return dir.path.replace('\\', '/').endsWith(expected)
    }

    // --- pending file --------------------------------------------------------

    private fun pendingFile(): File = File(appContext.filesDir, PENDING_FILE_NAME)

    private fun readPendingBytes(): ByteArray? {
        val file = pendingFile()
        if (!file.exists()) return null
        if (file.length() > MAX_BYTES) {
            throw HandoffException(ERR_HANDOFF_INVALID, "pending handoff exceeds size limit")
        }
        return try {
            file.readBytes()
        } catch (e: IOException) {
            throw HandoffException(ERR_HANDOFF_INVALID, "pending handoff unreadable")
        }
    }

    private fun parsePayload(bytes: ByteArray): Validated {
        val text = try {
            String(bytes, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            throw HandoffException(ERR_HANDOFF_INVALID, "pending handoff unreadable")
        }
        val json = try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw HandoffException(ERR_HANDOFF_INVALID, "pending handoff malformed")
        }
        return validate(json)
    }

    // --- atomic write --------------------------------------------------------

    /**
     * Writes [payload] to [target] through a sibling `*.tmp` file: write, flush,
     * `fd.sync()`, then rename over the target. On any failure only our own
     * temporary file is removed; the target and the pending copy are untouched.
     */
    private fun atomicWrite(target: File, payload: ByteArray) {
        val dir = target.parentFile
            ?: throw HandoffException(ERR_WRITE_FAILED, "target has no parent directory")
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            throw HandoffException(ERR_WRITE_FAILED, "cannot create target directory")
        }
        val tmp = File(dir, target.name + TMP_SUFFIX)
        try {
            FileOutputStream(tmp).use { out ->
                out.write(payload)
                out.flush()
                out.fd.sync()
            }
        } catch (e: IOException) {
            runCatching { if (tmp.exists()) tmp.delete() }
            throw HandoffException(ERR_WRITE_FAILED, "cannot stage ${target.name}")
        }
        if (!tmp.renameTo(target)) {
            runCatching { if (tmp.exists()) tmp.delete() }
            throw HandoffException(ERR_WRITE_FAILED, "cannot commit ${target.name}")
        }
    }

    // --- validation ----------------------------------------------------------

    private class Validated(val canonical: JSONObject, val publicFsUuid: String)

    /**
     * Validates a handoff against one of the three completed-storage schemas and
     * returns the canonical, bounded payload. Extra keys are dropped by
     * rebuilding the object; missing or malformed required keys raise
     * `handoff_invalid`.
     *
     * Schema 1 is the unchanged mixed layout: a Linux ratio in 10..90 plus
     * required non-null private partition GUID and FS UUID. Schema 2 is the
     * all-Linux layout only: `allocation_mode` must be `linux_only`, the ratio
     * must be exactly 100, and both private identifiers must be present and
     * explicitly `null`. A schema 2 payload with any other mode, ratio or
     * private value is rejected rather than reinterpreted.
     *
     * Schema 3 is the volume-identity fallback used when Android cannot read a
     * CID: `identity_mode` must be exactly `volume`, `card_cid` must be
     * explicitly `null`, and `allocation_mode` must be `mixed` (ratio 10..90,
     * real private identifiers) or `linux_only` (ratio 100, explicit null
     * private identifiers). All common identity, capacity and public-volume
     * checks are identical to the legacy schemas. Schemas 1 and 2 still require
     * a real CID, so a null CID can never be accepted by an older schema.
     */
    private fun validate(handoff: JSONObject): Validated {
        val magic = stringField(handoff, "magic") ?: invalid("magic")
        if (magic != MAGIC) invalid("magic")

        val schemaValue = handoff.opt("schema_version")
        if (schemaValue !is Number) invalid("schema_version")
        val schema = schemaValue.toInt()
        if (schemaValue.toDouble() != schema.toDouble()) invalid("schema_version")
        if (schema != SCHEMA_VERSION &&
            schema != SCHEMA_VERSION_LINUX_ONLY &&
            schema != SCHEMA_VERSION_VOLUME
        ) {
            invalid("schema_version")
        }

        val volumeIdentity = schema == SCHEMA_VERSION_VOLUME
        val volumeMode = if (volumeIdentity) {
            val identity = stringField(handoff, "identity_mode") ?: invalid("identity_mode")
            if (identity != MODE_VOLUME) invalid("identity_mode")
            val mode = stringField(handoff, "allocation_mode") ?: invalid("allocation_mode")
            if (mode != MODE_MIXED && mode != MODE_LINUX_ONLY) invalid("allocation_mode")
            mode
        } else {
            null
        }

        if (schema == SCHEMA_VERSION_LINUX_ONLY) {
            val mode = stringField(handoff, "allocation_mode") ?: invalid("allocation_mode")
            if (mode != MODE_LINUX_ONLY) invalid("allocation_mode")
        }

        val cardCid = if (volumeIdentity) {
            requireExplicitNull(handoff, "card_cid")
            null
        } else {
            val rawCid = stringField(handoff, "card_cid") ?: invalid("card_cid")
            if (!Identifiers.isValidCid(rawCid)) invalid("card_cid")
            Identifiers.normalizeCid(rawCid)
        }

        val diskValue = handoff.opt("disk_bytes")
        if (diskValue !is Number) invalid("disk_bytes")
        val diskBytes = diskValue.toLong()
        if (diskBytes <= 0L) invalid("disk_bytes")

        val ratioValue = handoff.opt("linux_ratio")
        if (ratioValue !is Number) invalid("linux_ratio")
        val ratio = ratioValue.toInt()
        if (ratioValue.toDouble() != ratio.toDouble()) invalid("linux_ratio")

        // Kept raw: the public FAT filesystem id is matched verbatim against the
        // platform volume UUID.
        val publicFsUuid = stringField(handoff, "public_fs_uuid") ?: invalid("public_fs_uuid")
        if (publicFsUuid.isEmpty()) invalid("public_fs_uuid")

        if (!handoff.has("public_partition_guid")) invalid("public_partition_guid")
        val publicGuid = optionalUuid(handoff, "public_partition_guid")

        val canonical = JSONObject()
            .put("magic", MAGIC)
            .put("schema_version", schema)
        if (volumeIdentity) {
            canonical
                .put("identity_mode", MODE_VOLUME)
                .put("allocation_mode", volumeMode)
        } else if (schema == SCHEMA_VERSION_LINUX_ONLY) {
            canonical.put("allocation_mode", MODE_LINUX_ONLY)
        }
        canonical
            .put("card_cid", if (volumeIdentity) JSONObject.NULL else cardCid)
            .put("disk_bytes", diskBytes)
            .put("linux_ratio", ratio)
            .put("public_fs_uuid", publicFsUuid)
            .put("public_partition_guid", publicGuid ?: JSONObject.NULL)

        val linuxOnlyLayout = if (volumeIdentity) {
            volumeMode == MODE_LINUX_ONLY
        } else {
            schema == SCHEMA_VERSION_LINUX_ONLY
        }
        if (linuxOnlyLayout) {
            if (ratio != LINUX_ONLY_RATIO) invalid("linux_ratio")
            requireExplicitNull(handoff, "private_partition_guid")
            requireExplicitNull(handoff, "private_fs_uuid")
            canonical.put("private_partition_guid", JSONObject.NULL)
            canonical.put("private_fs_uuid", JSONObject.NULL)
        } else {
            if (ratio < MIN_RATIO || ratio > MAX_RATIO) invalid("linux_ratio")
            canonical.put("private_partition_guid", requiredUuid(handoff, "private_partition_guid"))
            canonical.put("private_fs_uuid", requiredUuid(handoff, "private_fs_uuid"))
        }

        return Validated(canonical, publicFsUuid)
    }

    /** A required, canonical UUID field. */
    private fun requiredUuid(json: JSONObject, key: String): String =
        Identifiers.normalizeUuid(stringField(json, key) ?: invalid(key)) ?: invalid(key)

    /** A UUID field that may be an explicit `null` but never garbage. */
    private fun optionalUuid(json: JSONObject, key: String): String? {
        val value = json.opt(key)
        return when {
            value == null || value == JSONObject.NULL -> null
            value is String -> Identifiers.normalizeUuid(value) ?: invalid(key)
            else -> invalid(key)
        }
    }

    /** The key must exist and be exactly `null`; an absent key is not a null. */
    private fun requireExplicitNull(json: JSONObject, key: String) {
        if (!json.has(key) || !json.isNull(key)) invalid(key)
    }

    private fun encode(canonical: JSONObject): ByteArray {
        val bytes = canonical.toString().toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > MAX_BYTES) invalid("size")
        return bytes
    }

    private fun stringField(json: JSONObject, key: String): String? {
        if (!json.has(key) || json.isNull(key)) return null
        return json.opt(key) as? String
    }

    /** Raises a non-sensitive `handoff_invalid` for the named (non-secret) field. */
    private fun invalid(field: String): Nothing =
        throw HandoffException(ERR_HANDOFF_INVALID, "invalid handoff field: $field")

    private companion object {
        const val MAGIC = "armada-tf-handoff"

        /** Mixed handoff schema, unchanged. */
        const val SCHEMA_VERSION = 1

        /** All-Linux handoff schema with explicit null private identifiers. */
        const val SCHEMA_VERSION_LINUX_ONLY = 2

        /** Volume-identity fallback: no CID, the card is identified by volumes. */
        const val SCHEMA_VERSION_VOLUME = 3
        const val MODE_VOLUME = "volume"
        const val MODE_LINUX_ONLY = "linux_only"
        const val MODE_MIXED = "mixed"
        const val LINUX_ONLY_RATIO = 100

        const val FILE_NAME = "armada-tf-handoff.json"
        const val PENDING_FILE_NAME = "armada-tf-handoff.pending.json"
        const val TMP_SUFFIX = ".tmp"

        /** Bounded payload size accepted for both pending and published JSON. */
        const val MAX_BYTES = 16 * 1024

        const val MIN_RATIO = 10
        const val MAX_RATIO = 90

        const val ERR_PUBLIC_VOLUME_MISSING = "public_volume_missing"
        const val ERR_HANDOFF_INVALID = "handoff_invalid"
        const val ERR_WRITE_FAILED = "handoff_write_failed"
    }
}
