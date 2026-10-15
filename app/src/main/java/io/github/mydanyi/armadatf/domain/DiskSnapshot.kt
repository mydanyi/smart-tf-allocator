package io.github.mydanyi.armadatf.domain

/**
 * A pure, host-testable view of one physical storage disk.
 *
 * [id] is an opaque disk identifier. The privileged service builds it from the
 * platform `DiskInfo.id` (`disk:<major>,<minor>`), but the domain never parses
 * or constrains its syntax.
 */
data class DiskSnapshot(
    val id: String,
    val sizeBytes: Long,
    val cid: String,
    val isSd: Boolean,
    val isAdoptable: Boolean,
)
