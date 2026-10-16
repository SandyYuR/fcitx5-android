/* SPDX-License-Identifier: LGPL-2.1-or-later */
// Only the layout key syntax and INI format are reused. No Fcitx Instance, event
// loop, input context, addon loader or Rime addon is linked into this backend.
#include <fcitx-utils/key.h>
#include <fcitx-config/iniparser.h>
#include <fcitx-config/rawconfig.h>
#include "../../../../direct-rime/src/main/cpp/jni-strings.h"
using namespace direct_rime::jni;
namespace {
jobject key(JNIEnv* env, const fcitx::Key& value) {
    auto type = env->FindClass("org/fcitx/fcitx5/android/core/Key");
    auto init = env->GetMethodID(type, "<init>", "(IILjava/lang/String;Ljava/lang/String;)V");
    return env->NewObject(type, init, value.sym(), static_cast<jint>(value.states()),
        string(env, value.toString(fcitx::KeyStringFormat::Portable)),
        string(env, value.toString(fcitx::KeyStringFormat::Localized)));
}
jobject config(JNIEnv* env, const fcitx::RawConfig& value) {
    if (env->PushLocalFrame(16) != JNI_OK) return nullptr;
    auto type = env->FindClass("org/fcitx/fcitx5/android/core/RawConfig");
    auto init = env->GetMethodID(type, "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[Lorg/fcitx/fcitx5/android/core/RawConfig;)V");
    jobjectArray children = nullptr;
    if (value.hasSubItems()) {
        children = env->NewObjectArray(value.subItemsSize(), type, nullptr);
        int i = 0;
        for (const auto& name : value.subItems()) {
            auto child = config(env, *value.get(name));
            env->SetObjectArrayElement(children, i++, child); env->DeleteLocalRef(child);
        }
    }
    auto result = env->NewObject(type, init, string(env, value.name()), string(env, value.comment()), string(env, value.value()), children);
    if (children) env->DeleteLocalRef(children);
    env->DeleteLocalRef(type);
    return env->PopLocalFrame(result);
}
void fill(JNIEnv* env, jobject value, fcitx::RawConfig& output) {
    auto type = env->GetObjectClass(value);
    auto values = static_cast<jobjectArray>(env->GetObjectField(value, env->GetFieldID(type, "subItems", "[Lorg/fcitx/fcitx5/android/core/RawConfig;")));
    if (values) {
        for (jsize i = 0; i < env->GetArrayLength(values); ++i) {
            auto child = env->GetObjectArrayElement(values, i);
            auto name = static_cast<jstring>(env->GetObjectField(child, env->GetFieldID(type, "name", "Ljava/lang/String;")));
            fill(env, child, *output.get(string(env, name), true));
            env->DeleteLocalRef(name); env->DeleteLocalRef(child);
        }
        env->DeleteLocalRef(values);
    } else {
        auto text = static_cast<jstring>(env->GetObjectField(value, env->GetFieldID(type, "value", "Ljava/lang/String;")));
        output = string(env, text); env->DeleteLocalRef(text);
    }
    env->DeleteLocalRef(type);
}
}
extern "C" JNIEXPORT jobject JNICALL Java_org_fcitx_fcitx5_android_core_Key_parse(JNIEnv* env, jclass, jstring raw) {
    return key(env, fcitx::Key(string(env, raw)));
}
extern "C" JNIEXPORT jobject JNICALL Java_org_fcitx_fcitx5_android_core_Key_create(JNIEnv* env, jclass, jint sym, jint states) {
    return key(env, fcitx::Key(fcitx::KeySym(sym), fcitx::KeyStates(states)));
}
extern "C" JNIEXPORT jobject JNICALL Java_org_fcitx_fcitx5_android_utils_Ini_readFromIni(JNIEnv* env, jclass, jstring src) {
    FILE* file = std::fopen(string(env, src).c_str(), "rb");
    if (!file) return nullptr;
    fcitx::RawConfig value; fcitx::readFromIni(value, file); std::fclose(file);
    return config(env, value);
}
extern "C" JNIEXPORT void JNICALL Java_org_fcitx_fcitx5_android_utils_Ini_writeAsIni(JNIEnv* env, jclass, jstring dest, jobject value) {
    FILE* file = std::fopen(string(env, dest).c_str(), "wb");
    if (!file) { env->ThrowNew(env->FindClass("java/io/IOException"), "Unable to open INI file"); return; }
    fcitx::RawConfig result; fill(env, value, result); fcitx::writeAsIni(result, file); std::fclose(file);
}
