package io.github.mzdluo123.mirai.android.activity

import android.net.Uri
import android.os.Bundle
import android.widget.CheckBox
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import io.github.mzdluo123.mirai.android.R
import io.github.mzdluo123.mirai.android.appcenter.trace
import io.github.mzdluo123.mirai.android.databinding.ActivityPluginImportBinding
import io.github.mzdluo123.mirai.android.ui.plugin.PluginViewModel
import io.github.mzdluo123.mirai.android.utils.askFileName
import io.github.mzdluo123.mirai.android.utils.copyToFileDir
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File


class PluginImportActivity : AppCompatActivity() {

    private lateinit var uri: Uri

    private lateinit var pluginViewModel: PluginViewModel
    private lateinit var dialog: AlertDialog
    private lateinit var activityPluginImportBinding: ActivityPluginImportBinding

    private lateinit var importRadiogroup: RadioGroup
    private lateinit var desugaringCheckbox: CheckBox
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_plugin_import)

        importRadiogroup = findViewById(R.id.import_radioGroup)
        desugaringCheckbox = findViewById(R.id.desugaring_checkBox)
//        uri = Uri.parse(intent.getStringExtra("uri"))

//        val errorHandel = CoroutineExceptionHandler { _, e ->
//            Toast.makeText(this, "无法打开这个文件，请检查这是不是一个合法的插件jar文件", Toast.LENGTH_SHORT).show()
//            e.printStackTrace()
//            finish()
//
//        }
        uri = intent.data ?: return
        pluginViewModel = ViewModelProvider(this).get(PluginViewModel::class.java)
        activityPluginImportBinding =
            DataBindingUtil.setContentView(this, R.layout.activity_plugin_import)
//        lifecycleScope.launch(errorHandel) { loadPluginData() }
        activityPluginImportBinding.importBtn.setOnClickListener {
            startImport()
        }

    }

    private fun createDialog() {
        dialog = AlertDialog.Builder(this)
            .setTitle("正在编译")
            .setMessage("这可能需要一些时间，请不要最小化")
            .setCancelable(false)
            .create()
    }

    private fun startImport() {
        createDialog()
        val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
            lifecycleScope.launch(Dispatchers.Main) {
                dialog.dismiss()
                Toast.makeText(
                    this@PluginImportActivity,
                    "无法编译插件 \n${throwable}",
                    Toast.LENGTH_LONG
                ).show()
                throwable.printStackTrace()
                trace(
                    "install plugin error",
                    "type" to throwable.javaClass.name,
                    "msg" to throwable.message
                )
            }
        }



        dialog.show()
        when (importRadiogroup.checkedRadioButtonId) {
            R.id.compile_radioButton -> {
                lifecycleScope.launch(exceptionHandler) {
                    val name = withContext(Dispatchers.Main) {
                        askFileName()
                    } ?: return@launch
                    // 从contentprovider 读取插件数据到缓存
                    withContext(Dispatchers.IO) {
                        copyToFileDir(
                            uri,
                            name,
                            this@PluginImportActivity.getExternalFilesDir(null)!!.absolutePath
                        )
                    }
                    // 编译插件
                    pluginViewModel.compilePlugin(
                        File(baseContext.getExternalFilesDir(null), name),
                        desugaringCheckbox.isChecked
                    )
                    // 删除缓存
                    withContext(Dispatchers.IO) {
                        File(this@PluginImportActivity.getExternalFilesDir(null), name).delete()
                    }
                    dialog.dismiss()
                    Toast.makeText(this@PluginImportActivity, "安装成功,重启后即可加载", Toast.LENGTH_SHORT)
                        .show()
                    finish()
                    trace("install plugin success", "name" to name)
                }
            }
            R.id.copy_radioButton -> {
                lifecycleScope.launch(exceptionHandler) {
                    val name = withContext(Dispatchers.Main) {
                        askFileName()
                    } ?: return@launch
                    withContext(Dispatchers.IO) {
                        copyToFileDir(
                            uri,
                            name,
                            this@PluginImportActivity.getExternalFilesDir("plugins")!!.absolutePath
                        )
                    }
                    dialog.dismiss()
                    Toast.makeText(this@PluginImportActivity, "安装成功,重启后即可加载", Toast.LENGTH_SHORT)
                        .show()
                    finish()
                    trace("install plugin success", "name" to name)
                }
            }
        }

    }


}


