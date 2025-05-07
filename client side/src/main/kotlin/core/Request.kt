package org.example.core

import org.example.model.Vehicle
import java.io.Serializable

@kotlinx.serialization.Serializable
data class Request(
    val body: List<String>, // по факту input
    var vehicle: Vehicle? = null, //если нужно передавать vehicle то можно например для add
    val currentCommandsList: MutableList<String>? = null, //для поддержки релевантного списка команд
) : Serializable
