package io.github.mydanyi.armadatf.storage

import android.os.Binder
import android.os.IBinder
import android.os.StatFs
import io.github.mydanyi.armadatf.domain.Identifiers
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Thrown when a hidden platform package/storage member cannot be resolved or a
 * privileged native call fails unexpectedly.
 */
class NativeMigrationException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/** Readable capacity of one mounted volume; both fields are `null` when unknown. */
data class VolumeCapacity(val totalBytes: Long?, val availableBytes: Long?)

/**
 * Privileged-process-only bridge for the platform's native migration APIs.
 *
 * Everything is resolved reflectively by class name because the platform
 * interfaces are hidden: `IStorageManager` for `getPrimaryStorageUuid()` (no
 * arguments), and `IPackageManager` for `movePrimaryStorage(String)`,
 * `movePackage(String, String)`, `getMoveStatus(int)`, installed applications,
 * per-package state and `isPackageDeviceAdminOnAnyUser(String)`.
 *
 * The public (hidden) interface classes are always used for method lookup — the
 * generated binder proxies are non-public, so invoking their own methods would
 * raise [IllegalAccessException]. Every privileged call is wrapped in
 * [Binder.clearCallingIdentity] so the caller's identity does not decide the
 * result. An [InvocationTargetException] is unwrapped and a platform
 * [RuntimeException] (for example [SecurityException]) is rethrown unchanged.
 *
 * Factory-origin classification uses [factoryCatalog], one process-lifetime
 * cached catalog shared by both the full inventory and the single-package query.
 */
