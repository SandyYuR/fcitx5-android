#include "engine.h"
#include "jni-strings.h"
#include <jni.h>
#include <memory>
#include <stdexcept>

using direct_rime::Engine;
namespace {
// Accessed exclusively on rime-main. Retain the object across finalize/initialize
// because librime's setup (and glog initialization) is process-wide and runs once.
std::unique_ptr<Engine> engine;
Engine& instance() {
    if (!engine) throw std::logic_error("direct Rime has not started");
    return *engine;
}

using namespace direct_rime::jni;
jobject snapshot(JNIEnv* env, const direct_rime::Snapshot& s) {
    auto type = env->FindClass("org/fcitx/directrime/Snapshot");
    auto constructor = env->GetMethodID(type, "<init>",
        "(JJZILjava/lang/String;Ljava/lang/String;Ljava/lang/String;ZIZI[Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;[Ljava/lang/String;)V");
    if (!constructor) return nullptr;
    // One JNI result owns the commit and the corresponding UI state. Nothing is
    // delivered through a dropping SharedFlow or a native background callback.
    return env->NewObject(type, constructor, static_cast<jlong>(s.generation), static_cast<jlong>(s.revision),
        static_cast<jboolean>(s.handled), static_cast<jint>(s.gate), string(env, s.commit), string(env, s.preedit),
        string(env, s.schema), static_cast<jboolean>(s.ascii), static_cast<jint>(s.page),
        static_cast<jboolean>(s.lastPage), static_cast<jint>(s.cursor), strings(env, s.candidates),
        strings(env, s.comments), strings(env, s.codes), strings(env, s.tabs),
        string(env, s.schemaName), static_cast<jint>(s.highlighted), string(env, s.preview), strings(env, s.labels));
}
void error(JNIEnv* env, const std::exception& e) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), e.what());
}
}

#define JNI_METHOD(name) Java_org_fcitx_directrime_Native_##name
extern "C" JNIEXPORT void JNICALL JNI_METHOD(start)(JNIEnv* env, jobject, jstring shared, jstring user) {
    try {
        if (!engine) engine = std::make_unique<Engine>(rime_get_api());
        instance().start(string(env, shared), string(env, user));
    } catch (const std::exception& e) { error(env, e); }
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(stop)(JNIEnv* env, jobject) {
    try { if (engine) instance().stop(); } catch (const std::exception& e) { error(env, e); }
}
extern "C" JNIEXPORT jobject JNICALL JNI_METHOD(begin)(JNIEnv* env, jobject, jlong generation, jstring program) {
    try { return snapshot(env, instance().begin(generation, string(env, program))); }
    catch (const std::exception& e) { error(env, e); return nullptr; }
}
extern "C" JNIEXPORT jobject JNICALL JNI_METHOD(end)(JNIEnv* env, jobject, jlong generation) {
    try { return snapshot(env, instance().end(generation)); }
    catch (const std::exception& e) { error(env, e); return nullptr; }
}
extern "C" JNIEXPORT jobject JNICALL JNI_METHOD(key)(JNIEnv* env, jobject, jlong generation, jint sym, jint mask, jboolean release) {
    try { return snapshot(env, instance().key(generation, sym, mask, release)); }
    catch (const std::exception& e) { error(env, e); return nullptr; }
}
extern "C" JNIEXPORT jobject JNICALL JNI_METHOD(action)(JNIEnv* env, jobject, jlong generation, jlong revision, jint action, jint index, jstring value) {
    try { return snapshot(env, instance().action(generation, revision, action, index, string(env, value))); }
    catch (const std::exception& e) { error(env, e); return nullptr; }
}
extern "C" JNIEXPORT jboolean JNICALL JNI_METHOD(deploy)(JNIEnv* env, jobject) {
    try { return instance().deploy(); } catch (const std::exception& e) { error(env, e); return false; }
}
extern "C" JNIEXPORT jobjectArray JNICALL JNI_METHOD(schemas)(JNIEnv* env, jobject) {
    try { return strings(env, instance().schemas()); } catch (const std::exception& e) { error(env, e); return nullptr; }
}

extern "C" JNIEXPORT jobjectArray JNICALL JNI_METHOD(schemaEntries)(JNIEnv* env, jobject) {
    try { return strings(env, instance().schemaEntries()); } catch (const std::exception& e) { error(env, e); return nullptr; }
}
extern "C" JNIEXPORT jobjectArray JNICALL JNI_METHOD(options)(JNIEnv* env, jobject) {
    try { return strings(env, instance().options()); } catch (const std::exception& e) { error(env, e); return nullptr; }
}
extern "C" JNIEXPORT jstring JNICALL JNI_METHOD(schemaSelection)(JNIEnv* env, jobject) {
    try { return string(env, instance().schemaSelection()); } catch (const std::exception& e) { error(env, e); return nullptr; }
}
extern "C" JNIEXPORT jboolean JNICALL JNI_METHOD(selectSchemas)(JNIEnv* env, jobject, jobjectArray ids) {
    try {
        std::vector<std::string> values;
        for (jsize i = 0; i < env->GetArrayLength(ids); ++i) {
            auto id = static_cast<jstring>(env->GetObjectArrayElement(ids, i));
            values.push_back(string(env, id)); env->DeleteLocalRef(id);
        }
        return instance().selectSchemas(values);
    } catch (const std::exception& e) { error(env, e); return false; }
}
extern "C" JNIEXPORT jboolean JNICALL JNI_METHOD(sync)(JNIEnv* env, jobject) {
    try { return instance().sync(); } catch (const std::exception& e) { error(env, e); return false; }
}

extern "C" JNIEXPORT jobjectArray JNICALL JNI_METHOD(candidates)(JNIEnv* env, jobject, jlong generation, jint offset, jint limit) {
    try { return strings(env, instance().candidates(generation, offset, limit)); }
    catch (const std::exception& e) { error(env, e); return nullptr; }
}
