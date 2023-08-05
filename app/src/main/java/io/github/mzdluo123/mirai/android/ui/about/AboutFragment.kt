package io.github.mzdluo123.mirai.android.ui.about

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import io.github.mzdluo123.mirai.android.R
import io.github.mzdluo123.mirai.android.databinding.FragmentAboutBinding

class AboutFragment : Fragment() {
    private lateinit var aboutBinding: FragmentAboutBinding
    private var click = 0
    private lateinit var githubBtn: Button
    private lateinit var github2Btn: Button
    private lateinit var btnVisitForum: Button
    private lateinit var btnJoinGroup: Button
    private lateinit var imageView2: ImageView
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        aboutBinding = DataBindingUtil.inflate(
            layoutInflater,
            R.layout.fragment_about,
            container,
            false
        )
        @Suppress("DEPRECATION")
        aboutBinding.appVersion = requireContext().packageManager.getPackageInfo(
            requireContext().packageName,
            0
        ).versionName
        val root = aboutBinding.root

        githubBtn = root.findViewById(R.id.github_btn)
        github2Btn = root.findViewById(R.id.github2_btn)
        btnVisitForum = root.findViewById(R.id.btn_visit_forum)
        btnJoinGroup = root.findViewById(R.id.btn_join_group)
        imageView2 = root.findViewById(R.id.imageView2)

        @Suppress("UNRESOLVED_REFERENCE")
        aboutBinding.coreVersion = io.github.mzdluo123.mirai.android.BuildConfig.COREVERSION
        @Suppress("UNRESOLVED_REFERENCE")
        aboutBinding.consoleVersion = io.github.mzdluo123.mirai.android.BuildConfig.CONSOLEVERSION
        return root
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        githubBtn.setOnClickListener {
            openUrl("https://github.com/mamoe/mirai")
        }
        github2Btn.setOnClickListener {
            openUrl("https://github.com/MrXiaoM/MiraiAndroid")
        }
        btnVisitForum.setOnClickListener {
            openUrl("https://mirai.mamoe.net/")
        }
        imageView2.setOnClickListener {
            if (click < 4) {
                click++
                return@setOnClickListener
            }
            imageView2.setImageResource(R.drawable.avatar)
        }
        btnJoinGroup.setOnClickListener {
            if (!joinQQGroup("dHC-0eWzcLSivNoevCngF4E4UYXcBgbS")) {
                Toast.makeText(it.context, "拉起QQ失败，请确认你是否安装了QQ", Toast.LENGTH_SHORT).show()
            }
        }
    }


    private fun openUrl(url: String) {
        val uri = Uri.parse(url)
        startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    /****************
     *
     * 发起添加群流程。群号：Mirai&amp;nbsp;Developers&amp;nbsp;猫猫开发(1047497524) 的 key 为： dHC-0eWzcLSivNoevCngF4E4UYXcBgbS
     * 调用 joinQQGroup(dHC-0eWzcLSivNoevCngF4E4UYXcBgbS) 即可发起手Q客户端申请加群 Mirai&amp;nbsp;Developers&amp;nbsp;猫猫开发(1047497524)
     *
     * @param key 由官网生成的key
     * @return 返回true表示呼起手Q成功，返回false表示呼起失败
     */
    fun joinQQGroup(key: String): Boolean {
        val intent = Intent()
        intent.data =
            Uri.parse("mqqopensdkapi://bizAgent/qm/qr?url=http%3A%2F%2Fqm.qq.com%2Fcgi-bin%2Fqm%2Fqr%3Ffrom%3Dapp%26p%3Dandroid%26jump_from%3Dwebapi%26k%3D$key")
        // 此Flag可根据具体产品需要自定义，如设置，则在加群界面按返回，返回手Q主界面，不设置，按返回会返回到呼起产品界面    //intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(intent)
            true
        } catch (e: Exception) {
            // 未安装手Q或安装的版本不支持
            false
        }
    }


}
