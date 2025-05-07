package org.example.core

import org.example.model.Vehicle
import java.io.Serializable

@kotlinx.serialization.Serializable
data class Request(
    val body: List<String>,
    var vehicle: Vehicle? = null,
    val currentCommandsList: MutableList<String>? = null,
) : Serializable
