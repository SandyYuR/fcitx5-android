# 更新器崩溃与 MD3 遗留界面检查（2026-10-21）

## 更新器启动失败

用户日志 `org.fcitx.fcitx5.android.fx.rime-2026-10-21T01_04_08Z.txt` 记录：

```text
09:04:06.462 Unable to start activity UpdateCheckActivity
Caused by: ClassCastException:
androidx.appcompat.widget.Toolbar cannot be cast to
com.google.android.material.appbar.MaterialToolbar
```

`0.1.3-715-g6db49cec` 恢复了更新入口，但默认分支此前只修改了 Kotlin 工具栏类型，
对应 XML 仍创建 AppCompat Toolbar。编译和原有输入回归不能发现这种运行时类型错误。
hosts、镜像编辑与镜像列表三个页面也存在同样的不一致。
本次把全部相应 XML 工具栏改为 MaterialToolbar，并使用设置页表面色、无阴影和 64dp 高度。

## 检查及迁移范围

| 位置 | 本次处理 |
| --- | --- |
| 更新器四页 | 修复工具栏；hosts 开关改为 MaterialSwitch，输入框使用 TextInputLayout / TextInputEditText，按钮及下载进度使用 Material 组件 |
| 普通日志、崩溃日志 | 接入 SettingsUiActivity，使用 MD3 工具栏、主题配色与统一窗口边距；日志级别颜色使用当前主题的前景角色；崩溃提示随 Activity 销毁关闭 |
| 方案 ZIP 导入、图片裁剪、剪贴板同步页 | 接入同一设置主题，去除重复系统栏边距与固定强调色；保留实际导入、裁剪和同步逻辑 |
| 剪贴板编辑、布局文件选择窗 | 使用同一设置配色，分别保留浮动与透明窗口，不对这两种窗口重复施加全屏页面边距 |
| 主题选择、动态列表编辑、导入报错 | 共用弹窗改为 MaterialAlertDialogBuilder，保留验证通过后才关闭的行为 |
| 剪贴板同步的服务器、账户、存储和测试推送表单 | 输入框改为 Material outlined 文本框，保留字段 ID；按钮改为 MaterialButton |
| 布局、宏、主题、字体、校准等编辑控件 | 遗留按钮、文本框、复选框、开关改用 Material 子类；整数滑条由 Material Slider 提供，保留已有范围、进度映射及回调 |
| 编辑器下拉选择 | MaterialSpinner 保留 Spinner 的原生适配器、索引、选择回调及数据观察行为，使用 MaterialShapeDrawable 的描边、波纹、图标和菜单表面色 |
| 键盘内方案／输入法菜单 | 使用 Material 弹窗和设置主题 context；保留输入服务附着窗口、不抢焦点、返回及外部关闭机制，方案条目改为 MaterialRadioButton |

浮动编辑窗在解锁前使用设备保护存储读取设置，保留其 direct-boot 使用方式。
本次没有改动 Rime、用户词库、同步策略或逐键输入路径。

## 仍存在的基础类型与专用界面

`SettingsUiActivity` 的工具栏方法需要接收 AppCompat Toolbar 基类；部分字段与监听器
仍使用 Android 的 EditText、Button、CheckBox 或 Spinner 基类，以接收对应 Material 子类。
这些类型声明不是旧组件实例。MaterialSpinner 是保留 Spinner 行为的自定义 MD3 外观，
不是 MaterialAutoCompleteTextView；这样不必重写布局／宏编辑器的适配器和选择逻辑。

键盘按键、候选、主题预览及裁剪画布使用各自的绘制逻辑。系统文件选择器、权限／安装界面
由 Android 提供；扫码摄像头由 ZXing 提供。它们不作为普通设置页面重建。
`Theme.InputViewTheme` 为自绘键盘提供基础属性，键盘菜单单独使用 Material context。
无界面的剪贴板抓取 Activity 保留原有服务转发用途。

## 验证方式

新增 `MaterialSettingsPagesTest` 并纳入完整前端设备工作流：

- 实际启动更新器四页，检查工具栏类型、hosts 开关、文本框与下载进度组件。
- 分别以明亮和暗色启动普通日志、崩溃日志、导入、裁剪、剪贴板同步等页面。
- 打开浮动剪贴板编辑窗、透明布局选择窗、共用进度框，并检查 Material 组件及窗口背景。
- 实际触摸 Material Slider、点选下拉菜单，检查进度及选择值仍传回编辑器。
- 保存各页深浅色截图到设备测试产物，供视觉复查。

继续运行既有密码框方案菜单焦点／预测返回、部署、输入与日志导出回归。
CI 的最终提交、结果及签名包以 PR 描述为准；不将构建通过等同于页面启动通过。
