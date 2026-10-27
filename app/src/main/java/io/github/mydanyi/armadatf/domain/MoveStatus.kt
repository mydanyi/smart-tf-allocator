package io.github.mydanyi.armadatf.domain

/**
 * Decoded platform move status.
 *
 * `IPackageManager.getMoveStatus(int)` returns a percentage for an active move,
 * `-100` when the move finished, and one of the platform's negative failure
 * codes otherwise. A `-100` is never trusted on its own: the service must
 * observe the requested destination before reporting completion (see
 * [MigrationPolicy.destinationMatches]).
 */
sealed class MoveStatus {

    /** The platform is still moving; [percent] is the reported 0..100 progress. */
    data class InProgress(val percent: Int) : MoveStatus()

    /** The platform reported `-100`; the caller still has to verify the destination. */
    object Complete : MoveStatus()

    /**
     * A negative platform status other than `-100`.
     *
     * [code] is a stable name suitable for the JSON contract and [nativeCode] is
     * the raw platform value for diagnostics.
     */
    data class Failed(val code: String, val nativeCode: Int) : MoveStatus()
}

/** Pure decoding of the platform move status values. */
object MoveStatusDecoder {

    /** `StorageManager.MOVE_STATUS_COMPLETE`. */
    const val NATIVE_COMPLETE = -100

    fun decode(nativeStatus: Int): MoveStatus = when {
        nativeStatus >= 0 -> MoveStatus.InProgress(nativeStatus)
        nativeStatus == NATIVE_COMPLETE -> MoveStatus.Complete
        else -> MoveStatus.Failed(failureCode(nativeStatus), nativeStatus)
    }

    /**
     * Names the platform's `PackageManager` move failure codes.
     *
     * Every value `>= 0` is active progress and `-100` is completion; every
     * other negative value is a failure, including codes not named here. A
     * negative value is never decoded as progress.
     */
    fun failureCode(nativeStatus: Int): String = when (nativeStatus) {
        -1 -> "insufficient_storage"
        -2 -> "package_missing"
        -3 -> "system_package"
        -5 -> "invalid_location"
        -6 -> "internal_error"
        -7 -> "operation_pending"
        -8 -> "device_admin"
        -9 -> "third_party_not_allowed_internal"
        -10 -> "locked_user"
        else -> "failed_native"
    }
}
