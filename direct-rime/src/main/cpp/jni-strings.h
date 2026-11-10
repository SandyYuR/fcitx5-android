#pragma once
#include "engine.h"
#include <jni.h>
#include <limits>
#include <stdexcept>
namespace direct_rime::jni {
// Global reference populated once by JNI_OnLoad, before any entry point is used.
inline jclass stringArrayClass = nullptr;

template <typename T>
class LocalRef {
public:
    LocalRef(JNIEnv* env, T value) : env_(env), value_(value) {}
    LocalRef(const LocalRef&) = delete;
    LocalRef& operator=(const LocalRef&) = delete;
    ~LocalRef() { if (value_) env_->DeleteLocalRef(value_); }
    explicit operator bool() const { return value_ != nullptr; }
    T get() const { return value_; }
    T release() { auto value = value_; value_ = nullptr; return value; }
private:
    JNIEnv* env_;
    T value_;
};

class StringChars {
public:
    StringChars(JNIEnv* env, jstring source, const jchar* chars) : env_(env), source_(source), chars_(chars) {}
    StringChars(const StringChars&) = delete;
    StringChars& operator=(const StringChars&) = delete;
    ~StringChars() { env_->ReleaseStringChars(source_, chars_); }
private:
    JNIEnv* env_;
    jstring source_;
    const jchar* chars_;
};

inline std::string string(JNIEnv* env, jstring input) {
    if (!input) return {};
    // Java UTF-16 -> standard UTF-8, including supplementary-plane schema paths.
    const jchar* chars = env->GetStringChars(input, nullptr);
    if (!chars) throw std::runtime_error("cannot read JNI string");
    StringChars cleanup(env, input, chars);
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
    return result;
}
inline jstring string(JNIEnv* env, const std::string& input, std::u16string& decoded) {
    if (!direct_rime::utf16(input, decoded)) decoded = u"\ufffd";
    if (decoded.size() > static_cast<size_t>(std::numeric_limits<jsize>::max()))
        throw std::length_error("JNI string is too long");
    return env->NewString(reinterpret_cast<const jchar*>(decoded.data()), static_cast<jsize>(decoded.size()));
}
inline jstring string(JNIEnv* env, const std::string& input) {
    std::u16string decoded;
    return string(env, input, decoded);
}
inline jobjectArray strings(JNIEnv* env, const std::vector<std::string>& values,
                            std::u16string& decoded) {
    if (values.size() > static_cast<size_t>(std::numeric_limits<jsize>::max()))
        throw std::length_error("JNI string array is too long");
    const auto count = static_cast<jsize>(values.size());
    LocalRef<jobjectArray> result(env, env->NewObjectArray(count, stringArrayClass, nullptr));
    if (!result) return nullptr;
    for (jsize i = 0; i < count; ++i) {
        LocalRef<jstring> value(env, string(env, values[static_cast<size_t>(i)], decoded));
        if (!value) return nullptr;
        env->SetObjectArrayElement(result.get(), i, value.get());
        if (env->ExceptionCheck()) return nullptr;
    }
    return result.release();
}
inline jobjectArray strings(JNIEnv* env, const std::vector<std::string>& values) {
    std::u16string decoded;
    return strings(env, values, decoded);
}
}
