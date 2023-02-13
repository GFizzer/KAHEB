package fi.gfizzer.kaheb.network

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import fi.gfizzer.kaheb.utility.getTicketInventoryId
import fi.gfizzer.kaheb.utility.getTicketMaxReservableQty
import fi.gfizzer.kaheb.utility.getTicketName
import fi.gfizzer.kaheb.utility.getTicketVariants
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.gson.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

const val EVENT_ID_PREFIX = "https://kide.app/events/"
const val AUTH_URL = "https://api.kide.app/api/authentication/user"
const val GET_URL = "https://api.kide.app/api/products/"
const val POST_URL = "https://api.kide.app/api/reservations"
const val REQUEST_TIMEOUT = 90000L
const val GET_REQUEST_DELAY = 50L // How often a new GET request for ticket data should be sent, milliseconds.
// NOTE! A delay too small may cause you to be flagged as an attacker (and the server probably can't keep up)

class KideHandler {
    private val tickets = AtomicReference<JsonArray>(null)
    private val ticketsAvailable = AtomicBoolean(false)
    private var ticketsReserved = AtomicBoolean(false)

    private val client = HttpClient {
        install(ContentNegotiation) {
            gson()
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 10000
            requestTimeoutMillis = 10000
        }
    }

    suspend fun validateUser(tag: String?): Boolean {
        val response = client.get(AUTH_URL) {
            headers {
                append(HttpHeaders.Accept, "*/*")
                append(HttpHeaders.AcceptLanguage, "*")
                append(HttpHeaders.AcceptEncoding, "gzip")
                append(HttpHeaders.ContentType, "application/json;charset=utf-8")
                append(HttpHeaders.Connection, "keep-alive")
                append(HttpHeaders.TE, "trailers")
                append(HttpHeaders.Authorization, tag.toString())
            }
        }
        return response.status.value == 200
    }

    suspend fun validateEventUrl(url: String): JsonObject? {
        val prefixIndex = url.indexOf(EVENT_ID_PREFIX)
        if (prefixIndex == -1) {
            return null
        }

        val id = url.substring(prefixIndex + EVENT_ID_PREFIX.length)
        val response = client.get("$GET_URL/$id")
        val jelement = JsonParser.parseString(response.bodyAsText())

        return if (jelement is JsonObject) jelement else null
    }

    private suspend fun ticketsGetRequest(id: String) {
        val response = client.get("$GET_URL/$id")
        val jelement = JsonParser.parseString(response.bodyAsText())

        if (jelement == null || jelement !is JsonObject) {
            return
        }

        val variants = getTicketVariants(jelement)

        if (variants != null && variants.size() > 0) {
            tickets.set(variants)
            ticketsAvailable.set(true)
        }
    }

    private suspend fun getEventTicketVariants(id: String) = coroutineScope {
        val jobs = mutableListOf<Job>()
        val start = System.currentTimeMillis()

        while (!ticketsAvailable.get() && (System.currentTimeMillis() - start) < REQUEST_TIMEOUT) {
            delay(GET_REQUEST_DELAY)
            val job = launch { ticketsGetRequest(id) }
            jobs.add(job)
        }

        jobs.forEach { job -> job.cancel() }
    }

    private suspend fun ticketPostRequest(authTag: String, iid: String, qty: Int) {
        val response = client.post(POST_URL) {
            headers {
                append(HttpHeaders.Accept, "*/*")
                append(HttpHeaders.AcceptLanguage, "*")
                append(HttpHeaders.AcceptEncoding, "gzip")
                append(HttpHeaders.ContentType, "application/json;charset=utf-8")
                append(HttpHeaders.Connection, "keep-alive")
                append(HttpHeaders.TE, "trailers")
                append(HttpHeaders.Authorization, authTag)
            }
            setBody(JsonParser.parseString(
                "{'toCreate':[{'inventoryId': $iid, 'quantity': $qty}]}")
                .asJsonObject)
        }

        if (response.status.value == 200) {
            ticketsReserved.set(true)
        }
    }

    private suspend fun reserveAllTickets(authTag: String, searchTag: String?, buyAllTickets: Boolean=false) {
        coroutineScope {
            val jobs = mutableListOf<Job>()

            for (t in tickets.get().asJsonArray) {
                val tic = t.asJsonObject ?: continue
                val name = getTicketName(tic)
                    ?.lowercase()

                if ((searchTag == null) || name!!.contains(searchTag)) {
                    val iid = getTicketInventoryId(tic)
                    val qty = if (searchTag != null || buyAllTickets) getTicketMaxReservableQty(tic)
                        else 1
                    val job = launch { ticketPostRequest(authTag, iid!!, qty!!) }
                    jobs.add(job)
                }
            }

            jobs.joinAll()
            if (searchTag != null) {
                reserveAllTickets(authTag, null)
            }
            else if (!buyAllTickets) {
                reserveAllTickets(authTag, null, true)
            }
        }
    }

    suspend fun runTicketProcess(authTag: String, id: String, searchTag: String?): Boolean {
        val job = CoroutineScope(Dispatchers.IO).launch {
            getEventTicketVariants(id)

            if (!ticketsAvailable.get()) {
                return@launch
            }

            reserveAllTickets(authTag, searchTag)
        }

        job.join()
        val success = ticketsReserved.get()
        resetClient()

        return success
    }

    private fun resetClient() {
        tickets.set(null)
        ticketsAvailable.set(false)
        ticketsReserved.set(false)
    }

    fun closeClient() {
        client.close()
    }
}