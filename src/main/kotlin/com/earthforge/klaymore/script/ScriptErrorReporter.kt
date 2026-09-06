package com.earthforge.klaymore.script

object ScriptErrorReporter {
  private val errors = mutableListOf<String>()

  fun report(message: String) {
    errors.add(message)
    println("[Klaymore Script] $message")
  }

  @JvmStatic fun reportStatic(message: String) = report(message)

  fun getLastErrors(): List<String> = errors.toList()

  @JvmStatic
  fun getLastError(): String? {
    return errors.lastOrNull()
  }

  fun clearErrors() = errors.clear()
}
