/*
 * rime_bridge.c — Kotlin/JNI 直连 librime 的桥接层（纯 C，不经过 fcitx5）
 *
 * 为什么是「批量」接口：
 *   现有链路一次按键要跨 JNI 多次（sendKey -> Fcitx::Instance() -> AndroidFrontend
 *   -> InputContext -> fcitx5-rime RimeState -> librime，再由 Fcitx UI 事件逐条回流），
 *   报告 §5.3 明确禁止把「JNI 次数是主瓶颈」当结论，所以这里不宣称性能收益，
 *   只做结构性改变：一次 native 调用把 commit + context + status 打包成单个 Object[] 返回。
 *
 * Object[] 布局（固定头 16 槽 + 候选区 2N 槽，N = 候选条数）：
 *   0  Integer  dispatch 结果：0=交给引擎 1=被门控吞掉 2=无法映射丢弃
 *   1  String   commit text（本次按键产生的上屏文本，无则 null）
 *   2  String   preedit（composition.preedit）
 *   3  Integer  caret pos（composition.cursor_pos）
 *   4  Integer  sel_start
 *   5  Integer  sel_end
 *   6  Integer  page_no
 *   7  Integer  page_size
 *   8  Integer  highlighted_candidate_index（-1 表示无）
 *   9  Integer  is_last_page（0/1）
 *   10 String   select_keys（menu.select_keys）
 *   11 String   commit_text_preview
 *   12 String   schema_id
 *   13 String   schema_name
 *   14 Integer  status 位：bit0 disabled bit1 composing bit2 ascii_mode bit3 full_shape
 *                          bit4 simplified bit5 traditional bit6 ascii_punct
 *   15 Integer  N（候选条数）
 *   16..        N 对 (String text, String comment)
 *
 * 未搬运的字段（README 有交代）：menu.select_labels、commit.data_size 之后的扩展。
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include "direct_rime.h"
#include "rime_api.h"

#include <android/log.h>
#define LOG_TAG "direct-rime"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static RimeApi* g_api = NULL;
static JavaVM* g_vm = NULL;

/* 部署状态由 librime 的 notification handler 写入，process_key 前读它做门控 */
static int g_deploy_running = 0;
static jclass g_bridge_class = NULL;
static jmethodID g_notify_method = NULL;

static void notification_handler(void* context_object,
                                 RimeSessionId session_id,
                                 const char* message_type,
                                 const char* message_value) {
    (void)context_object;
    if (!message_type || !message_value) return;
    if (strcmp(message_type, "deploy") == 0) {
        if (strcmp(message_value, "start") == 0) {
            g_deploy_running = 1;
        } else if (strcmp(message_value, "success") == 0 ||
                   strcmp(message_value, "failure") == 0) {
            g_deploy_running = 0;
        }
        LOGI("deploy %s (session=%lu)", message_value, (unsigned long)session_id);
    }

    /* 回调 Java。真机才走得到这里，本机无设备，故这条路径标注为未验证。 */
    if (!g_vm || !g_bridge_class || !g_notify_method) return;
    // notification 回调跑在 librime 自己的线程上（部署线程/引擎线程），
    // 不是 JVM 创建的线程。AttachCurrentThread 会把本线程与 JVM 绑定，
    // 但 Detach 必须配对且不能在仍有 Java 帧运行时调——部署回调里调
    // DetachCurrentThread 会把 librime 部署线程的 JNI 绑定撕掉，
    // 下一次回调/ native 调用进同一线程即 abort（"attempting to detach
    // while still running code"）。这里只 Attach 不 Detach：线程退出时
    // ART 自动回收绑定，少量线程泄漏的绑定不影响输入法进程。
    JNIEnv* env = NULL;
    if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK || !env) return;
    jstring jtype = (*env)->NewStringUTF(env, message_type ? message_type : "");
    jstring jvalue = (*env)->NewStringUTF(env, message_value ? message_value : "");
    (*env)->CallStaticVoidMethod(env, g_bridge_class, g_notify_method,
                                 (jlong)session_id, jtype, jvalue);
    (*env)->DeleteLocalRef(env, jtype);
    (*env)->DeleteLocalRef(env, jvalue);
}

