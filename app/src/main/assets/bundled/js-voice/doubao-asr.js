const ENDPOINT = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async";
const TRANSLATION_SERVICE_ID = "text.translate";
const TRANSLATION_SERVICE_VERSION = 1;
const TRANSLATION_TARGETS = ["Chinese", "English", "Japanese", "Korean", "French", "German", "Spanish"];
const STRINGS = {
  en: {
    name: "Doubao ASR (V3)",
    apiKey: "API key",
    apiKeyDescription: "Stored by Foxy and injected into the approved host request.",
    resource: "Resource ID",
    endpoint: "WebSocket endpoint",
    endpointDescription: "Must use the approved openspeech.bytedance.com host.",
    model10Hourly: "Big Model 1.0 · hourly",
    model10Concurrent: "Big Model 1.0 · concurrency",
    model20Hourly: "Big Model 2.0 · hourly",
    model20Concurrent: "Big Model 2.0 · concurrency",
    itn: "Normalize numbers and dates",
    punctuation: "Add punctuation",
    translate: "Translate final result",
    translationProvider: "Translation service provider",
    translationProviderDescription: "Select an installed plugin providing text.translate v1. A single provider is selected automatically.",
    translationTarget: "Translation target language",
    translationFallback: "Keep source text if translation fails",
    translationServiceMissing: "Doubao ASR: install a plugin providing text.translate v1",
    translationProviderAmbiguous: "Doubao ASR: select a text.translate provider in plugin settings",
    translationFailed: "Doubao ASR translation failed: %1"
  },
  "zh-CN": {
    name: "豆包语音识别（V3）",
    apiKey: "API Key",
    apiKeyDescription: "由 Foxy 加密保存，并注入已授权主机的请求。",
    resource: "资源 ID",
    endpoint: "WebSocket 地址",
    endpointDescription: "必须使用已授权的 openspeech.bytedance.com 主机。",
    model10Hourly: "大模型 1.0 · 按小时",
    model10Concurrent: "大模型 1.0 · 并发版",
    model20Hourly: "大模型 2.0 · 按小时",
    model20Concurrent: "大模型 2.0 · 并发版",
    itn: "规范化数字和日期",
    punctuation: "添加标点",
    translate: "翻译最终识别结果",
    translationProvider: "翻译服务提供方",
    translationProviderDescription: "选择已安装且提供 text.translate v1 的插件；只有一个提供方时会自动选择。",
    translationTarget: "翻译目标语言",
    translationFallback: "翻译失败时保留原文",
    translationServiceMissing: "豆包语音识别：请安装提供 text.translate v1 的插件",
    translationProviderAmbiguous: "豆包语音识别：请在插件设置中选择一个 text.translate 服务提供方",
    translationFailed: "豆包语音识别翻译失败：%1"
  },
  "zh-TW": {
    name: "豆包語音辨識（V3）",
    apiKey: "API Key",
    apiKeyDescription: "由 Foxy 加密儲存，並注入已授權主機的請求。",
    resource: "資源 ID",
    endpoint: "WebSocket 端點",
    endpointDescription: "必須使用已授權的 openspeech.bytedance.com 主機。",
    model10Hourly: "大模型 1.0 · 按小時",
    model10Concurrent: "大模型 1.0 · 併發版",
    model20Hourly: "大模型 2.0 · 按小時",
    model20Concurrent: "大模型 2.0 · 併發版",
    itn: "正規化數字和日期",
    punctuation: "加入標點",
    translate: "翻譯最終辨識結果",
    translationProvider: "翻譯服務提供者",
    translationProviderDescription: "選擇已安裝且提供 text.translate v1 的外掛；只有一個提供者時會自動選擇。",
    translationTarget: "翻譯目標語言",
    translationFallback: "翻譯失敗時保留原文",
    translationServiceMissing: "豆包語音辨識：請安裝提供 text.translate v1 的外掛",
    translationProviderAmbiguous: "豆包語音辨識：請在外掛設定中選擇一個 text.translate 服務提供者",
    translationFailed: "豆包語音辨識翻譯失敗：%1"
  }
};

