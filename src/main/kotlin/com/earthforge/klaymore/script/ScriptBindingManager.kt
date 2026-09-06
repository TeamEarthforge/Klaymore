package com.earthforge.klaymore.script

object ScriptBindingManager {
  private val containers = mutableListOf<ScriptContainer>()

  @JvmStatic
  fun register(container: ScriptContainer) {
    if (container !in containers) {
      containers.add(container)
    }
  }

  @JvmStatic
  fun unregister(container: ScriptContainer) {
    containers.remove(container)
  }

  @JvmStatic fun getContainers(): List<ScriptContainer> = containers.toList()

  @JvmStatic
  fun findByScriptName(scriptName: String): List<ScriptContainer> =
      containers.filter { it.scriptName == scriptName }

  @JvmStatic
  fun findByTarget(target: Any): List<ScriptContainer> =
      containers.filter { it.getTarget() === target }

  @JvmStatic
  fun clearAll() {
    containers.clear()
  }
}
