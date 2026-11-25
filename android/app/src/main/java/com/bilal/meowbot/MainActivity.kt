package com.bilal.meowbot

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnAppInfo).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }

        val tapIds = mapOf(
            "double" to R.id.tapDouble, "twice" to R.id.tapTwice,
            "single" to R.id.tapSingle, "long" to R.id.tapLong,
        )
        val tapGroup = findViewById<RadioGroup>(R.id.tapMode)
        tapGroup.check(tapIds[prefs.tapMode] ?: R.id.tapDouble)
        tapGroup.setOnCheckedChangeListener { _, id ->
            prefs.tapMode = tapIds.entries.first { it.value == id }.key
        }

        // hız: iki kedi arası bekleme (ms)
        val speedIds = mapOf(R.id.speedFast to 60L, R.id.speedNormal to 150L, R.id.speedSlow to 400L)
        val speedGroup = findViewById<RadioGroup>(R.id.speed)
        speedGroup.check(speedIds.entries.minByOrNull { kotlin.math.abs(it.value - prefs.moveDelayMs) }!!.key)
        speedGroup.setOnCheckedChangeListener { _, id -> prefs.moveDelayMs = speedIds.getValue(id) }

        findViewById<TextView>(R.id.help).text = """
            Nasıl kullanılır?

            1) Yukarıdaki butonla Erişilebilirlik ayarlarını aç → "Yüklü uygulamalar" → MeowBot → Aç.
               (Samsung'da gri/kısıtlı görünürse: "Uygulama bilgisi" → sağ üstteki ⋮ → "Kısıtlı ayarlara izin ver", sonra tekrar dene.)

            2) Ekranın kenarında 🐱 🔁 🎯 paneli çıkar. Panelden tutup sürükleyerek yerini değiştirebilirsin.

            3) Meowdoku'yu aç, bir seviyeye gir ve 🐱'a dokun → bulmacayı çözer, kedileri yerleştirir.

            Kendi kendine oynasın:
            • Bir seviyeyi bitir, "Sonraki/Devam" butonu görününce 🎯'a dokun.
            • Çıkan kırmızı hedefi o butonun üstüne sürükle ve 🎯'a tekrar dokun (kaydedilir).
            • Yeni seviyede 🔁'a dokun → çözer, sonrakine geçer, tekrar çözer... Durdurmak için ⏹.

            Kedi yerine ✗ çıkıyorsa yukarıdan "İki ayrı dokunuş"u seç.

            Tahta yanlış okunursa görüntü Galeri → Pictures/MeowBot klasörüne kaydedilir.
            🐱'a basılı tutarak da istediğin an tespit görüntüsünü kaydedebilirsin.
        """.trimIndent()
    }

    override fun onResume() {
        super.onResume()
        val status = findViewById<TextView>(R.id.status)
        if (BotService.instance != null) {
            status.text = "✅ MeowBot açık. Meowdoku'yu açıp 🐱'a dokunabilirsin."
            status.setBackgroundColor(Color.rgb(200, 240, 200))
            status.setTextColor(Color.rgb(20, 70, 20))
        } else {
            status.text = "⚠️ MeowBot kapalı. Aşağıdaki butondan Erişilebilirlik ayarlarında aç."
            status.setBackgroundColor(Color.rgb(255, 225, 190))
            status.setTextColor(Color.rgb(100, 50, 0))
        }
    }
}
