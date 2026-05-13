#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <avif/avif.h>
#include <cstdint>

#define LOG_TAG "imageshare-avif"
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_imageshare_core_processing_NativeAvifEncoder_nativeEncode(
    JNIEnv* env,
    jclass,
    jobject bitmap,
    jint quality
) {
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        ALOGW("getInfo failed");
        return nullptr;
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        ALOGW("unsupported bitmap format %d", info.format);
        return nullptr;
    }

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        ALOGW("lockPixels failed");
        return nullptr;
    }

    avifImage* image = avifImageCreate(info.width, info.height, 8, AVIF_PIXEL_FORMAT_YUV420);
    if (!image) {
        AndroidBitmap_unlockPixels(env, bitmap);
        ALOGW("avifImageCreate failed");
        return nullptr;
    }

    avifRGBImage rgb;
    avifRGBImageSetDefaults(&rgb, image);
    rgb.format = AVIF_RGB_FORMAT_RGBA;
    rgb.depth = 8;
    rgb.pixels = static_cast<uint8_t*>(pixels);
    rgb.rowBytes = info.stride;

    if (avifImageRGBToYUV(image, &rgb) != AVIF_RESULT_OK) {
        avifImageDestroy(image);
        AndroidBitmap_unlockPixels(env, bitmap);
        ALOGW("avifImageRGBToYUV failed");
        return nullptr;
    }

    avifEncoder* encoder = avifEncoderCreate();
    if (!encoder) {
        avifImageDestroy(image);
        AndroidBitmap_unlockPixels(env, bitmap);
        ALOGW("avifEncoderCreate failed");
        return nullptr;
    }

    encoder->maxThreads = 4;
    encoder->quality = quality;
    encoder->qualityAlpha = quality;

    avifRWData output = AVIF_DATA_EMPTY;
    avifResult addResult = avifEncoderAddImage(encoder, image, 1, AVIF_ADD_IMAGE_FLAG_SINGLE);
    avifResult finishResult = AVIF_RESULT_UNKNOWN_ERROR;
    if (addResult == AVIF_RESULT_OK) {
        finishResult = avifEncoderFinish(encoder, &output);
    }

    AndroidBitmap_unlockPixels(env, bitmap);

    if (addResult != AVIF_RESULT_OK || finishResult != AVIF_RESULT_OK) {
        ALOGW("avif encode failed: add=%d finish=%d", addResult, finishResult);
        avifRWDataFree(&output);
        avifEncoderDestroy(encoder);
        avifImageDestroy(image);
        return nullptr;
    }

    jbyteArray result = env->NewByteArray(static_cast<jsize>(output.size));
    if (result) {
        env->SetByteArrayRegion(
            result,
            0,
            static_cast<jsize>(output.size),
            reinterpret_cast<const jbyte*>(output.data)
        );
    }

    avifRWDataFree(&output);
    avifEncoderDestroy(encoder);
    avifImageDestroy(image);
    return result;
}
