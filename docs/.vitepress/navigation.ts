import { legacySidebar } from './legacy-navigation'

export const nav = [
  { text: '开始使用', link: '/guide/quick-start', activeMatch: '^/(guide|manual)/' },
  { text: '功能指南', link: '/features/overview', activeMatch: '^/features/' },
  { text: '维护文档', link: '/maintainer/', activeMatch: '^/maintainer/' },
  { text: '历史版本', link: '/legacy/', activeMatch: '^/legacy/' },
  { text: '全部文档', link: '/contents', activeMatch: '^/contents' },
]

const start = [{ text: '开始使用', items: [
  { text: '认识中州鹅', link: '/guide/introduction' },
  { text: '安装与升级', link: '/guide/installation' },
  { text: '第一次中文输入', link: '/guide/quick-start' },
  { text: '方案、布局与数据的区别', link: '/guide/concepts' },
  { text: '版本共存与数据隔离', link: '/guide/builds-and-plugins' },
  { text: '从旧版迁移', link: '/guide/migrate-from-upstream' },
  { text: '直连版现状与范围', link: '/guide/direct-rime-migration' },
  { text: '完整用户手册', link: '/manual/DIRECT_RIME_USER_GUIDE_zh-CN' },
] }, { text: '遇到问题', items: [
  { text: '常见问题', link: '/troubleshooting/faq' },
  { text: '后台与自启动', link: '/troubleshooting/oem-startup' },
  { text: '日志与问题反馈', link: '/troubleshooting/report-issue' },
] }]

const features = [
  { text: '功能地图', items: [
    { text: '全部现有功能', link: '/features/overview' },
    { text: 'Rime 方案、部署与同步', link: '/features/rime-enhancements' },
    { text: '符号、表情与颜文字', link: '/features/symbol-panel' },
  ] },
  { text: '键盘与输入', collapsed: false, items: [
    { text: '浮动键盘与移动手柄', link: '/features/keyboard/float-keyboard' },
    { text: '分体键盘', link: '/features/keyboard/split-keyboard' },
    { text: '单手模式', link: '/features/keyboard/one-handed' },
    { text: '大小与位置调整', link: '/features/keyboard/adjust-mode' },
    { text: '滑动与长按', link: '/features/keyboard/swipe-actions' },
    { text: '候选栏与候选窗口', link: '/features/candidate-window' },
    { text: '工具栏（Kawaii Bar）', link: '/features/kawaii-bar' },
  ] },
  { text: '布局与编辑器', collapsed: true, items: [
    { text: '键盘布局编辑器', link: '/features/editor/layout-editor' },
    { text: '弹出字符编辑器', link: '/features/editor/popup-editor' },
    { text: '宏编辑器', link: '/features/editor/macrokey-editor' },
    { text: '字体集编辑器', link: '/features/editor/fontset-editor' },
    { text: '电脑与局域网编辑器', link: '/features/online-editor' },
    { text: '组合时覆盖按键', link: '/features/keyboard/compose-override' },
    { text: '文本编辑底部栏', link: '/features/text-editor' },
  ] },
  { text: '主题与设置外观', collapsed: true, items: [
    { text: '主题编辑与随机主题', link: '/features/theme/theme-editor' },
    { text: 'Monet 动态取色', link: '/features/theme/monet' },
    { text: '磨砂按键', link: '/features/theme/frosted-blur' },
    { text: '设置界面与返回动画', link: '/features/theme/settings-appearance' },
    { text: '二维码分享与导入', link: '/features/theme/share-import' },
  ] },
  { text: '语音输入', collapsed: false, items: [
    { text: '选择引擎与 AIDL 提供方', link: '/features/voice-input' },
    { text: 'JS 插件安装与使用', link: '/features/voice-input-js' },
    { text: 'JS 插件开发接口', link: '/maintainer/implementation/js-voice-plugins' },
  ] },
  { text: '数据与常用工具', collapsed: true, items: [
    { text: '剪贴板与远端同步', link: '/features/clipboard-sync' },
    { text: '备份、恢复与密钥迁移', link: '/features/backup-restore' },
    { text: '更新检查与断点下载', link: '/features/update-checker' },
    { text: '系统分享与文件导入', link: '/features/shared-import' },
  ] },
  { text: '按键字段参考', collapsed: true, items: [
    { text: '按键类型与共通字段', link: '/features/keys/overview' },
    { text: '字母键 AlphabetKey', link: '/features/keys/alphabet-key' },
    { text: '宏键 MacroKey', link: '/features/keys/macro-key' },
    { text: '布局切换键', link: '/features/keys/layout-switch-key' },
    { text: '空格键', link: '/features/keys/space-key' },
    { text: '退格键', link: '/features/keys/backspace-key' },
    { text: '语言键', link: '/features/keys/language-key' },
    { text: '符号键', link: '/features/keys/symbol-key' },
    { text: '大小写键', link: '/features/keys/caps-key' },
    { text: '回车键', link: '/features/keys/return-key' },
    { text: '逗号键', link: '/features/keys/comma-key' },
    { text: '兼容键名速查', link: '/features/keys/fcitx-keys' },
  ] },
]

