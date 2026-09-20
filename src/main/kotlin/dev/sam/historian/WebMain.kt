package dev.sam.historian

import com.google.adk.kt.webserver.AdkServerConfig
import com.google.adk.kt.webserver.dev.AdkDevServer

fun main() {
    println("ADK dev UI on http://localhost:8080")
    AdkDevServer(AdkServerConfig.inMemory(HistorianAgent.rootAgent)).start(wait = true)
}
