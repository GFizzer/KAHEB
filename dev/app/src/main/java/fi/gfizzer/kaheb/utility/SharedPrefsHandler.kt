package fi.gfizzer.kaheb.utility

import android.content.Context
import android.content.SharedPreferences

/*
Kide.app Async HTTP Event Bot (KAHEB) - Android
@author: Vertti Nuotio
@version: 1.0.0A
*/

const val USER_DATA_PATH = "userData"
const val AUTH_TAG_PREF = "authTag"

class SharedPrefsHandler {
    private var sharedPrefs: SharedPreferences? = null

    fun setContext(context: Context) {
        sharedPrefs = context.getSharedPreferences(USER_DATA_PATH, Context.MODE_PRIVATE)
    }

    fun getUserAuthTag(): String? {
        return sharedPrefs!!.getString(AUTH_TAG_PREF, null)
    }

    fun setUserAuthTag(tag: String?) {
        val editor = sharedPrefs!!.edit()
        editor.putString(AUTH_TAG_PREF, tag)
        editor.apply()
    }
}