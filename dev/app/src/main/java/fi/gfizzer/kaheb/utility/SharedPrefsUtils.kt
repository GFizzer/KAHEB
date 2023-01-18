package fi.gfizzer.kaheb.utility

import android.content.Context

const val USER_DATA_PATH = "userData"
const val AUTH_TAG_PREF = "authTag"

class SharedPrefsUtils(context: Context) {
    private val sharedPrefs = context.getSharedPreferences(USER_DATA_PATH, Context.MODE_PRIVATE)

    fun getUserAuthTag(): String? {
        return sharedPrefs.getString(AUTH_TAG_PREF, null)
    }

    fun setUserAuthTag(tag: String) {
        val editor = sharedPrefs.edit()
        editor.putString(AUTH_TAG_PREF, tag)
        editor.apply()
    }
}