#include <jni.h>
#include <oboe/Oboe.h>

extern "C"
JNIEXPORT jstring JNICALL
Java_com_asaudio_dlms_NativeDsp_getEngineStatus(
        JNIEnv* env,
        jobject /* this */) {

    const char* status = "AS AUDIO Oboe DSP Engine Ready";

    return env->NewStringUTF(status);
}
