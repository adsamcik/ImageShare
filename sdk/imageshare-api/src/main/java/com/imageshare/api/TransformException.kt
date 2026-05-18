package com.imageshare.api

public class TransformException(
    public val code: TransformResult.ErrorCode,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
