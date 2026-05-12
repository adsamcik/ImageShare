package com.imageshare.feature.preset

object DefaultPresets {
    const val DEFAULT_PRESET_ID = "small-file"

    val SmallFile = Preset(
        id = DEFAULT_PRESET_ID,
        displayName = "Small file",
        format = OutputFormat.JPEG,
        resize = ResizeMode.LongEdge(SMALL_FILE_LONG_EDGE_PX),
        quality = 70,
        metadata = MetadataPolicy.StripAll,
        alphaFallback = AlphaFallback.FillWhite,
    )

    val BestQuality = Preset(
        id = "best-quality",
        displayName = "Best quality",
        format = OutputFormat.JPEG,
        resize = ResizeMode.LongEdge(BEST_QUALITY_LONG_EDGE_PX),
        quality = 92,
        metadata = MetadataPolicy.PreserveSafe,
        alphaFallback = AlphaFallback.Error,
    )

    val SocialUpload = Preset(
        id = "social-upload",
        displayName = "Social upload",
        format = OutputFormat.WEBP_LOSSY,
        resize = ResizeMode.LongEdge(SOCIAL_UPLOAD_LONG_EDGE_PX),
        quality = 85,
        metadata = MetadataPolicy.StripAll,
        alphaFallback = AlphaFallback.SwitchToPng,
    )

    val Email = Preset(
        id = "email",
        displayName = "Email",
        format = OutputFormat.JPEG,
        resize = ResizeMode.LongEdge(EMAIL_LONG_EDGE_PX),
        quality = 60,
        metadata = MetadataPolicy.StripAll,
        alphaFallback = AlphaFallback.FillWhite,
        targetSizeBytes = EMAIL_TARGET_BYTES,
    )

    val Custom = Preset(
        id = "custom",
        displayName = "Custom",
        format = OutputFormat.JPEG,
        resize = ResizeMode.Original,
        quality = 90,
        metadata = MetadataPolicy.PreserveSafe,
        alphaFallback = AlphaFallback.Error,
    )

    val ALL: List<Preset> = listOf(
        SmallFile,
        BestQuality,
        SocialUpload,
        Email,
        Custom,
    )

    private const val SMALL_FILE_LONG_EDGE_PX = 1600
    private const val BEST_QUALITY_LONG_EDGE_PX = 2560
    private const val SOCIAL_UPLOAD_LONG_EDGE_PX = 2048
    private const val EMAIL_LONG_EDGE_PX = 1280
    private const val EMAIL_TARGET_BYTES = 1L * 1024L * 1024L
}