function stringsFor(locale) {
  const tag = String(locale || "en").toLowerCase().replace(/_/g, "-");
  if (tag.includes("hant") || tag.startsWith("zh-tw") || tag.startsWith("zh-hk") || tag.startsWith("zh-mo")) {
    return STRINGS["zh-TW"];
  }
  if (tag.startsWith("zh")) return STRINGS["zh-CN"];
  return STRINGS.en;
}

function uuid() {
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, ch => {
    const random = Math.floor(Math.random() * 16);
    return (ch === "x" ? random : (random & 3) | 8).toString(16);
  });
}

const START_PREFIX = "ERARAA==";
const AUDIO_PREFIX = "ESABAA==";
const FINAL_AUDIO_PREFIX = "ESIBAA==";
let state = newSessionState();

function newSessionState() {
  return {
    ending: false,
    lastPartial: "",
    finalUtterances: new Map(),
    translationEnabled: false,
    translationProvider: "",
    translationTarget: "English",
    translationFallback: true
  };
}

function setting(context, key) {
  const value = context[key];
  return value === undefined || value === null ? "" : String(value).trim();
}

function configureTranslation(ui, t) {
  ui.switch("translationEnabled", t.translate, false);
  ui.serviceSelect("translationProvider", t.translationProvider, TRANSLATION_SERVICE_ID, TRANSLATION_SERVICE_VERSION, "", {
    visibleWhen: {key: "translationEnabled", value: "true"},
    description: t.translationProviderDescription
  });
  ui.select("translationTarget", t.translationTarget, TRANSLATION_TARGETS, "English", {
    visibleWhen: {key: "translationEnabled", value: "true"}
  });
  ui.switch("translationFallback", t.translationFallback, true, {
    visibleWhen: {key: "translationEnabled", value: "true"}
  });
}

function resolveTranslationProvider(context, t) {
  const services = foxy.services.list(TRANSLATION_SERVICE_ID).filter(item =>
    item.serviceId === TRANSLATION_SERVICE_ID && item.version === TRANSLATION_SERVICE_VERSION);
  let provider = setting(context, "translationProvider");
  if (!provider) {
    if (services.length === 1) provider = services[0].pluginId;
    else if (services.length > 1) throw new Error(t.translationProviderAmbiguous);
  }
  if (!provider || !services.some(item => item.pluginId === provider)) {
    throw new Error(t.translationServiceMissing + (provider ? " (" + provider + ")" : ""));
  }
  return provider;
}

async function translateFinal(text) {
  const source = String(text || "").trim();
  if (!source || !state.translationEnabled) return {final: source, ended: true};
  try {
    const result = await foxy.services.call(
      state.translationProvider,
      TRANSLATION_SERVICE_ID,
      TRANSLATION_SERVICE_VERSION,
      {text: source, targetLanguage: state.translationTarget}
    );
    const translated = String(result && result.translatedText || "").trim();
    if (!translated) throw new Error("empty translation result");
    return {final: translated, ended: true};
  } catch (error) {
    if (state.translationFallback) return {final: source, ended: true};
    const t = stringsFor(foxy.locale);
    return {error: String(t.translationFailed).replace("%1", error && (error.message || error) || error)};
  }
}