static RimeApi* api_of(JNIEnv* env) {
    if (g_api) return g_api;
    g_api = rime_get_api();
    if (!g_api) {
        LOGE("rime_get_api() returned NULL");
    }
    (void)env;
    return g_api;
}

/* ---- 小工具 ---- */

static jstring to_jstring(JNIEnv* env, const char* s) {
    if (!s) return NULL;
    return (*env)->NewStringUTF(env, s);
}

static jobject box_int(JNIEnv* env, jint v) {
    jclass cls = (*env)->FindClass(env, "java/lang/Integer");
    jmethodID mid = (*env)->GetStaticMethodID(env, cls, "valueOf", "(I)Ljava/lang/Integer;");
    return (*env)->CallStaticObjectMethod(env, cls, mid, v);
}

static void set_int(JNIEnv* env, jobjectArray arr, jsize idx, jint v) {
    jobject boxed = box_int(env, v);
    (*env)->SetObjectArrayElement(env, arr, idx, boxed);
    (*env)->DeleteLocalRef(env, boxed);
}

static void set_str(JNIEnv* env, jobjectArray arr, jsize idx, const char* s) {
    jstring js = to_jstring(env, s);
    (*env)->SetObjectArrayElement(env, arr, idx, js);
    if (js) (*env)->DeleteLocalRef(env, js);
}

/* Object[] 槽位编号。改这里必须同步改 Kotlin 侧 RimeSnapshot.fromObjectArray()，
 * 两边都从同一份常量表取值，避免手写下标错位。 */
#define DISPATCH_SLOT      0
#define COMMIT_SLOT        1
#define PREEDIT_SLOT       2
#define CARET_SLOT         3
#define SEL_START_SLOT     4
#define SEL_END_SLOT       5
#define PAGE_NO_SLOT       6
#define PAGE_SIZE_SLOT     7
#define HIGHLIGHTED_SLOT   8
#define LAST_PAGE_SLOT     9
#define SELECT_KEYS_SLOT   10
#define PREVIEW_SLOT       11
#define SCHEMA_ID_SLOT     12
#define SCHEMA_NAME_SLOT   13
#define STATUS_FLAGS_SLOT  14
#define COUNT_SLOT         15
#define CAND_BASE          16
#define HEAD_SLOTS         CAND_BASE

/*
 * 打包 commit + context + status。dispatch 由调用方给出。
 * 返回新构造的 Object[]；出错返回 NULL。
 */
