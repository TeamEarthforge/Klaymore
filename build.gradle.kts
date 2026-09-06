import groovy.lang.Closure
import org.gradle.api.tasks.bundling.AbstractArchiveTask

plugins {
  id("com.gtnewhorizons.gtnhconvention")
  kotlin("jvm")
}

repositories { mavenCentral() }

// ⚠️ 说明：kotlin("jvm") 插件会自动把 kotlin-stdlib 加到 implementation
// 我们不在乎它加不加——因为 kotlin 运行类 + 编译器 全部来自独立的 klaymore-runtime.jar
// （与主 JAR 同放 mods/ 目录），主模块 shadowJar 时会 exclude 掉所有 kotlin 相关 class，
// 避免重复、避免 kotlin-stdlib 版本不一致冲突。
//
// ⚠️ 【2025-08-30 重大决定：完全不 relocate kotlin！】
//     之前为了"理论上"的冲突防范 relocate kotlin，结果引入了一连串难以彻底解决的 bug：
//       1. builtins 字符串常量被 relocate 二次污染 → FirFallbackBuiltinSymbolProvider 找资源失败
//       2. 开发环境 runClient 主模块未 relocate vs runtime relocate → 两套 kotlin 命名空间冲突
//       3. 编译器看 ScriptContainer.superclass = kotlin.Any vs stdlib 只认 shadow.kotlin.Any
//     → "Missing stdlib class / Cannot access class 'kotlin.Any'" 等一整套类型系统崩溃。
//     Forge 1.7.10 年代（2014 年）Kotlin 才 1.0 刚出来，根本不可能有其他 mod 带 Kotlin，
//     冲突概率 = 0。所以废掉 relocate，保持原汁原味 kotlin.* 包名。

dependencies {}

afterEvaluate {
  tasks
      .matching { it.name.endsWith("ShadowJar") || it.name == "shadowJar" }
      .configureEach eachTask@{
        val task = this@eachTask as AbstractArchiveTask
        println("[Klaymore] 配置 shadow 任务: ${task.name}")
        println("[Klaymore]   模式：【不 relocate kotlin】，仅 exclude 打包避免重复类。")

        // ---------- 只对几个小的第三方包做 relocate（它们和 kotlin 无关，也不需要改资源路径） ----------
        //   kotlin / kotlinx / org.jetbrains.kotlin / org.jetbrains.annotations 全部不再 relocate！
        val groovyTask = task as groovy.lang.GroovyObject
        val emptyClosure =
            object : Closure<Any>(task) {
              @Suppress("unused") fun doCall() {}
            }
        val relocate =
            fun(pattern: String, dest: String) {
              try {
                groovyTask.invokeMethod("relocate", arrayOf(pattern, dest, emptyClosure))
                println("[Klaymore]   relocate: $pattern -> $dest")
              } catch (_: Exception) {
                try {
                  groovyTask.invokeMethod("relocate", arrayOf(pattern, dest))
                  println("[Klaymore]   relocate: $pattern -> $dest (双参数版)")
                } catch (ex: Exception) {
                  println("[Klaymore]   警告: 无法添加 relocate 规则 $pattern: ${ex.message}")
                }
              }
            }

        // 只剩这几个可能和其他 mods 冲突的小工具包做 relocate（不重要，出错影响小）
        relocate("org.codehaus", "com.earthforge.klaymore.shadow.org.codehaus")
        relocate("org.jdom", "com.earthforge.klaymore.shadow.org.jdom")

        // ---------- 打包时排除所有 Kotlin 相关依赖的 class 文件！----------
        //   kotlin-stdlib / kotlin-compiler / kotlinx-coroutines 等 jar 的所有内容，
        //   都不在主 JAR 里出现，由 klaymore-runtime.jar 统一提供。
        //   这样主 JAR 大小只有几百 KB，秒级构建。
        task.exclude("kotlin/**")
        task.exclude("kotlinx/**")
        task.exclude("org/jetbrains/kotlin/**")
        task.exclude("org/jetbrains/annotations/**")
        task.exclude("org/intellij/lang/annotations/**")
        task.exclude("org/codehaus/**")
        task.exclude("org/jdom/**")

        // META-INF 里也清理掉 kotlin 相关文件（如果有的话），避免冲突
        task.exclude("META-INF/kotlin/**")
        task.exclude("META-INF/*.kotlin_metadata")
        task.exclude("META-INF/kotlin-stdlib*.kotlin_module")
        task.exclude("META-INF/kotlinx-coroutines*.kotlin_module")
      }
}

afterEvaluate {
  // 修复 Gradle 9 严格模式下 reobfJar 与 script-runtime:copyRuntime 的隐式依赖告警
  tasks
      .matching { it.name == "reobfJar" }
      .configureEach { dependsOn(":script-runtime:copyRuntime") }
}

afterEvaluate {
  configure<com.diffplug.gradle.spotless.SpotlessExtension> {
    kotlin {
      clearSteps()
      ktfmt("0.52")
    }
    kotlinGradle {
      clearSteps()
      ktfmt("0.52")
    }
  }
}
