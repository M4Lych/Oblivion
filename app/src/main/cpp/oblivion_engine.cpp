#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>

#include <mutex>
#include <string>
#include <sys/stat.h>

#define LOG_TAG "OblivionNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

std::mutex g_loader_mutex;
void* g_engine_handle = nullptr;
void* g_cxx_handle = nullptr;
void* g_fmod_handle = nullptr;

bool is_regular_file(const std::string& path) {
    struct stat info {};
    return stat(path.c_str(), &info) == 0 && S_ISREG(info.st_mode);
}

void* open_optional_dependency(const std::string& path, const char* label) {
    if (!is_regular_file(path)) {
        LOGI("Optional dependency is not staged: %s", label);
        return nullptr;
    }

    dlerror();
    void* handle = dlopen(path.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (handle == nullptr) {
        const char* error = dlerror();
        LOGI("Optional dependency load failed (%s): %s", label,
             error == nullptr ? "unknown dynamic linker error" : error);
    } else {
        LOGI("Optional dependency mapped: %s", label);
    }
    return handle;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_m4lych_oblivion_engine_OblivionGameActivity_bootstrapEngine(
    JNIEnv* env, jobject /* activity */, jstring sandbox_dir) {
    if (env == nullptr || sandbox_dir == nullptr) {
        LOGE("Native loader received a null environment or staging directory");
        return JNI_FALSE;
    }

    const char* dir_chars = env->GetStringUTFChars(sandbox_dir, nullptr);
    if (dir_chars == nullptr) {
        LOGE("Could not read the staging directory string");
        return JNI_FALSE;
    }
    const std::string base_dir(dir_chars);
    env->ReleaseStringUTFChars(sandbox_dir, dir_chars);

    if (base_dir.empty()) {
        LOGE("The private staging directory path is empty");
        return JNI_FALSE;
    }

    std::lock_guard<std::mutex> lock(g_loader_mutex);
    if (g_engine_handle != nullptr) {
        LOGI("Engine library was already mapped in this process");
        return JNI_TRUE;
    }

    const std::string cxx_path = base_dir + "/libc++_shared.so";
    const std::string fmod_path = base_dir + "/libfmod.so";
    const std::string engine_path = base_dir + "/libminecraftpe.so";

    LOGI("Checking authorized runtime files in Oblivion's private staging directory");
    g_cxx_handle = open_optional_dependency(cxx_path, "libc++_shared.so");
    g_fmod_handle = open_optional_dependency(fmod_path, "libfmod.so");

    if (!is_regular_file(engine_path)) {
        LOGE("Engine library is not staged; no runtime was loaded");
        return JNI_FALSE;
    }

    dlerror();
    g_engine_handle = dlopen(engine_path.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (g_engine_handle == nullptr) {
        const char* error = dlerror();
        LOGE("Engine library mapping failed: %s",
             error == nullptr ? "unknown dynamic linker error" : error);
        return JNI_FALSE;
    }

    // dlopen only maps the shared object. This baseline does not call android_main,
    // GameActivity initialization, or any undocumented Minecraft entry point.
    LOGI("Engine shared object mapped; no entry point or gameplay session was started");
    return JNI_TRUE;
}
