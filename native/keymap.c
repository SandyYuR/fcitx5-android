/*
 * keymap.c — Android KeyEvent -> librime (keycode, mask) 的纯逻辑映射
 *
 * 这份文件刻意不依赖任何 libc（不用 malloc / memcpy / printf），
 * 因此既能编进 arm64 的 JNI 库，也能用 `-target wasm32 -nostdlib` 编出来
 * 直接喂给 Node 的 WebAssembly 真跑（本机没有真机与模拟器，这是唯一能实测的通道）。
 *
 * 口径来源（都是当前仓库里的真实代码，不是推测）：
 *   - 修饰键位：librime src/rime/key_table.h:15-45 与
 *     fcitx5 src/lib/fcitx-utils/keysym.h:22-59 的低位完全一致，
 *     且 fcitx5-rime rimestate.cpp:208-238 把 fcitx KeyStates 原样当 librime mask 传，
 *     所以 Android metaState 只需映射到同一批位，不做重排。
 *   - keycode -> keysym：主仓库 codegen 生成的 FcitxKeyMapping.keyCodeToSym()
 *     （app/build/generated/ksp/fxDebug/kotlin/.../FcitxKeyMapping.kt:421-534）。
 *   - unicode 优先：KeySym.fromKeyEvent() 先取 event.unicodeChar，
 *     并跳过 \t / \n / KeyCharacterMap 私有区。
 */
#include "direct_rime.h"

/* ---- Android KeyEvent 常量（frameworks/base/core/java/android/view/KeyEvent.java）---- */
#define AK_SPACE        62
#define AK_ENTER        66
#define AK_DEL          67
#define AK_TAB          61
#define AK_ESCAPE       111
#define AK_SHIFT_LEFT   59
#define AK_SHIFT_RIGHT  60
#define AK_CTRL_LEFT    113
#define AK_CTRL_RIGHT   114
#define AK_ALT_LEFT     57
#define AK_ALT_RIGHT    58
#define AK_CAPS_LOCK    115
#define AK_META_LEFT    117
#define AK_META_RIGHT   118
#define AK_A            29
#define AK_Z            54
#define AK_0            7
#define AK_9            16
#define AK_COMMA        55
#define AK_PERIOD       56
#define AK_MINUS        69
#define AK_EQUALS       70
#define AK_SLASH        76
#define AK_BACKSLASH    73
#define AK_SEMICOLON    74
#define AK_APOSTROPHE   75
#define AK_AT           77
#define AK_POUND        18
#define AK_STAR         17
#define AK_PLUS         81
#define AK_LBRACKET     71
#define AK_RBRACKET     72
#define AK_GRAVE        68
#define AK_DPAD_UP      19
#define AK_DPAD_DOWN    20
#define AK_DPAD_LEFT    21
#define AK_DPAD_RIGHT   22
#define AK_MOVE_HOME    122
#define AK_MOVE_END     123
#define AK_PAGE_UP      92
#define AK_PAGE_DOWN    93
#define AK_INSERT       124
#define AK_FORWARD_DEL  112
#define AK_F1           131
#define AK_F12          142
#define AK_NUMPAD_0     144
#define AK_NUMPAD_9     153
#define AK_NUMPAD_DIVIDE    154
#define AK_NUMPAD_MULTIPLY  155
#define AK_NUMPAD_SUBTRACT  156
#define AK_NUMPAD_ADD       157
#define AK_NUMPAD_DOT       158
#define AK_NUMPAD_COMMA     159
#define AK_NUMPAD_ENTER     160
#define AK_NUMPAD_EQUALS    161

/* ---- X11 keysym ---- */
#define XK_BackSpace 0xff08
#define XK_Tab       0xff09
#define XK_Return    0xff0d
#define XK_Escape    0xff1b
#define XK_Home      0xff50
#define XK_Left      0xff51
#define XK_Up        0xff52
#define XK_Right     0xff53
#define XK_Down      0xff54
#define XK_Page_Up   0xff55
#define XK_Page_Down 0xff56
#define XK_End       0xff57
#define XK_Insert    0xff63
#define XK_F1        0xffbe
#define XK_F12       0xffc9
#define XK_Shift_L   0xffe1
#define XK_Shift_R   0xffe2
#define XK_Control_L 0xffe3
#define XK_Control_R 0xffe4
#define XK_Caps_Lock 0xffe5
#define XK_Meta_L    0xffe7
#define XK_Meta_R    0xffe8
#define XK_Alt_L     0xffe9
#define XK_Alt_R     0xffea
#define XK_Delete    0xffff

#define XK_KP_Enter    0xff8d
#define XK_KP_Multiply 0xffaa
#define XK_KP_Add      0xffab
#define XK_KP_Separator 0xffac
#define XK_KP_Subtract 0xffad
#define XK_KP_Decimal  0xffae
#define XK_KP_Divide   0xffaf
#define XK_KP_0        0xffb0
#define XK_KP_9        0xffb9
#define XK_KP_Equal    0xffbd

