package io.github.mydanyi.armadatf.storage

import android.os.Binder
import android.os.IBinder
import io.github.mydanyi.armadatf.domain.AllocationMode
import io.github.mydanyi.armadatf.domain.AllocationPlan
import io.github.mydanyi.armadatf.domain.AllocationPolicy
import io.github.mydanyi.armadatf.domain.DiskSnapshot
import io.github.mydanyi.armadatf.domain.Identifiers
import io.github.mydanyi.armadatf.domain.PlatformPaths
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException

/**
 * Thrown when the privileged `mount` binder service cannot be reached or a
 * reflective lookup of a hidden platform member fails.
 */
class StorageUnavailableException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * Thrown when the physical card behind an [AllocationPlan] is no longer the
 * same card (different id, size, CID or SD/adoptable flags).
 */
class CardChangedException(message: String) : IllegalStateException(message)

/**
 * Privileged-process-only wrapper around the platform `mount` binder service.
 *
 * The hidden platform members `android.os.ServiceManager` and the binder stub
 * of the storage manager are not part of the public SDK, so they are resolved
 * reflectively by class name instead of being imported. This class never uses
 * a Context, ContentResolver, broadcast receiver, Shizuku client or any UI.
 *
 * A missing binder or a failed reflective lookup throws an explicit exception
 * rather than degrading to a false, empty result.
 */
class NativeStorage {

    /**
     * One logical storage volume as reported by the platform.
     *
     * [diskId] is `null` for volumes not backed by a physical disk.
     *
     * [fsUuid] preserves the platform value with surrounding whitespace trimmed.
     * Public FAT volumes report short ids such as `1A2B-3C4D` that must NOT be
     * canonicalised to a 32/36-hex UUID, so this field is stored verbatim.
     *
     * [partGuid] is the canonical dashed, lowercase UUID that adopted private
     * partitions report, and is `null` when the platform reports nothing usable.
     */
    data class VolumeSnapshot(
        val id: String,
        val diskId: String?,
        val type: Int,
        val state: Int,
        val fsUuid: String?,
        val partGuid: String?,
        val path: String?,
    )

    /**
     * Returns a snapshot of every physical disk the platform knows about.
     *
     * Only disks whose `id` parses as `disk:<major>,<minor>` and whose SD flag
     * is set are marked [DiskSnapshot.isSd]. The sysfs `device/type` is always
     * authoritative when it is readable; a known non-SD type is rejected. On
     * firmware that hides those attributes, the SD flag is trusted only together
     * with a valid native removable MMC `sysPath`. Non-SD disks are still listed
     * with `isSd = false`.
     */
    fun disks(): List<DiskSnapshot> {
        val manager = storageManager()
        val raw = invoke(manager, "getDisks")
        val infos = raw as? Array<*>
            ?: throw StorageUnavailableException(
                "getDisks() returned ${raw?.javaClass?.name ?: "null"} instead of an array",
            )
        return infos.map { info ->
            toDiskSnapshot(
                info ?: throw StorageUnavailableException("getDisks() returned a null entry"),
            )
        }
    }

    /** Returns a snapshot of every volume the platform knows about. */
    fun volumes(): List<VolumeSnapshot> {
        val manager = storageManager()
        val raw = invoke(manager, "getVolumes", 0)
        val infos = raw as? Array<*>
            ?: throw StorageUnavailableException(
                "getVolumes(0) returned ${raw?.javaClass?.name ?: "null"} instead of an array",
            )
        return infos.map { info ->
            toVolumeSnapshot(
                info ?: throw StorageUnavailableException("getVolumes(0) returned a null entry"),
            )
        }
    }

