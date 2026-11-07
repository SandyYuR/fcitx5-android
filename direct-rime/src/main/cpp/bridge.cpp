#include "engine.h"
#include "jni-strings.h"
#include <jni.h>
#include <algorithm>
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
jclass snapshotClass = nullptr;
jmethodID snapshotConstructor = nullptr;
jmethodID snapshotReuse = nullptr;
jobject previousSnapshot = nullptr;
std::optional<direct_rime::Snapshot> previousPresentation;
jclass rawInputClass = nullptr;
jmethodID rawInputConstructor = nullptr;
constexpr auto snapshotSignature =
    "(JJJZILjava/lang/String;Ljava/lang/String;Ljava/lang/String;ZIZI[Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;[Ljava/lang/String;J)V";
jobject newSnapshot(JNIEnv* env, const direct_rime::Snapshot& s) {
    // One JNI result owns the commit and the corresponding UI state. Nothing is
    // delivered through a dropping SharedFlow or a native background callback.
    return env->NewObject(snapshotClass, snapshotConstructor, static_cast<jlong>(s.generation), static_cast<jlong>(s.revision), static_cast<jlong>(s.optionsRevision),
        static_cast<jboolean>(s.handled), static_cast<jint>(s.gate), string(env, s.commit), string(env, s.preedit),
        string(env, s.schema), static_cast<jboolean>(s.ascii), static_cast<jint>(s.page),
        static_cast<jboolean>(s.lastPage), static_cast<jint>(s.cursor), strings(env, s.candidates),
        strings(env, s.comments), strings(env, s.tabs),
        string(env, s.schemaName), static_cast<jint>(s.highlighted), string(env, s.preview), strings(env, s.labels), static_cast<jlong>(s.inputStatistics));
}
void clearSnapshotCache(JNIEnv* env) {
    if (previousSnapshot) env->DeleteGlobalRef(previousSnapshot);
    previousSnapshot = nullptr;
    previousPresentation.reset();
}
jobject snapshot(JNIEnv* env, direct_rime::Snapshot s) {
    // Always return a new revision-bearing Snapshot. Reuse only immutable display
    // values; commits and handled actions must still take their normal path.
    auto result = previousSnapshot && previousPresentation && s.canReusePresentation(*previousPresentation)
        ? env->CallStaticObjectMethod(snapshotClass, snapshotReuse, previousSnapshot, static_cast<jlong>(s.revision), static_cast<jlong>(s.inputStatistics))
        : newSnapshot(env, s);
    if (!result || env->ExceptionCheck()) return result;
    const bool cacheable = s.canCachePresentation();
    clearSnapshotCache(env);
    if (cacheable) {
        previousSnapshot = env->NewGlobalRef(result);
        if (previousSnapshot) {
            std::string().swap(s.commit); // Never retain an extra native copy of committed text.
            previousPresentation = std::move(s);
        }
    }
    return result;
}
void error(JNIEnv* env, const std::exception& e) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), e.what());
}
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    auto cacheClass = [env](const char* name) -> jclass {
        auto local = env->FindClass(name);
        if (!local) return nullptr;
        auto global = static_cast<jclass>(env->NewGlobalRef(local));
        env->DeleteLocalRef(local);
        return global;
    };
    snapshotClass = cacheClass("org/fcitx/directrime/Snapshot");
    if (snapshotClass) snapshotConstructor = env->GetMethodID(snapshotClass, "<init>", snapshotSignature);
    if (snapshotConstructor) snapshotReuse = env->GetStaticMethodID(snapshotClass, "reusePresentation",
        "(Lorg/fcitx/directrime/Snapshot;JJ)Lorg/fcitx/directrime/Snapshot;");
    if (snapshotReuse) stringArrayClass = cacheClass("java/lang/String");
    if (stringArrayClass) rawInputClass = cacheClass("org/fcitx/directrime/RawInputState");
    if (rawInputClass) rawInputConstructor = env->GetMethodID(rawInputClass, "<init>", "(Ljava/lang/String;I)V");
    if (!snapshotConstructor || !snapshotReuse || !stringArrayClass || !rawInputConstructor) {
        if (snapshotClass) env->DeleteGlobalRef(snapshotClass);
        if (stringArrayClass) env->DeleteGlobalRef(stringArrayClass);
        if (rawInputClass) env->DeleteGlobalRef(rawInputClass);
        snapshotClass = nullptr;
        stringArrayClass = nullptr;
        rawInputClass = nullptr;
        snapshotConstructor = nullptr;
        snapshotReuse = nullptr;
        rawInputConstructor = nullptr;
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}
extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return;
    clearSnapshotCache(env);
    env->DeleteGlobalRef(snapshotClass);
    env->DeleteGlobalRef(stringArrayClass);
    env->DeleteGlobalRef(rawInputClass);
    snapshotClass = nullptr;
    stringArrayClass = nullptr;
    snapshotConstructor = nullptr;
    snapshotReuse = nullptr;
    rawInputClass = nullptr;
    rawInputConstructor = nullptr;
}

