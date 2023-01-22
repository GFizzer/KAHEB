package fi.gfizzer.kaheb

import android.graphics.Color
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import fi.gfizzer.kaheb.network.KideHandler
import fi.gfizzer.kaheb.utility.*
import kotlinx.coroutines.*
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/*
Kide.app Async HTTP Event Bot (KAHEB) - Android
@author: Vertti Nuotio
@version: 1.1.0A
*/

const val START_BUFFER = 2000L

class MainActivity : AppCompatActivity() {
    private val kideHandler = KideHandler()
    private val sharedPrefsHandler = SharedPrefsHandler()

    private var userAuthTag: String? = null
    private var eventId: String? = null
    private var searchTag: String? = null
    private var salesStart: ZonedDateTime? = null

    private var userAuthTagValid = false
    private var eventIdValid = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sharedPrefsHandler.setContext(this)
        userAuthTag = sharedPrefsHandler.getUserAuthTag()

        CoroutineScope(Dispatchers.IO).launch {
            val validationResult = async { kideHandler.validateUser(userAuthTag) }
            withContext(Dispatchers.Main) { initUI(validationResult) }
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
        // User auth tag update
        val updateAuthTagButton = getUiElement(R.id.updateAuthTagButton)
        updateAuthTagButton.setOnClickListener {
            val newAuthTag = getUiElement(R.id.updateAuthTagField).text.toString()
            updateAuthTag(newAuthTag)
        }

        // Event URL check
        val checkEventUrlButton = getUiElement(R.id.checkEventUrlButton)
        checkEventUrlButton.setOnClickListener {
            val url = getUiElement(R.id.eventUrlField).text.toString()
            checkEventUrl(url)
        }

        // Custom search tag set
        val searchTagButton = getUiElement(R.id.searchTagButton)
        searchTagButton.setOnClickListener {
            val tag = getUiElement(R.id.searchTagField).text
                .toString()
                .trim()
                .lowercase()
            setSearchTag(tag)
        }

        // Start
        val startButton = getUiElement(R.id.startButton)
        startButton.setOnClickListener {
            CoroutineScope(Dispatchers.Main).launch { startBotting() }
        }

        // Update auth when loaded from SharedPrefs
        val authValidationResult = getUiElement(R.id.authValidationResult)
        if (validationResult.await()) {
            authValidationResult.text = "Validated"
            userAuthTagValid = true
        } else {
            authValidationResult.text = "Failed validation"
            userAuthTagValid = false
        }
    }

    private fun updateAuthTag(tag: String) {
        val updateAuthTagField = getUiElement(R.id.updateAuthTagField) as EditText
        updateAuthTagField.text.clear()

        CoroutineScope(Dispatchers.IO).launch {
            val validationResult = async { kideHandler.validateUser(tag) }
            if (validationResult.await()) {
                getUiElement(R.id.authValidationResult).text = "Validated"
                sharedPrefsHandler.setUserAuthTag(tag)
                userAuthTag = tag
                userAuthTagValid = true
                CoroutineScope(Dispatchers.Main).launch { newAuthTagValidAlert() }
            } else {
                CoroutineScope(Dispatchers.Main).launch { newAuthTagInvalidAlert() }
            }
        }
    }

    private fun newAuthTagValidAlert() {
        AlertDialog.Builder(this)
            .setTitle("New auth tag saved")
            .setMessage("Your new authorization tag has been successfully saved.")
            .setNeutralButton("OK", null)
            .show()
    }

    private fun newAuthTagInvalidAlert() {
        AlertDialog.Builder(this)
            .setTitle("Tag validation failed")
            .setMessage("The tag you tried to enter was not valid. Your tag has not been changed.")
            .setNeutralButton("OK", null)
            .show()
    }

    private fun checkEventUrl(url: String) {
        eventIdValid = false
        val eventCheckResult =  getUiElement(R.id.eventCheckResult)
        val eventNameDisplay = getUiElement(R.id.eventNameDisplay)
        val salesStartDisplay = getUiElement(R.id.salesStartDisplay)

        eventCheckResult.text = "..."
        eventNameDisplay.text = ""
        salesStartDisplay.text = ""

        CoroutineScope(Dispatchers.IO).launch {
            val eventJob = async { kideHandler.validateEventUrl(url) }

            val eventUrlField = getUiElement(R.id.eventUrlField) as EditText
            eventUrlField.text.clear()

            val eventInfo = eventJob.await()

            if (eventInfo == null) {
                eventCheckResult.text = "Event URL invalid!"
                setTextDelayed(R.id.eventCheckResult, "---", 5000L)
                return@launch
            } else {
                eventCheckResult.text = "Event found!"
                salesStart = getEventSalesStart(eventInfo)
                eventId = getEventId(eventInfo)
                eventIdValid = true
            }

            // Cannot edit UI elements outside Main thread
            withContext(Dispatchers.Main) {
                eventNameDisplay.text = getEventName(eventInfo)
                salesStartDisplay.text = getEventSalesStartString(eventInfo)
            }
        }
    }

    private fun setSearchTag(tag: String) {
        val searchTagInfo = getUiElement(R.id.searchTagInfo)
        if (tag == "") {
            searchTag = null
            searchTagInfo.text = "Tag deleted"
            CoroutineScope(Dispatchers.Default).launch {
                setTextDelayed(R.id.searchTagInfo, "", 5000L)
            }
        } else {
            searchTag = tag
            searchTagInfo.text = "Tag set"
        }
    }

    private suspend fun startBotting() {
        val progressStatusInfo = getUiElement(R.id.progressStatusInfo)
        progressStatusInfo.text = ""

        if (!userAuthTagValid || !eventIdValid) {
            progressStatusInfo.setTextColor(Color.RED)
            progressStatusInfo.text = "Invalid user auth tag or event id!"
            CoroutineScope(Dispatchers.Default).launch {
                setTextDelayed(progressStatusInfo.id, "", 5000L)
            }
            return
        }
        switchAllButtons(false)
        progressStatusInfo.setTextColor(Color.BLACK)
        progressStatusInfo.text = "Waiting until sales begin..."

        val startDelay = getStartDelay()
        delay(startDelay)

        progressStatusInfo.text = "Processing..."

        val success = kideHandler.runTicketProcess(userAuthTag!!, eventId!!, searchTag)
        if (!success) {
            progressStatusInfo.setTextColor(Color.RED)
            progressStatusInfo.text = "Process timed out or reached error"
            setTextDelayed(R.id.progressStatusInfo, "", 5000L)
        } else {
            progressStatusInfo.text = "Success!"
        }

        switchAllButtons(true)
    }

    private fun switchAllButtons(state: Boolean) {
        getUiElement(R.id.updateAuthTagButton).isEnabled = state
        getUiElement(R.id.checkEventUrlButton).isEnabled = state
        getUiElement(R.id.searchTagButton).isEnabled = state
        getUiElement(R.id.startButton).isEnabled = state
    }

    private suspend fun setTextDelayed(id: Int, text: String, delay: Long) {
        delay(delay)
        getUiElement(id).text = text
    }

    private fun getStartDelay(): Long {
        val now = ZonedDateTime.now(salesStart?.zone)
        val diff = ChronoUnit.MILLIS.between(now, salesStart)

        return maxOf(0, diff - START_BUFFER)
    }
}