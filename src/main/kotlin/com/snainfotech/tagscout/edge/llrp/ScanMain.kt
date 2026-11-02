package com.snainfotech.tagscout.edge.llrp

/**
 * Finds fixed RFID readers on this PC's network and lists their antennas.
 *
 *   .\gradlew.bat scan                       scan the local subnet(s)
 *   .\gradlew.bat scan --args="192.168.1.111"   probe one or more readers directly
 *
 * Read-only: nothing on the readers is changed and no inventory is started.
 * Close the vendor's sample app first — a reader allows one LLRP client at a time.
 */
fun main(args: Array<String>) {
    val hosts = args.toList().filter { it.isNotBlank() }
    val targets = hosts.ifEmpty { NetworkScanner.localCandidates() }
    println(if (hosts.isEmpty()) "Scanning ${targets.size} addresses on this PC's network for LLRP readers (port ${Llrp.PORT})..."
            else "Probing ${targets.joinToString(", ")} ...")

    var lastShown = -1
    val found = NetworkScanner.scan(targets) { checked, total ->
        val pct = checked * 100 / total
        if (hosts.isEmpty() && pct / 10 != lastShown) {
            lastShown = pct / 10
            print("\r  checked $checked of $total")
        }
    }
    if (hosts.isEmpty()) println()

    if (found.isEmpty()) {
        println("\nNo LLRP readers found.")
        println("Check: the reader is on and on this network, LLRP is enabled on it (port ${Llrp.PORT}), and no firewall blocks it.")
        return
    }
    println("\nFound ${found.size} reader(s):\n")
    for (r in found) {
        println("  ${r.host}" + (r.webConsolePort?.let { "   (web console on port $it)" } ?: "   (no web console found)"))
        val info = r.info
        if (info == null) {
            println("    Could not read details: ${r.error}\n")
            continue
        }
        println("    Maker: ${info.manufacturer}    Model ID: ${info.modelId}    Firmware: ${info.firmware}")
        if (info.readerId.isNotBlank()) println("    Reader ID / MAC: ${info.readerId}")
        println("    Antenna ports: ${info.maxAntennas}")
        if (info.antennas.isEmpty()) {
            println("    (the reader didn't report antenna status)")
        } else {
            for (a in info.antennas) {
                println("      Port ${a.port}: ${if (a.connected) "CONNECTED" else "not connected"}" +
                    (if (a.gainCentiDbi != 0) "   gain ${a.gainCentiDbi / 100.0} dBi" else ""))
            }
            println("    Connected: ${info.connectedPorts.ifEmpty { listOf("none") }.joinToString(", ")}")
        }
        println()
    }
}
