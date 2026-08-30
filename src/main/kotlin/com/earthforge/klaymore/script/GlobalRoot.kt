package com.earthforge.klaymore.script

import java.io.File

object GlobalRoot {
  const val ROOT_SCRIPT_NAME = "Root.kts"
  private val rootTarget = Dummy("root")

  @Volatile
  private var _rootContainer: ScriptContainer? = null

  @JvmStatic
  val container: ScriptContainer?
    get() = _rootContainer

  @JvmStatic
  val target: Dummy
    get() = rootTarget

  @JvmStatic
  fun getInstance(): Any? = _rootContainer?.getScriptInstance()

  @JvmStatic
  fun isLoaded(): Boolean = _rootContainer != null

  @JvmStatic
  fun mountIfPresent(): Boolean {
    val scriptDir = PersistenceStorage.getScriptDirectory() ?: return false
    val rootFile = File(scriptDir, ROOT_SCRIPT_NAME)
    if (!rootFile.exists() || !rootFile.isFile) {
      println("[Klaymore GlobalRoot] $ROOT_SCRIPT_NAME not found in ${scriptDir.absolutePath}, skipping global root mount.")
      return false
    }
    return mountSync(rootFile)
  }

  @JvmStatic
  fun mountSync(rootFile: File): Boolean {
    unmount()
    println("[Klaymore GlobalRoot] Sync mounting $ROOT_SCRIPT_NAME from ${rootFile.absolutePath} ...")
    val container = ScriptContainerFactory.createAndMount(
        ROOT_SCRIPT_NAME,
        rootFile,
        rootTarget,
        null,
        null
    )
    return if (container == null) {
      System.err.println("[Klaymore GlobalRoot] FAILED to mount $ROOT_SCRIPT_NAME (compile/instantiate error, see logs)")
      false
    } else {
      _rootContainer = container
      println("[Klaymore GlobalRoot] $ROOT_SCRIPT_NAME mounted successfully. All scripts can now access it via container.root or bindRoot()")
      true
    }
  }

  @JvmStatic
  fun mount(rootFile: File) {
    unmount()
    println("[Klaymore GlobalRoot] Async mounting $ROOT_SCRIPT_NAME from ${rootFile.absolutePath} ...")
    ScriptContainerFactory.createAndMountAsync(
        ROOT_SCRIPT_NAME,
        rootFile,
        rootTarget,
        null,
        null
    ) { container ->
      if (container == null) {
        System.err.println("[Klaymore GlobalRoot] FAILED to mount $ROOT_SCRIPT_NAME (compile/instantiate error, see logs)")
      } else {
        _rootContainer = container
        println("[Klaymore GlobalRoot] $ROOT_SCRIPT_NAME mounted successfully. All scripts can now access it via container.root or bindRoot()")
      }
    }
  }

  @JvmStatic
  fun unmount() {
    val old = _rootContainer
    _rootContainer = null
    if (old != null) {
      try {
        ScriptContainerFactory.unmount(old)
      } catch (t: Throwable) {
        System.err.println("[Klaymore GlobalRoot] WARN: unmount old root failed: ${t.message}")
      }
      println("[Klaymore GlobalRoot] Previous root container unmounted.")
    }
  }
}
