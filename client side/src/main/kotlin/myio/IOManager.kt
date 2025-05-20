package myio

class IOManager(
    private var input: InputManager,
    private var output: OutputManager
) {
    fun outputLine(prompt: String){
        output.write(prompt+'\n')
    }
    fun outputInline(prompt: String){
        output.write(prompt)
    }
    fun readLine():String{
        return input.readLine() ?: ""
    }
    fun outputLine(ob : Any) {
        output.write(ob.toString()+"\n")
    }
    fun outputLine(col: Collection<Any>) {
        for(c in col) {
            output.write(c.toString()+"\n")
        }
    }

    fun error(message: String) {
        output.error(message)
    }
    fun getInput(): InputManager { return input}
    fun setInput(inp: InputManager){input=inp}
    fun hasNextLine(): Boolean = input.hasInput()
}