# Klaymore 脚本系统 —— 形式化设计方案 (v2.0)

## 1. 项目背景与核心痛点

本项目旨在为 **Minecraft 1.7.10 Forge** 环境提供一个基于 **Kotlin** 的现代脚本系统（WebUI + Kotlin DSL）。在开发战棋、RPG 等复杂玩法时，现有设计暴露了两个根本性的架构缺陷：

1. **脚本间硬引用困境**：Kotlin 脚本编译器限制导致不同脚本文件无法直接相互引用（无法 `import` 另一个脚本的类），强制开发者将所有逻辑写在同一文件或依赖繁琐的反射调用。
2. **逻辑拓扑难以持久化**：依赖内存指针（`parent/children` 树）构建的父子关系在服务器重启后完全丢失，且“恢复树形结构”在数学上等价于 NP-Hard 的图重建问题。
3. **实体查找效率低下**：通过遍历世界实体（`World.getEntities()`）来寻找挂载特定脚本的实体，时间复杂度为 O(N)，在战棋（大量棋子）场景下不可接受。

------

## 2. 底层数学原理与架构范式转移

为了解决上述痛点，系统必须从依赖“图结构（Graph）”降维至依赖“集合论（Set Theory）”与“关系代数（Relational Algebra）”。

### 2.1 身份即映射（Identity = Mapping）

- **摒弃内存指针**：脚本容器（`ScriptContainer`）、目标实体（`Entity`）、逻辑父级之间**绝不持有对方的 JVM 对象引用**。
- **确立 Key 体系**：所有交互通过不可变的字符串 `Key`（如 `"entity:uuid-xxx"`、`"dummy:root"`）进行。`PersistenceManager` 提供 `Key -> Object` 的纯函数映射 \( f: Key \rightarrow Instance \)。

### 2.2 拓扑即偏序集（Topology = Poset via Path）

- 将复杂的树形引用降维为**字符串路径（Path）**。
- 每个 `ScriptContainer` 拥有一个绝对路径属性（如 `"/game/red/soldier_1"`）。
- **数学性质**：路径集合构成一个**树状偏序集（Tree Poset）**。父子关系由路径前缀（`startsWith`）动态推导，而非存储在内存中。
- **优势**：路径是值对象（Value Object），天然可序列化；重启后只需读取路径字符串即可瞬态重建逻辑拓扑，无需恢复内存指针。

### 2.3 通信即事实（Communication = Facts）

- **弃用广播式事件**（仅保留 Forge 原生事件如 `TickEvent`）。
- 引入**三元组事实库（Triple FactBase）**，遵循 `(Subject, Predicate, Object)` 模型（类似 RDF 语义网）。
- System 脚本向事实库写入 `(棋子UUID, "cmd.move", "坐标")`，棋子脚本订阅特定 `Predicate` 并检查 `Subject` 是否匹配自身，实现 **O(1) 定位与解耦通信**。

------

## 3. 详细设计决策

### 3.1 路径（Path）的定义与存储

**决策**：路径由**父脚本在运行时动态生成**，通过 `initialPersistentData` 传入，并随容器的持久化数据自动落盘。

- **不写在脚本源码里**：避免硬编码导致无法复用（100 个士兵不能复制 100 份脚本）。

- **不依赖文件路径**：文件路径是“蓝图的地址”，无法代表运行时实例的坐标。

- **实现机制**：

  kotlin

  ```
  // 父脚本创建子容器时
  val initData = mapOf("klaymore.path" to "/game/red/squad_1")
  ScriptContainerFactory.createAndMountAsync(
      scriptName = "Soldier.kt",
      target = entity,
      initialPersistentData = initData
  )
  // ScriptContainer 初始化时自动提取并存储该 path
  ```

  

### 3.2 脚本编写体验（DX）优化：消灭模板方法

**问题**：用户必须手动实现 `bindTarget(target)`、`bindContainer(container)` 等约定方法，样板代码繁多。

**决策**：采用 **Kotlin 属性注入（Field Injection）**，利用 `lateinit var`。

- **框架侧**：`ScriptInjectionUtils` 不再调用方法，而是直接通过反射注入同名成员字段。

  kotlin

  ```
  // 框架内部注入逻辑
  fun injectFields(instance: Any, target: Any?, container: ScriptContainer) {
      instance::class.java.getDeclaredField("target")?.let { 
          it.isAccessible = true; it.set(instance, target) 
      }
      instance::class.java.getDeclaredField("container")?.let { 
          it.isAccessible = true; it.set(instance, container) 
      }
  }
  ```

  

- **用户侧（极致简洁）**：

  kotlin

  ```
  class SoldierScript {
      lateinit var target: Entity      // 自动注入
      lateinit var container: ScriptContainer // 自动注入
  
      @Subscribe(event = TickEvent.ServerTickEvent::class)
      fun onTick() {
          if (target.isDead) container.parent?.run("removeChild", container)
      }
  }
  ```

  

