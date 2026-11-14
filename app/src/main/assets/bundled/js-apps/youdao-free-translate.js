/**
 * 翻译（免密钥）— 元书脚本的中州鹅 JS App 适配版
 *
 * 原脚本：翻译-免密钥.js；沿用有道公开演示接口，无需 API 密钥。
 *
 * 用法：选中文字后打开工具，或在面板输入／手动粘贴；
 * 选择目标语言 → 翻译（原文、译文同时显示）→ 确认上屏。关闭会撤销临时预览。
 * 不自动读取剪贴板，不在打开面板时发送请求。
 *
 * 支持前缀：中 英 日 韩 法 德 俄 西，后接空格或冒号。
 * 例如「英 你好」「日：你好」。有前缀时优先于下拉选择。
 * 自动模式交给接口判断方向，不保证任意语言两两互译。
 * 待翻译文本仅在点击翻译后发给 aidemo.youdao.com；公开演示服务
 * 可能限流或停用，本脚本不会自动重试或切换到其它服务。
 */

(function () {
  'use strict';

  const LANGUAGE_VALUES = [
    ['auto', 'auto'], ['zhCHS', 'zh-CHS'], ['en', 'en'], ['ja', 'ja'],
    ['ko', 'ko'], ['fr', 'fr'], ['de', 'de'], ['ru', 'ru'], ['es', 'es']
  ];
  const STRINGS = {
    en: {
      name: 'Translate (no API key)', close: 'Close',
      sourceAuto: 'Detect language', original: 'Original text', placeholder: 'Type or paste text, for example: 英 你好',
      result: 'Translation', previewUnavailable: 'This editor cannot show a temporary preview. Review the translation here, then confirm to insert it.',
      clear: 'Clear', translate: 'Translate', retranslate: 'Again', reuse: 'Use', replace: 'Apply', insert: 'Apply',
      changed: 'The text or language changed. Translate again before inserting.', empty: 'Enter text to translate.', failed: 'Translation failed: ',
      defaultTarget: 'Default target language', settingHint: 'No API key. Prefixes such as “英 你好” take priority. The public demo may be limited.',
      rateLimited: 'The public service rate limit was reached. Try again later; this does not mean the selected language is unsupported.', http: 'HTTP status ', badJson: 'The service returned invalid JSON.', badFormat: 'The service response format is invalid.', noResult: 'The service returned no valid translation.', serviceCode: 'Service error code ',
      languages: {auto:'Auto',zhCHS:'Simplified Chinese',en:'English',ja:'Japanese',ko:'Korean',fr:'French',de:'German',ru:'Russian',es:'Spanish'}
    },
    zhCN: {
      name: '翻译（免密钥）', close: '关闭',
      sourceAuto: '自动识别', original: '原文', placeholder: '输入或粘贴文本，例如：英 你好',
      result: '译文', previewUnavailable: '当前输入框不支持临时预览；请在此核对译文，确认后再写入。',
      clear: '清空', translate: '翻译', retranslate: '再译', reuse: '作原文', replace: '替换', insert: '插入',
      changed: '文本或语言已改变，请重新翻译后再确认上屏。', empty: '请输入要翻译的文本。', failed: '翻译失败：',
      defaultTarget: '默认目标语言', settingHint: '无需密钥；前缀（如“英 你好”）优先。公开接口可能限流或停用。',
      rateLimited: '公开接口触发频率限制，请稍后再试；这不表示所选语言不受支持。', http: 'HTTP 状态码 ', badJson: '接口返回的内容不是有效 JSON，服务可能暂不可用。', badFormat: '接口返回格式异常。', noResult: '接口没有返回有效译文。', serviceCode: '接口返回错误码 ',
      languages: {auto:'自动',zhCHS:'简体中文',en:'英语',ja:'日语',ko:'韩语',fr:'法语',de:'德语',ru:'俄语',es:'西班牙语'}
    },
    zhTW: {
      name: '翻譯（免金鑰）', close: '關閉',
      sourceAuto: '自動偵測', original: '原文', placeholder: '輸入或貼上文字，例如：英 你好',
      result: '譯文', previewUnavailable: '目前輸入框不支援暫時預覽；請在此核對譯文，確認後再寫入。',
      clear: '清除', translate: '翻譯', retranslate: '再譯', reuse: '作原文', replace: '取代', insert: '插入',
      changed: '文字或語言已變更，請重新翻譯後再確認寫入。', empty: '請輸入要翻譯的文字。', failed: '翻譯失敗：',
      defaultTarget: '預設目標語言', settingHint: '無需金鑰；前綴（如「英 你好」）優先。公開介面可能限流或停用。',
      rateLimited: '公開介面觸發頻率限制，請稍後再試；這不表示所選語言不受支援。', http: 'HTTP 狀態碼 ', badJson: '介面回傳的內容不是有效 JSON。', badFormat: '介面回傳格式異常。', noResult: '介面沒有回傳有效譯文。', serviceCode: '介面回傳錯誤碼 ',
      languages: {auto:'自動',zhCHS:'簡體中文',en:'英語',ja:'日語',ko:'韓語',fr:'法語',de:'德語',ru:'俄語',es:'西班牙語'}
    }
  };
  const PREFIXES = {
    '中': 'zh-CHS', '英': 'en', '日': 'ja', '韩': 'ko',
    '法': 'fr', '德': 'de', '俄': 'ru', '西': 'es'
  };
  let result = '';
  let resultKey = '';
  let openedWithSelection = false;

  function stringsFor(locale) {
    const tag = String(locale || 'en').toLowerCase().replace(/_/g, '-');
    if (tag === 'zh-tw' || tag === 'zh-hk' || tag.startsWith('zh-hant')) return STRINGS.zhTW;
    if (tag.startsWith('zh')) return STRINGS.zhCN;
    return STRINGS.en;
  }

  function languagesFor(t) {
    return LANGUAGE_VALUES.map(function (item) {
      return {label: t.languages[item[0]], value: item[1]};
    });
  }

  function validTarget(value) {
    return LANGUAGE_VALUES.some(function (item) { return item[1] === value; }) ? value : 'auto';
  }

  function requestInput(input, target) {
    // 必须有分隔符，避免把「英语很好」「中文测试」的首字误删。
    const prefix = /^([中英日韩法德俄西])(?:\s+|[:：])([\s\S]*)$/.exec(input.trimStart());
    return {
      text: (prefix ? prefix[2] : input).trim(),
      target: prefix ? PREFIXES[prefix[1]] : target
    };
  }

  function panel(input, target, message, isError) {
    const t = stringsFor(foxy.locale);
    const children = [
      {type: 'header', gap: 4, children: [
        {type: 'text', text: t.name, size: 20, bold: true, weight: 1},
        {type: 'button', id: 'close', label: t.close, style: 'text', icon: 'close', iconOnly: true}
      ]},
      {type: 'row', gap: 6, children: [
        {type: 'text', text: t.sourceAuto, size: 14, bold: true, weight: 1},
        {type: 'text', text: '→', size: 16, muted: true},
        {type: 'select', id: 'target', options: languagesFor(t), value: target, weight: 2, height: 48}
      ]},
      {type: 'input', id: 'input', label: t.original, labelPlacement: 'top',
        multiline: true, value: input, placeholder: t.placeholder, height: result ? 84 : 112}
    ];
    if (message) children.push({
      type: 'text', text: message, size: 12, muted: !isError,
      tone: isError ? 'error' : '', maxLines: 3
    });
    if (result) children.push({
      type: 'input', id: 'result', label: t.result, labelPlacement: 'top',
      multiline: true, readOnly: true, value: result, height: 84
    });
    const buttons = [
      {type: 'button', id: 'clear', label: t.clear, style: 'text'}
    ];
    if (result) buttons.push({type: 'button', id: 'use_result', label: t.reuse, style: 'text'});
    buttons.push(
      {type: 'button', id: 'translate', label: result ? t.retranslate : t.translate,
        style: result ? 'tonal' : 'filled'}
    );
    if (result) buttons.push({
      type: 'button', id: 'confirm', label: openedWithSelection ? t.replace : t.insert,
      style: 'filled'
    });
    children.push({type: 'actions', children: buttons});
    return foxy.ui.dialog.show({
      maxWidth: 380, maxHeight: 440,
      layout: {type: 'column', gap: 8, children: children}
    });
  }

  async function clearResult() {
    result = '';
    resultKey = '';
    await foxy.editor.clearComposition();
  }

  async function translate(text, target, t) {
    const url = 'https://aidemo.youdao.com/trans?from=auto&to=' +
      encodeURIComponent(target) + '&q=' + encodeURIComponent(text);
    const response = JSON.parse(await foxy.http.request({
      url: url, method: 'GET', timeoutMs: 30000
    }));
    if (!response || response.status !== 200) {
      const status = response && response.status;
      if (status === 411 || status === 429) throw new Error(t.rateLimited);
      throw new Error(t.http + (status || 'No response'));
    }
    let data;
    try {
      data = JSON.parse(response.body);
    } catch (_) {
      throw new Error(t.badJson);
    }
    if (!data || typeof data !== 'object' || Array.isArray(data)) {
      throw new Error(t.badFormat);
    }
    if (String(data.errorCode) === '411') {
      throw new Error(t.rateLimited);
    }
    if (data.errorCode !== undefined && String(data.errorCode) !== '0') {
      throw new Error(t.serviceCode + String(data.errorCode).slice(0, 40));
    }
    const lines = data.translation;
    if (!Array.isArray(lines) || lines.length === 0 ||
        !lines.every(function (line) { return typeof line === 'string'; })) {
      throw new Error(t.noResult);
    }
    const translated = lines.join('\n').trim();
    if (!translated) throw new Error(t.noResult);
    return translated;
  }

  foxy.plugin.register({
    id: 'youdao-free-translate',
    name: stringsFor(foxy.locale).name,
    version: '1.0.0',
    hosts: ['aidemo.youdao.com'],
    features: [],
    configure: function (ui, locale) {
      const t = stringsFor(locale);
      ui.select('defaultTarget', t.defaultTarget, languagesFor(t), 'auto', {
        description: t.settingHint
      });
    }
  });

  foxy.app.register({
    id: 'youdao_translate',
    label: stringsFor(foxy.locale).name,
    permissions: ['editor.read_selection', 'editor.composition', 'editor.commit', 'network.request'],
    onOpen: async function (context) {
      const event = context.event;
      const t = stringsFor(foxy.locale);
      const values = context.values || {};
      let input = typeof values.input === 'string' ? values.input : '';
      let target = validTarget(values.target);
      try {
        if (event === 'close' || event === 'cancel' || event === 'dismiss') {
          await clearResult();
          openedWithSelection = false;
          return {close: true};
        }
        if (event === 'open') {
          result = '';
          resultKey = '';
          target = validTarget((context.settings || {}).defaultTarget);
          input = String(await foxy.editor.getSelection() || '');
          openedWithSelection = input.trim().length > 0;
          return panel(input, target);
        }
        const key = JSON.stringify([input, target]);
        if (result && resultKey !== key) await clearResult();
        if (event === 'clear') {
          await clearResult();
          return panel('', target);
        }
        if (event === 'use_result' && result) {
          const nextInput = result;
          await clearResult();
          return panel(nextInput, target);
        }
        if (event === 'confirm') {
          if (!result || resultKey !== key) {
            return panel(input, target, t.changed, true);
          }
          await foxy.editor.commit(result);
          result = '';
          resultKey = '';
          openedWithSelection = false;
          return {close: true};
        }
        if (event !== 'translate') return panel(input, target);
        await clearResult();
        const request = requestInput(input, target);
        if (!request.text) return panel(input, target, t.empty, true);
        const translated = await translate(request.text, request.target, t);
        result = translated;
        resultKey = key;
        let previewUnavailable = false;
        // The panel result is authoritative. Some editors reject composing text even though
        // they still accept a final commit, so a failed temporary preview must not discard a
        // successful network translation.
        try {
          await foxy.editor.setComposition(translated);
        } catch (_) { previewUnavailable = true; }
        return panel(input, target, previewUnavailable ? t.previewUnavailable : '');
      } catch (error) {
        result = '';
        resultKey = '';
        // 回填或渲染失败后尽量撤回预览；失效会话由宿主负责兜底恢复。
        try { await foxy.editor.clearComposition(); } catch (_) {}
        const message = String(error && error.message ? error.message : error).slice(0, 180);
        return panel(input, target, t.failed + message, true);
      }
    }
  });
})();