int dr_keycode_to_keysym(int android_keycode) {
    switch (android_keycode) {
        case AK_SPACE: return DR_KEYSYM_SPACE;
        case AK_ENTER: return XK_Return;
        case AK_DEL: return XK_BackSpace;
        case AK_TAB: return XK_Tab;
        case AK_ESCAPE: return XK_Escape;

        case AK_SHIFT_LEFT: return XK_Shift_L;
        case AK_SHIFT_RIGHT: return XK_Shift_R;
        case AK_CTRL_LEFT: return XK_Control_L;
        case AK_CTRL_RIGHT: return XK_Control_R;
        case AK_CAPS_LOCK: return XK_Caps_Lock;
        case AK_ALT_LEFT: return XK_Alt_L;
        case AK_ALT_RIGHT: return XK_Alt_R;
        case AK_META_LEFT: return XK_Meta_L;
        case AK_META_RIGHT: return XK_Meta_R;

        case AK_DPAD_UP: return XK_Up;
        case AK_DPAD_DOWN: return XK_Down;
        case AK_DPAD_LEFT: return XK_Left;
        case AK_DPAD_RIGHT: return XK_Right;
        case AK_MOVE_HOME: return XK_Home;
        case AK_MOVE_END: return XK_End;
        case AK_PAGE_UP: return XK_Page_Up;
        case AK_PAGE_DOWN: return XK_Page_Down;
        case AK_INSERT: return XK_Insert;
        case AK_FORWARD_DEL: return XK_Delete;

        case AK_COMMA: return ',';
        case AK_PERIOD: return '.';
        case AK_MINUS: return '-';
        case AK_EQUALS: return '=';
        case AK_SLASH: return '/';
        case AK_BACKSLASH: return '\\';
        case AK_SEMICOLON: return ';';
        case AK_APOSTROPHE: return '\'';
        case AK_AT: return '@';
        case AK_POUND: return '#';
        case AK_STAR: return '*';
        case AK_PLUS: return '+';
        case AK_LBRACKET: return '[';
        case AK_RBRACKET: return ']';
        case AK_GRAVE: return '`';

        case AK_NUMPAD_DIVIDE: return XK_KP_Divide;
        case AK_NUMPAD_MULTIPLY: return XK_KP_Multiply;
        case AK_NUMPAD_SUBTRACT: return XK_KP_Subtract;
        case AK_NUMPAD_ADD: return XK_KP_Add;
        case AK_NUMPAD_DOT: return XK_KP_Decimal;
        case AK_NUMPAD_COMMA: return XK_KP_Separator;
        case AK_NUMPAD_ENTER: return XK_KP_Enter;
        case AK_NUMPAD_EQUALS: return XK_KP_Equal;
        default: break;
    }
    if (android_keycode >= AK_0 && android_keycode <= AK_9) return '0' + (android_keycode - AK_0);
    if (android_keycode >= AK_A && android_keycode <= AK_Z) return 'a' + (android_keycode - AK_A);
    if (android_keycode >= AK_F1 && android_keycode <= AK_F12) return XK_F1 + (android_keycode - AK_F1);
    if (android_keycode >= AK_NUMPAD_0 && android_keycode <= AK_NUMPAD_9)
        return XK_KP_0 + (android_keycode - AK_NUMPAD_0);
    return 0;
}

uint32_t dr_metastate_to_mask(int android_metastate) {
    uint32_t mask = 0;
    if (android_metastate & AMETA_SHIFT_ON) mask |= DR_SHIFT_MASK;
    if (android_metastate & AMETA_CAPS_LOCK_ON) mask |= DR_LOCK_MASK;
    if (android_metastate & AMETA_CTRL_ON) mask |= DR_CTRL_MASK;
    if (android_metastate & AMETA_ALT_ON) mask |= DR_ALT_MASK;
    if (android_metastate & AMETA_META_ON) mask |= DR_SUPER_MASK;
    /* NumLock 在 librime 里没有对应的输入语义位（kMod2Mask = 1<<4 未被 key_binder 使用），
     * 主仓库 rimestate.cpp:208 也只取 Mod1/CapsLock/Shift/Ctrl/Super 五位，故此处不映射。 */
    return mask;
}

int dr_build_key(int android_keycode, int android_metastate, int unicode_char, int action,
                 uint32_t* out_mask) {
    int keysym;
    uint32_t mask = dr_metastate_to_mask(android_metastate);

    /* 与 KeySym.fromKeyEvent 同口径：有可打印字符且不是被排除的几个控制字符时优先用字符码。
     * 这样 Shift+a 得到 'A' 而不是 'a'+ShiftMask —— 与现有链路一致。 */
    if (unicode_char != 0 && unicode_char != '\t' && unicode_char != '\n' &&
        unicode_char != 0xEF01 /* KeyCharacterMap.HEX_INPUT */ &&
        unicode_char != 0xEF02 /* KeyCharacterMap.PICKER_DIALOG_INPUT */) {
        keysym = unicode_char;
    } else {
        keysym = dr_keycode_to_keysym(android_keycode);
    }

    if (keysym == 0) {
        if (out_mask) *out_mask = 0;
        return 0;
    }
    if (action == DR_ACTION_UP) mask |= DR_RELEASE_MASK;
    if (out_mask) *out_mask = mask;
    return keysym;
}

dr_dispatch dr_gate_key(int mapped_ok, int maintenance_mode, int has_session) {
    if (!mapped_ok) return DR_DISPATCH_DROP;
    if (maintenance_mode || !has_session) return DR_DISPATCH_SWALLOW;
    return DR_DISPATCH_TO_RIME;
}

int dr_generation_allows(uint64_t result_generation, uint64_t current_generation) {
    return result_generation == current_generation ? 1 : 0;
}
