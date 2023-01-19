package fi.gfizzer.kaheb.network

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*

const val EVENT_ID_PREFIX = "https://kide.app/events/"
const val AUTH_URL = "https://api.kide.app/api/authentication/user"
const val GET_URL = "https://api.kide.app/api/products/"
const val POST_URL = "https://api.kide.app/api/reservations"
const val REQUEST_TIMEOUT = 30  // Timeout parameter for all aiohttp requests, seconds
const val GET_REQUEST_DELAY = 0.05  // How often a new GET request for ticket data should be sent, seconds.
// NOTE! A delay too small may cause you to be flagged as an attacker (and the server probably can't keep up)

class KideHandler {
    private val client = HttpClient()

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

        val eventId = url.substring(prefixIndex + EVENT_ID_PREFIX.length)
        val response = client.get("$GET_URL/$eventId")
        val jelement = JsonParser.parseString(response.bodyAsText())

        return if (jelement is JsonObject) {
            jelement.asJsonObject
        } else {
            null
        }
    }

    fun closeClient() {
        client.close()
    }
}