package com.bilal.meowbot

import android.content.Context

/** Ayarlar (telefonda kalıcı saklanır). */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("meowbot", Context.MODE_PRIVATE)

    /** double | twice | single | long */
    var tapMode: String
        get() = sp.getString("tapMode", "double")!!
        set(v) = sp.edit().putString("tapMode", v).apply()

    /** İki kedi arası bekleme (ms) */
    var moveDelayMs: Long
        get() = sp.getLong("moveDelayMs", 150)
        set(v) = sp.edit().putLong("moveDelayMs", v).apply()

    /** Son kediden sonra kazanma ekranı için bekleme (ms) */
    var afterSolveMs: Long
        get() = sp.getLong("afterSolveMs", 2500)
        set(v) = sp.edit().putLong("afterSolveMs", v).apply()

    /** "Sonraki" butonunun ekran konumu (-1 = ayarlanmadı) */
    var nextX: Int
        get() = sp.getInt("nextX", -1)
        set(v) = sp.edit().putInt("nextX", v).apply()
    var nextY: Int
        get() = sp.getInt("nextY", -1)
        set(v) = sp.edit().putInt("nextY", v).apply()

    val hasNext get() = nextX >= 0 && nextY >= 0

    /** Panelin ekrandaki yeri */
    var panelX: Int
        get() = sp.getInt("panelX", 0)
        set(v) = sp.edit().putInt("panelX", v).apply()
    var panelY: Int
        get() = sp.getInt("panelY", 400)
        set(v) = sp.edit().putInt("panelY", v).apply()
}
