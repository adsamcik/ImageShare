package com.imageshare.feature.preset

enum class OutputFormat { JPEG, PNG, WEBP_LOSSY, WEBP_LOSSLESS }

enum class MetadataPolicy { StripAll, PreserveSafe, PreserveAll }

sealed class ResizeMode {
    data class LongEdge(val pixels: Int) : ResizeMode() {
        init {
            require(pixels >= MIN_DIMENSION_PX) { "Long edge must be at least $MIN_DIMENSION_PX pixels." }
        }
    }

    data class Exact(val width: Int, val height: Int) : ResizeMode() {
        init {
            require(width >= MIN_DIMENSION_PX) { "Width must be at least $MIN_DIMENSION_PX pixels." }
            require(height >= MIN_DIMENSION_PX) { "Height must be at least $MIN_DIMENSION_PX pixels." }
        }
    }

    data class Percentage(val pct: Int) : ResizeMode() {
        init {
            require(pct in MIN_PERCENTAGE..MAX_PERCENTAGE) {
                "Percentage must be between $MIN_PERCENTAGE and $MAX_PERCENTAGE."
            }
        }
    }

    data object Original : ResizeMode()

    private companion object {
        const val MIN_DIMENSION_PX = 16
        const val MIN_PERCENTAGE = 10
        const val MAX_PERCENTAGE = 100
    }
}

enum class AlphaFallback { Error, FillWhite, SwitchToPng }

data class Preset(
    val id: String,
    val displayName: String,
    val format: OutputFormat,
    val resize: ResizeMode,
    val quality: Int,
    val metadata: MetadataPolicy,
    val alphaFallback: AlphaFallback,
    val targetSizeBytes: Long? = null,
    val builtIn: Boolean = true,
) {
    init {
        require(id.matches(ID_PATTERN)) { "Preset id must be a stable kebab-case key." }
        require(displayName.isNotBlank()) { "Display name must not be blank." }
        require(quality in MIN_QUALITY..MAX_QUALITY) {
            "Quality must be between $MIN_QUALITY and $MAX_QUALITY."
        }
        require(targetSizeBytes == null || targetSizeBytes > 0L) {
            "Target size must be positive when set."
        }
    }

    private companion object {
        val ID_PATTERN = Regex("[a-z0-9]+(-[a-z0-9]+)*")
        const val MIN_QUALITY = 1
        const val MAX_QUALITY = 100
    }
}
