package io.github.mzdluo123.mirai.android.ui.tools

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.textfield.TextInputLayout
import io.github.mzdluo123.mirai.android.R
import java.io.File


class ToolsFragment : Fragment() {

    private val viewModel by viewModels<ToolsFragmentViewModel>()

    private lateinit var menu: TextInputLayout
    private lateinit var btnExportDevice: Button
    private lateinit var btnResetDevice: Button

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_tools, container, false)

        menu = root.findViewById(R.id.menu)
        btnExportDevice = root.findViewById(R.id.btn_export_device)
        btnResetDevice = root.findViewById(R.id.btn_reset_device)

        return root
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    @SuppressLint("SdCardPath")
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        val adapter =
            ArrayAdapter(requireContext(), R.layout.item_list_menu, mutableListOf<String>())
        (menu.editText as AutoCompleteTextView).setAdapter(adapter)
        viewModel.botList.observe(viewLifecycleOwner) { arrayOfFiles ->
            if (arrayOfFiles == null) {
                return@observe
            }
            adapter.clear()
            adapter.addAll(arrayOfFiles.map { it.name })
            adapter.notifyDataSetChanged()
        }

        btnExportDevice.setOnClickListener {
            val intent = Intent(Intent.ACTION_SEND)
            val uri = FileProvider.getUriForFile(
                requireContext(),
                requireContext().packageName + ".provider",
                getDeviceFile() ?: return@setOnClickListener
            )
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.type = "application/octet-stream";
            requireContext().startActivity(intent)
        }
        btnResetDevice.setOnClickListener {
            getDeviceFile()?.delete() ?: return@setOnClickListener
            Toast.makeText(it.context, "成功", Toast.LENGTH_SHORT).show()
        }
//        btn_open_data_folder.setOnClickListener {
//            val intent = Intent()
//
//            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK;
//            intent.component = ComponentName(
//                "com.android.documentsui",
//                "com.android.documentsui.files.FilesActivity"
//            )
//
//            requireContext().startActivity(intent)
//
//        }
    }

    private fun getDeviceFile(): File? {
        val folder = menu.editText?.text
        if (folder?.isEmpty() != false) {
            Toast.makeText(context ?: return null, "请选择bot", Toast.LENGTH_SHORT).show()
            return null
        }
        return File(requireContext().getExternalFilesDir(""), "bots/${folder}/device.json")
    }

}