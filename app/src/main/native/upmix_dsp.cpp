#include <jni.h>
#include <cmath>
#include <cstring>
#include <aaudio/AAudio.h>
#include <vector>
#include <android/log.h>

#define LOG_TAG "AndroidSurroundNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

struct AudioStream {
    AAudioStream *stream = nullptr;
    int32_t deviceId = 0;
    int32_t channelCount = 0;
    bool isActive = false;
};

static std::vector<AudioStream> gStreams;

static float gAllPassState = 0.0f;
static float gLfeX1 = 0.0f;
static float gLfeY1 = 0.0f;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_androidsurround_audio_NativeEngine_openStream(
    JNIEnv *env, jclass, jint deviceId, jint sampleRate, jint channelCount) {

    AAudioStreamBuilder *builder = nullptr;
    int32_t result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK) {
        LOGE("Failed to create stream builder: %d", result);
        return JNI_FALSE;
    }

    AAudioStreamBuilder_setDeviceId(builder, deviceId);
    AAudioStreamBuilder_setSampleRate(builder, sampleRate);
    AAudioStreamBuilder_setChannelCount(builder, channelCount);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);

    AudioStream audioStream;
    result = AAudioStreamBuilder_openStream(builder, &audioStream.stream);
    AAudioStreamBuilder_delete(builder);

    if (result != AAUDIO_OK) {
        LOGE("Failed to open stream for device %d: %d", deviceId, result);
        return JNI_FALSE;
    }

    audioStream.deviceId = deviceId;
    audioStream.channelCount = channelCount;
    audioStream.isActive = true;
    gStreams.push_back(audioStream);

    LOGI("Opened AAudio stream for device %d, %dch, %dHz",
         deviceId, channelCount, sampleRate);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_androidsurround_audio_NativeEngine_startStream(
    JNIEnv *env, jclass, jint index) {

    if (index < 0 || index >= (int)gStreams.size()) return JNI_FALSE;

    auto &as = gStreams[index];
    int32_t result = AAudioStream_requestStart(as.stream);
    if (result != AAUDIO_OK) {
        LOGE("Failed to start stream %d: %d", index, result);
        return JNI_FALSE;
    }
    as.isActive = true;
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_androidsurround_audio_NativeEngine_writeFrames(
    JNIEnv *env, jclass, jint index, jfloatArray data) {

    if (index < 0 || index >= (int)gStreams.size()) return -1;

    auto &as = gStreams[index];
    if (!as.stream || !as.isActive) return -1;

    jsize len = env->GetArrayLength(data);
    jfloat *buffer = env->GetFloatArrayElements(data, nullptr);

    jint frames = len / as.channelCount;
    jint written = AAudioStream_write(as.stream, buffer, frames, 0);

    env->ReleaseFloatArrayElements(data, buffer, JNI_ABORT);
    return written;
}

extern "C" JNIEXPORT void JNICALL
Java_com_androidsurround_audio_NativeEngine_closeAllStreams(
    JNIEnv *env, jclass) {

    for (auto &as : gStreams) {
        if (as.stream) {
            AAudioStream_requestStop(as.stream);
            AAudioStream_close(as.stream);
        }
    }
    gStreams.clear();
    LOGI("All AAudio streams closed");
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_androidsurround_audio_NativeEngine_processUpmix(
    JNIEnv *env, jclass,
    jfloatArray input,
    jint inputChannels,
    jint outputChannels,
    jboolean mixLfe,
    jfloat lfeCutoff,
    jfloat fcCutoff,
    jfloat rearDelayMs,
    jint sampleRate,
    jint method) {

    jsize inputLen = env->GetArrayLength(input);
    jfloat *inPtr = env->GetFloatArrayElements(input, nullptr);

    int frames = inputLen / inputChannels;
    int outputLen = frames * outputChannels;
    jfloatArray result = env->NewFloatArray(outputLen);
    jfloat *outPtr = env->GetFloatArrayElements(result, nullptr);

    float lfeB0 = 0, lfeB1 = 0, lfeA1 = 0;
    if (mixLfe && lfeCutoff > 0) {
        float fc = lfeCutoff / sampleRate;
        float K = tanf((float)(M_PI * fc));
        float norm = 1.0f / (1.0f + K);
        lfeB0 = K * norm;
        lfeB1 = lfeB0;
        lfeA1 = (K - 1.0f) * norm;
    }

    int rearDelaySamples = (int)(rearDelayMs * sampleRate / 1000.0f);
    std::vector<float> delayBuf;
    if (rearDelaySamples > 0) {
        delayBuf.resize(rearDelaySamples * outputChannels, 0);
    }
    int delayWritePos = 0;

    for (int f = 0; f < frames; f++) {
        float l = inPtr[f * inputChannels];
        float r = (inputChannels > 1) ? inPtr[f * inputChannels + 1] : l;
        float mid = (l + r) * 0.5f;
        float side = (l - r) * 0.5f;

        float allPassOut;
        {
            float coeff = 0.7f;
            allPassOut = -coeff * mid + gAllPassState;
            gAllPassState = mid + coeff * allPassOut;
        }

        float lfeOut = 0;
        if (mixLfe && lfeCutoff > 0) {
            lfeOut = lfeB0 * mid + lfeB1 * gLfeX1 - lfeA1 * gLfeY1;
            gLfeX1 = mid;
            gLfeY1 = lfeOut;
        }

        float *frame = outPtr + f * outputChannels;

        switch (method) {
            case 0: // PSD
                frame[0] = l * 0.9f + allPassOut * 0.1f;
                frame[1] = r * 0.9f + allPassOut * 0.1f;
                if (outputChannels > 2) frame[2] = mid * 0.8f + allPassOut * 0.2f;
                if (outputChannels > 3) frame[3] = lfeOut;
                if (outputChannels > 4) frame[4] = side * 0.85f + allPassOut * 0.15f;
                if (outputChannels > 5) frame[5] = -side * 0.85f + allPassOut * 0.15f;
                if (outputChannels > 6) frame[6] = side * 0.6f + allPassOut * 0.4f;
                if (outputChannels > 7) frame[7] = -side * 0.6f + allPassOut * 0.4f;
                break;
            case 1: // SIMPLE
                frame[0] = l;
                frame[1] = r;
                if (outputChannels > 2) frame[2] = mid;
                if (outputChannels > 3) frame[3] = lfeOut;
                if (outputChannels > 4) frame[4] = side * 0.7f;
                if (outputChannels > 5) frame[5] = -side * 0.7f;
                if (outputChannels > 6) frame[6] = side * 0.5f;
                if (outputChannels > 7) frame[7] = -side * 0.5f;
                break;
            case 2: // DOLBY
            {
                float sMid = mid * 1.1f;
                float sSide = side * 1.2f;
                frame[0] = l;
                frame[1] = r;
                if (outputChannels > 2) frame[2] = fmaxf(-1.0f, fminf(1.0f, sMid));
                if (outputChannels > 3) frame[3] = lfeOut;
                if (outputChannels > 4) frame[4] = sSide * 0.9f;
                if (outputChannels > 5) frame[5] = -sSide * 0.9f;
                if (outputChannels > 6) frame[6] = sSide * 0.5f;
                if (outputChannels > 7) frame[7] = -sSide * 0.5f;
                break;
            }
        }
    }

    env->ReleaseFloatArrayElements(input, inPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(result, outPtr, 0);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_androidsurround_audio_NativeEngine_resetFilters(JNIEnv *env, jclass) {
    gAllPassState = 0.0f;
    gLfeX1 = 0.0f;
    gLfeY1 = 0.0f;
}
