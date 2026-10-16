#pragma once
#include "engine.h"
#include <jni.h>
#include <stdexcept>
namespace direct_rime::jni {
inline std::string string(JNIEnv* env, jstring input) {
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
inline jstring string(JNIEnv* env, const std::string& input) {
    std::u16string decoded;
    if (!direct_rime::utf16(input, decoded)) decoded = u"\ufffd";
    return env->NewString(reinterpret_cast<const jchar*>(decoded.data()), decoded.size());
}
inline jobjectArray strings(JNIEnv* env, const std::vector<std::string>& values) {
    auto type = env->FindClass("java/lang/String");
    auto result = env->NewObjectArray(values.size(), type, nullptr);
    env->DeleteLocalRef(type);
    for (size_t i = 0; i < values.size(); ++i) {
        auto value = string(env, values[i]);
        env->SetObjectArrayElement(result, i, value); env->DeleteLocalRef(value);
    }
    return result;
}
}
