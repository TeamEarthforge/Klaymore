import org.gradle.api.tasks.bundling.AbstractArchiveTask
import groovy.lang.Closure

plugins {
  id("com.gtnewhorizons.gtnhconvention")
  kotlin("jvm")
}

repositories { mavenCentral() }

// ⚠️ 说明：kotlin("jvm") 插件会自动把 kotlin-stdlib 加到 implementation
// 我们不在乎它加不加——因为主模块 shadowJar 只 relocate 自己代码引用，
// 且 kotlin-stdlib 这些依赖（implementation/compileOnly）**全都不会被打进最终 mod JAR**
// 运行时 Kotlin 类全部来自独立的 klaymore-runtime.jar（与主 JAR 同放 mods/ 目录）

dependencies {}

afterEvaluate {
  tasks.matching {
    it.name.endsWith("ShadowJar") || it.name == "shadowJar"
  }.configureEach eachTask@{
    val task = this@eachTask as AbstractArchiveTask
    println("[Klaymore] 配置 shadow 任务: ${task.name}")
    println("[Klaymore]   模式：仅 relocate 主模块字节码引用（不打包 Kotlin 类本体，不合并 runtime）")

    // ---------- 仅对主模块代码做 relocate 字节码重写（匹配 klaymore-runtime.jar 中的包名）----------
    //   只处理几十个/几百个主类，毫秒级完成！
    //   通过 GroovyObject.invokeMethod 直接调用 shadow Jar 任务的 relocate DSL 方法（兼容 GTNH 自定义 ShadowJar 类型）
    val groovyTask = task as groovy.lang.GroovyObject
    val emptyClosure = object : Closure<Any>(task) {
      @Suppress("unused") fun doCall() {}
    }
    val relocate = fun(pattern: String, dest: String) {
      try {
        // 优先尝试: relocate(pattern, dest, closure)（ShadowRelocator 标准签名）
        groovyTask.invokeMethod("relocate", arrayOf(pattern, dest, emptyClosure))
        println("[Klaymore]   relocate: $pattern -> $dest")
      } catch (_: Exception) {
        try {
          // 兜底：relocate(pattern, dest)
          groovyTask.invokeMethod("relocate", arrayOf(pattern, dest))
          println("[Klaymore]   relocate: $pattern -> $dest (双参数版)")
        } catch (ex: Exception) {
          println("[Klaymore]   警告: 无法添加 relocate 规则 $pattern: ${ex.message}")
        }
      }
    }

    relocate("kotlin", "com.earthforge.klaymore.shadow.kotlin")
    relocate("kotlinx", "com.earthforge.klaymore.shadow.kotlinx")
    relocate("org.jetbrains.kotlin", "com.earthforge.klaymore.shadow.org.jetbrains.kotlin")
    relocate("org.jetbrains.annotations", "com.earthforge.klaymore.shadow.org.jetbrains.annotations")
    relocate("org.intellij.lang.annotations", "com.earthforge.klaymore.shadow.org.intellij.lang.annotations")
    relocate("org.codehaus", "com.earthforge.klaymore.shadow.org.codehaus")
    relocate("org.jdom", "com.earthforge.klaymore.shadow.org.jdom")

    // ---------- 最关键：打包时排除所有 Kotlin 相关依赖的 class 文件！----------
    //   kotlin-stdlib 等 jar 的所有内容，都不在主 JAR 里出现，由 klaymore-runtime.jar 统一提供
    //   主 JAR 大小只有几百 KB，shadowJar / reobfJar 都是秒级完成！
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
