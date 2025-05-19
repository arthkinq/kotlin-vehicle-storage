package org.example.core

import java.io.Serializable

@kotlinx.serialization.Serializable
data class Response(
    var responseText: String = "Success"
) : Serializable {
    val newCommandsList = mutableListOf<String>()
    fun updateCommands(list: List<String>) {
        newCommandsList.addAll(list)
    }
}