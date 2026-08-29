package com.earthforge.klaymore.script

object ScriptErrorReporter {
    private val errors = mutableListOf<String>()

    fun report(message: String) {
        errors.add(message)
        // 在 1.7.10 下通常用 println 或 Log4j
        println("[Klaymore Script] $message")
    }

    fun getLastErrors(): List<String> = errors.toList()
    @JvmStatic
    fun getLastError(): String? {
        return errors.lastOrNull()
    }
    fun clearErrors() = errors.clear()
}
