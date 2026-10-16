#include "engine.h"
#include <jni.h>
#include <memory>
#include <stdexcept>

using direct_rime::Engine;
namespace {
// Accessed exclusively on rime-main. Retain the object across finalize/initialize
// because librime's setup (and glog initialization) is process-wide and runs once.
std::unique_ptr<Engine> engine;

std::string string(JNIEnv* env, jstring input) {
    if (!input) return {};
    // Java UTF-16 -> standard UTF-8, including supplementary-plane schema paths.
    const jchar* chars = env->GetStringChars(input, nullptr);
    if (!chars) throw std::runtime_error("cannot read JNI string");
    std::string result;
    const auto length = env->GetStringLength(input);
    for (jsize i = 0; i < length; ++i) {
        uint32_t cp = chars[i];
        if (cp >= 0xd800 && cp <= 0xdbff && i + 1 < length && chars[i + 1] >= 0xdc00 && chars[i + 1] <= 0xdfff)
            cp = 0x10000 + ((cp - 0xd800) << 10) + (chars[++i] - 0xdc00);
        else if (cp >= 0xd800 && cp <= 0xdfff) cp = 0xfffd;
        if (cp < 0x80) result += static_cast<char>(cp);
        else if (cp < 0x800) { result += static_cast<char>(0xc0 | (cp >> 6)); result += static_cast<char>(0x80 | (cp & 63)); }
        else if (cp < 0x10000) {
            result += static_cast<char>(0xe0 | (cp >> 12)); result += static_cast<char>(0x80 | ((cp >> 6) & 63)); result += static_cast<char>(0x80 | (cp & 63));
        } else {
            result += static_cast<char>(0xf0 | (cp >> 18)); result += static_cast<char>(0x80 | ((cp >> 12) & 63));
            result += static_cast<char>(0x80 | ((cp >> 6) & 63)); result += static_cast<char>(0x80 | (cp & 63));
        }
    }
    env->ReleaseStringChars(input, chars);
    return result;
}
jstring string(JNIEnv* env, const std::string& input) {
    std::u16string decoded;
    if (!direct_rime::utf16(input, decoded)) decoded = u"\ufffd";
    return env->NewString(reinterpret_cast<const jchar*>(decoded.data()), decoded.size());
}
jobjectArray strings(JNIEnv* env, const std::vector<std::string>& values) {
    auto type = env->FindClass("java/lang/String");
    auto result = env->NewObjectArray(values.size(), type, nullptr);
    env->DeleteLocalRef(type);
    for (size_t i = 0; i < values.size(); ++i) {
        auto value = string(env, values[i]);
        env->SetObjectArrayElement(result, i, value); env->DeleteLocalRef(value);
    }
    return result;
}
jobject snapshot(JNIEnv* env, const direct_rime::Snapshot& s) {
    auto type = env->FindClass("org/fcitx/directrime/Snapshot");
    auto constructor = env->GetMethodID(type, "<init>",
        "(JJZILjava/lang/String;Ljava/lang/String;Ljava/lang/String;ZIZI[Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;)V");
    if (!constructor) return nullptr;
    // One JNI result owns the commit and the corresponding UI state. Nothing is
    // delivered through a dropping SharedFlow or a native background callback.
    return env->NewObject(type, constructor, static_cast<jlong>(s.generation), static_cast<jlong>(s.revision),
        static_cast<jboolean>(s.handled), static_cast<jint>(s.gate), string(env, s.commit), string(env, s.preedit),
        string(env, s.schema), static_cast<jboolean>(s.ascii), static_cast<jint>(s.page),
        static_cast<jboolean>(s.lastPage), static_cast<jint>(s.cursor), strings(env, s.candidates),
        strings(env, s.comments), strings(env, s.codes), strings(env, s.tabs));
}
void error(JNIEnv* env, const std::exception& e) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), e.what());
}
}

#define JNI_METHOD(name) Java_org_fcitx_directrime_Native_##name
extern "C" JNIEXPORT void JNICALL JNI_METHOD(start)(JNIEnv* env, jobject, jstring shared, jstring user) {
    try {
        if (!engine) engine = std::make_unique<Engine>(rime_get_api());
        engine->start(string(env, shared), string(env, user));
    } catch (const std::exception& e) { error(env, e); }
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(stop)(JNIEnv* env, jobject) {
    try { if (engine) engine->stop(); } catch (const std::exception& e) { error(env, e); }
}
extern "C" JNIEXPORT jobject JNICALL JNI_METHOD(begin)(JNIEnv* env, jobject, jlong generation, jstring program) {
    try { return snapshot(env, engine->begin(generation, string(env, program))); }
    catch (const std::exception& e) { error(env, e); return nullptr; }
}
extern "C" JNIEXPORT jobject JNICALL JNI_METHOD(end)(JNIEnv* env, jobject, jlong generation) {
    try { return snapshot(env, engine->end(generation)); }
    catch (const std::exception& e) { error(env, e); return nullptr; }
}
extern "C" JNIEXPORT jobject JNICALL JNI_METHOD(key)(JNIEnv* env, jobject, jlong generation, jint sym, jint mask, jboolean release) {
    try { return snapshot(env, engine->key(generation, sym, mask, release)); }
    catch (const std::exception& e) { error(env, e); return nullptr; }
}
extern "C" JNIEXPORT jobject JNICALL JNI_METHOD(action)(JNIEnv* env, jobject, jlong generation, jlong revision, jint action, jint index, jstring value) {
    try { return snapshot(env, engine->action(generation, revision, action, index, string(env, value))); }
    catch (const std::exception& e) { error(env, e); return nullptr; }
}
extern "C" JNIEXPORT jboolean JNICALL JNI_METHOD(deploy)(JNIEnv* env, jobject) {
    try { return engine->deploy(); } catch (const std::exception& e) { error(env, e); return false; }
}
extern "C" JNIEXPORT jobjectArray JNICALL JNI_METHOD(schemas)(JNIEnv* env, jobject) {
    try { return strings(env, engine->schemas()); } catch (const std::exception& e) { error(env, e); return nullptr; }
}