### 3.3 解决“1 个脚本文件 → N 个运行时实例”复用问题

**决策**：明确区分“蓝图（Class）”与“实例（Instance）”。

- **蓝图缓存**：`ScriptLoader` 将编译后的 `CompiledScript` 存入内存缓存（同时复用 `ScriptClassCache` 的磁盘缓存）。同一 `.kt` 文件只编译一次。

- **工厂方法**：提供 `spawnChild` API，每次调用生成**全新的 Kotlin 对象实例**（`newInstance()`），并赋予不同的 `target` 和 `path`，从而实现一个 `Soldier.kt` 蓝图生成千军万马。

  kotlin

  ```
  // 在 ScriptContainerFactory 中
  fun spawnChild(
      parentContainer: ScriptContainer,
      scriptName: String,
      target: Any,
      customPath: String
  ): ScriptContainer? {
      val compiled = ScriptLoader.loadScript(resolveFile(scriptName)) ?: return null
      // 每次调用 instantiateScript 都会生成新的 Kotlin 实例
      return finishMount(compiled, scriptName, target, parentContainer, 
                         mapOf("klaymore.path" to customPath))
  }
  ```

  

------

## 4. 系统核心组件与映射关系

| 层级       | 数学对象                          | 代码实现                                                     | 持久化策略                                               |
| :--------- | :-------------------------------- | :----------------------------------------------------------- | :------------------------------------------------------- |
| **蓝图层** | 函数/类定义                       | `Soldier.kt` 源文件                                          | 文件系统（.kt）                                          |
| **实例层** | 集合元素                          | `ScriptContainer` + `lateinit var target`                    | `PersistentData`（存储 `path`、`target_uuid`、业务状态） |
| **索引层** | 全映射 \( f: Key \to Container \) | **新增 `ContainerIndex`（ConcurrentHashMap）**               | 仅内存（重启后由 `EntityJoinWorldEvent` 触发重建）       |
| **拓扑层** | 偏序关系（路径前缀）              | `container.path` 字符串                                      | 随 `PersistentData` 存储，重启后自动重建 `Trie` 索引     |
| **通信层** | 三元组关系 \( (S, P, O) \)        | **新增 `FactBase`（`Map<Subject, Map<Predicate, Object>>`）** | JSON/NBT 序列化，彻底替代“恢复树”逻辑                    |

------

## 5. 战棋玩法落地示例（完整闭环）

### 5.1 游戏启动 / 实体加载（瞬态重建）

1. 服务器加载世界，触发 `EntityJoinWorldEvent`。
2. 框架读取该实体的持久化 NBT，获取绑定的 `scriptName`（如 `"Soldier.kt"`）和 `path`（如 `"/game/red/soldier_1"`）。
3. 调用 `spawnChild`（父容器传 `null` 或根容器），传入 `target=实体` 和 `customPath`。
4. **结果**：`ContainerIndex` 注册该实体，`path` 重建逻辑拓扑，游戏状态恢复如初，**无需遍历世界，无需恢复指针**。

### 5.2 System 指挥棋子移动（O(1) 调度）

kotlin

```
// System.kt
fun commandMove(entityUUID: String, pos: BlockPos) {
    // 1. 写入事实（不关心谁监听）
    FactBase.put(entityUUID, "cmd.move", pos)
    
    // 2. 也可直接通过索引调用（紧急情况）
    val container = ContainerIndex.get(entityUUID)
    container?.run("moveTo", pos)
}
```



### 5.3 棋子响应（解耦监听）

kotlin

```
// Soldier.kt
lateinit var target: Entity
lateinit var container: ScriptContainer

fun onFactReceived(subject: String, predicate: String, obj: Any) {
    if (predicate == "cmd.move" && subject == target.uniqueID.toString()) {
        moveTo(obj as BlockPos)
    }
}
```



------

## 6. 总结

本设计方案通过引入**离散数学（集合、映射、偏序集）**作为底层理论支撑，将 Klaymore 脚本系统从“脆弱的图结构”彻底重构为“值对象驱动的无状态架构”：

1. **路径（Path）** 解决了拓扑持久化与 O(1) 分组查找问题。
2. **字段注入（Field Injection）** 消灭了模板代码，提升了脚本编写体验。
3. **蓝图与实例分离（`spawnChild`）** 实现了脚本逻辑的无限复用。
4. **事实库（FactBase）** 取代广播事件，实现了基于匹配的效率最优通信。

这套设计确保了战棋、RPG 等复杂模组在 1.7.10 老旧环境下的代码可维护性与运行时性能，为开发者提供了接近现代编程范式的流畅体验。