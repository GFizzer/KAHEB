package fi.gfizzer.kaheb.utility

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// Event data
fun getEventName(event: JsonObject): String? {
    return getEventProduct(event)
        ?.get("name")
        ?.asString
}

fun getEventSalesStart(event: JsonObject): ZonedDateTime? {
    val salesStartString = getEventProduct(event)
        ?.get("dateSalesFrom")
        ?.asString

    return ZonedDateTime.parse(salesStartString, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}

fun getEventSalesStartString(event: JsonObject): String? {
    val salesStart = getEventSalesStart(event)

    return salesStart?.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM))
        ?.toString()
}

fun getEventId(event: JsonObject): String? {
    return getEventProduct(event)
        ?.get("id")
        ?.asString
}

// Ticket data
fun getTicketName(ticket: JsonObject): String? {
    return ticket.get("name")
        ?.asString
}

fun getTicketInventoryId(ticket: JsonObject): String? {
    return ticket.get("inventoryId")
        ?.asString
}

fun getTicketMaxReservableQty(ticket: JsonObject): Int? {
    return ticket.get("productVariantMaximumReservableQuantity")
        ?.asInt
}

fun getTicketVariants(event: JsonObject): JsonArray? {
    return event.get("model")
        ?.asJsonObject
        ?.get("variants")
        ?.asJsonArray
}

// Helpers
private fun getEventProduct(event: JsonObject): JsonObject? {
    return event.get("model")
        ?.asJsonObject
        ?.get("product")
        ?.asJsonObject
}