package com.snainfotech.tagscout.edge

import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.log4j.BasicConfigurator
import org.apache.log4j.Level
import org.apache.log4j.Logger
import org.llrp.ltk.generated.enumerations.GetReaderCapabilitiesRequestedData
import org.llrp.ltk.generated.messages.ERROR_MESSAGE
import org.llrp.ltk.generated.messages.GET_READER_CAPABILITIES
import org.llrp.ltk.generated.messages.GET_READER_CAPABILITIES_RESPONSE
import org.llrp.ltk.net.LLRPConnectionAttemptFailedException
import org.llrp.ltk.net.LLRPConnector
import org.llrp.ltk.net.LLRPEndpoint
import org.llrp.ltk.types.LLRPMessage

private val log = KotlinLogging.logger {}

// ── Reader connection parameters ─────────────────────────────────────
// Hardcoded for now. File 3 will move these into a config file / Firestore.
private const val READER_IP = "192.168.1.111"
private const val READER_PORT = 5084          // Standard LLRP port
private const val CONNECT_TIMEOUT_MS = 10_000L
private const val TRANSACT_TIMEOUT_MS = 10_000L

/**
 * Minimal LLRPEndpoint — the callback interface LTKJava uses for unsolicited
 * messages from the reader (tag reports, reader events, errors).
 *
 * At this stage we only ask the reader for capabilities and disconnect, so
 * nothing unsolicited should arrive. In File 3+ this becomes the entry point
 * for tag reports.
 */
private class LoggingEndpoint : LLRPEndpoint {
    override fun messageReceived(message: LLRPMessage?) {
        log.debug { "Unsolicited LLRP message from reader: ${message?.name}" }
    }

    override fun errorOccured(message: String?) {
        log.error { "LLRP error from reader: $message" }
    }
}

fun main(args: Array<String>) {
    // Silence log4j 1.x (used internally by LTKJava). Without this, log4j
    // dumps warnings about missing appenders. Routes everything to stderr at WARN.
    BasicConfigurator.configure()
    Logger.getRootLogger().level = Level.WARN

    log.info { "TagScoutEdge starting - version 0.2.0" }
    log.info { "JVM: ${System.getProperty("java.version")} on ${System.getProperty("os.name")}" }
    log.info { "Target reader: $READER_IP:$READER_PORT" }

    val endpoint = LoggingEndpoint()
    val connector = LLRPConnector(endpoint, READER_IP, READER_PORT)

    try {
        log.info { "Connecting (timeout ${CONNECT_TIMEOUT_MS}ms)..." }
        connector.connect(CONNECT_TIMEOUT_MS)
        log.info { "Connected to reader." }

        // Build and send GET_READER_CAPABILITIES with "All" requested data.
        val request = GET_READER_CAPABILITIES()
        request.requestedData =
            GetReaderCapabilitiesRequestedData(GetReaderCapabilitiesRequestedData.General_Device_Capabilities)

        log.info { "Requesting reader capabilities..." }
        val response: LLRPMessage? = connector.transact(request, TRANSACT_TIMEOUT_MS)

        when (response) {
            is GET_READER_CAPABILITIES_RESPONSE -> {
                printCapabilities(response)
                // Full XML dump at DEBUG level — invaluable for later tuning.
                log.debug { "Full capability response XML:\n${response.toXMLString()}" }
            }
            is ERROR_MESSAGE -> {
                log.error { "Reader returned ERROR_MESSAGE: ${response.toXMLString()}" }
            }
            null -> {
                log.error { "Transaction timed out after ${TRANSACT_TIMEOUT_MS}ms" }
            }
            else -> {
                log.warn { "Unexpected response type: ${response.javaClass.simpleName}" }
                log.debug { response.toXMLString() }
            }
        }
    } catch (e: LLRPConnectionAttemptFailedException) {
        log.error { "Could not connect to $READER_IP:$READER_PORT" }
        log.error { "LLRP connection attempt failed: ${e.message}" }
        log.error { "Common causes:" }
        log.error { "  - Reader is powered off" }
        log.error { "  - Wrong IP address (currently $READER_IP)" }
        log.error { "  - Reader is on a different subnet" }
        log.error { "  - Windows Firewall blocking outbound LLRP (port $READER_PORT)" }
        log.error { "  - Reader already has an LLRP client connected (only one allowed)" }
    } catch (e: Exception) {
        log.error(e) { "Unexpected error during reader operation" }
    } finally {
        try {
            connector.disconnect()
            log.info { "Disconnected cleanly." }
        } catch (e: Exception) {
            log.warn { "Error during disconnect (safe to ignore): ${e.message}" }
        }
    }

    log.info { "Done." }
}

/**
 * Prints key fields from the capability response. Each access is guarded
 * because fields are optional in the LLRP spec — the reader might not report
 * everything, and we want to log what we CAN see rather than crash on a null.
 */
private fun printCapabilities(resp: GET_READER_CAPABILITIES_RESPONSE) {
    log.info { "═══ Reader Capabilities ═══" }

    try {
        val general = resp.generalDeviceCapabilities
        if (general != null) {
            log.info { "  Device Manufacturer ID:   ${general.deviceManufacturerName?.toLong()}" }
            log.info { "  Model Number:             ${general.modelName?.toLong()}" }
            log.info { "  Firmware Version:         ${general.readerFirmwareVersion?.toString()}" }
            log.info { "  Max Antennas Supported:   ${general.maxNumberOfAntennaSupported?.toInteger()}" }
            log.info { "  Can Set Antenna Props:    ${general.canSetAntennaProperties?.toBoolean()}" }
            log.info { "  Has UTC Clock:            ${general.hasUTCClockCapability?.toBoolean()}" }

            // GPIOCapabilities has an all-caps acronym — must use explicit getter.
            val gpio = general.getGPIOCapabilities()
            if (gpio != null) {
                log.info { "  GPI Port Count:           ${gpio.numGPIs?.toInteger()}" }
                log.info { "  GPO Port Count:           ${gpio.numGPOs?.toInteger()}" }
            } else {
                log.info { "  GPIO Capabilities:        (not reported)" }
            }
        } else {
            log.warn { "  No general device capabilities reported" }
        }
    } catch (e: Exception) {
        log.warn { "  Could not read general capabilities: ${e.message}" }
    }

    try {
        val llrpCap = resp.llrpCapabilities
        if (llrpCap != null) {
            log.info { "  Max ROSpecs:              ${llrpCap.maxNumROSpecs?.toLong()}" }
            log.info { "  Max AccessSpecs:          ${llrpCap.maxNumAccessSpecs?.toLong()}" }
            log.info { "  Can Do RF Survey:         ${llrpCap.canDoRFSurvey?.toBoolean()}" }
            log.info { "  Supports Tag Inventory:   ${llrpCap.canDoTagInventoryStateAwareSingulation?.toBoolean()}" }
        }
    } catch (e: Exception) {
        log.warn { "  Could not read LLRP capabilities: ${e.message}" }
    }

    log.info { "═══════════════════════════" }
}