package io.github.mydanyi.armadatf.domain

/**
 * Pure identifier helpers shared by the domain, the privileged service and the
 * handoff writer. No Android framework types are referenced here so the whole
 * file stays host-testable.
 */
object Identifiers {

    /** SD card CIDs are 32 hexadecimal characters. */
    const val CID_LENGTH = 32

    private const val UUID_HEX_LENGTH = 32

    fun isHexDigit(character: Char): Boolean =
        character in '0'..'9' || character in 'a'..'f' || character in 'A'..'F'

    fun isValidCid(raw: String): Boolean =
        raw.length == CID_LENGTH && raw.all { isHexDigit(it) }

    /**
     * Validates [raw] as a 32-hex CID and returns its lowercase form.
     *
     * @throws IllegalArgumentException when [raw] is not exactly 32 hex chars.
     */
    fun normalizeCid(raw: String): String {
        require(isValidCid(raw)) { "CID must be $CID_LENGTH hex characters" }
        return raw.lowercase()
    }

    /**
     * Normalises a GPT/filesystem UUID to the canonical dashed lowercase form.
     *
     * Native adopted-storage GUIDs are sometimes reported without hyphens, so
     * both the 32-hex and the 36-char dashed forms are accepted. Returns `null`
     * for a `null` input or anything that is not 32 hex characters once
     * separators are removed, so callers decide whether an invalid value is
     * fatal or optional.
     */
    fun normalizeUuid(raw: String?): String? {
        if (raw == null) return null
        val compact = raw.trim().replace("-", "").lowercase()
        if (compact.length != UUID_HEX_LENGTH || !compact.all { isHexDigit(it) }) return null
        return buildString(36) {
            append(compact, 0, 8).append('-')
            append(compact, 8, 12).append('-')
            append(compact, 12, 16).append('-')
            append(compact, 16, 20).append('-')
            append(compact, 20, 32)
        }
    }
}
