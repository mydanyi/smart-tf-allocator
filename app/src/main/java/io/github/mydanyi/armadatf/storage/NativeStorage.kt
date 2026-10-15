package io.github.mydanyi.armadatf.storage

import android.os.Binder
import android.os.IBinder
import io.github.mydanyi.armadatf.domain.AllocationPlan
import io.github.mydanyi.armadatf.domain.AllocationPolicy
import io.github.mydanyi.armadatf.domain.DiskSnapshot
import io.github.mydanyi.armadatf.domain.Identifiers
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
     * Only disks whose `id` parses as `disk:<major>,<minor>`, whose SD flag is
     * set and whose sysfs `device/type` is `SD` are marked [DiskSnapshot.isSd].
     * Non-SD disks are still listed with `isSd = false`.
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
     * Asks the platform to split the card described by [plan] into a mixed
     * adopted-storage layout with [AllocationPlan.linuxPercent] percent
     * reserved for the Linux/public share.
     *
     * The disk id and the percentage are validated against [plan] first, then
     * the disk is re-fetched from the platform and re-checked with
     * [AllocationPolicy.matches] so that a card swapped in after the plan was
     * prepared cannot be partitioned: the fresh platform state must still match
     * the exact id, size and CID the plan was captured for.
     *
     * The native call is made with [Binder.clearCallingIdentity] so the
     * caller's identity is not applied to the privileged partition operation.
     */
    fun partitionMixed(plan: AllocationPlan) {
        require(DISK_ID_REGEX.matches(plan.diskId)) { "invalid disk id '${plan.diskId}'" }
        require(AllocationPolicy.isValidPercent(plan.linuxPercent)) {
            "linuxPercent must be in ${AllocationPolicy.MIN_PERCENT}..${AllocationPolicy.MAX_PERCENT} " +
                "but was ${plan.linuxPercent}"
        }
        val disk = disks().firstOrNull { it.id == plan.diskId }
            ?: throw CardChangedException("disk '${plan.diskId}' is no longer present")
        if (!AllocationPolicy.matches(plan, disk)) {
            throw CardChangedException("disk '${plan.diskId}' no longer matches the allocation plan")
        }
        val manager = storageManager()
        val identity = Binder.clearCallingIdentity()
        try {
            invoke(manager, "partitionMixed", plan.diskId, plan.linuxPercent)
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

    private fun toDiskSnapshot(info: Any): DiskSnapshot {
        val id = stringField(info, "id")
        val flags = intField(info, "flags")
        val sizeBytes = longField(info, "size")
        val majorMinor = parseDiskId(id)
        val isSd = majorMinor != null &&
            (flags and FLAG_SD) != 0 &&
            sysfsType(majorMinor) == "SD"
        val isAdoptable = (flags and FLAG_ADOPTABLE) != 0
        val cid = if (majorMinor == null) "" else readCid(majorMinor)
        return DiskSnapshot(
            id = id,
            sizeBytes = sizeBytes,
            cid = cid,
            isSd = isSd,
            isAdoptable = isAdoptable,
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
     * the empty string on this read-only listing; [AllocationPolicy.plan] then
     * refuses to build a plan for that disk.
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
