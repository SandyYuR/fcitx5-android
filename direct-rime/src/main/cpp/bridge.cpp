#include "engine.h"
#include "jni-strings.h"
#include <jni.h>
#include <algorithm>
#include <memory>
#include <stdexcept>
#if INPUT_DIAGNOSTICS
#include <android/trace.h>
#endif

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
jclass errorClass = nullptr;
jmethodID errorConstructor = nullptr;
constexpr auto snapshotSignature =
    "(JJJZILjava/lang/String;Ljava/lang/String;Ljava/lang/String;ZIZI[Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;[Ljava/lang/String;JJ)V";
jobject newSnapshot(JNIEnv* env, const direct_rime::Snapshot& s) {
    // NewString copies its UTF-16 input before returning, so all presentation
    // fields can share one grow-only conversion buffer within this snapshot.
    std::u16string decoded;
#if INPUT_DIAGNOSTICS
    class SnapshotTrace {
    public:
        SnapshotTrace() { ATrace_beginSection("rime.direct.jni_snapshot"); }
        ~SnapshotTrace() { finish(); }
        void finish() { if (active_) { ATrace_endSection(); active_ = false; } }
    private:
        bool active_ = true;
    } trace;
#endif
    // One JNI result owns the commit and the corresponding UI state. Nothing is
    // delivered through a dropping SharedFlow or a native background callback.
    LocalRef<jstring> commit(env, string(env, s.commit, decoded));
    if (!commit) return nullptr;
    LocalRef<jstring> preedit(env, string(env, s.preedit, decoded));
    if (!preedit) return nullptr;
    // Candidate payloads can dwarf schema/status strings. Convert them first so
    // a long value establishes the shared buffer's capacity in one growth.
    LocalRef<jobjectArray> candidates(env, strings(env, s.candidates, decoded));
    if (!candidates) return nullptr;
    LocalRef<jobjectArray> comments(env, strings(env, s.comments, decoded));
    if (!comments) return nullptr;
    LocalRef<jobjectArray> tabs(env, strings(env, s.tabs, decoded));
    if (!tabs) return nullptr;
    LocalRef<jstring> schema(env, string(env, s.schema, decoded));
    if (!schema) return nullptr;
    LocalRef<jstring> schemaName(env, string(env, s.schemaName, decoded));
    if (!schemaName) return nullptr;
    LocalRef<jstring> preview(env, string(env, s.preview, decoded));
    if (!preview) return nullptr;
    LocalRef<jobjectArray> labels(env, strings(env, s.labels, decoded));
    if (!labels) return nullptr;
    auto result = env->NewObject(snapshotClass, snapshotConstructor, static_cast<jlong>(s.generation), static_cast<jlong>(s.revision), static_cast<jlong>(s.optionsRevision),
        static_cast<jboolean>(s.handled), static_cast<jint>(s.gate), commit.get(), preedit.get(),
        schema.get(), static_cast<jboolean>(s.ascii), static_cast<jint>(s.page),
        static_cast<jboolean>(s.lastPage), static_cast<jint>(s.cursor), candidates.get(),
        comments.get(), tabs.get(), schemaName.get(), static_cast<jint>(s.highlighted), preview.get(), labels.get(),
        static_cast<jlong>(s.inputStatistics), static_cast<jlong>(s.tabsRevision));
#if INPUT_DIAGNOSTICS
    trace.finish();
#endif
    return result;
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
void error(JNIEnv* env, const std::exception& e) noexcept {
    // Allocation/access failures already leave the authoritative Java exception pending.
    if (env->ExceptionCheck() || !errorClass || !errorConstructor) return;
    try {
        LocalRef<jstring> message(env, string(env, std::string(e.what())));
        if (!message) return;
        LocalRef<jthrowable> exception(env, reinterpret_cast<jthrowable>(
            env->NewObject(errorClass, errorConstructor, message.get())));
        if (!exception || env->ExceptionCheck()) return;
        env->Throw(exception.get());
    } catch (...) {
        // Keep the error bridge noexcept. This ASCII fallback is valid Modified UTF-8.
        if (!env->ExceptionCheck()) env->ThrowNew(errorClass, "native error");
    }
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
    if (rawInputConstructor) errorClass = cacheClass("java/lang/IllegalStateException");
    if (errorClass) errorConstructor = env->GetMethodID(errorClass, "<init>", "(Ljava/lang/String;)V");
    if (!snapshotConstructor || !snapshotReuse || !stringArrayClass || !rawInputConstructor || !errorConstructor) {
        if (snapshotClass) env->DeleteGlobalRef(snapshotClass);
        if (stringArrayClass) env->DeleteGlobalRef(stringArrayClass);
        if (rawInputClass) env->DeleteGlobalRef(rawInputClass);
        if (errorClass) env->DeleteGlobalRef(errorClass);
        snapshotClass = nullptr;
        stringArrayClass = nullptr;
        rawInputClass = nullptr;
        errorClass = nullptr;
        snapshotConstructor = nullptr;
        snapshotReuse = nullptr;
        rawInputConstructor = nullptr;
        errorConstructor = nullptr;
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
    env->DeleteGlobalRef(errorClass);
    snapshotClass = nullptr;
    stringArrayClass = nullptr;
    snapshotConstructor = nullptr;
    snapshotReuse = nullptr;
    rawInputClass = nullptr;
    rawInputConstructor = nullptr;
    errorClass = nullptr;
    errorConstructor = nullptr;
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
        LocalRef<jstring> text(env, string(env, value->text));
        if (!text) return nullptr;
        return env->NewObject(rawInputClass, rawInputConstructor, text.get(), static_cast<jint>(value->caret));
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
        const auto count = env->GetArrayLength(ids);
        if (env->ExceptionCheck()) return false;
        for (jsize i = 0; i < count; ++i) {
            LocalRef<jstring> id(env, static_cast<jstring>(env->GetObjectArrayElement(ids, i)));
            if (!id) {
                if (env->ExceptionCheck()) return false;
                throw std::invalid_argument("schema ID must not be null");
            }
            values.push_back(string(env, id.get()));
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
