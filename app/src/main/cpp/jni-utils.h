/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
#ifndef FCITX5_ANDROID_JNI_UTILS_H
#define FCITX5_ANDROID_JNI_UTILS_H

#include <jni.h>

#include <string>

#include <android/log.h>

/**
 * 让 [bytes] 可以安全地交给 `JNIEnv::NewStringUTF`。
 *
 * CheckJNI 一旦收到非法 UTF-8 字节序列就会直接 `abort()` 整个进程
 * （"JNI DETECTED ERROR IN APPLICATION: input is not valid Modified UTF-8"），
 * 而 NewStringUTF 的输入来自我们无法控制的 fcitx/rime 内部：候选注释、输入 tab 的
 * label 都可能带着孤立的续字节（实测见过 Rime `get_input_tabs` 返回的 label 里出现
 * 单个 0x81），这会让输入法在打字途中整个崩掉，且因为是 native abort，Java 侧拿不到
 * 任何堆栈。
 *
 * 这里把每个非法字节替换成 U+FFFD，最坏情况是标签里多一个不可读字符，而不是 SIGABRT。
 * 合法序列原样拷过去，包括 4 字节的（emoji），JNI 能接受。
 */
inline std::string sanitizeUtf8(const char *bytes) {
    constexpr const char *kReplacement = "\xEF\xBF\xBD"; // U+FFFD
    std::string out;
    if (bytes == nullptr) return out;
    const auto *p = reinterpret_cast<const unsigned char *>(bytes);
    bool replaced = false;
    while (*p != 0) {
        const unsigned char lead = *p;
        size_t length;
        bool valid;
        if (lead < 0x80) {
            length = 1;
            valid = true;
        } else if (lead >= 0xC2 && lead <= 0xDF) {
            length = 2;
            valid = (p[1] & 0xC0) == 0x80;
        } else if (lead >= 0xE0 && lead <= 0xEF) {
            length = 3;
            valid = (p[1] & 0xC0) == 0x80 && (p[2] & 0xC0) == 0x80
                    && !(lead == 0xE0 && p[1] < 0xA0)   // 过长编码
                    && !(lead == 0xED && p[1] > 0x9F);  // 代理对半区
        } else if (lead >= 0xF0 && lead <= 0xF4) {
            length = 4;
            valid = (p[1] & 0xC0) == 0x80 && (p[2] & 0xC0) == 0x80 && (p[3] & 0xC0) == 0x80
                    && !(lead == 0xF0 && p[1] < 0x90)   // 过长编码
                    && !(lead == 0xF4 && p[1] > 0x8F);  // 超出 U+10FFFF
        } else {
            length = 1;
            valid = false;
        }
        if (!valid) {
            // 截断的多字节序列会撞上结尾的 NUL，而 NUL 不是续字节，所以上面的检查
            // 绝不会读过缓冲区末尾（`&&` 短路保证了这一点）。
            out += kReplacement;
            replaced = true;
            p += 1;
            continue;
        }
        out.append(reinterpret_cast<const char *>(p), length);
        p += length;
    }
    if (replaced) {
        __android_log_print(ANDROID_LOG_WARN, "Fcitx5Jni",
                            "sanitizeUtf8: replaced invalid UTF-8 byte(s) before NewStringUTF");
    }
    return out;
}

void throwJavaException(JNIEnv *env, const char *msg) {
    jclass c = env->FindClass("java/lang/Exception");
    env->ThrowNew(c, msg);
    env->DeleteLocalRef(c);
}

class CString {
private:
    JNIEnv *env_;
    jstring str_;
    const char *chr_;

public:
    CString(JNIEnv *env, jstring str)
            : env_(env), str_(str), chr_(env->GetStringUTFChars(str, nullptr)) {}

    ~CString() {
        env_->ReleaseStringUTFChars(str_, chr_);
    }

    operator std::string() { return chr_; }

    operator const char *() { return chr_; }

    const char *operator*() { return chr_; }
};

template<typename T = jobject>
class JRef {
private:
    JNIEnv *env_;
    T ref_;

public:
    JRef(JNIEnv *env, jobject ref) : env_(env), ref_(reinterpret_cast<T>(ref)) {}

    ~JRef() {
        env_->DeleteLocalRef(ref_);
    }

    operator T() { return ref_; }

    T operator*() { return ref_; }
};

class JString {
private:
    JNIEnv *env_;
    jstring jstring_;

public:
    /**
     * 注意：这里必须走 [sanitizeUtf8]。构造 JString 的地方读的都是 fcitx/rime 的内部
     * 字符串（候选、注释、tab label、翻译），其中可能出现非法 UTF-8 字节；直接丢给
     * NewStringUTF 会触发 CheckJNI 的 abort，整个输入法进程随之消失。
     * 临时 std::string 的生命周期覆盖 NewStringUTF 这次调用，指针不会悬垂。
     */
    JString(JNIEnv *env, const char *chars)
            : env_(env), jstring_(env->NewStringUTF(sanitizeUtf8(chars).c_str())) {}

    JString(JNIEnv *env, const std::string &string)
            : JString(env, string.c_str()) {}

    ~JString() {
        env_->DeleteLocalRef(jstring_);
    }

    operator jstring() { return jstring_; }

    jstring operator*() { return jstring_; }
};

class JEnv {
private:
    JNIEnv *env = nullptr;

public:
    explicit JEnv(JavaVM *jvm) {
        if (jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
            jvm->AttachCurrentThread(&env, nullptr);
        }
    }

    operator JNIEnv *() { return env; }

    JNIEnv *operator->() { return env; }
};

