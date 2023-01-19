package fi.gfizzer.kaheb

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import fi.gfizzer.kaheb.network.KideHandler
import fi.gfizzer.kaheb.utility.SharedPrefsHandler
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {
    private val kideHandler = KideHandler()
    private val sharedPrefsHandler = SharedPrefsHandler()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sharedPrefsHandler.setContext(this)
        val userAuthTag: String? = sharedPrefsHandler.getUserAuthTag()

        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            val validationResult = async { kideHandler.validateUser(userAuthTag) }
            initUI(validationResult)
        }
    }

    private suspend fun initUI(validationResult: Deferred<Boolean>) {
        val authValidationResult = findViewById<TextView>(R.id.authValidationResult)
        val updateAuthTagField = findViewById<EditText>(R.id.updateAuthTagField)
        val updateAuthTagButton = findViewById<Button>(R.id.updateAuthTagButton)

        authValidationResult.text = if (validationResult.await()) "Validated" else "Failed validation"

        updateAuthTagButton.setOnClickListener {
            val newAuthTag = updateAuthTagField.text.toString()
            CoroutineScope(Dispatchers.IO).launch { updateAuthTag(newAuthTag, authValidationResult) }
        }
    }

    private suspend fun updateAuthTag(tag: String?, validationResultView: TextView) {
        sharedPrefsHandler.setUserAuthTag(tag)
        validationResultView.text = "..."

        withContext(Dispatchers.IO) {
            val validationResult = async { kideHandler.validateUser(tag) }
            validationResultView.text = if (validationResult.await()) "Validated" else "Failed validation"
        }
    }
}