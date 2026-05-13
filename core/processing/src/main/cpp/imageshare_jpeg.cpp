#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <turbojpeg.h>

#define LOG_TAG "imageshare-jpeg"
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_imageshare_core_processing_NativeJpegEncoder_nativeEncode(
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

    tjhandle compressor = tjInitCompress();
    if (!compressor) {
        AndroidBitmap_unlockPixels(env, bitmap);
        ALOGW("tjInitCompress failed");
        return nullptr;
    }

    unsigned char* jpegBuf = nullptr;
    unsigned long jpegSize = 0;
    int rc = tjCompress2(
        compressor,
        static_cast<const unsigned char*>(pixels),
        static_cast<int>(info.width),
        static_cast<int>(info.stride),
        static_cast<int>(info.height),
        TJPF_RGBA,
        &jpegBuf,
        &jpegSize,
        TJSAMP_420,
        quality,
        TJFLAG_FASTDCT
    );

    AndroidBitmap_unlockPixels(env, bitmap);

    if (rc != 0) {
        ALOGW("tjCompress2 failed: %s", tjGetErrorStr2(compressor));
        if (jpegBuf) {
            tjFree(jpegBuf);
        }
        tjDestroy(compressor);
        return nullptr;
    }

    jbyteArray result = env->NewByteArray(static_cast<jsize>(jpegSize));
    if (result) {
        env->SetByteArrayRegion(
            result,
            0,
            static_cast<jsize>(jpegSize),
            reinterpret_cast<const jbyte*>(jpegBuf)
        );
    }

    tjFree(jpegBuf);
    tjDestroy(compressor);
    return result;
}