foxy.plugin.register({
  id: "doubao-asr",
  name: stringsFor(foxy.locale).name,
  version: "1.2.0",
  hosts: ["openspeech.bytedance.com"],
  features: [{type: "voice.asr", id: "doubao-asr", label: stringsFor(foxy.locale).name, sampleRate: 16000}],
  configure(ui, locale) {
    const t = stringsFor(locale);
    ui.secret("apiKey", t.apiKey, {description: t.apiKeyDescription});
    ui.select("resourceId", t.resource, [
      {label: t.model10Hourly, value: "volc.bigasr.sauc.duration"},
      {label: t.model10Concurrent, value: "volc.bigasr.sauc.concurrent"},
      {label: t.model20Hourly, value: "volc.seedasr.sauc.duration"},
      {label: t.model20Concurrent, value: "volc.seedasr.sauc.concurrent"}
    ], "volc.bigasr.sauc.duration");
    ui.text("endpoint", t.endpoint, ENDPOINT, {description: t.endpointDescription});
    ui.switch("enableItn", t.itn, true);
    ui.switch("enablePunctuation", t.punctuation, true);
    configureTranslation(ui, t);
  },
  async start(context) {
    state = newSessionState();
    state.translationEnabled = context.translationEnabled === "true";
    state.translationFallback = context.translationFallback !== "false";
    const preferredTarget = setting(context, "translationTarget");
    state.translationTarget = TRANSLATION_TARGETS.includes(preferredTarget) ? preferredTarget : "English";
    if (state.translationEnabled) {
      state.translationProvider = resolveTranslationProvider(context, stringsFor(foxy.locale));
    }
    const socket = foxy.websocket.connect(
      context.endpoint || ENDPOINT,
      "apiKey",
      "X-Api-Key",
      "",
      JSON.stringify({
        "X-Api-Resource-Id": context.resourceId || "volc.bigasr.sauc.duration",
        "X-Api-Request-Id": context.taskId || uuid(),
        "X-Api-Connect-Id": uuid(),
        "X-Api-Sequence": "-1"
      })
    );
    await foxy.websocket.waitOpen(socket);
    const request = {
      user: {uid: "foxy"},
      audio: {format: "pcm", rate: context.sampleRate, bits: 16, channel: 1, codec: "raw"},
      request: {
        model_name: "bigmodel",
        enable_itn: context.enableItn !== "false",
        enable_punc: context.enablePunctuation !== "false",
        show_utterances: true,
        result_type: "full"
      }
    };
    foxy.websocket.sendGzipFrame(socket, START_PREFIX, foxy.binary.utf8Base64(JSON.stringify(request)));
    foxy.voice.ready();
    return socket;
  },
  async audio(context) {
    foxy.websocket.sendGzipFrame(context.socket, AUDIO_PREFIX, context.pcm);
  },
  async stop(context) {
    state.ending = true;
    foxy.websocket.sendGzipFrame(context.socket, FINAL_AUDIO_PREFIX, "");
  },
  async onMessage(event) {
    if (event.binaryBase64) {
      const decoded = decodeServerFrame(event.binaryBase64);
      if (!decoded) return {};
      if (decoded.error) return {error: decoded.error};
      return transcriptAction(decoded.payload, decoded.isLast);
    }
    if (event.message) {
      try {
        return transcriptAction(JSON.parse(event.message), false);
      } catch (_) {
        return {};
      }
    }
    return {};
  }
});

function decodeServerFrame(base64) {
  const total = foxy.binary.byteLength(base64);
  if (total < 4) return null;
  const first = foxy.binary.readUInt8(base64, 0);
  const headerBytes = (first & 0x0f) * 4;
  if (headerBytes < 4 || headerBytes > total) throw new Error("Invalid V3 response header");
  const typeAndFlags = foxy.binary.readUInt8(base64, 1);
  const type = typeAndFlags >>> 4;
  const flags = typeAndFlags & 0x0f;
  const compression = foxy.binary.readUInt8(base64, 2) & 0x0f;
  let offset = headerBytes;
  if ((flags & 0x01) !== 0) {
    if (total < offset + 4) return null;
    offset += 4;
  }

  if (type === 0x0f) {
    if (total < offset + 8) throw new Error("Truncated V3 error frame");
    const code = foxy.binary.readInt32BE(base64, offset);
    offset += 4;
    const size = foxy.binary.readInt32BE(base64, offset);
    offset += 4;
    const message = decodePayload(base64, offset, size, compression);
    let detail = message;
    try {
      const error = JSON.parse(message);
      detail = error.message || (error.error && error.error.message) || message;
    } catch (_) {}
    return {error: "Doubao ASR error " + code + ": " + detail};
  }
  if (type !== 0x09 && type !== 0x0b) return null;
  if (total < offset + 4) return null;
  const size = foxy.binary.readInt32BE(base64, offset);
  offset += 4;
  if (size <= 0) return null;
  if (total < offset + size) throw new Error("Truncated V3 response payload");
  const payload = decodePayload(base64, offset, size, compression);
  if (!payload.trim()) return null;
  try {
    return {isLast: (flags & 0x02) !== 0, payload: JSON.parse(payload)};
  } catch (_) {
    return null;
  }
}

function decodePayload(base64, offset, size, compression) {
  if (size < 0) throw new Error("Invalid V3 payload size");
  const payload = foxy.binary.sliceBase64(base64, offset, size);
  if (compression === 0x01) return foxy.binary.gunzipUtf8(payload);
  if (compression === 0x00) return foxy.binary.base64Utf8(payload);
  throw new Error("Unsupported V3 response compression: " + compression);
}