const maintenance = [
  { text: '中州鹅 · 现行维护', items: [
    { text: '维护文档首页', link: '/maintainer/' },
    { text: '维护资料完整目录', link: '/maintainer/catalog' },
    { text: '构建、引擎与验证手册', link: '/maintainer/DIRECT_RIME_MAINTAINER_GUIDE_zh-CN' },
    { text: 'Windows 本机构建', link: '/maintainer/LOCAL_BUILD_ENVIRONMENT_zh-CN' },
    { text: '文档维护与分类约定', link: '/maintainer/documentation' },
  ] },
  { text: '代码与文档对照', collapsed: false, items: [
    { text: '核对范围与修订结论', link: '/maintainer/changes/' },
    { text: '直连版逐提交对照', link: '/maintainer/changes/direct-rime' },
    { text: '继承功能与旧提交索引', link: '/maintainer/changes/inherited' },
  ] },
  { text: '现行实现协议', collapsed: false, items: [
    { text: '协议索引', link: '/maintainer/implementation/' },
    { text: '正确性约束与代码定位', link: '/maintainer/implementation/correctness-contracts' },
    { text: 'JS 语音宿主与插件 API', link: '/maintainer/implementation/js-voice-plugins' },
    { text: 'ClipCascade 接收限额', link: '/maintainer/implementation/clipcascade-receive-limits' },
    { text: '自定义 hosts 下载', link: '/maintainer/implementation/hosts-downloads' },
    { text: '加密备份格式', link: '/maintainer/implementation/protected-backup-format' },
    { text: '整包恢复事务', link: '/maintainer/implementation/user-data-restore' },
  ] },
  { text: '中州鹅 · 历史与证据', collapsed: true, items: [
    { text: '直连版历史阅读路线', link: '/maintainer/history/' },
    { text: '全部验证与性能记录', link: '/maintainer/evidence' },
    { text: '2026-10-31 审阅修复', link: '/maintainer/history/review-fixes-2026-10-31' },
    { text: '2026-10-30 审阅记录', link: '/maintainer/history/review-2026-10-30' },
    { text: '2026-10-27 修复与合入', link: '/maintainer/history/review-2026-10' },
    { text: '旧审阅的 139 项对照', link: '/maintainer/history/DIRECT_RIME_LEGACY_REVIEW_COMPARISON_2026-10-24' },
    { text: '2026-10-21 交付记录', link: '/maintainer/history/DIRECT_RIME_RELEASE_READY_2026-10-21' },
    { text: '从原型到直连主应用', link: '/direct-rime' },
  ] },
  { text: '靓企鹅中州韵 · 旧版', collapsed: true, items: [
    { text: '版本区别与归档说明', link: '/legacy/' },
    { text: '含 Fcitx 的原站维护报告', link: '/legacy/fcitx/maintainer/' },
    { text: '后续批注与原始方案', link: '/legacy/fcitx/notes/' },
  ] },
]

const directory = [{ text: '文档导航', items: [
  { text: '全部文档', link: '/contents' },
  { text: '完整用户手册', link: '/manual/DIRECT_RIME_USER_GUIDE_zh-CN' },
  { text: '功能总览', link: '/features/overview' },
  { text: '维护文档', link: '/maintainer/' },
  { text: '版本与历史档案', link: '/legacy/' },
  { text: '参与文档修改', link: '/about/contribute-docs' },
  { text: '致谢与许可证', link: '/about/credits' },
  { text: '支持项目', link: '/about/donate' },
] }]

export const sidebar = {
  '/guide/': start,
  '/manual/': start,
  '/troubleshooting/': start,
  '/features/': features,
  '/maintainer/': maintenance,
  '/direct-rime': maintenance,
  '/contents': directory,
  '/about/': directory,
  '/legacy/': [{ text: '版本与档案', items: [
    { text: '版本区别', link: '/legacy/' },
    { text: '旧版完整原站', link: '/legacy/fcitx/contents' },
    { text: '旧版维护报告', link: '/legacy/fcitx/maintainer/' },
    { text: '后续批注与早期方案', link: '/legacy/fcitx/notes/' },
    { text: '返回中州鹅维护文档', link: '/maintainer/' },
  ] }, ...legacySidebar.map(group => ({ ...group, collapsed: true }))],
}