class GlobalRefSingleton {
public:
    JavaVM *jvm;

    jclass Object;

    jclass String;

    jclass Integer;
    jmethodID IntegerInit;

    jclass Boolean;
    jmethodID BooleanInit;

    jclass Fcitx;
    jmethodID ShowToast;
    jmethodID HandleFcitxEvent;

    jclass InputMethodEntry;
    jmethodID InputMethodEntryInit;
    jmethodID InputMethodEntryInitWithSubMode;

    jclass RawConfig;
    jfieldID RawConfigName;
    jfieldID RawConfigValue;
    jfieldID RawConfigSubItems;
    jmethodID RawConfigInit;
    jmethodID RawConfigSetSubItems;

    jclass AddonInfo;
    jmethodID AddonInfoInit;

    jclass Action;
    jmethodID ActionInit;

    jclass Key;
    jmethodID KeyInit;

    jclass FormattedText;
    jmethodID FormattedTextFromByteCursor;

    jclass CandidateAction;
    jmethodID CandidateActionInit;

    jclass Candidate;
    jmethodID CandidateInit;

    explicit GlobalRefSingleton(JavaVM *jvm_) : jvm(jvm_) {
        JNIEnv *env;
        jvm->AttachCurrentThread(&env, nullptr);

        Object = reinterpret_cast<jclass>(env->NewGlobalRef(env->FindClass("java/lang/Object")));

        String = reinterpret_cast<jclass>(env->NewGlobalRef(env->FindClass("java/lang/String")));

        Integer = reinterpret_cast<jclass>(env->NewGlobalRef(env->FindClass("java/lang/Integer")));
        IntegerInit = env->GetMethodID(Integer, "<init>", "(I)V");

        Boolean = reinterpret_cast<jclass>(env->NewGlobalRef(env->FindClass("java/lang/Boolean")));
        BooleanInit = env->GetMethodID(Boolean, "<init>", "(Z)V");

        Fcitx = reinterpret_cast<jclass>(env->NewGlobalRef(env->FindClass("org/fcitx/fcitx5/android/core/Fcitx")));
        ShowToast = env->GetStaticMethodID(Fcitx, "showToast", "(Ljava/lang/String;)V");
        HandleFcitxEvent = env->GetStaticMethodID(Fcitx, "handleFcitxEvent", "(I[Ljava/lang/Object;)V");

        InputMethodEntry = reinterpret_cast<jclass>(env->NewGlobalRef(env->FindClass("org/fcitx/fcitx5/android/core/InputMethodEntry")));
        InputMethodEntryInit = env->GetMethodID(InputMethodEntry, "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)V");
        InputMethodEntryInitWithSubMode = env->GetMethodID(InputMethodEntry, "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ZLjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");

        RawConfig = reinterpret_cast<jclass>(env->NewGlobalRef(env->FindClass("org/fcitx/fcitx5/android/core/RawConfig")));
        RawConfigName = env->GetFieldID(RawConfig, "name", "Ljava/lang/String;");
        RawConfigValue = env->GetFieldID(RawConfig, "value", "Ljava/lang/String;");
        RawConfigSubItems = env->GetFieldID(RawConfig, "subItems", "[Lorg/fcitx/fcitx5/android/core/RawConfig;");
        RawConfigInit = env->GetMethodID(RawConfig, "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[Lorg/fcitx/fcitx5/android/core/RawConfig;)V");
        RawConfigSetSubItems = env->GetMethodID(RawConfig, "setSubItems", "([Lorg/fcitx/fcitx5/android/core/RawConfig;)V");

        AddonInfo = reinterpret_cast<jclass>(env->NewGlobalRef(env->FindClass("org/fcitx/fcitx5/android/core/AddonInfo")));
        AddonInfoInit = env->GetMethodID(AddonInfo, "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IZZZZ[Ljava/lang/String;[Ljava/lang/String;)V");

        Action = reinterpret_cast<jclass>(env->NewGlobalRef(env->FindClass("org/fcitx/fcitx5/android/core/Action")));
        ActionInit = env->GetMethodID(Action, "<init>", "(IZZZLjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[Lorg/fcitx/fcitx5/android/core/Action;)V");

        Key = reinterpret_cast<jclass>(env->NewGlobalRef(env->FindClass("org/fcitx/fcitx5/android/core/Key")));
        KeyInit = env->GetMethodID(Key, "<init>", "(IILjava/lang/String;Ljava/lang/String;)V");

        FormattedText = reinterpret_cast<jclass>(env->NewGlobalRef(env->FindClass("org/fcitx/fcitx5/android/core/FormattedText")));
        FormattedTextFromByteCursor = env->GetStaticMethodID(FormattedText, "fromByteCursor", "([Ljava/lang/String;[II)Lorg/fcitx/fcitx5/android/core/FormattedText;");

        CandidateAction = reinterpret_cast<jclass>(env->NewGlobalRef(env->FindClass("org/fcitx/fcitx5/android/core/CandidateAction")));
        CandidateActionInit = env->GetMethodID(CandidateAction, "<init>", "(ILjava/lang/String;ZLjava/lang/String;ZZ)V");

        Candidate = reinterpret_cast<jclass>(env->NewGlobalRef(env->FindClass("org/fcitx/fcitx5/android/core/CandidateWord")));
        CandidateInit = env->GetMethodID(Candidate, "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)V");
    }

    [[nodiscard]] JEnv AttachEnv() const { return JEnv(jvm); }
};

extern GlobalRefSingleton *GlobalRef;

#endif //FCITX5_ANDROID_JNI_UTILS_H