static jobjectArray pack_state(JNIEnv* env, RimeSessionId session, jint dispatch) {
    RimeApi* api = api_of(env);
    if (!api) return NULL;

    RIME_STRUCT(RimeCommit, commit);
    RIME_STRUCT(RimeContext, context);
    RIME_STRUCT(RimeStatus, status);

    Bool has_commit = api->get_commit(session, &commit);
    Bool has_context = api->get_context(session, &context);
    Bool has_status = api->get_status(session, &status);

    int n = 0;
    /* RIME_STRUCT_HAS_MEMBER 宏展开成 `&member - &var`，member 必须写成 var.member 形式 */
    if (has_context && RIME_STRUCT_HAS_MEMBER(context, context.menu) &&
        context.menu.num_candidates > 0) {
        n = context.menu.num_candidates;
    }

    jclass obj_cls = (*env)->FindClass(env, "java/lang/Object");
    jobjectArray arr = (*env)->NewObjectArray(env, HEAD_SLOTS + 2 * n, obj_cls, NULL);
    if (!arr) goto cleanup;

    set_int(env, arr, 0, dispatch);
    set_str(env, arr, 1, has_commit ? commit.text : NULL);
    set_str(env, arr, 2, has_context ? context.composition.preedit : NULL);
    set_int(env, arr, 3, has_context ? context.composition.cursor_pos : 0);
    set_int(env, arr, 4, has_context ? context.composition.sel_start : 0);
    set_int(env, arr, 5, has_context ? context.composition.sel_end : 0);
    set_int(env, arr, 6, has_context ? context.menu.page_no : 0);
    set_int(env, arr, 7, has_context ? context.menu.page_size : 0);
    set_int(env, arr, 8, has_context ? context.menu.highlighted_candidate_index : -1);
    set_int(env, arr, 9, (has_context && context.menu.is_last_page) ? 1 : 0);
    set_str(env, arr, 10, has_context ? context.menu.select_keys : NULL);
    set_str(env, arr, 11, has_context ? context.commit_text_preview : NULL);
    set_str(env, arr, 12, has_status ? status.schema_id : NULL);
    set_str(env, arr, 13, has_status ? status.schema_name : NULL);

    int flags = 0;
    if (has_status) {
        if (status.is_disabled) flags |= 1 << 0;
        if (status.is_composing) flags |= 1 << 1;
        if (status.is_ascii_mode) flags |= 1 << 2;
        if (status.is_full_shape) flags |= 1 << 3;
        if (status.is_simplified) flags |= 1 << 4;
        if (status.is_traditional) flags |= 1 << 5;
        if (status.is_ascii_punct) flags |= 1 << 6;
    }
    set_int(env, arr, STATUS_FLAGS_SLOT, flags);
    set_int(env, arr, COUNT_SLOT, n);

    jsize cursor = CAND_BASE;
    if (has_context && n > 0) {
        for (int i = 0; i < n; i++) {
            set_str(env, arr, cursor++, context.menu.candidates[i].text);
            set_str(env, arr, cursor++, context.menu.candidates[i].comment);
        }
    }

cleanup:
    if (has_commit) api->free_commit(&commit);
    if (has_context) api->free_context(&context);
    if (has_status) api->free_status(&status);
    return arr;
}

/* ---- JNI_OnLoad：缓存 JavaVM ---- */

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)reserved;
    g_vm = vm;
    return JNI_VERSION_1_6;
}

/* ---- 生命周期 ---- */

JNIEXPORT jboolean JNICALL
Java_org_fxrime_direct_RimeBridge_nativeSetup(JNIEnv* env, jclass cls,
                                              jstring shared_dir, jstring user_dir,
                                              jstring log_dir, jstring app_name) {
    RimeApi* api = api_of(env);
    if (!api) return JNI_FALSE;

    g_bridge_class = (jclass)(*env)->NewGlobalRef(env, cls);
    if (g_bridge_class) {
        g_notify_method = (*env)->GetStaticMethodID(env, g_bridge_class, "onRimeNotification",
                                                    "(JLjava/lang/String;Ljava/lang/String;)V");
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            g_notify_method = NULL;
        }
    }

    const char* c_shared = (*env)->GetStringUTFChars(env, shared_dir, NULL);
    const char* c_user = (*env)->GetStringUTFChars(env, user_dir, NULL);
    const char* c_log = log_dir ? (*env)->GetStringUTFChars(env, log_dir, NULL) : NULL;
    const char* c_name = app_name ? (*env)->GetStringUTFChars(env, app_name, NULL) : NULL;

    RIME_STRUCT(RimeTraits, traits);
    traits.shared_data_dir = c_shared;
    traits.user_data_dir = c_user;
    traits.log_dir = c_log;
    traits.distribution_name = "DirectRime";
    traits.distribution_code_name = "direct-rime-proto";
    traits.distribution_version = "0.1.0";
    traits.app_name = c_name ? c_name : "org.fxrime.direct";
    traits.min_log_level = 2; /* WARNING */

    api->setup(&traits);
    api->initialize(&traits);
    if (RIME_API_AVAILABLE(api, set_notification_handler)) {
        api->set_notification_handler(notification_handler, NULL);
    }

    (*env)->ReleaseStringUTFChars(env, shared_dir, c_shared);
    (*env)->ReleaseStringUTFChars(env, user_dir, c_user);
    if (c_log) (*env)->ReleaseStringUTFChars(env, log_dir, c_log);
    if (c_name) (*env)->ReleaseStringUTFChars(env, app_name, c_name);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_org_fxrime_direct_RimeBridge_nativeFinalize(JNIEnv* env, jclass cls) {
    (void)env; (void)cls;
    if (g_bridge_class && g_vm) {
        JNIEnv* e = env;
        (*e)->DeleteGlobalRef(e, g_bridge_class);
        g_bridge_class = NULL;
        g_notify_method = NULL;
    }
    if (g_api) {
        g_api->finalize();
        g_api = NULL;
    }
}

