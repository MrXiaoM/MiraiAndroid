package io.github.mzdluo123.mirai.android.activity

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.mzdluo123.mirai.android.R
import io.github.mzdluo123.mirai.android.miraiconsole.AndroidLoginSolver
import io.github.mzdluo123.mirai.android.service.BotService
import io.github.mzdluo123.mirai.android.service.ServiceConnector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CaptchaActivity : AppCompatActivity() {
    private lateinit var conn: ServiceConnector
    private lateinit var captchaView: ImageView
    private lateinit var captchaConfirmBtn: Button
    private lateinit var captchaInput: EditText
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_captcha)
        captchaView = findViewById(R.id.captcha_view)
        captchaConfirmBtn = findViewById(R.id.captchaConfirm_btn)
        captchaInput = findViewById(R.id.captcha_input)

        conn = ServiceConnector(this)
        lifecycle.addObserver(conn)
        conn.connectStatus.observe(this) {
            if (it) {
                val data = conn.botService.captcha
                lifecycleScope.launch(Dispatchers.Main) {
                    val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
                    captchaView.setImageBitmap(bitmap)
                }
            }
        }
    }


    override fun onStart() {
        super.onStart()
        val intent = Intent(baseContext, BotService::class.java)
        bindService(intent, conn, Context.BIND_AUTO_CREATE)
        captchaConfirmBtn.setOnClickListener {
            conn.botService.submitVerificationResult(captchaInput.text.toString())
            // 删除通知
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(AndroidLoginSolver.CAPTCHA_NOTIFICATION_ID)
            finish()
        }
    }


}