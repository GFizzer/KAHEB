package fi.gfizzer.kaheb

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.TextView
import com.google.gson.internal.bind.util.ISO8601Utils
import fi.gfizzer.kaheb.network.KideHandler
import fi.gfizzer.kaheb.utility.SharedPrefsHandler
import kotlinx.coroutines.*
import java.text.DateFormat.*
import java.text.ParsePosition

class MainActivity : AppCompatActivity() {
    private val kideHandler = KideHandler()
    private val sharedPrefsHandler = SharedPrefsHandler()

    private var userAuthTag: String? = null
    private var eventId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sharedPrefsHandler.setContext(this)
        userAuthTag = sharedPrefsHandler.getUserAuthTag()

        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            val validationResult = async { kideHandler.validateUser(userAuthTag) }
            initUI(validationResult)
        }
    }

    override fun onDestroy() {
        kideHandler.closeClient()
        super.onDestroy()
    }

    private fun getUiElement(id: Int): TextView {
        return findViewById(id)
    }

    private suspend fun initUI(validationResult: Deferred<Boolean>) {
        val updateAuthTagButton = getUiElement(R.id.updateAuthTagButton)
        updateAuthTagButton.setOnClickListener {
            val newAuthTag = getUiElement(R.id.updateAuthTagField).text.toString()
            CoroutineScope(Dispatchers.IO).launch { updateAuthTag(newAuthTag) }
        }

        val checkEventUrlButton = getUiElement(R.id.checkEventUrlButton)
        checkEventUrlButton.setOnClickListener {
            val url = getUiElement(R.id.eventUrlField).text.toString()
            CoroutineScope(Dispatchers.IO).launch { checkEventUrl(url) }
        }

        val authValidationResult = getUiElement(R.id.authValidationResult)
        authValidationResult.text = if (validationResult.await()) "Validated" else "Failed validation"
    }

    private suspend fun updateAuthTag(tag: String) {
        userAuthTag = tag
        sharedPrefsHandler.setUserAuthTag(tag)
        getUiElement(R.id.authValidationResult).text = "..."

        withContext(Dispatchers.IO) {
            val validationResult = async { kideHandler.validateUser(tag) }
            getUiElement(R.id.authValidationResult).text = if (validationResult.await()) "Validated" else "Failed validation"
            getUiElement(R.id.updateAuthTagField).text = ""
        }
    }

    private suspend fun checkEventUrl(url: String) {
        val eventCheckResult =  getUiElement(R.id.eventCheckResult)
        val eventNameDisplay = getUiElement(R.id.eventNameDisplay)
        val salesStartDisplay = getUiElement(R.id.salesStartDisplay)

        // Cannot edit UI elements outside Main thread
        withContext(Dispatchers.Main) {
            eventCheckResult.text = "..."
            eventNameDisplay.text = ""
            salesStartDisplay.text = ""
        }

        withContext(Dispatchers.IO) {
            val eventJob = async { kideHandler.validateEventUrl(url) }
            val event = eventJob.await()
            eventCheckResult.text = if (event != null) "Event found!" else "Event not found"
            getUiElement(R.id.eventUrlField).text = ""

            eventId = event?.get("model")?.
                asJsonObject?.get("product")?.
                asJsonObject?.get("id")?.asString

            if (event == null) {
                return@withContext
            }

            // Cannot edit UI elements outside Main thread
            withContext(Dispatchers.Main) {
                eventNameDisplay.text = event.get("model")?.
                    asJsonObject?.get("product")?.
                    asJsonObject?.get("name")?.asString

                val salesStartIso = event.get("model")?.
                    asJsonObject?.get("product")?.
                    asJsonObject?.get("dateSalesFrom")?.asString
                val salesStart = ISO8601Utils.parse(salesStartIso, ParsePosition(0))
                salesStartDisplay.text = getDateTimeInstance().format(salesStart)
            }
        }
    }
}