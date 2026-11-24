
#include <jni.h>
#include <oboe/Oboe.h>

#include <atomic>
#include <cmath>
#include <cstdint>
#include <memory>
#include <mutex>

class ToneEngine : public oboe::AudioStreamDataCallback {
public:
    bool startTestTone() {
        std::lock_guard<std::mutex> lock(mMutex);

        if (mStream) {
            mStream->requestStop();
            mStream->close();
            mStream.reset();
        }

        mFramesPlayed.store(0);
        mPhase = 0.0;

        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)
                ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
                ->setSharingMode(oboe::SharingMode::Shared)
                ->setFormat(oboe::AudioFormat::Float)
                ->setChannelCount(2)
                ->setSampleRate(oboe::Unspecified)
                ->setUsage(oboe::Usage::Media)
                ->setContentType(oboe::ContentType::Music)
                ->setDataCallback(this);

        oboe::Result result = builder.openStream(mStream);
        if (result != oboe::Result::OK || !mStream) {
            return false;
        }

        result = mStream->requestStart();
        if (result != oboe::Result::OK) {
            mStream->close();
            mStream.reset();
            return false;
        }

        return true;
    }

    void stop() {
        std::lock_guard<std::mutex> lock(mMutex);
        if (mStream) {
            mStream->requestStop();
            mStream->close();
            mStream.reset();
        }
    }

    oboe::DataCallbackResult onAudioReady(
            oboe::AudioStream* audioStream,
            void* audioData,
            int32_t numFrames) override {

        auto* output = static_cast<float*>(audioData);
        const double pi = 3.14159265358979323846;
        const double sampleRate =
                static_cast<double>(audioStream->getSampleRate());
        const double phaseStep = (2.0 * pi * 440.0) / sampleRate;

        for (int32_t frame = 0; frame < numFrames; ++frame) {
            const float sample =
                    static_cast<float>(0.12 * std::sin(mPhase));

            output[frame * 2] = sample;
            output[frame * 2 + 1] = sample;

            mPhase += phaseStep;
            if (mPhase >= 2.0 * pi) {
                mPhase -= 2.0 * pi;
            }
        }

        const int64_t totalFrames =
                mFramesPlayed.fetch_add(numFrames) + numFrames;

        if (totalFrames >= static_cast<int64_t>(sampleRate * 2.0)) {
            return oboe::DataCallbackResult::Stop;
        }

        return oboe::DataCallbackResult::Continue;
    }

private:
    std::shared_ptr<oboe::AudioStream> mStream;
    std::atomic<int64_t> mFramesPlayed{0};
    std::mutex mMutex;
    double mPhase = 0.0;
};

static ToneEngine gToneEngine;

extern "C"
JNIEXPORT jstring JNICALL
Java_com_asaudio_dlms_NativeDsp_getEngineStatus(
        JNIEnv* env,
        jobject) {
    return env->NewStringUTF("AS AUDIO Oboe DSP Engine Ready");
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_asaudio_dlms_NativeDsp_startTestTone(
        JNIEnv*,
        jobject) {
    return gToneEngine.startTestTone() ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_asaudio_dlms_NativeDsp_stopTestTone(
        JNIEnv*,
        jobject) {
    gToneEngine.stop();
}
