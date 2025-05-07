package org.example.commands

import org.example.IO.IOManager
import org.example.core.CollectionManager
import org.example.core.Response
import org.example.model.Vehicle

class FilterByEnginePowerCommand :  FilterByCharacteristicCommand (
    name = "filter_by_engine_power",
    description = "Find all the elements with the specified engine power value.",
    size = 1
){
    override fun execute(
        args: List<String>,
        collectionManager: CollectionManager,
        ioManager: IOManager,
        vehicle: Vehicle?
    ): Response {
        if(!checkSizeOfArgs(args.size)) {
            return Response("Error: Args can be size ${size}.")
        }
        return super.execute(listOf("enginePower", args[0]), collectionManager, ioManager, null)


    }
}