    /**
     * Asks the platform to format the card described by [plan] for its
     * [AllocationMode]: all-Android becomes one adopted private volume, all-Linux
     * becomes one public volume, and the mixed range keeps the public + private
     * split with [AllocationPlan.linuxPercent] percent reserved for the public
     * share.
     *
     * The disk id and the percentage are validated against [plan] first, then
     * the disk is re-fetched from the platform and re-checked with
     * [AllocationPolicy.matches] so that a card swapped in after the plan was
     * prepared cannot be partitioned: the fresh platform state must still match
     * the exact id, size, CID (when known) and platform path the plan was
     * captured for.
     *
     * When the plan uses the volume-identity fallback (no readable CID),
     * [expectedVolumes] must be the capture taken at prepare time. The fresh
     * public/private identities are compared immediately before the native call;
     * a mismatch is fatal. Mount state, mount paths and emulated volumes are
     * deliberately ignored by that comparison.
     *
     * The native call is made with [Binder.clearCallingIdentity] so the
     * caller's identity is not applied to the privileged partition operation.
     */
    fun partition(plan: AllocationPlan, expectedVolumes: List<VolumeSnapshot>? = null) {
        require(DISK_ID_REGEX.matches(plan.diskId)) { "invalid disk id '${plan.diskId}'" }
        val call = AllocationPolicy.partitionCall(plan)
        val disk = disks().firstOrNull { it.id == plan.diskId }
            ?: throw CardChangedException("disk '${plan.diskId}' is no longer present")
        if (!AllocationPolicy.matches(plan, disk)) {
            throw CardChangedException("disk '${plan.diskId}' no longer matches the allocation plan")
        }
        if (plan.cardCid.isEmpty()) {
            val expected = expectedVolumes
                ?: throw CardChangedException("volume identity was not captured before partition")
            if (!VolumeIdentity.matches(expected, volumes(), plan.diskId)) {
                throw CardChangedException(
                    "volumes on '${plan.diskId}' no longer match the prepared layout",
                )
            }
        }
        val manager = storageManager()
        val identity = Binder.clearCallingIdentity()
        try {
            val ratio = call.ratio
            if (ratio != null) {
                invoke(manager, call.method, call.diskId, ratio)
            } else {
                invoke(manager, call.method, call.diskId)
            }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    // --- platform access -----------------------------------------------------

    /**
     * Resolves the `mount` binder and its storage-manager interface by name.
     *
     * Both types are hidden platform classes, so this uses `Class.forName`
     * rather than a typed import. Any failure is surfaced as
     * [StorageUnavailableException] rather than a silent `null`.
     */
    private fun storageManager(): Any {
        val binder = try {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getMethod("getService", String::class.java)
            getService.invoke(null, MOUNT_SERVICE) as? IBinder
        } catch (e: ClassNotFoundException) {
            throw StorageUnavailableException("android.os.ServiceManager is not available", e)
        } catch (e: NoSuchMethodException) {
            throw StorageUnavailableException("ServiceManager.getService is not available", e)
        } catch (e: InvocationTargetException) {
            throw StorageUnavailableException(
                "ServiceManager.getService failed",
                e.cause ?: e,
            )
        } catch (e: IllegalAccessException) {
            throw StorageUnavailableException("ServiceManager.getService is not accessible", e)
        } ?: throw StorageUnavailableException("mount service is not available to this process")

        val stub = try {
            Class.forName("android.os.storage.IStorageManager\$Stub")
        } catch (e: ClassNotFoundException) {
            throw StorageUnavailableException(
                "android.os.storage.IStorageManager is not available",
                e,
            )
        }
        return try {
            val asInterface = stub.getMethod("asInterface", IBinder::class.java)
            asInterface.invoke(null, binder)
                ?: throw StorageUnavailableException("mount service interface could not be created")
        } catch (e: NoSuchMethodException) {
            throw StorageUnavailableException("IStorageManager.Stub.asInterface is not available", e)
        } catch (e: InvocationTargetException) {
            val cause = e.cause
            if (cause is RuntimeException) throw cause
            throw StorageUnavailableException("IStorageManager.Stub.asInterface failed", cause ?: e)
        } catch (e: IllegalAccessException) {
            throw StorageUnavailableException("IStorageManager.Stub.asInterface is not accessible", e)
        }
    }

    /**
     * Invokes a method on the storage-manager interface.
     *
     * The method is resolved from the public (hidden) `IStorageManager`
     * interface class, not from `manager.javaClass`: the AIDL-generated proxy
     * that [storageManager] returns is a non-public class, so invoking a method
     * declared on it would raise [IllegalAccessException]. Resolving through
     * the public interface keeps the reflective call legal.
     *
     * An [InvocationTargetException] is unwrapped: the platform's
     * `SecurityException` (a [RuntimeException]) is rethrown as-is so callers
     * can diagnose a permission failure directly instead of digging through a
     * reflective wrapper.
     */
    private fun invoke(manager: Any, name: String, vararg args: Any): Any? {
        val parameterTypes = args.map { primitiveType(it) }.toTypedArray()
        val method = try {
            storageManagerInterface().getMethod(name, *parameterTypes)
        } catch (e: NoSuchMethodException) {
            throw StorageUnavailableException("mount service method '$name' is not available", e)
        }
        return try {
            method.invoke(manager, *args)
        } catch (e: InvocationTargetException) {
            val cause = e.cause
            if (cause is RuntimeException) throw cause
            throw StorageUnavailableException("mount service method '$name' failed", cause ?: e)
        } catch (e: ReflectiveOperationException) {
            throw StorageUnavailableException("mount service method '$name' failed", e)
        }
    }

    /** The public (hidden) storage-manager interface class. */
    private fun storageManagerInterface(): Class<*> = try {
        Class.forName("android.os.storage.IStorageManager")
    } catch (e: ClassNotFoundException) {
        throw StorageUnavailableException("android.os.storage.IStorageManager is not available", e)
    }

    private fun primitiveType(value: Any): Class<*> = when (value) {
        is Int -> Int::class.javaPrimitiveType!!
        is Long -> Long::class.javaPrimitiveType!!
        is Boolean -> Boolean::class.javaPrimitiveType!!
        else -> value.javaClass
    }

    // --- snapshot mapping ----------------------------------------------------

    /**
     * Maps one platform disk object to a [DiskSnapshot].
     *
     * The platform id and the SD flag are mandatory. A readable sysfs
     * `device/type` always decides the SD/non-SD question; when it is
     * unreadable (restricted sysfs), the SD flag is trusted only if the
     * platform also reports a valid removable MMC `sysPath`. The CID is read
     * unchanged from sysfs and is never fabricated: an unreadable CID stays
     * empty, and only a valid platform path can then carry the identity.
     */
    private fun toDiskSnapshot(info: Any): DiskSnapshot {
        val id = stringField(info, "id")
        val flags = intField(info, "flags")
        val sizeBytes = longField(info, "size")
        val majorMinor = parseDiskId(id)
        val platformPath = PlatformPaths.normalize(optionalStringField(info, "sysPath"))
        val sysfs = majorMinor?.let { sysfsType(it) }
        val sdFlagged = majorMinor != null && (flags and FLAG_SD) != 0
        val isSd = sdFlagged && when (sysfs) {
            null -> PlatformPaths.isSdMmcPath(platformPath)
            else -> sysfs == "SD"
        }
        val isAdoptable = (flags and FLAG_ADOPTABLE) != 0
        val cid = if (majorMinor == null) "" else readCid(majorMinor)
        return DiskSnapshot(
            id = id,
            sizeBytes = sizeBytes,
            cid = cid,
            isSd = isSd,
            isAdoptable = isAdoptable,
            platformPath = platformPath,
        )
    }

    /**
     * Maps the public fields of one platform volume object to a
     * [VolumeSnapshot].
     *
     * Public FAT filesystem ids are preserved verbatim (only trimmed) because
     * they are short values like `1A2B-3C4D`; only [VolumeSnapshot.partGuid]
     * is canonicalised to a dashed 32/36-hex UUID. Visible for the host-side
     * mapping test, which feeds a fake object with public fields and never
     * touches a real binder.
     */
    internal fun toVolumeSnapshot(info: Any): VolumeSnapshot {
        val id = stringField(info, "id")
        val disk = fieldValue(info, "disk")
        val diskId = disk?.let { stringField(it, "id") }
        return VolumeSnapshot(
            id = id,
            diskId = diskId,
            type = intField(info, "type"),
            state = intField(info, "state"),
            fsUuid = stringFieldOrNull(info, "fsUuid")?.trim(),
            partGuid = Identifiers.normalizeUuid(stringFieldOrNull(info, "partGuid")),
            path = stringFieldOrNull(info, "path"),
        )
    }

    // --- sysfs helpers -------------------------------------------------------

    private fun sysfsType(majorMinor: Pair<Int, Int>): String? {
        val path = "/sys/dev/block/${majorMinor.first}:${majorMinor.second}/device/type"
        return try {
            File(path).readText().trim()
        } catch (e: IOException) {
            null
        }
    }

    /**
     * Reads the SD card CID from sysfs. An inaccessible or malformed CID yields
     * the empty string on this read-only listing; the value is never invented.
     * [AllocationPolicy.plan] then either uses the platform MMC path as the
     * fallback identity or refuses the disk when there is no valid path.
     */
    private fun readCid(majorMinor: Pair<Int, Int>): String {
        val path = "/sys/dev/block/${majorMinor.first}:${majorMinor.second}/device/cid"
        val raw = try {
            File(path).readText().trim()
        } catch (e: IOException) {
            return ""
        }
        return if (Identifiers.isValidCid(raw)) raw.lowercase() else ""
    }

    // --- reflection helpers --------------------------------------------------

    private fun fieldValue(target: Any, name: String): Any? {
        val field = try {
            target.javaClass.getField(name)
        } catch (e: NoSuchFieldException) {
            throw StorageUnavailableException(
                "$name is not a public field of ${target.javaClass.name}",
                e,
            )
        }
        return try {
            field.get(target)
        } catch (e: IllegalAccessException) {
            throw StorageUnavailableException(
                "$name could not be read from ${target.javaClass.name}",
                e,
            )
        }
    }

    private fun stringField(target: Any, name: String): String =
        fieldValue(target, name) as? String
            ?: throw StorageUnavailableException("$name of ${target.javaClass.name} was not a String")

    private fun stringFieldOrNull(target: Any, name: String): String? =
        fieldValue(target, name) as? String

    /**
     * Reads an optional public field, returning `null` both when the field is
     * absent and when it holds something that is not a String. Used for
     * `DiskInfo.sysPath`, which older platform objects do not expose; a missing
     * optional field must not fail the whole listing.
     */
    private fun optionalStringField(target: Any, name: String): String? = try {
        target.javaClass.getField(name).get(target) as? String
    } catch (e: NoSuchFieldException) {
        null
    } catch (e: IllegalAccessException) {
        throw StorageUnavailableException(
            "$name could not be read from ${target.javaClass.name}",
            e,
        )
    }

    private fun intField(target: Any, name: String): Int =
        (fieldValue(target, name) as? Number)?.toInt()
            ?: throw StorageUnavailableException("$name of ${target.javaClass.name} was not numeric")

    private fun longField(target: Any, name: String): Long =
        (fieldValue(target, name) as? Number)?.toLong()
            ?: throw StorageUnavailableException("$name of ${target.javaClass.name} was not numeric")

    private fun parseDiskId(id: String): Pair<Int, Int>? {
        val match = DISK_ID_REGEX.matchEntire(id) ?: return null
        val major = match.groupValues[1].toIntOrNull() ?: return null
        val minor = match.groupValues[2].toIntOrNull() ?: return null
        return major to minor
    }

    private companion object {
        const val MOUNT_SERVICE = "mount"

        /** `DiskInfo.FLAG_ADOPTABLE` (bit 0). */
        const val FLAG_ADOPTABLE = 1

        /** `DiskInfo.FLAG_SD` (bit 2, value 4). */
        const val FLAG_SD = 4

        val DISK_ID_REGEX = Regex("^disk:(\\d+),(\\d+)$")
    }
}

/**
 * Pure identity comparison for the volume-identity fallback.
 *
 * When Android cannot read the card CID, the prepared plan is bound to the
 * current public/private volumes instead. Only identity fields are compared —
 * volume id, volume type, filesystem UUID and partition GUID — while mount
 * state, mount path and emulated volumes are ignored, so a remount does not look
 * like a different card. Volumes are sorted before comparison so platform
 * enumeration order cannot matter.
 */
internal object VolumeIdentity {

    data class Identity(
        val id: String,
        val type: Int,
        val fsUuid: String?,
        val partGuid: String?,
    )

    fun identities(diskId: String, volumes: List<NativeStorage.VolumeSnapshot>): List<Identity> =
        volumes
            .filter { it.diskId == diskId }
            .filter { it.type == TYPE_PUBLIC || it.type == TYPE_PRIVATE }
            .map { volume ->
                Identity(
                    id = volume.id,
                    type = volume.type,
                    fsUuid = volume.fsUuid?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
                    partGuid = volume.partGuid,
                )
            }
            .sortedWith(compareBy<Identity>({ it.id }, { it.type }))

    /** True when the fresh volumes still carry exactly the captured identities. */
    fun matches(
        expected: List<NativeStorage.VolumeSnapshot>,
        fresh: List<NativeStorage.VolumeSnapshot>,
        diskId: String,
    ): Boolean = identities(diskId, expected) == identities(diskId, fresh)

    /** `VolumeInfo.TYPE_PUBLIC`. */
    private const val TYPE_PUBLIC = 0

    /** `VolumeInfo.TYPE_PRIVATE`. */
    private const val TYPE_PRIVATE = 1
}

/**
 * Outcome of one pure layout check.
 *
 * [Verified] carries the exact volumes the handoff may be built from, so no
 * later re-read can mix in a different volume.
 */
internal sealed class VolumeLayoutCheck {
    data class Verified(
        val publicVolume: NativeStorage.VolumeSnapshot?,
        val privateVolume: NativeStorage.VolumeSnapshot?,
    ) : VolumeLayoutCheck()

    /** The layout is not ready yet; [reason] is a stable, log-safe token. */
    data class Waiting(val reason: String) : VolumeLayoutCheck()
}

/**
 * Pure verification predicate for the freshly created card layout.
 *
 * Only public (type 0) and private (type 1) volumes on the card are considered;
 * emulated volumes (type 2) are ignored. Counts include unmounted volumes, so a
 * stale sibling left over from the previous layout can never be hidden behind
 * one mounted volume. The known GUID/UUID sets are the pre-partition capture,
 * used to prove the new identifiers are really new.
 */
internal object VolumeLayoutVerifier {

    fun check(
        mode: AllocationMode,
        volumes: List<NativeStorage.VolumeSnapshot>,
        knownPrivateGuids: Set<String>,
        knownPublicFsUuids: Set<String>,
    ): VolumeLayoutCheck {
        val publicVolumes = volumes.filter { it.type == TYPE_PUBLIC }
        val privateVolumes = volumes.filter { it.type == TYPE_PRIVATE }
        return when (mode) {
            AllocationMode.ANDROID_ONLY ->
                checkAndroidOnly(publicVolumes, privateVolumes, knownPrivateGuids)
            AllocationMode.LINUX_ONLY ->
                checkLinuxOnly(publicVolumes, privateVolumes, knownPublicFsUuids)
            AllocationMode.MIXED ->
                checkMixed(publicVolumes, privateVolumes, knownPrivateGuids)
        }
    }

    /**
     * Exactly one mounted private volume, no public volume, a canonical private
     * FS UUID and a brand-new private GUID.
     *
     * The endpoint still has to prove the same things the mixed layout proves:
     * a formatted-but-unmounted volume is not ready yet, and a non-canonical
     * FS UUID cannot be the adopted private filesystem.
     */
    private fun checkAndroidOnly(
        publicVolumes: List<NativeStorage.VolumeSnapshot>,
        privateVolumes: List<NativeStorage.VolumeSnapshot>,
        knownPrivateGuids: Set<String>,
    ): VolumeLayoutCheck {
        if (publicVolumes.isNotEmpty()) return VolumeLayoutCheck.Waiting("public_volume_present")
        if (privateVolumes.size != 1) return VolumeLayoutCheck.Waiting("private_volume_count")
        val privateVolume = privateVolumes.single()
        if (privateVolume.state != STATE_MOUNTED) {
            return VolumeLayoutCheck.Waiting("private_volume_not_mounted")
        }
        if (Identifiers.normalizeUuid(privateVolume.fsUuid) == null) {
            return VolumeLayoutCheck.Waiting("private_fs_uuid_not_canonical")
        }
        val privateGuid = privateVolume.partGuid
        if (privateGuid.isNullOrEmpty()) {
            return VolumeLayoutCheck.Waiting("private_partition_guid_missing")
        }
        if (privateGuid in knownPrivateGuids) {
            return VolumeLayoutCheck.Waiting("private_partition_guid_not_new")
        }
        return VolumeLayoutCheck.Verified(publicVolume = null, privateVolume = privateVolume)
    }

    /**
     * Exactly one mounted public volume, no private volume, and a brand-new FS
     * UUID. An unmounted public volume means the card is not ready yet.
     */
    private fun checkLinuxOnly(
        publicVolumes: List<NativeStorage.VolumeSnapshot>,
        privateVolumes: List<NativeStorage.VolumeSnapshot>,
        knownPublicFsUuids: Set<String>,
    ): VolumeLayoutCheck {
        if (privateVolumes.isNotEmpty()) return VolumeLayoutCheck.Waiting("private_volume_present")
        if (publicVolumes.size != 1) return VolumeLayoutCheck.Waiting("public_volume_count")
        val publicVolume = publicVolumes.single()
        if (publicVolume.state != STATE_MOUNTED) {
            return VolumeLayoutCheck.Waiting("public_volume_not_mounted")
        }
        val publicFsUuid = publicVolume.fsUuid?.trim()
        if (publicFsUuid.isNullOrEmpty()) {
            return VolumeLayoutCheck.Waiting("public_fs_uuid_missing")
        }
        if (publicFsUuid.lowercase() in knownPublicFsUuids) {
            return VolumeLayoutCheck.Waiting("public_fs_uuid_not_new")
        }
        return VolumeLayoutCheck.Verified(publicVolume = publicVolume, privateVolume = null)
    }

    /** Exactly one mounted public + one mounted private volume, new private GUID. */
    private fun checkMixed(
        publicVolumes: List<NativeStorage.VolumeSnapshot>,
        privateVolumes: List<NativeStorage.VolumeSnapshot>,
        knownPrivateGuids: Set<String>,
    ): VolumeLayoutCheck {
        if (publicVolumes.size != 1 || privateVolumes.size != 1) {
            return VolumeLayoutCheck.Waiting("volume_pair_not_ready")
        }
        val publicVolume = publicVolumes.single()
        val privateVolume = privateVolumes.single()
        if (publicVolume.state != STATE_MOUNTED || privateVolume.state != STATE_MOUNTED) {
            return VolumeLayoutCheck.Waiting("volume_pair_not_mounted")
        }
        val publicFsUuid = publicVolume.fsUuid
        if (publicFsUuid.isNullOrEmpty()) {
            return VolumeLayoutCheck.Waiting("public_fs_uuid_missing")
        }
        if (Identifiers.normalizeUuid(privateVolume.fsUuid) == null) {
            return VolumeLayoutCheck.Waiting("private_fs_uuid_not_canonical")
        }
        val privateGuid = privateVolume.partGuid
        if (privateGuid.isNullOrEmpty()) {
            return VolumeLayoutCheck.Waiting("private_partition_guid_missing")
        }
        if (privateGuid in knownPrivateGuids) {
            return VolumeLayoutCheck.Waiting("private_partition_guid_not_new")
        }
        return VolumeLayoutCheck.Verified(
            publicVolume = publicVolume,
            privateVolume = privateVolume,
        )
    }

    /** `VolumeInfo.TYPE_PUBLIC`. */
    private const val TYPE_PUBLIC = 0

    /** `VolumeInfo.TYPE_PRIVATE`. */
    private const val TYPE_PRIVATE = 1

    /** `VolumeInfo.STATE_MOUNTED`. */
    private const val STATE_MOUNTED = 2
}
