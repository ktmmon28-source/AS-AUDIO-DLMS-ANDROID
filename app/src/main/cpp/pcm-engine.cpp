
#include <jni.h>
#include <oboe/Oboe.h>

#include <algorithm>
#include <cmath>
#include <array>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>

class PcmEngine : public oboe::AudioStreamDataCallback {
public:
    bool start(int32_t sampleRate) {
        std::lock_guard<std::mutex> lock(mMutex);

        closeLocked();

        if (sampleRate <= 0) return false;

        mReadFrame.store(0);
        mWriteFrame.store(0);

        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)
                ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
                ->setSharingMode(oboe::SharingMode::Shared)
                ->setFormat(oboe::AudioFormat::Float)
                ->setChannelCount(2)
                ->setSampleRate(sampleRate)
                ->setUsage(oboe::Usage::Media)
                ->setContentType(oboe::ContentType::Music)
                ->setDataCallback(this);

        oboe::Result result = builder.openStream(mStream);
        if (result != oboe::Result::OK || !mStream) {
            return false;
        }

        result = mStream->requestStart();
        if (result != oboe::Result::OK) {
            closeLocked();
            return false;
        }

        return true;
    }

    int32_t write(const int16_t* samples,
                  int32_t sampleCount,
                  int32_t channels) {
        if (samples == nullptr || channels < 1 || channels > 2) {
            return 0;
        }

        const int32_t inputFrames = sampleCount / channels;

        uint64_t writeFrame = mWriteFrame.load(std::memory_order_relaxed);
        const uint64_t readFrame = mReadFrame.load(std::memory_order_acquire);

        const uint64_t queued = writeFrame - readFrame;
        const uint64_t available = kCapacityFrames - queued;

        const int32_t framesToWrite = static_cast<int32_t>(
                std::min<uint64_t>(inputFrames, available));
        float leftSum = 0.0f;
        float rightSum = 0.0f;
        for (int32_t i = 0; i < framesToWrite; ++i) {
            const uint64_t index = (writeFrame + i) & kMask;

            const float left =
                    static_cast<float>(samples[i * channels]) / 32768.0f;

            const float right = channels == 2
                    ? static_cast<float>(samples[i * 2 + 1]) / 32768.0f
                    : left;
leftSum += left * left;
rightSum += right * right;
            mBuffer[index * 2] = left;
            mBuffer[index * 2 + 1] = right;
        }
if (framesToWrite > 0) {
    mLeftLevel.store(
            std::sqrt(leftSum / framesToWrite),
            std::memory_order_relaxed
    );

    mRightLevel.store(
            std::sqrt(rightSum / framesToWrite),
            std::memory_order_relaxed
    );
}
        mWriteFrame.store(
                writeFrame + framesToWrite,
                std::memory_order_release
        );

        return framesToWrite;
    }

    int64_t queuedFrames() const {
        const uint64_t writeFrame =
                mWriteFrame.load(std::memory_order_acquire);
        const uint64_t readFrame =
                mReadFrame.load(std::memory_order_acquire);

        return static_cast<int64_t>(writeFrame - readFrame);
    }
    float leftLevel() const {
    return mLeftLevel.load(std::memory_order_relaxed);
}

float rightLevel() const {
    return mRightLevel.load(std::memory_order_relaxed);
}
    void stop() {
    std::lock_guard<std::mutex> lock(mMutex);

    mLeftLevel.store(0.0f, std::memory_order_relaxed);
    mRightLevel.store(0.0f, std::memory_order_relaxed);

    closeLocked();
    }
    oboe::DataCallbackResult onAudioReady(
            oboe::AudioStream*,
            void* audioData,
            int32_t numFrames) override {

        auto* output = static_cast<float*>(audioData);

        const uint64_t readFrame =
                mReadFrame.load(std::memory_order_relaxed);
        const uint64_t writeFrame =
                mWriteFrame.load(std::memory_order_acquire);

        const uint64_t available = writeFrame - readFrame;
        const int32_t framesToRead = static_cast<int32_t>(
                std::min<uint64_t>(available, numFrames));
        
        for (int32_t i = 0; i < framesToRead; ++i) {
            const uint64_t index = (readFrame + i) & kMask;

            output[i * 2] = mBuffer[index * 2];
            output[i * 2 + 1] = mBuffer[index * 2 + 1];
        }

        for (int32_t i = framesToRead; i < numFrames; ++i) {
            output[i * 2] = 0.0f;
            output[i * 2 + 1] = 0.0f;
        }

        mReadFrame.store(
                readFrame + framesToRead,
                std::memory_order_release
        );

        return oboe::DataCallbackResult::Continue;
    }

private:
    static constexpr uint64_t kCapacityFrames = 65536;
    static constexpr uint64_t kMask = kCapacityFrames - 1;

    std::array<float, kCapacityFrames * 2> mBuffer{};

    std::atomic<uint64_t> mReadFrame{0};
    std::atomic<uint64_t> mWriteFrame{0};
    std::atomic<float> mLeftLevel{0.0f};
    std::atomic<float> mRightLevel{0.0f};
    std::shared_ptr<oboe::AudioStream> mStream;
    std::mutex mMutex;

    void closeLocked() {
        if (mStream) {
            mStream->requestStop();
            mStream->close();
            mStream.reset();
        }
    }
};

static PcmEngine gPcmEngine;

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_asaudio_dlms_NativeDsp_startPcmStream(
        JNIEnv*,
        jobject,
        jint sampleRate) {
    return gPcmEngine.start(sampleRate) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_asaudio_dlms_NativeDsp_writePcm(
        JNIEnv* env,
        jobject,
        jshortArray samples,
        jint offset,
        jint sampleCount,
        jint channels) {

    if (samples == nullptr || offset < 0 || sampleCount <= 0) {
        return 0;
    }

    const jsize length = env->GetArrayLength(samples);

    if (offset > length || sampleCount > length - offset) {
        return 0;
    }

    jshort* data = env->GetShortArrayElements(samples, nullptr);
    if (data == nullptr) return 0;

    const int32_t written = gPcmEngine.write(
            reinterpret_cast<const int16_t*>(data + offset),
            sampleCount,
            channels
    );

    env->ReleaseShortArrayElements(samples, data, JNI_ABORT);

    return written;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_asaudio_dlms_NativeDsp_getPcmQueuedFrames(
        JNIEnv*,
        jobject) {
    return static_cast<jlong>(gPcmEngine.queuedFrames());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_asaudio_dlms_NativeDsp_stopPcmStream(
        JNIEnv*,
        jobject) {
    gPcmEngine.stop();
}
extern "C"
JNIEXPORT jfloat JNICALL
Java_com_asaudio_dlms_NativeDsp_getPcmLeftLevel(
        JNIEnv*,
        jobject) {
    return gPcmEngine.leftLevel();
}

extern "C"
JNIEXPORT jfloat JNICALL
Java_com_asaudio_dlms_NativeDsp_getPcmRightLevel(
        JNIEnv*,
        jobject) {
    return gPcmEngine.rightLevel();
}