class NativeMigration(
    private val factoryCatalog: FactoryAppCatalog = FactoryAppCatalog(),
) {

    /**
     * Current primary-storage UUID; `null` means internal storage.
     *
     * [userId] is kept for the existing service callers, but
     * `IStorageManager.getPrimaryStorageUuid()` takes no arguments.
     */
    @Suppress("UNUSED_PARAMETER")
    fun primaryStorageUuid(userId: Int): String? = privileged {
        callStorage("getPrimaryStorageUuid", emptyArray()) as? String
    }

    /** Starts a primary-storage move; [targetUuid] is `null` for internal storage. */
    fun movePrimaryStorage(targetUuid: String?): Int = privileged {
        asMoveId(
            callPackage("movePrimaryStorage", arrayOf(STRING), targetUuid),
            "movePrimaryStorage",
        )
    }

    /** Starts a package move; [targetUuid] is `null` for internal storage. */
    fun movePackage(packageName: String, targetUuid: String?): Int = privileged {
        asMoveId(
            callPackage("movePackage", arrayOf(STRING, STRING), packageName, targetUuid),
            "movePackage",
        )
    }

    /** Raw platform status for [moveId]: progress, `-100`, or a negative failure. */
    fun moveStatus(moveId: Int): Int = privileged {
        (callPackage("getMoveStatus", arrayOf(INT), moveId) as? Number)?.toInt()
            ?: throw NativeMigrationException("getMoveStatus did not return a status")
    }

    /**
     * Total/available bytes for a mounted path. `StatFs` is read-only and needs
     * no Context; an unavailable or unmounted path yields `null` capacities
     * rather than an invented value.
     */
    fun capacity(path: String?): VolumeCapacity {
        if (path.isNullOrBlank()) return VolumeCapacity(null, null)
        return try {
            val stat = StatFs(path)
            VolumeCapacity(stat.totalBytes, stat.availableBytes)
        } catch (e: Exception) {
            VolumeCapacity(null, null)
        }
    }

    // --- installed applications ---------------------------------------------

    /** One installed application as reported by `IPackageManager`. */
    data class PackageSnapshot(
        val packageName: String,
        val isSystem: Boolean,
        val isUpdatedSystem: Boolean,
        val installLocation: Int,
        val sourceUuid: String?,
        /**
         * OEM factory origin: the code is read from a trusted system-image
         * partition, or the identity is in the factory preload catalog. This
         * stays true after an OEM update moved the code under `/data/app` and
         * dropped the platform system flags.
         */
        val isPreinstalled: Boolean = false,
    )

    /**
     * Every installed application for [userId], or throws
     * [NativeMigrationException] when the platform list or the factory catalog
     * cannot be read.
     */
    fun installedApps(userId: Int): List<PackageSnapshot> = privileged {
        val manager = systemService(SERVICE_PACKAGE, PACKAGE_INTERFACE)
        val method = installedApplicationsMethod()
            ?: throw NativeMigrationException(
                "IPackageManager.getInstalledApplications is not available",
            )
        val slice = invoke(method, manager, flagsArgument(method, 0), userId)
        val factoryPackages = factoryCatalog.packages()
        parceledList(slice).mapNotNull { info ->
            info?.let { toPackageSnapshot(it, factoryPackages) }
        }
    }

    /** One package, or `null` when it is not installed for [userId]. */
    fun packageSnapshot(packageName: String, userId: Int): PackageSnapshot? = privileged {
        val manager = systemService(SERVICE_PACKAGE, PACKAGE_INTERFACE)
        val method = packageInfoMethod()
            ?: throw NativeMigrationException("IPackageManager.getPackageInfo is not available")
        val args: Array<Any?> = if (method.parameterCount == 3) {
            arrayOf(packageName, flagsArgument(method, 1), userId)
        } else {
            arrayOf(packageName, flagsArgument(method, 1))
        }
        val info = invokePackageInfo(method, manager, args) ?: return@privileged null
        packageInfoSnapshot(info, factoryCatalog.packages())
    }

    /**
     * Device-admin state of one package via the canonical
     * `IPackageManager.isPackageDeviceAdminOnAnyUser(String)` check, or `null`
     * when that optional query cannot be answered — typically because the
     * privileged caller lacks `MANAGE_USERS`; `MOVE_PACKAGE` and
     * `MANAGE_DEVICE_ADMINS` do not grant this read.
     *
     * `null` means "unknown", never "not an admin", and must not block a
     * request: the platform's `MovePackageHelper` enforces the device-admin ban
     * itself and fails an actual admin with `MOVE_FAILED_DEVICE_ADMIN`. All
     * native enforcement stays in place; the value only feeds the preflight
     * filter.
     *
     * [userId] is retained for the existing service callers; the platform check
     * is not per-user.
     */
    @Suppress("UNUSED_PARAMETER")
    fun isDeviceAdmin(packageName: String, userId: Int): Boolean? = try {
        privileged {
            callPackage("isPackageDeviceAdminOnAnyUser", arrayOf(STRING), packageName) as? Boolean
        }
    } catch (e: Exception) {
        // Optional metadata: a missing/denied package query must not fail
        // the whole listing. The unknown state is not an admin verdict; the
        // platform's own move performs the authoritative check.
        null
    }

    /**
     * Maps one platform `PackageInfo` object returned by `getPackageInfo`.
     *
     * `getPackageInfo` returns a `PackageInfo`, not the `ApplicationInfo` that
     * [toPackageSnapshot] reads, so its `applicationInfo` field is unwrapped
     * first. `PackageInfo.installLocation` is preferred over the nested value
     * when the platform reports it. [factoryPackages] is the cached factory
     * catalog used for origin classification. Visible for the host-side mapping
     * test.
     */
    internal fun packageInfoSnapshot(
        info: Any,
        factoryPackages: Set<String> = emptySet(),
    ): PackageSnapshot {
        val applicationInfo = optionalObjectField(info, "applicationInfo")
            ?: throw NativeMigrationException(
                "applicationInfo of ${info.javaClass.name} was not available",
            )
        return toPackageSnapshot(applicationInfo, factoryPackages).copy(
            installLocation = optionalIntField(info, "installLocation")
                ?: optionalIntField(applicationInfo, "installLocation")
                ?: INSTALL_LOCATION_UNSPECIFIED,
        )
    }

    /**
     * Maps one platform `ApplicationInfo` object.
     *
     * `volumeUuid` is the authoritative source location. When the platform
     * leaves it empty, the UUID is read from the `/mnt/expand/<uuid>/...` prefix
     * of `sourceDir`/`dataDir`; a path without such a prefix is internal
     * storage, not a guessed volume.
     *
     * Factory origin is derived from the actual code location: any `sourceDir`
     * inside a trusted system-image partition is factory-preloaded even without
     * the platform system flags. An OEM update that moved the code under
     * `/data/app` is still recognised through [factoryPackages], the package
     * identities read once from the factory preload directories. Visible for the
     * host-side mapping test.
     */
    internal fun toPackageSnapshot(
        info: Any,
        factoryPackages: Set<String> = emptySet(),
    ): PackageSnapshot {
        val flags = intField(info, "flags")
        val packageName = stringField(info, "packageName")
        val sourceDir = optionalStringField(info, "sourceDir")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val volumeUuid = optionalStringField(info, "volumeUuid")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: volumeUuidFromPath(sourceDir)
            ?: volumeUuidFromPath(optionalStringField(info, "dataDir"))
        return PackageSnapshot(
            packageName = packageName,
            isSystem = (flags and FLAG_SYSTEM) != 0,
            isUpdatedSystem = (flags and FLAG_UPDATED_SYSTEM_APP) != 0,
            installLocation = optionalIntField(info, "installLocation")
                ?: INSTALL_LOCATION_UNSPECIFIED,
            sourceUuid = volumeUuid,
            isPreinstalled = FactoryAppCatalog.isTrustedFactoryPath(sourceDir) ||
                packageName in factoryPackages,
        )
    }

    /** Extracts the volume UUID from a `/mnt/expand/<uuid>/...` path. */
    internal fun volumeUuidFromPath(raw: String?): String? {
        val path = raw?.trim() ?: return null
        val index = path.indexOf(EXPAND_MARKER)
        if (index < 0) return null
        val segment = path.substring(index + EXPAND_MARKER.length).substringBefore('/')
        if (segment.isEmpty()) return null
        return segment.takeIf { Identifiers.normalizeUuid(it) != null }
    }

    // --- platform access -----------------------------------------------------

    private fun <T> privileged(block: () -> T): T {
        val identity = Binder.clearCallingIdentity()
        try {
            return block()
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    private fun callStorage(
        name: String,
        parameterTypes: Array<Class<*>>,
        vararg args: Any?,
    ): Any? = call(SERVICE_MOUNT, STORAGE_INTERFACE, storageInterface(), name, parameterTypes, args)

    private fun callPackage(
        name: String,
        parameterTypes: Array<Class<*>>,
        vararg args: Any?,
    ): Any? = call(SERVICE_PACKAGE, PACKAGE_INTERFACE, packageInterface(), name, parameterTypes, args)

    private fun call(
        serviceName: String,
        interfaceName: String,
        interfaceClass: Class<*>,
        name: String,
        parameterTypes: Array<Class<*>>,
        args: Array<out Any?>,
    ): Any? {
        val manager = systemService(serviceName, interfaceName)
        val method = try {
            interfaceClass.getMethod(name, *parameterTypes)
        } catch (e: NoSuchMethodException) {
            throw NativeMigrationException("$interfaceName.$name is not available", e)
        }
        return invoke(method, manager, *args)
    }

    private fun storageInterface(): Class<*> = classFor(STORAGE_INTERFACE)

    private fun packageInterface(): Class<*> = classFor(PACKAGE_INTERFACE)

    private fun classFor(name: String): Class<*> = try {
        Class.forName(name)
    } catch (e: ClassNotFoundException) {
        throw NativeMigrationException("$name is not available", e)
    }

    private fun systemService(serviceName: String, interfaceName: String): Any {
        val binder = binderFor(serviceName)
            ?: throw NativeMigrationException("$serviceName service is not available")
        val stub = try {
            Class.forName("$interfaceName\$Stub")
        } catch (e: ClassNotFoundException) {
            throw NativeMigrationException("$interfaceName is not available", e)
        }
        val asInterface = try {
            stub.getMethod("asInterface", IBinder::class.java)
        } catch (e: NoSuchMethodException) {
            throw NativeMigrationException("$interfaceName.Stub.asInterface is not available", e)
        }
        return invoke(asInterface, null, binder)
            ?: throw NativeMigrationException("$interfaceName could not be created")
    }

    private fun binderFor(name: String): IBinder? {
        val getService = try {
            Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
        } catch (e: ClassNotFoundException) {
            throw NativeMigrationException("android.os.ServiceManager is not available", e)
        } catch (e: NoSuchMethodException) {
            throw NativeMigrationException("ServiceManager.getService is not available", e)
        }
        return invoke(getService, null, name) as? IBinder
    }

    private fun invoke(method: Method, target: Any?, vararg args: Any?): Any? = try {
        method.invoke(target, *args)
    } catch (e: InvocationTargetException) {
        val cause = e.cause
        if (cause is RuntimeException) throw cause
        throw NativeMigrationException("${method.name} failed", cause ?: e)
    } catch (e: ReflectiveOperationException) {
        throw NativeMigrationException("${method.name} failed", e)
    }

    private fun invokePackageInfo(
        method: Method,
        target: Any,
        args: Array<Any?>,
    ): Any? = try {
        method.invoke(target, *args)
    } catch (e: InvocationTargetException) {
        val cause = e.cause
        if (cause is android.content.pm.PackageManager.NameNotFoundException) {
            null
        } else if (cause is RuntimeException) {
            throw cause
        } else {
            throw NativeMigrationException("${method.name} failed", cause ?: e)
        }
    } catch (e: ReflectiveOperationException) {
        throw NativeMigrationException("${method.name} failed", e)
    }

    private fun installedApplicationsMethod(): Method? {
        val candidates = packageInterface().methods.filter {
            it.name == "getInstalledApplications" && it.parameterCount == 2
        }
        return candidates.firstOrNull { it.parameterTypes[0] == Long::class.javaPrimitiveType }
            ?: candidates.firstOrNull { it.parameterTypes[0] == Int::class.javaPrimitiveType }
    }

    private fun packageInfoMethod(): Method? {
        val candidates = packageInterface().methods.filter { it.name == "getPackageInfo" }
        return candidates.firstOrNull {
            it.parameterCount == 3 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == Long::class.javaPrimitiveType &&
                it.parameterTypes[2] == Int::class.javaPrimitiveType
        } ?: candidates.firstOrNull {
            it.parameterCount == 3 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType &&
                it.parameterTypes[2] == Int::class.javaPrimitiveType
        } ?: candidates.firstOrNull {
            it.parameterCount == 2 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType
        }
    }

    private fun flagsArgument(method: Method, index: Int): Any =
        if (method.parameterTypes[index] == Long::class.javaPrimitiveType) 0L else 0

    private fun parceledList(slice: Any?): List<*> {
        if (slice == null) return emptyList<Any?>()
        val getList = try {
            Class.forName("android.content.pm.ParceledListSlice").getMethod("getList")
        } catch (e: ReflectiveOperationException) {
            throw NativeMigrationException("ParceledListSlice.getList is not available", e)
        }
        return invoke(getList, slice) as? List<*>
            ?: throw NativeMigrationException("ParceledListSlice did not return a list")
    }

    private fun asMoveId(value: Any?, method: String): Int =
        (value as? Number)?.toInt()
            ?: throw NativeMigrationException("$method did not return a move id")

    // --- reflection helpers --------------------------------------------------

    private fun stringField(target: Any, name: String): String =
        optionalStringField(target, name)
            ?: throw NativeMigrationException("$name of ${target.javaClass.name} was not a String")

    private fun optionalStringField(target: Any, name: String): String? = try {
        target.javaClass.getField(name).get(target) as? String
    } catch (e: NoSuchFieldException) {
        null
    } catch (e: IllegalAccessException) {
        throw NativeMigrationException("$name could not be read from ${target.javaClass.name}", e)
    }

    private fun intField(target: Any, name: String): Int =
        optionalIntField(target, name)
            ?: throw NativeMigrationException("$name of ${target.javaClass.name} was not numeric")

    private fun optionalIntField(target: Any, name: String): Int? = try {
        (target.javaClass.getField(name).get(target) as? Number)?.toInt()
    } catch (e: NoSuchFieldException) {
        null
    } catch (e: IllegalAccessException) {
        throw NativeMigrationException("$name could not be read from ${target.javaClass.name}", e)
    }

    private fun optionalObjectField(target: Any, name: String): Any? = try {
        target.javaClass.getField(name).get(target)
    } catch (e: NoSuchFieldException) {
        null
    } catch (e: IllegalAccessException) {
        throw NativeMigrationException("$name could not be read from ${target.javaClass.name}", e)
    }

    private companion object {
        const val SERVICE_MOUNT = "mount"
        const val SERVICE_PACKAGE = "package"

        const val STORAGE_INTERFACE = "android.os.storage.IStorageManager"
        const val PACKAGE_INTERFACE = "android.content.pm.IPackageManager"

        const val EXPAND_MARKER = "/mnt/expand/"

        /** `ApplicationInfo.FLAG_SYSTEM`. */
        const val FLAG_SYSTEM = 1

        /** `ApplicationInfo.FLAG_UPDATED_SYSTEM_APP`. */
        const val FLAG_UPDATED_SYSTEM_APP = 1 shl 7

        /** `PackageManager.INSTALL_LOCATION_UNSPECIFIED`. */
        const val INSTALL_LOCATION_UNSPECIFIED = -1

        val INT: Class<*> = Int::class.javaPrimitiveType!!
        val STRING: Class<*> = String::class.java
    }
}