JNIEXPORT jstring JNICALL
Java_org_fxrime_direct_RimeBridge_nativeGetVersion(JNIEnv* env, jclass cls) {
    (void)cls;
    RimeApi* api = api_of(env);
    if (!api) return NULL;
    const char* v = api->get_version();
    return to_jstring(env, v ? v : "");
}

/* ---- 会话 ---- */

JNIEXPORT jlong JNICALL
Java_org_fxrime_direct_RimeBridge_nativeCreateSession(JNIEnv* env, jclass cls) {
    (void)cls;
    RimeApi* api = api_of(env);
    if (!api) return 0;
    return (jlong)api->create_session();
}

JNIEXPORT jboolean JNICALL
Java_org_fxrime_direct_RimeBridge_nativeFindSession(JNIEnv* env, jclass cls, jlong session) {
    (void)cls;
    RimeApi* api = api_of(env);
    if (!api) return JNI_FALSE;
    return api->find_session((RimeSessionId)session) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_fxrime_direct_RimeBridge_nativeDestroySession(JNIEnv* env, jclass cls, jlong session) {
    (void)cls;
    RimeApi* api = api_of(env);
    if (!api) return JNI_FALSE;
    return api->destroy_session((RimeSessionId)session) ? JNI_TRUE : JNI_FALSE;
}

/* ---- 按键：唯一的批量入口 ----
 * 参数带 Android 原始事件的三要素，映射在 native 侧完成，
 * 门控（部署期 / 无 session）也在这里判定，避免 Kotlin 侧再问一次状态。
 */
JNIEXPORT jobjectArray JNICALL
Java_org_fxrime_direct_RimeBridge_nativeProcessKey(JNIEnv* env, jclass cls, jlong session,
                                                   jint android_keycode, jint android_metastate,
                                                   jint unicode_char, jint action) {
    (void)cls;
    RimeApi* api = api_of(env);
    if (!api) return NULL;

    uint32_t mask = 0;
    int keysym = dr_build_key(android_keycode, android_metastate, unicode_char, action, &mask);

    int has_session = api->find_session((RimeSessionId)session) ? 1 : 0;
    int maintenance = api->is_maintenance_mode() ? 1 : 0;
    if (g_deploy_running) maintenance = 1;

    dr_dispatch d = dr_gate_key(keysym != 0, maintenance, has_session);
    if (d != DR_DISPATCH_TO_RIME) {
        /* 吞或丢都要返回一个 shape 完整的数组，让 Kotlin 侧永远拿到一致的布局 */
        return pack_state(env, (RimeSessionId)session, (jint)d);
    }

    /* process_key 返回 Bool：true = librime 已消费此键，false = 引擎不处理。
     * 必须回传这个结果，否则引擎不处理的键会凭空消失——既不进 librime，
     * 也不落回编辑器，用户按了键却没有任何反应（输入正确性红线，AGENTS.md §0.7.6）。
     * 原链路 rimestate.cpp:238-243 正是用这个返回值决定要不要 filterAndAccept。*/
    int consumed = api->process_key((RimeSessionId)session, keysym, (int)mask) ? 1 : 0;
    jint dispatch = consumed ? (jint)DR_DISPATCH_TO_RIME : (jint)DR_DISPATCH_PASSTHROUGH;
    return pack_state(env, (RimeSessionId)session, dispatch);
}

/* ---- 候选操作：都返回同一份批量状态 ---- */

JNIEXPORT jobjectArray JNICALL
Java_org_fxrime_direct_RimeBridge_nativeSelectCandidate(JNIEnv* env, jclass cls, jlong session,
                                                        jint index) {
    (void)cls;
    RimeApi* api = api_of(env);
    if (!api) return NULL;
    api->select_candidate((RimeSessionId)session, (size_t)index);
    return pack_state(env, (RimeSessionId)session, (jint)DR_DISPATCH_TO_RIME);
}

JNIEXPORT jobjectArray JNICALL
Java_org_fxrime_direct_RimeBridge_nativeSelectCandidateOnCurrentPage(JNIEnv* env, jclass cls,
                                                                     jlong session, jint index) {
    (void)cls;
    RimeApi* api = api_of(env);
    if (!api) return NULL;
    if (RIME_API_AVAILABLE(api, select_candidate_on_current_page)) {
        api->select_candidate_on_current_page((RimeSessionId)session, (size_t)index);
    }
    return pack_state(env, (RimeSessionId)session, (jint)DR_DISPATCH_TO_RIME);
}

JNIEXPORT jobjectArray JNICALL
Java_org_fxrime_direct_RimeBridge_nativeChangePage(JNIEnv* env, jclass cls, jlong session,
                                                   jboolean backward) {
    (void)cls;
    RimeApi* api = api_of(env);
    if (!api) return NULL;
    if (RIME_API_AVAILABLE(api, change_page)) {
        api->change_page((RimeSessionId)session, backward ? True : False);
    }
    return pack_state(env, (RimeSessionId)session, (jint)DR_DISPATCH_TO_RIME);
}

JNIEXPORT jobjectArray JNICALL
Java_org_fxrime_direct_RimeBridge_nativeDeleteCandidate(JNIEnv* env, jclass cls, jlong session,
                                                        jint index) {
    (void)cls;
    RimeApi* api = api_of(env);
    if (!api) return NULL;
    if (RIME_API_AVAILABLE(api, delete_candidate)) {
        api->delete_candidate((RimeSessionId)session, (size_t)index);
    }
    return pack_state(env, (RimeSessionId)session, (jint)DR_DISPATCH_TO_RIME);
}

JNIEXPORT jobjectArray JNICALL
Java_org_fxrime_direct_RimeBridge_nativeCommitComposition(JNIEnv* env, jclass cls, jlong session) {
    (void)cls;
    RimeApi* api = api_of(env);
    if (!api) return NULL;
    api->commit_composition((RimeSessionId)session);
    return pack_state(env, (RimeSessionId)session, (jint)DR_DISPATCH_TO_RIME);
}

JNIEXPORT jobjectArray JNICALL
Java_org_fxrime_direct_RimeBridge_nativeClearComposition(JNIEnv* env, jclass cls, jlong session) {
    (void)cls;
    RimeApi* api = api_of(env);
    if (!api) return NULL;
    api->clear_composition((RimeSessionId)session);
    return pack_state(env, (RimeSessionId)session, (jint)DR_DISPATCH_TO_RIME);
}

/* ---- 开关与方案 ---- */

JNIEXPORT void JNICALL
Java_org_fxrime_direct_RimeBridge_nativeSetOption(JNIEnv* env, jclass cls, jlong session,
                                                  jstring option, jboolean value) {
    (void)cls;
    RimeApi* api = api_of(env);
    if (!api || !option) return;
    const char* o = (*env)->GetStringUTFChars(env, option, NULL);
    api->set_option((RimeSessionId)session, o, value ? True : False);
    (*env)->ReleaseStringUTFChars(env, option, o);
}

JNIEXPORT jboolean JNICALL
Java_org_fxrime_direct_RimeBridge_nativeGetOption(JNIEnv* env, jclass cls, jlong session,
                                                  jstring option) {
    (void)cls;
    RimeApi* api = api_of(env);
    if (!api || !option) return JNI_FALSE;
    const char* o = (*env)->GetStringUTFChars(env, option, NULL);
    Bool v = api->get_option((RimeSessionId)session, o);
    (*env)->ReleaseStringUTFChars(env, option, o);
    return v ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobjectArray JNICALL
Java_org_fxrime_direct_RimeBridge_nativeGetSchemaList(JNIEnv* env, jclass cls) {
    (void)cls;
    RimeApi* api = api_of(env);
    if (!api) return NULL;
    RimeSchemaList list;
    memset(&list, 0, sizeof(list));
    if (!api->get_schema_list(&list)) return NULL;

    jclass obj_cls = (*env)->FindClass(env, "java/lang/Object");
    jobjectArray arr = (*env)->NewObjectArray(env, (jsize)(list.size * 2), obj_cls, NULL);
    jsize k = 0;
    for (size_t i = 0; i < list.size; i++) {
        set_str(env, arr, k++, list.list[i].schema_id);
        set_str(env, arr, k++, list.list[i].name);
    }
    api->free_schema_list(&list);
    return arr;
}

JNIEXPORT jboolean JNICALL
Java_org_fxrime_direct_RimeBridge_nativeSelectSchema(JNIEnv* env, jclass cls, jlong session,
                                                     jstring schema_id) {
    (void)cls;
    RimeApi* api = api_of(env);
    if (!api || !schema_id) return JNI_FALSE;
    const char* s = (*env)->GetStringUTFChars(env, schema_id, NULL);
    Bool ok = api->select_schema((RimeSessionId)session, s);
    (*env)->ReleaseStringUTFChars(env, schema_id, s);
    return ok ? JNI_TRUE : JNI_FALSE;
}

/* ---- 定制能力：input tabs（报告 §2.4 点名要求，不是可选） ---- */

JNIEXPORT jobjectArray JNICALL
Java_org_fxrime_direct_RimeBridge_nativeGetInputTabs(JNIEnv* env, jclass cls, jlong session,
                                                     jint position) {
    (void)cls;
    RimeApi* api = api_of(env);
    if (!api || !RIME_API_AVAILABLE(api, get_input_tabs)) return NULL;

    char** labels = NULL;
    size_t* spans = NULL;
    int* sources = NULL;
    size_t count = 0;
    if (!api->get_input_tabs((RimeSessionId)session, (size_t)position,
                             &labels, &spans, &sources, &count) || count == 0) {
        if (labels) api->free_input_tabs(labels, spans, sources, count);
        return NULL;
    }

    jclass obj_cls = (*env)->FindClass(env, "java/lang/Object");
    /* 布局：每个 tab 三槽 (label, span, source) */
    jobjectArray arr = (*env)->NewObjectArray(env, (jsize)(count * 3), obj_cls, NULL);
    jsize k = 0;
    for (size_t i = 0; i < count; i++) {
        set_str(env, arr, k++, labels[i]);
        set_int(env, arr, k++, (jint)spans[i]);
        set_int(env, arr, k++, (jint)sources[i]);
    }
    api->free_input_tabs(labels, spans, sources, count);
    return arr;
}

JNIEXPORT jobjectArray JNICALL
Java_org_fxrime_direct_RimeBridge_nativeSelectTab(JNIEnv* env, jclass cls, jlong session,
                                                  jint position, jstring label, jint span) {
    (void)cls;
    RimeApi* api = api_of(env);
    if (!api || !RIME_API_AVAILABLE(api, select_tab) || !label) return NULL;
    const char* l = (*env)->GetStringUTFChars(env, label, NULL);
    api->select_tab((RimeSessionId)session, (size_t)position, l, (size_t)span);
    (*env)->ReleaseStringUTFChars(env, label, l);
    return pack_state(env, (RimeSessionId)session, (jint)DR_DISPATCH_TO_RIME);
}

JNIEXPORT jobjectArray JNICALL
Java_org_fxrime_direct_RimeBridge_nativeClearTabs(JNIEnv* env, jclass cls, jlong session) {
    (void)cls;
    RimeApi* api = api_of(env);
    if (!api || !RIME_API_AVAILABLE(api, clear_tabs)) return NULL;
    api->clear_tabs((RimeSessionId)session);
    return pack_state(env, (RimeSessionId)session, (jint)DR_DISPATCH_TO_RIME);
}

JNIEXPORT jobjectArray JNICALL
Java_org_fxrime_direct_RimeBridge_nativeGetCandidateCode(JNIEnv* env, jclass cls, jlong session,
                                                         jint index) {
    (void)cls;
    RimeApi* api = api_of(env);
    if (!api || !RIME_API_AVAILABLE(api, get_candidate_code)) return NULL;

    char** segments = NULL;
    size_t num = 0, matching = 0;
    if (!api->get_candidate_code((RimeSessionId)session, (size_t)index,
                                 &segments, &num, &matching) || num == 0) {
        if (segments) api->free_candidate_code(segments, num);
        return NULL;
    }
    jclass obj_cls = (*env)->FindClass(env, "java/lang/Object");
    /* 布局：[0]=matching_segments，其后 num 个 segment 字符串 */
    jobjectArray arr = (*env)->NewObjectArray(env, (jsize)(num + 1), obj_cls, NULL);
    set_int(env, arr, 0, (jint)matching);
    for (size_t i = 0; i < num; i++) set_str(env, arr, (jsize)(i + 1), segments[i]);
    api->free_candidate_code(segments, num);
    return arr;
}

/* ---- 部署 / 维护 ---- */

JNIEXPORT jboolean JNICALL
Java_org_fxrime_direct_RimeBridge_nativeIsMaintenanceMode(JNIEnv* env, jclass cls) {
    (void)cls;
    RimeApi* api = api_of(env);
    if (!api) return JNI_FALSE;
    return (api->is_maintenance_mode() || g_deploy_running) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_fxrime_direct_RimeBridge_nativeStartMaintenance(JNIEnv* env, jclass cls,
                                                         jboolean full_check) {
    (void)cls;
    RimeApi* api = api_of(env);
    if (!api) return JNI_FALSE;
    Bool ok = api->start_maintenance(full_check ? True : False);
    if (ok) g_deploy_running = 1;
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_org_fxrime_direct_RimeBridge_nativeJoinMaintenanceThread(JNIEnv* env, jclass cls) {
    (void)cls;
    RimeApi* api = api_of(env);
    if (!api) return;
    api->join_maintenance_thread();
    g_deploy_running = 0;
    (void)env;
}

JNIEXPORT jboolean JNICALL
Java_org_fxrime_direct_RimeBridge_nativeSyncUserData(JNIEnv* env, jclass cls) {
    (void)cls;
    RimeApi* api = api_of(env);
    if (!api) return JNI_FALSE;
    return api->sync_user_data() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_org_fxrime_direct_RimeBridge_nativeGetInput(JNIEnv* env, jclass cls, jlong session) {
    (void)cls;
    RimeApi* api = api_of(env);
    if (!api) return NULL;
    return to_jstring(env, api->get_input((RimeSessionId)session));
}

JNIEXPORT jint JNICALL
Java_org_fxrime_direct_RimeBridge_nativeGetCaretPos(JNIEnv* env, jclass cls, jlong session) {
    (void)cls;
    RimeApi* api = api_of(env);
    if (!api) return 0;
    return (jint)api->get_caret_pos((RimeSessionId)session);
}
