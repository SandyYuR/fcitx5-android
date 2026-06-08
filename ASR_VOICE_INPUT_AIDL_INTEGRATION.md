# ASR 语音输入 AIDL 接入说明

本文针对本仓库新增的语音输入桥接协议（IME 录音、插件识别）说明第三方如何接入。

## 1. 协议文件（必须保持包名）

位于 `lib/common`：

- `org.fcitx.fcitx5.android.common.ipc.IVoiceInputProvider`
- `org.fcitx.fcitx5.android.common.ipc.IVoiceInputCallback`
- `org.fcitx.fcitx5.android.common.ipc.VoiceInputIpc`

> `package` 与接口名决定 Binder descriptor。  
> 若只改插件端包名，主程序将无法调用。

## 2. 主程序如何发现 Provider

主程序会按以下 Action 查询可绑定 Service（见 `VoiceInputProviderManager`）：

- `${hostAppId}.plugin.VOICE_INPUT`
- `${hostReleaseLikeId}.plugin.VOICE_INPUT`
- `${hostReleaseLikeId}.debug.plugin.VOICE_INPUT`
- `org.fcitx.fcitx5.android.plugin.VOICE_INPUT`
- `org.fcitx.fcitx5.android.debug.plugin.VOICE_INPUT`

其中后缀来自 `VoiceInputIpc.SERVICE_ACTION_SUFFIX`。

## 3. 插件端 Manifest 最小配置

```xml
<service
    android:name=".YourVoiceInputService"
    android:exported="true">
    <intent-filter>
        <action android:name="${mainApplicationId}.plugin.VOICE_INPUT" />
    </intent-filter>
    <!-- 可选：兼容固定 host 包名 -->
    <intent-filter>
        <action android:name="org.fcitx.fcitx5.android.plugin.VOICE_INPUT" />
    </intent-filter>
</service>
```

如果你本身就是 fcitx 插件，仍建议保留现有 plugin metadata（`@xml/plugin`）；  
但 AIDL 语音桥接本身的关键是上面的可绑定 Service Action。

## 4. `IVoiceInputProvider` 接口语义

1. `isAvailable()`：当前能否服务（模型、资源、许可证等）。
2. `getPreferredConfig()`：返回推荐参数（采样率/位深/声道/silenceMs）。
3. `configure(Bundle)`：接收会话参数，未知键应忽略。
4. `startSession(cb)`：开始会话，准备好后回调 `cb.onReady()`。
5. `feedAudio(pcm, offset, len, ptsMs)`：接收 PCM 16-bit little-endian。
6. `endStream()`：输入结束，输出最后结果并结束。
7. `cancelSession()` / `stopSession()`：中断或停止并清理资源。

## 5. `IVoiceInputCallback` 回调约定

常见顺序：

1. `onReady()`
2. `onPartialResult(text)`（可多次）
3. `onSegmentFinal(text)`（可多次）
4. `onSessionEnded()`

异常：

1. `onError(code, message)`
2. `onSessionEnded()`

`onVolumeLevel(rms)` 为可选 UI 级别提示（0..32767）。

## 6. 参数键（`VoiceInputIpc.ConfigKeys`）

- `sampleRate`（Int）
- `bitsPerSample`（Int，当前主程序按 16bit 处理）
- `channels`（Int，当前主程序按单声道处理）
- `silenceMs`（Long）
- `language`（String，可选提示）

## 7. 关键行为边界

1. **录音在 IME 侧进行**：插件仅消费 `feedAudio` 数据，不必自行申请麦克风前台录音链路。
2. **Provider 调用需容错**：主程序已做超时/异常处理，但插件仍应避免阻塞 Binder 线程。
3. **会话结束要明确**：无论成功/失败，都应保证最终进入 `onSessionEnded()`。

## 8. 可选：浮窗回退通道

当 AIDL `bindService` 失败时，主程序会尝试：

- Action：`<hostAppId>.plugin.VOICE_INPUT_FLOATING`
- 组件名推导：`<ProviderService同包>.FloatingService`

这一路径是可选兼容能力，不是 AIDL 最小接入必需项。