#define JNI_METHOD(name) Java_org_fcitx_directrime_Native_##name
extern "C" JNIEXPORT void JNICALL JNI_METHOD(start)(JNIEnv* env, jobject, jstring shared, jstring user,
                                                    jstring version, jboolean fullCheck) {
    try {
        if (!engine) engine = std::make_unique<Engine>(rime_get_api());
        instance().start(string(env, shared), string(env, user), string(env, version), fullCheck);
        clearSnapshotCache(env);
    } catch (const std::exception& e) { error(env, e); }
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(stop)(JNIEnv* env, jobject) {
    try { if (engine) instance().stop(); clearSnapshotCache(env); } catch (const std::exception& e) { error(env, e); }
}
extern "C" JNIEXPORT jobject JNICALL JNI_METHOD(begin)(JNIEnv* env, jobject, jlong generation, jstring program) {
    try { return snapshot(env, instance().begin(generation, string(env, program))); }
    catch (const std::exception& e) { error(env, e); return nullptr; }
}
extern "C" JNIEXPORT jobject JNICALL JNI_METHOD(beginInput)(JNIEnv* env, jobject, jlong generation, jstring program, jboolean noLearning) {
    try { return snapshot(env, instance().begin(generation, string(env, program), noLearning)); }
    catch (const std::exception& e) { error(env, e); return nullptr; }
}
extern "C" JNIEXPORT jlongArray JNICALL JNI_METHOD(sessionStats)(JNIEnv* env, jobject) {
    try {
        const auto values = instance().sessionStats();
        jlong data[] = {values[0], values[1], values[2]};
        auto result = env->NewLongArray(3);
        if (result) env->SetLongArrayRegion(result, 0, 3, data);
        return result;
    } catch (const std::exception& e) { error(env, e); return nullptr; }
}
extern "C" JNIEXPORT jlongArray JNICALL JNI_METHOD(operationTiming)(JNIEnv* env, jobject) {
    try {
        const auto values = instance().operationTiming();
        jlong data[16];
        std::copy(values.begin(), values.end(), data);
        auto result = env->NewLongArray(16);
        if (result) env->SetLongArrayRegion(result, 0, 16, data);
        return result;
    } catch (const std::exception& e) { error(env, e); return nullptr; }
}
extern "C" JNIEXPORT jobject JNICALL JNI_METHOD(end)(JNIEnv* env, jobject, jlong generation) {
    try { return snapshot(env, instance().end(generation)); }
    catch (const std::exception& e) { error(env, e); return nullptr; }
}
extern "C" JNIEXPORT jobject JNICALL JNI_METHOD(key)(JNIEnv* env, jobject, jlong generation, jint sym, jint mask, jboolean release, jboolean statistics) {
    try { return snapshot(env, instance().key(generation, sym, mask, release, statistics)); }
    catch (const std::exception& e) { error(env, e); return nullptr; }
}
extern "C" JNIEXPORT jobject JNICALL JNI_METHOD(action)(JNIEnv* env, jobject, jlong generation, jlong revision, jint action, jint index, jstring value, jboolean statistics) {
    try { return snapshot(env, instance().action(generation, revision, action, index, string(env, value), statistics)); }
    catch (const std::exception& e) { error(env, e); return nullptr; }
}
extern "C" JNIEXPORT jboolean JNICALL JNI_METHOD(deploy)(JNIEnv* env, jobject) {
    try { const bool ok = instance().deploy(); clearSnapshotCache(env); return ok; }
    catch (const std::exception& e) { error(env, e); return false; }
}
extern "C" JNIEXPORT jobject JNICALL JNI_METHOD(rawInput)(JNIEnv* env, jobject, jlong generation, jlong revision) {
    try {
        auto value = instance().rawInput(generation, revision);
        if (!value) return nullptr;
        auto text = string(env, value->text);
        if (!text) return nullptr;
        auto result = env->NewObject(rawInputClass, rawInputConstructor, text, static_cast<jint>(value->caret));
        env->DeleteLocalRef(text);
        return result;
    } catch (const std::exception& e) { error(env, e); return nullptr; }
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
    try { const bool ok = instance().sync(); clearSnapshotCache(env); return ok; }
    catch (const std::exception& e) { error(env, e); return false; }
}

extern "C" JNIEXPORT jobjectArray JNICALL JNI_METHOD(candidates)(JNIEnv* env, jobject, jlong generation, jint offset, jint limit) {
    try { return strings(env, instance().candidates(generation, offset, limit)); }
    catch (const std::exception& e) { error(env, e); return nullptr; }
}
