/*
 * direct_rime.h — 直连 librime 的纯 C 桥接层（无 fcitx5、无 C++）
 *
 * 设计口径（来自 docs/maintainer/DIRECT_RIME_REEVALUATION_2026-10-07.md §4）：
 *   1. 按键：Android KeyEvent -> (keycode=X11 keysym, mask=与 fcitx KeyStates 同位的 RimeModifier)
 *      librime src/rime/key_table.h 与 fcitx fcitx-utils/keysym.h 的低 8 位完全一致，
 *      故 Android metaState 只需映射到同一批位，不需要重排。
 *   2. 批量返回：一次 JNI 调用返回 commit + context + status 打包成的单个 Object[]，
 *      避免逐字段回调（foxy 用同样的 Object[] 扁平数组思路）。
 *   3. 部署期门控：is_maintenance_mode() 为真时 process_key 不被调用，
 *      且按下与抬起都要吞（对齐 fcitx5-rime rimestate.cpp 的 filterAndAccept 语义）。
 */
#ifndef DIRECT_RIME_H_
#define DIRECT_RIME_H_

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ---------- 与 librime key_table.h 对齐的修饰键位 ---------- */
#define DR_SHIFT_MASK   (1u << 0)
#define DR_LOCK_MASK    (1u << 1)   /* CapsLock */
#define DR_CTRL_MASK    (1u << 2)
#define DR_ALT_MASK     (1u << 3)   /* 也是 kMod1Mask */
#define DR_SUPER_MASK   (1u << 26)
#define DR_RELEASE_MASK (1u << 30)

/* Android KeyEvent.getMetaState() 的位（frameworks/base/core/java/android/view/KeyEvent.java） */
#define AMETA_SHIFT_ON     0x00000001
#define AMETA_CAPS_LOCK_ON 0x00100000
#define AMETA_CTRL_ON      0x00001000
#define AMETA_ALT_ON       0x00000002
#define AMETA_META_ON      0x00010000
#define AMETA_NUM_LOCK_ON  0x00200000

/* 可打印字符区：X11 keysym 的 ASCII/Latin-1 段直接就是字符码 */
#define DR_KEYSYM_BACKSPACE 0xff08
#define DR_KEYSYM_RETURN    0xff0d
#define DR_KEYSYM_ESCAPE    0xff1b
#define DR_KEYSYM_SPACE     0x0020

/*
 * 事件方向。librime 的 kReleaseMask(1<<30) 由本层在 up 事件上置位，
 * 与 fcitx5-rime rimestate.cpp 的 `if (event.isRelease()) intStates |= (1 << 30)` 同义。
 */
#define DR_ACTION_DOWN 0
#define DR_ACTION_UP   1

/* ---------- 按键映射：Android -> Rime ---------- */

/* Android keyCode -> X11 keysym；未知返回 0。只覆盖输入法实际会遇到的键。 */
int dr_keycode_to_keysym(int android_keycode);

/* Android metaState -> Rime mask（不含 release 位） */
uint32_t dr_metastate_to_mask(int android_metastate);

/*
 * 合成 process_key 的最终实参。
 * unicode_char：Android KeyEvent.getUnicodeChar() 的结果，非 0 时优先于 keycode 映射
 *               （沿用主仓库 KeySym.fromKeyEvent 的「先 charCode」口径）。
 * action：DR_ACTION_DOWN / DR_ACTION_UP，UP 时结果置 kReleaseMask。
 * 返回 0 表示该事件无法映射，调用方应丢弃而不是塞 0 进 process_key。
 */
int dr_build_key(int android_keycode, int android_metastate, int unicode_char, int action,
                 uint32_t* out_mask);

/* ---------- 按键门控：决定这次按键该走哪条路 ---------- */
typedef enum {
    DR_DISPATCH_TO_RIME = 0,     /* 正常交给 process_key 且 librime 已消费 */
    DR_DISPATCH_SWALLOW = 1,     /* 吞掉：down 与 up 都不进引擎、也不给编辑器 */
    DR_DISPATCH_DROP    = 2,     /* 无法映射，直接丢 */
    DR_DISPATCH_PASSTHROUGH = 3  /* 进了引擎但 librime 不处理，应原样透传给编辑器 */
} dr_dispatch;

/*
 * 门控判定。理由（对齐 rimestate.cpp:183-202）：
 *   - 部署/维护期内引擎没有有效 session，未处理的键会被 frontend 当普通字符提交进编辑器，
 *     产生 "hhh" 之类的垃圾文本；所以按下与抬起都必须吞。
 *   - 无 session 同理。
 */
dr_dispatch dr_gate_key(int mapped_ok, int maintenance_mode, int has_session);

/* ---------- 会话代次：异步结果的作废判据 ---------- */
/*
 * generation 每次「焦点切换 / session 重建 / 引擎重启」自增。
 * 异步返回的候选/preedit 在消费前比对代次，不符即丢弃。
 * 返回 1 表示允许消费，0 表示丢弃。
 */
int dr_generation_allows(uint64_t result_generation, uint64_t current_generation);

#ifdef __cplusplus
}
#endif

#endif  /* DIRECT_RIME_H_ */
