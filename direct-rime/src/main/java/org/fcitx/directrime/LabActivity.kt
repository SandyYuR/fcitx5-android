package org.fcitx.directrime

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class LabActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var status: TextView
    private val controls = mutableListOf<Button>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 32, 24, 24) }
        content.addView(TextView(this).apply { text = "Rime 直连实验"; textSize = 26f })
        content.addView(TextView(this).apply {
            text = "使用独立引擎和数据目录，可与靓企鹅共存。导入的是 Rime 用户目录 ZIP 副本，原目录不会被修改。此版本用于输入正确性与性能实验，尚未通过完整行为等价验证。"
        })
        status = TextView(this).apply { text = "先安装测试方案，或导入自己的方案并部署。" }.also { content.addView(it) }
        fun button(label: String, click: () -> Unit) {
            content.addView(Button(this).apply { text = label; setOnClickListener { click() }; controls += this })
        }
        button("安装小型测试方案") { work { RimeRuntime.deploy(applicationContext, smoke = true) } }
        button("导入 Rime 用户目录 ZIP") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "application/zip"; addCategory(Intent.CATEGORY_OPENABLE)
            }, 1)
        }
        button("重新部署实验方案") { work { RimeRuntime.deploy(applicationContext) } }
        button("恢复上一份实验数据") { work { RimeRuntime.deploy(applicationContext, restorePrevious = true) } }
        button("启用实验输入法") { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        button("切换输入法") { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() }
        content.addView(EditText(this).apply {
            id = R.id.editor_one
            hint = "在这里测试：输入 ni，选择“你”；输入 hao，选择“好”"; minLines = 4; gravity = android.view.Gravity.TOP
        })
        content.addView(EditText(this).apply {
            id = R.id.editor_two
            hint = "第二个编辑框：测试焦点切换后独立输入"; minLines = 2
        })
        content.addView(TextView(this).apply {
            text = "候选长按可忘词；“方案”切换到下一方案。部署期间按键会被拦截；部署完成后重新聚焦编辑框。旧实验数据保留在应用私有 rime/previous 目录。"
        })
        setContentView(ScrollView(this).apply { addView(content) })
    }
    private fun work(block: suspend () -> Boolean) {
        controls.forEach { it.isEnabled = false }; status.text = "正在部署，暂时停止接收输入…"
        scope.launch {
            try {
                status.text = if (block()) "部署成功。切换到实验输入法并重新聚焦编辑框。"
                else "部署失败，请检查方案和依赖；原输入法仍可使用。"
            } catch (e: Exception) { status.text = "操作失败：${e.message}" }
            finally { controls.forEach { it.isEnabled = true } }
        }
    }
    @Deprecated("Uses the platform picker to keep this experiment independent of the production app")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            work { RimeRuntime.deploy(applicationContext, profile = { contentResolver.openInputStream(uri) ?: error("无法读取 ZIP") }) }
        }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
