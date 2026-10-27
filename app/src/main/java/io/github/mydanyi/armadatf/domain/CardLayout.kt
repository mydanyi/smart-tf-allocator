package io.github.mydanyi.armadatf.domain

/**
 * Live layout of one card, inferred only from the volumes the platform reports
 * on that exact disk.
 *
 * [PUBLIC_ONLY] is ordinary portable storage. It is deliberately not called a
 * completed Linux allocation: only the app-confirmed Linux volume identities
 * can prove that, never the mere presence of a public FAT volume.
 */
enum class CardLayout(val jsonName: String) {
    /** No public or private volume on the disk. */
    NONE("none"),

    /** Exactly one adopted private volume and no public volume. */
    ANDROID_ONLY("android_only"),

    /** Exactly one public volume and one adopted private volume. */
    MIXED("mixed"),

    /** Exactly one public volume and no adopted private volume. */
    PUBLIC_ONLY("public_only"),

    /** More than one volume of either kind; the layout is ambiguous. */
    UNSUPPORTED("unsupported"),
}

/** Pure inference of the layout from the volume counts on one disk. */
object CardLayoutPolicy {

    fun infer(publicVolumes: Int, privateVolumes: Int): CardLayout {
        require(publicVolumes >= 0) { "publicVolumes must not be negative" }
        require(privateVolumes >= 0) { "privateVolumes must not be negative" }
        return when {
            publicVolumes == 0 && privateVolumes == 0 -> CardLayout.NONE
            publicVolumes == 0 && privateVolumes == 1 -> CardLayout.ANDROID_ONLY
            publicVolumes == 1 && privateVolumes == 1 -> CardLayout.MIXED
            publicVolumes == 1 && privateVolumes == 0 -> CardLayout.PUBLIC_ONLY
            else -> CardLayout.UNSUPPORTED
        }
    }

    /** True only for layouts that actually contain an adopted Android volume. */
    fun hasAdoptedVolume(layout: CardLayout): Boolean =
        layout == CardLayout.ANDROID_ONLY || layout == CardLayout.MIXED
}