async function transcriptAction(payload, isLast) {
  const error = extractError(payload);
  if (error) return {error: error};
  const utterances = extractUtterances(payload);
  let transcript = "";
  if (utterances.length > 0) {
    let partial = "";
    utterances.forEach(utterance => {
      if (utterance.definite) {
        const key = utterance.start !== "" || utterance.end !== ""
          ? utterance.start + ":" + utterance.end
          : utterance.resultIndex + ":" + utterance.utteranceIndex + ":" + utterance.text;
        state.finalUtterances.set(key, utterance.text);
      } else {
        partial = utterance.text;
      }
    });
    transcript = joinTexts(Array.from(state.finalUtterances.values()).concat(partial));
  } else {
    transcript = extractText(payload).trim();
  }
  if (transcript) state.lastPartial = transcript;
  if (isLast && state.ending) {
    const finalText = transcript || state.lastPartial;
    return finalText ? translateFinal(finalText) : {ended: true};
  }
  return transcript ? {partial: transcript} : {};
}

function extractError(value) {
  if (!value || typeof value !== "object") return "";
  const code = Number(value.code || 0);
  if (code !== 0 && code !== 1000 && code !== 1002) return value.message || "Doubao ASR failed";
  if (value.error && value.error.message) return value.error.message;
  if (value.payload_msg) return extractError(value.payload_msg);
  return "";
}

function extractUtterances(value) {
  if (!value || typeof value !== "object") return [];
  if (value.payload_msg) return extractUtterances(value.payload_msg);
  const out = [];
  appendUtterances(value.utterances, out);
  if (value.result && !Array.isArray(value.result)) appendUtterances(value.result.utterances, out);
  appendResults(value.result, out);
  appendResults(value.results, out);
  return out;
}

function appendResults(results, out) {
  if (!Array.isArray(results)) return;
  results.forEach((result, index) => {
    if (result && typeof result === "object") appendUtterances(result.utterances, out, index);
  });
}

function appendUtterances(items, out, resultIndex = -1) {
  if (!Array.isArray(items)) return;
  items.forEach((item, utteranceIndex) => {
    if (!item || typeof item !== "object") return;
    const text = String(item.text || item.utterance || "").trim();
    if (!text) return;
    out.push({
      text: text,
      definite: !!item.definite,
      start: firstPresent(item.start_time, item.startTime, item.start),
      end: firstPresent(item.end_time, item.endTime, item.end),
      resultIndex: resultIndex,
      utteranceIndex: utteranceIndex
    });
  });
}

function firstPresent(...values) {
  for (const value of values) {
    if (value !== undefined && value !== null && value !== "") return String(value);
  }
  return "";
}

function extractText(value) {
  if (!value || typeof value !== "object") return "";
  if (typeof value.text === "string" && value.text.trim()) return value.text.trim();
  if (value.result && typeof value.result.text === "string") return value.result.text.trim();
  if (Array.isArray(value.result)) {
    return joinTexts(value.result.map(item => item && (item.text || item.utterance || "")));
  }
  if (Array.isArray(value.results)) {
    return joinTexts(value.results.map(item => item && (item.text || item.utterance || "")));
  }
  if (value.payload_msg) return extractText(value.payload_msg);
  return "";
}

function joinTexts(parts) {
  let output = "";
  parts.filter(Boolean).forEach(part => {
    const text = String(part).trim();
    if (!text) return;
    if (output && needsSpace(output[output.length - 1], text[0])) output += " ";
    output += text;
  });
  return output;
}

function needsSpace(previous, next) {
  const noSpaceBefore = "，。！？、；：,.!?;:)]}）】》";
  const noSpaceAfter = "([{（【《";
  if (/\s/.test(previous) || /\s/.test(next) || noSpaceBefore.includes(next) || noSpaceAfter.includes(previous)) return false;
  return !isCjk(previous) && !isCjk(next);
}

function isCjk(char) {
  const code = char.charCodeAt(0);
  return (code >= 0x2e80 && code <= 0x9fff) ||
    (code >= 0xf900 && code <= 0xfaff) ||
    (code >= 0x3040 && code <= 0x30ff) ||
    (code >= 0xac00 && code <= 0xd7af);
}
