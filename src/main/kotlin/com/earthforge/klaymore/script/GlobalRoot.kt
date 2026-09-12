package com.earthforge.klaymore.script

import java.io.File

/** 全局脚本根管理器。服务端根和客户端根独立挂载、独立 target。 */
object GlobalRoot {

  // ---- 服务端根 ----
  const val ROOT_SCRIPT_NAME = "Root.kt"
  const val SERVER_ROOT_SUBDIR = "server"
  const val ROOT_BINDING_KEY = "dummy:root"
  private val rootTarget = Dummy("root")

  @Volatile private var _rootContainer: ScriptContainer? = null

  // ---- 客户端根 ----
  const val CLIENT_ROOT_SUBDIR = "client"
  const val CLIENT_ROOT_BINDING_KEY = "dummy:client_root"
  private val clientRootTarget = Dummy("client_root")

  @Volatile private var _clientRootContainer: ScriptContainer? = null

  @JvmStatic
  val container: ScriptContainer?
    get() = _rootContainer

  @JvmStatic
  val target: Dummy
    get() = rootTarget

  @JvmStatic fun getInstance(): Any? = _rootContainer?.getScriptInstance()

  @JvmStatic fun isLoaded(): Boolean = _rootContainer != null

  /** 解析服务端 Root.kt 文件路径，优先 server/ 子目录 */
  @JvmStatic
  fun resolveServerRootFile(): File? {
    val scriptDir = PersistenceStorage.getScriptDirectory() ?: return null
    val serverDir = File(scriptDir, SERVER_ROOT_SUBDIR)
    val inServer = File(serverDir, ROOT_SCRIPT_NAME)
    if (inServer.isFile) return inServer
    val legacy = File(scriptDir, ROOT_SCRIPT_NAME)
    if (legacy.isFile) return legacy
    return inServer
  }

  @JvmStatic
  fun mountIfPresent(): Boolean {
    val rootFile = resolveServerRootFile()
    if (rootFile == null || !rootFile.exists() || !rootFile.isFile) {
      println("[Klaymore GlobalRoot] $ROOT_SCRIPT_NAME not found, skipping server root mount.")
      return false
    }
    val initData = PersistenceStorage.getCachedBindingData(ROOT_BINDING_KEY)
    val initial = if (initData.isEmpty()) null else initData
    return mountSync(rootFile, initial)
  }

  @JvmStatic fun mountSync(rootFile: File): Boolean = mountSync(rootFile, null)

  @JvmStatic
  fun mountSync(rootFile: File, initialPersistentData: Map<String, *>?): Boolean {
    unmount()
    println(
        "[Klaymore GlobalRoot] Sync mounting server $ROOT_SCRIPT_NAME from ${rootFile.absolutePath} ...")
    val container =
        ScriptContainerFactory.createAndMount(
            ROOT_SCRIPT_NAME, rootFile, rootTarget, null, initialPersistentData, ScriptSide.SERVER)
    return if (container == null) {
      System.err.println(
          "[Klaymore GlobalRoot] FAILED to mount server $ROOT_SCRIPT_NAME (compile/instantiate error, see logs)")
      false
    } else {
      _rootContainer = container
      PersistenceStorage.markBound(ROOT_BINDING_KEY)
      println("[Klaymore GlobalRoot] Server $ROOT_SCRIPT_NAME mounted successfully.")
      true
    }
  }

  @JvmStatic
  fun mount(rootFile: File) {
    unmount()
    val initData = PersistenceStorage.getCachedBindingData(ROOT_BINDING_KEY)
    val initial = if (initData.isEmpty()) null else initData
    println(
        "[Klaymore GlobalRoot] Async mounting server $ROOT_SCRIPT_NAME from ${rootFile.absolutePath} ...")
    ScriptContainerFactory.createAndMountAsync(
        ROOT_SCRIPT_NAME, rootFile, rootTarget, null, initial, ScriptSide.SERVER) { container ->
          if (container == null) {
            System.err.println(
                "[Klaymore GlobalRoot] FAILED to mount server $ROOT_SCRIPT_NAME (compile/instantiate error, see logs)")
          } else {
            _rootContainer = container
            PersistenceStorage.markBound(ROOT_BINDING_KEY)
            println("[Klaymore GlobalRoot] Server $ROOT_SCRIPT_NAME mounted successfully.")
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
        System.err.println(
            "[Klaymore GlobalRoot] WARN: unmount old server root failed: ${t.message}")
      }
      println("[Klaymore GlobalRoot] Previous server root container unmounted.")
    }
  }

  @JvmStatic
  val clientContainer: ScriptContainer?
    get() = _clientRootContainer

  @JvmStatic
  val clientTarget: Dummy
    get() = clientRootTarget

  @JvmStatic fun getClientInstance(): Any? = _clientRootContainer?.getScriptInstance()

  @JvmStatic fun isClientLoaded(): Boolean = _clientRootContainer != null

  @JvmStatic
  fun resolveClientRootFile(): File? {
    val scriptDir = PersistenceStorage.getScriptDirectory() ?: return null
    val clientDir = File(scriptDir, CLIENT_ROOT_SUBDIR)
    return File(clientDir, ROOT_SCRIPT_NAME)
  }

  /** ClientProxy.init 阶段尽早挂载客户端根 */
  @JvmStatic
  fun mountClientIfPresent(): Boolean {
    val rootFile = resolveClientRootFile()
    if (rootFile == null || !rootFile.exists() || !rootFile.isFile) {
      println(
          "[Klaymore GlobalRoot] client/$ROOT_SCRIPT_NAME not found, skipping client root mount.")
      return false
    }
    unmountClient()
    println(
        "[Klaymore GlobalRoot] Mounting client $ROOT_SCRIPT_NAME from ${rootFile.absolutePath} ...")
    val container =
        ScriptContainerFactory.createAndMount(
            "ClientRoot.kt", rootFile, clientRootTarget, null, null, ScriptSide.CLIENT)
    return if (container == null) {
      System.err.println(
          "[Klaymore GlobalRoot] FAILED to mount client $ROOT_SCRIPT_NAME (see logs)")
      false
    } else {
      _clientRootContainer = container
      println("[Klaymore GlobalRoot] Client $ROOT_SCRIPT_NAME mounted successfully.")
      true
    }
  }

  @JvmStatic
  fun unmountClient() {
    val old = _clientRootContainer
    _clientRootContainer = null
    if (old != null) {
      try {
        ScriptContainerFactory.unmount(old)
      } catch (t: Throwable) {
        System.err.println("[Klaymore GlobalRoot] WARN: unmount client root failed: ${t.message}")
      }
      println("[Klaymore GlobalRoot] Client root container unmounted.")
    }
  }

  /** 同时卸载两端根（退出游戏/世界时调用） */
  @JvmStatic
  fun unmountAll() {
    unmount()
    unmountClient()
  }
}
