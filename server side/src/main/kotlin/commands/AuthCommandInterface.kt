package commands

import common.Response
import myio.IOManager

interface AuthCommandInterface {
    fun execute(username: String, plainPasswordStr: String, ioManager: IOManager): Response
}
