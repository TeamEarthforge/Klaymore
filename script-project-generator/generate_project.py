#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Klaymore 脚本项目生成器（GTNH 版）
====================================
仅一个配置文件（config.properties），只需填 JAR 路径。
生成使用 GTNH Gradle 插件的独立项目，外部依赖用 rfg.deobf 包裹。

使用：
  python generate_project.py            # 生成项目
  python generate_project.py --zip      # 生成并打包 zip
  Windows 用户可直接双击 generate.bat
"""
import os
import sys
import shutil
import zipfile
from pathlib import Path
from datetime import datetime

SCRIPT_DIR = Path(__file__).resolve().parent


class PropertiesParser:
    """简单 Java .properties 解析器"""
    @staticmethod
    def parse(file_path: Path) -> dict:
        props = {}
        if not file_path.exists():
            return props
        with open(file_path, 'r', encoding='utf-8') as f:
            for raw_line in f:
                line = raw_line.strip()
                if not line or line.startswith('#') or line.startswith('!'):
                    continue
                if '=' in line:
                    k, v = line.split('=', 1)
                elif ':' in line:
                    k, v = line.split(':', 1)
                else:
                    continue
                props[k.strip()] = v.strip().replace('\\:', ':').replace('\\\\', '\\')
        return props


class KlaymoreProjectGenerator:
    """Klaymore 脚本项目生成器（GTNH Gradle + rfg.deobf）"""

    def __init__(self, config: dict):
        self.config = config

        # ---- 项目基本信息 ----
        self.output_dir = Path(config.get('outputDir', 'generated-project')).resolve()
        self.project_name = config.get('projectName', 'klaymore-scripts')
        self.mod_group = config.get('modGroup', 'com.earthforge.klaymore')
        self.script_api_package = config.get('scriptApiPackage', 'com.earthforge.klaymore.script')

        # ---- 版本 ----
        self.minecraft_version = config.get('minecraftVersion', '1.7.10')
        self.forge_version = config.get('forgeVersion', '10.13.4.1614')
        self.mappings_channel = config.get('mappingsChannel', 'stable')
        self.mappings_version = config.get('mappingsVersion', '12')
        self.kotlin_version = config.get('kotlinVersion', '2.2.21')

        # ---- JAR 路径 ----
        self.klaymore_jar = config.get('klaymoreJar', '').strip()
        self.minecraft_dev_jar = config.get('minecraftDevJar', '').strip()
        self.forge_jar = config.get('forgeJar', '').strip()
        self.extra_jars = self._collect_extra_jars(config)

    # ------------------------------------------------------------------
    # 配置辅助
    # ------------------------------------------------------------------
    @staticmethod
    def _collect_extra_jars(config: dict) -> list:
        """从配置中收集所有额外 Mod JAR（支持两种写法）"""
        jars = []
        seen = set()

        def _add(path: str):
            p = path.strip()
            if not p:
                return
            key = os.path.normcase(os.path.abspath(p)) if Path(p).is_absolute() else p.lower()
            if key in seen:
                return
            seen.add(key)
            jars.append(p)

        # 方式 A: extraJars= 逗号分隔
        csv = config.get('extraJars', '').strip()
        if csv:
            for part in csv.split(','):
                _add(part)

        # 方式 B: extraJar.N= 编号多行（按数字顺序）
        numbered = []
        for k, v in config.items():
            if not k.startswith('extraJar.'):
                continue
            suffix = k[len('extraJar.'):]
            try:
                idx = int(suffix)
            except ValueError:
                continue
            numbered.append((idx, v))
        numbered.sort(key=lambda x: x[0])
        for _, v in numbered:
            _add(v)

        return jars

    # ------------------------------------------------------------------
    # 主流程
    # ------------------------------------------------------------------
    def generate(self):
        print(f"[信息] 开始生成 Klaymore 脚本项目（RFG 极简三件套）...")
        print(f"[信息] 输出目录    : {self.output_dir}")
        print(f"[信息] 项目名称    : {self.project_name}")
        print(f"[信息] Minecraft   : {self.minecraft_version}")
        print(f"[信息] Forge       : {self.forge_version}")
        print(f"[信息] Mappings    : {self.mappings_channel}-{self.mappings_version}")
        print(f"[信息] Kotlin      : {self.kotlin_version}")
        print(f"[信息] Klaymore JAR: {self.klaymore_jar or '(未填写，将无法识别 @Subscribe 注解)'}")
        if self.extra_jars:
            print(f"[信息] 额外 Mod JAR: {len(self.extra_jars)} 个")
            for j in self.extra_jars:
                print(f"       - {j}")

        self._validate_config()
        self._prepare_output_dir()
        self._create_dirs()
        self._generate_build_gradle_kts()
        self._generate_settings_gradle_kts()
        self._generate_gradle_properties()
        self._generate_gradle_wrapper()
        self._generate_example_scripts()
        self._generate_readme()
        self._generate_gitignore()
        self._generate_libs_dir_placeholder()

        print(f"\n[成功] 项目生成完成!")
        print(f"  → 项目路径: {self.output_dir}")
        print(f"  → 用 IntelliJ IDEA 打开该目录 → 'Load Gradle Project'")
        print(f"  → 写好脚本后: 把 src/main/kotlin/*.kt 复制到服务端 scripts/ 目录（可改名 .kts）")

    def _validate_config(self):
        errors = []
        warnings = []

        def _check(name: str, path: str, required: bool):
            if not path:
                if required:
                    errors.append(f"{name} 未填写！请在 config.properties 设置对应路径。")
                return
            p = Path(path)
            if not p.exists():
                (errors if required else warnings).append(
                    f"{name} 路径不存在（请检查路径是否正确）: {path}"
                )
            elif not p.suffix.lower() == '.jar':
                warnings.append(f"{name} 看起来不是 .jar 文件: {path}")

        _check("Klaymore JAR (klaymoreJar)", self.klaymore_jar, True)
        _check("Minecraft Dev JAR (minecraftDevJar)", self.minecraft_dev_jar, False)
        _check("Forge JAR (forgeJar)", self.forge_jar, False)
        for i, jar in enumerate(self.extra_jars, 1):
            _check(f"额外 Mod JAR #{i}", jar, False)

        for w in warnings:
            print(f"[警告] {w}")
        if errors:
            print("\n[错误] 配置验证失败:")
            for e in errors:
                print(f"  ✗ {e}")
            print("\n请修正 config.properties 后重试。")
            sys.exit(1)

    def _prepare_output_dir(self):
        if self.output_dir.exists():
            ts = datetime.now().strftime('%Y%m%d_%H%M%S')
            backup = self.output_dir.parent / f"{self.output_dir.name}_backup_{ts}"
            print(f"[警告] 输出目录已存在，自动备份为: {backup.name}")
            shutil.move(str(self.output_dir), str(backup))
        self.output_dir.mkdir(parents=True, exist_ok=True)

    def _create_dirs(self):
        for d in ['src/main/kotlin', 'src/main/resources', 'gradle/wrapper', 'libs', 'scripts']:
            (self.output_dir / d).mkdir(parents=True, exist_ok=True)
        print("[信息] 目录结构已创建")

    # ------------------------------------------------------------------
    # 文件生成：build.gradle.kts
    # ------------------------------------------------------------------
    def _generate_build_gradle_kts(self):
        # ---- 依赖项拼接 ----
        dep_lines = []

        # 1) Kotlin 标准库 + 脚本 API
        dep_lines.append("    // ===== Kotlin & 脚本运行时（自动添加）=====")
        dep_lines.append('    implementation(kotlin("stdlib-jdk8"))')
        dep_lines.append('    implementation(kotlin("script-runtime"))')
        dep_lines.append('    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")')
        dep_lines.append(f'    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm:{self.kotlin_version}")')
        dep_lines.append(f'    implementation("org.jetbrains.kotlin:kotlin-scripting-common:{self.kotlin_version}")')
        dep_lines.append(f'    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm-host:{self.kotlin_version}")')
        dep_lines.append("")

        # 2) Klaymore JAR（双保险：
        #       A = implementation(files(...))      裸挂本地 JAR，IDEA 100% 能识别
        #       B = implementation(rfg.deobf(...)) 混淆 JAR 自动按 MCP 反混淆
        #    klaymore-dev.jar 本身就是反混淆的，A 就够了；混着放更稳）
        dep_lines.append("    // ===== Klaymore Mod API（来自 config.properties 的 klaymoreJar，双保险）=====")
        if self.klaymore_jar:
            km = self._escape_path(self.klaymore_jar)
            dep_lines.append(f'    // 保险 A：裸挂本地文件（IDEA 一定能识别，不走 rfg 管道）')
            dep_lines.append(f'    implementation(files("{km}"))')
            dep_lines.append(f'    // 保险 B：rfg.deobf 包裹（混淆 JAR 自动反混淆成可读类名）')
            dep_lines.append(f'    implementation(rfg.deobf(files("{km}")))')
        else:
            dep_lines.append('    // implementation(files("F:/path/to/klaymore-dev.jar"))')
            dep_lines.append('    // implementation(rfg.deobf(files("F:/path/to/klaymore.jar")))')
        dep_lines.append("")

        # 3) 强制使用的本地 Minecraft / Forge JAR（如果用户填了）
        #    ★ 保险：用户填了 minecraftDevJar 就直接裸挂 implementation(files(...)) + rfg.deobf 两套，
        #      不依赖 RFG 自己下载/解析/patchedMinecraft configuration 的时机问题
        added_local_mc_forge = False
        if self.minecraft_dev_jar or self.forge_jar:
            dep_lines.append("    // ===== 强制本地 Minecraft / Forge JAR（填写后 IDEA 立即显示，不依赖 RFG）=====")
            if self.minecraft_dev_jar:
                m = self._escape_path(self.minecraft_dev_jar)
                dep_lines.append(f'    // ★ Minecraft Dev JAR（用户显式指定，裸挂 + rfg 双保险）')
                dep_lines.append(f'    implementation(files("{m}"))')
                dep_lines.append(f'    implementation(rfg.deobf(files("{m}")))')
                added_local_mc_forge = True
            if self.forge_jar:
                f = self._escape_path(self.forge_jar)
                dep_lines.append(f'    // ★ Forge JAR（用户显式指定，裸挂 + rfg 双保险）')
                dep_lines.append(f'    implementation(files("{f}"))')
                dep_lines.append(f'    implementation(rfg.deobf(files("{f}")))')
                added_local_mc_forge = True
            dep_lines.append("")

        # 4) 额外 Mod JAR（同样双保险：裸挂 + rfg.deobf）
        dep_lines.append("    // ===== 其他 Mod API（来自 config.properties 的 extraJar.N / extraJars，双保险）=====")
        if self.extra_jars:
            for jar in self.extra_jars:
                j = self._escape_path(jar)
                dep_lines.append(f'    implementation(files("{j}"))')
                dep_lines.append(f'    implementation(rfg.deobf(files("{j}")))')
        else:
            dep_lines.append('    // （暂无，在 config.properties 中用 extraJar.1 / extraJars 添加）')
            dep_lines.append('    // implementation(files("F:/mods/IC2.jar"))')
            dep_lines.append('    // implementation(rfg.deobf(files("F:/mods/IC2.jar")))')
        dep_lines.append("")

        # 5) libs/ 目录下所有 JAR（用户直接拖 JAR 进来即可，无需改配置）
        #    双保险：implementation 裸挂 + rfg.deobf（都只处理 *.jar）
        dep_lines.append("    // ===== 本地 libs/ 目录（直接把 JAR 拖进此目录自动生效，双保险）=====")
        dep_lines.append('    implementation(fileTree("libs") { include("*.jar") })')
        dep_lines.append('    implementation(rfg.deobf(fileTree("libs") { include("*.jar") }))')
        dep_lines.append("")

        dep_block = "\n".join(dep_lines)

        # 传给 build.gradle.kts 模板：是否需要本地 JAR 兜底（klaymore/mc/forge 用户显式路径）
        has_local_klaymore = "true" if self.klaymore_jar else "false"
        has_local_mc = "true" if self.minecraft_dev_jar else "false"
        has_local_forge = "true" if self.forge_jar else "false"

        build_gradle = f'''plugins {{
    // --- 极简三件套：RFG 反混淆 + Kotlin + IDEA ---
    //   只用 RetroFuturaGradle 底层插件（提供 rfg.deobf(...) 扩展），
    //   不用 GTNH Convention（它是 Mod 开发模板，会生成 patchedMc/forge 子模块、
    //   Minecraft Facet 等脚本项目完全不需要的东西，容易导致 IDEA Storage Inconsistency）。
    //   ★ 版本 2.0.2：与 Klaymore 项目实际使用版本同步，class file 69.0（JDK 25），
    //     通过 gradle.properties 的 Gradle toolchain 自动 provision JDK 25，用户无需手动设置。
    id("com.gtnewhorizons.retrofuturagradle") version("2.0.2")
    kotlin("jvm") version("{self.kotlin_version}")
    idea
}}

group = "{self.mod_group}"
version = "1.0.0"

// ------------------------------------------------------------------
// RetroFuturaGradle 2.0.2 会自动读取 gradle.properties 中的版本号：
//   minecraftVersion / forgeVersion / channel / mappingsVersion
// 不需要显式 minecraft {{}} 块，避免 DSL 字段名在不同 2.x 小版本里变动的问题
// ------------------------------------------------------------------

// ------------------------------------------------------------------
// 仓库
// ------------------------------------------------------------------
repositories {{
    // RFG / GTNH 系列仓库（兜底找映射和依赖）
    maven {{
        name = "GTNH Maven"
        url = uri("https://nexus.gtnewhorizons.com/repository/public/")
    }}
    maven {{ name = "jitpack"; url = uri("https://jitpack.io") }}
    // 本地 libs/ 目录（JAR 直接拖进来自动生效）
    flatDir {{ dirs("libs") }}
    mavenCentral()
    mavenLocal()
}}

// ------------------------------------------------------------------
// 依赖声明
//   所有外部 JAR 统一用 rfg.deobf(...) 包裹：
//   - 如果你给的是混淆版 JAR（CurseForge 原版），会按 MCP 映射自动反混淆成可读类名
//   - 如果你给的是 -dev.jar（已反混淆），等价于 implementation
// ------------------------------------------------------------------
dependencies {{
{dep_block}
    // 测试（可选）
    testImplementation(kotlin("test"))
}}

// ------------------------------------------------------------------
// ★★★ 根因修复（方案 A 自动兜底 + 方案 B 本地路径显式）：Minecraft/Klaymore/Forge IDEA 不显示
//
//   方案 B（最稳，推荐）：在 config.properties 填下面 3 个路径，生成器会用
//      implementation(files("你填的路径"))
//   **直接裸挂本地 JAR** —— IDEA Gradle Tooling API 100% 能识别，不依赖任何中间管道。
//      klaymoreJar=            ← 必填（你已经填了）
//      minecraftDevJar=        ← 强烈建议填：1.7.10 反混淆 Minecraft dev.jar 绝对路径
//      forgeJar=               ← 可选：forge-1.7.10-xxx-universal.jar 绝对路径
//
//   方案 A（自动兜底）：等 RFG 插件注册好 patchedMinecraft / forgeUniversal / vanilla_minecraft
//      这 3 个 configuration 后，用 plugins.withId + afterEvaluate 时机把它们的 files
//      显式 add 到 implementation（而不是 extendsFrom，IDEA 不识别 extendsFrom 非 Maven artifacts）
//
//   方案 B 优先级最高：只要你填了 minecraftDevJar，IDEA External Libraries 里即使
//   找不到 patchedMinecraft configuration，也至少能看到你手动指定的那个 JAR
// ------------------------------------------------------------------
plugins.withId("com.gtnewhorizons.retrofuturagradle") {{
    // ★ RFG 插件 apply 完成后的瞬间 → afterEvaluate 时 configuration 已经注册
    afterEvaluate {{
        val added = mutableSetOf<String>()
        listOf("patchedMinecraft", "forgeUniversal", "vanilla_minecraft",
                // 兼容不同 RFG 小版本里的配置名
                "forge", "minecraft", "minecraftPatched", "patchedMc").forEach {{ name ->
            val cfg = configurations.findByName(name) ?: return@forEach
            if (added.contains(name)) return@forEach
            try {{
                val fc = project.files(cfg)
                if (!fc.isEmpty) {{
                    project.dependencies.add("implementation", fc)
                    added.add(name)
                    println("[Klaymore] [方案A-afterEvaluate] 显式加 MC/Forge 依赖: $name  ->  ${{fc.files.size}} 个 JAR")
                }}
            }} catch (_: Throwable) {{}}
        }}

        // 额外：打印 implementation classpath 头部 20 行 JAR 名（诊断用）
        try {{
            val cp = configurations.compileClasspath.orNull
            if (cp != null) {{
                val jars = cp.files.take(20).map {{ it.name }}.joinToString("\\n    - ")
                println("[Klaymore] compileClasspath 前 20 个 JAR: \\n    - $jars")
            }}
        }} catch (_: Throwable) {{}}
    }}
}}
// 再晚一层兜底：整个 Gradle projectsEvaluated 之后再挂一次（防止不同 IDEA Gradle 快照时机不同）
gradle.projectsEvaluated {{
    listOf("patchedMinecraft", "forgeUniversal", "vanilla_minecraft",
            "forge", "minecraft", "minecraftPatched", "patchedMc").forEach {{ name ->
        val cfg = configurations.findByName(name) ?: return@forEach
        try {{
            val fc = project.files(cfg)
            if (!fc.isEmpty) {{
                project.dependencies.add("implementation", fc)
                println("[Klaymore] [方案A-projectsEvaluated 兜底] 显式加 MC/Forge 依赖: $name")
            }}
        }} catch (_: Throwable) {{}}
    }}
}}

// ------------------------------------------------------------------
// 诊断任务：打印 implementation classpath 所有 JAR（快速看 Minecraft/Klaymore 在不在）
//   用法: gradlew printClasspath   或   ./gradlew printClasspath
// ------------------------------------------------------------------
tasks.register("printClasspath") {{
    description = "打印 implementation/compileClasspath 下所有 JAR，诊断 IDEA External Libraries 看不到依赖的问题"
    group = "Klaymore"
    notCompatibleWithConfigurationCache("直接访问 Project.configurations，不支持 configuration cache（诊断用任务，没关系）")
    doLast {{
        fun sep() = println("=".repeat(90))
        fun line() = println("-".repeat(90))
        sep()
        println("[printClasspath] compileClasspath JAR 列表:")
        sep()
        val cc = configurations.compileClasspath.orNull
        if (cc == null) {{
            println("  (compileClasspath configuration 不存在)")
        }} else {{
            val all = cc.files.toList().distinctBy {{ it.absolutePath }}.sortedBy {{ it.name.lowercase() }}
            if (all.isEmpty()) {{
                println("  (空) → 说明 dependencies 没挂上！检查上面 build 阶段的 [Klaymore] 日志。")
            }} else {{
                all.forEachIndexed {{ i, f ->
                    val idx = (i + 1).toString().padStart(3)
                    val tag = buildString {{
                        val n = f.name.lowercase()
                        if (n.contains("minecraft") || n.contains("patched") || n.contains("mc-") || n.contains("recompiled")) append(" [MC★]")
                        if (n.contains("forge") || n.contains("fml")) append(" [Forge]")
                        if (n.contains("klaymore")) append(" [KLAYMORE★]")
                    }}
                    println("  $idx. ${{f.name}}$tag")
                    println("         path: ${{f.absolutePath}}")
                }}
                line()
                println("合计 ${{all.size}} 个 JAR。标记说明:")
                println("   [MC★]        = Minecraft 反混淆 JAR（有这个就说明 Minecraft 类真的在 classpath 里）")
                println("   [Forge]      = Forge universal JAR（有这个就说明 Forge API 在 classpath 里）")
                println("   [KLAYMORE★]  = Klaymore 脚本引擎 JAR（有这个就说明 @Subscribe 注解可用）")
            }}
        }}
        sep()
        println("")
        println("如果上面 [MC★] / [KLAYMORE★] 都存在，但 IDEA External Libraries 里看不到：")
        println("  1. 右侧 Gradle 面板 -> Reload All Gradle Projects")
        println("  2. 等 IDEA 右下角 Indexing 结束 -> 再等 30 秒")
        println("  3. 还不行 -> 走教程里的 强制清缓存 6 步（删 .idea/.gradle/build/）")
        sep()
    }}
}}

// ------------------------------------------------------------------
// 切断 main SourceSet 对 Mod 专用 classes 任务的 dependsOn
// RFG 默认会让 :classes dependsOn :patchedMcClasses / :apiClasses / :injectedTagsClasses
// 但我们是脚本项目，这些 SourceSet 根本用不上；如果不切断，即使下面把任务 disable 了也会报
// "task was disabled but dependency was still declared" 校验失败
// ------------------------------------------------------------------
afterEvaluate {{
    // 切断 :compileKotlin / :classes / :compileJava 对 Mod 专用 SourceSet classes 的依赖
    setOf("compileKotlin", "compileJava", "classes", "mainClasses").forEach {{ taskName ->
        tasks.findByName(taskName)?.let {{ task ->
            // 过滤掉：patchedMc / api / injectedTags / injectedInterfaces / mcLauncher 相关依赖任务名
            val filter = task.dependsOn.filterIsInstance<Any>().filterNot {{ dep ->
                val s = dep.toString().lowercase()
                s.contains("patchedmc") || s.contains("apiclasses") ||
                s.contains("injectedtags") || s.contains("injectedinterfaces") ||
                s.contains("mclauncher") || s.contains("compilepatched") ||
                s.contains("injectinterfacesclasses") || s.contains("decompile")
            }}
            task.setDependsOn(filter)
        }}
    }}
}}

// ------------------------------------------------------------------
// 禁用 RFG 自动为 Mod 项目创建的整条任务链（脚本项目完全不需要）
// ------------------------------------------------------------------
tasks.matching {{
    val n = it.name.lowercase()
    listOf(
        // 反编译 / 映射 / MC 源码处理
        "compilepatchedmc", "compileforge", "compilemc", "compileapi",
        "patchedmcclasses", "apiclasses", "mclauncherclasses",
        "decompile", "patchdecomp", "cleanupdecomp", "remapdecompiled",
        "decompressdecompiledsources", "applyjst", "mergevanilla",
        "deobfuscate", "reobfuscate", "reobf",
        "extractdependency", "generateforge", "downloadvanilla",
        "downloadfernflower", "generatesrgsources", "patches",
        // injected interfaces / tags / mcLauncher
        "injectinterfaces", "compileinjectedinterfaces", "processinjectedinterfaces", "injectedinterfacesclasses",
        "injecttags", "compileinjectedtags", "processinjectedtags", "injectedtagsclasses",
        "compilemclauncher", "processmclauncher", "extractnatives", "createmclauncherfiles"
    ).any {{ n.startsWith(it.lowercase()) }}
}}.configureEach {{
    enabled = false
}}

// ------------------------------------------------------------------
// Java / Kotlin 目标版本（与 MC 1.7.10 保持 JDK 8 兼容）
// 注意：这里只是编译目标字节码版本，Gradle 自己运行时用 JDK 11+ 都 OK
// ------------------------------------------------------------------
kotlin {{
    jvmToolchain(8)
}}
java {{
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}}

// ------------------------------------------------------------------
// Kotlin 编译选项（JDK8 目标字节码 + 严格空检查）
// ------------------------------------------------------------------
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {{
    compilerOptions {{
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
        freeCompilerArgs.addAll("-Xjsr305=strict", "-opt-in=kotlin.RequiresOptIn")
    }}
}}

// ------------------------------------------------------------------
// IDEA：显式标记源码根、下载源码/Javadoc
// ------------------------------------------------------------------
idea {{
    module {{
        // 强制把 src/main/kotlin 标为源码根
        sourceDirs.plusAssign(file("src/main/kotlin"))
        resourceDirs.plusAssign(file("src/main/resources"))
        // 下载 sources / javadoc 提升 IDE 体验
        isDownloadJavadoc = true
        isDownloadSources = true
        // 排除冗余目录（减少索引）
        excludeDirs.plusAssign(file("scripts"))
        excludeDirs.plusAssign(file("gradle"))
    }}
}}
'''
        (self.output_dir / 'build.gradle.kts').write_text(build_gradle, encoding='utf-8')
        print("[信息] build.gradle.kts 已生成（RFG 反混淆 + Kotlin，无多余模块）")

    @staticmethod
    def _escape_path(p: str) -> str:
        return p.replace('\\', '\\\\').replace('"', '\\"')

    # ------------------------------------------------------------------
    # 文件生成：settings.gradle.kts
    # 极简配置：不再用 GTNH Settings Convention（它是 Mod 开发项目的）
    # 我们只是脚本项目，直接管理插件仓库 + 项目名即可
    # ------------------------------------------------------------------
    def _generate_settings_gradle_kts(self):
        s = f'''pluginManagement {{
    repositories {{
        // RetroFuturaGradle 插件发布仓库
        maven {{
            name = "GTNH Maven"
            url = uri("https://nexus.gtnewhorizons.com/repository/public/")
            mavenContent {{
                includeGroup("com.gtnewhorizons")
                includeGroupByRegex("com\\\\.gtnewhorizons\\\\..+")
            }}
        }}
        gradlePluginPortal()
        mavenCentral()
        mavenLocal()
    }}
}}

dependencyResolutionManagement {{
    // 注意：不用 PREFER_SETTINGS，因为 RFG 插件会动态添加一些旧版 MC 专用仓库
    // （mojang / forge / sponge 等），设为 PREFER_PROJECT 即可让两边的仓库都生效
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {{
        maven {{
            name = "GTNH Maven"
            url = uri("https://nexus.gtnewhorizons.com/repository/public/")
        }}
        maven {{ name = "jitpack"; url = uri("https://jitpack.io") }}
        mavenCentral()
        mavenLocal()
    }}
}}

rootProject.name = "{self.project_name}"
'''
        (self.output_dir / 'settings.gradle.kts').write_text(s, encoding='utf-8')
        print("[信息] settings.gradle.kts 已生成（极简版，无 GTNH Settings Convention）")

    def _generate_gradle_properties(self):
        props = f'''# =========================================================================
# 自动生成 - Klaymore 脚本项目 Gradle 属性
# 生成时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}
#
# 下面的 minecraftVersion / forgeVersion / channel / mappingsVersion
# 会被 RetroFuturaGradle（RFG 2.0.2）读取，作为 rfg.deobf(...) 的 MCP 反混淆依据
# =========================================================================

# ---- RetroFuturaGradle 必需的 MCP/MC/Forge 版本（来自 config.properties）----
minecraftVersion={self.minecraft_version}
forgeVersion={self.forge_version}
channel={self.mappings_channel}
mappingsVersion={self.mappings_version}

# FML 官方 1.7.10 conf（兜底找 extra mappings）
remoteMappings = https\\://raw.githubusercontent.com/MinecraftForge/FML/1.7.10/conf/

# 让 RFG 自动给混淆过的 Minecraft List/Map 注入泛型签名（IDE 显示更友好）
enableGenericInjection = true

# ---- 项目元数据（仅 IDE 显示用，不影响构建）----
modName={self.project_name}
modGroup={self.mod_group}

# ---- Kotlin 风格 ----
kotlin.code.style = official
kotlin.stdlib.default.dependency = false

# ---- Gradle 性能 ----
org.gradle.jvmargs = -Xmx3G -XX:MaxMetaspaceSize=768m -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8
org.gradle.parallel = true
org.gradle.configuration-cache = true
org.gradle.caching = true

# ---- RetroFuturaGradle 2.0.2 需要 JDK 25 才能运行（class file 69.0）----
# ★ 用 Gradle 9.3+ 自带的 toolchain 自动下载 JDK 25：
#   - 下载到 ~/.gradle/jdks/（缓存复用，一次性）
#   - 只给 Gradle 守护进程用，不修改系统 JAVA_HOME，不影响其他项目
#   - 只是"构建过程用 JDK25"，脚本编译输出依然是 JDK8 字节码（服务端兼容）
org.gradle.daemon.jvm.version = 25
org.gradle.java.installations.auto-download = true
org.gradle.java.installations.auto-detect = true
# 如果下载 Adoptium JDK 25 慢，启用下面的清华镜像：
# org.gradle.jvm.toolchain.download.base-url = https\://mirrors.tuna.tsinghua.edu.cn/Adoptium/
'''
        (self.output_dir / 'gradle.properties').write_text(props, encoding='utf-8')
        print("[信息] gradle.properties 已生成（RFG 2.0.2 版，自动 provision JDK 25 给守护进程）")

    # ------------------------------------------------------------------
    # Gradle Wrapper
    # ------------------------------------------------------------------
    def _generate_gradle_wrapper(self):
        (self.output_dir / 'gradle' / 'wrapper').mkdir(parents=True, exist_ok=True)

        props = '''distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\\://services.gradle.org/distributions/gradle-9.3.1-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
'''
        (self.output_dir / 'gradle' / 'wrapper' / 'gradle-wrapper.properties').write_text(props, encoding='utf-8')

        bat = r'''@rem Gradle startup script for Windows
@if "%DEBUG%"=="" @echo off
setlocal
set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi
set DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"
set JAVA_EXE=java.exe
if defined JAVA_HOME goto findJavaFromJavaHome
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute
echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.
goto fail
:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe
if exist "%JAVA_EXE%" goto execute
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME%
goto fail
:execute
set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar
"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
:end
if %ERRORLEVEL% equ 0 goto mainEnd
:fail
exit /b 1
:mainEnd
endlocal
'''
        (self.output_dir / 'gradlew.bat').write_text(bat, encoding='utf-8')

        sh = '''#!/bin/sh
##############################################################################
#   Gradle start up script for POSIX
##############################################################################
app_path=$0
while
    APP_HOME=${app_path%"${app_path##*/}"}
    [ -h "$app_path" ]
do
    ls=$( ls -ld -- "$app_path" )
    link=${ls#*' -> '}
    case $link in
      /*)   app_path=$link ;;
      *)    app_path=$APP_HOME$link ;;
    esac
done
APP_BASE_NAME=${0##*/}
APP_HOME=$( cd "${APP_HOME:-./}" && pwd -P ) || exit
if [ -n "$JAVA_HOME" ] ; then
    JAVACMD=$JAVA_HOME/bin/java
else
    JAVACMD=java
fi
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'
eval "set -- $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS \"-Dorg.gradle.appname=$APP_BASE_NAME\" -classpath \"$CLASSPATH\" org.gradle.wrapper.GradleWrapperMain \"$@\""
exec "$JAVACMD" "$@"
'''
        sh_path = self.output_dir / 'gradlew'
        sh_path.write_text(sh, encoding='utf-8')
        try:
            os.chmod(sh_path, 0o755)
        except Exception:
            pass

        # ------------------------------------------------------------------
        # gradle-wrapper.jar：尝试从 Klaymore 主项目 / 常见位置拷贝（否则 gradlew 无法启动）
        # ------------------------------------------------------------------
        wrapper_jar = self.output_dir / 'gradle' / 'wrapper' / 'gradle-wrapper.jar'
        candidates = [
            Path(__file__).resolve().parent.parent / 'gradle' / 'wrapper' / 'gradle-wrapper.jar',  # Klaymore 根
            Path(__file__).resolve().parent / 'gradle' / 'wrapper' / 'gradle-wrapper.jar',          # 生成器自己的
        ]
        copied = False
        for src in candidates:
            if src.exists() and src.is_file() and src.stat().st_size > 1024:
                try:
                    shutil.copy(src, wrapper_jar)
                    copied = True
                    break
                except Exception:
                    pass
        if not copied:
            print("[警告] gradle-wrapper.jar 未找到（Gradle Wrapper 不能用命令行直接启动，但不影响 IDEA 导入）。"
                  "\n       如需命令行启动：把任意正常 Gradle 项目里的 gradle/wrapper/gradle-wrapper.jar 复制到："
                  f"\n       {wrapper_jar}")

        print("[信息] Gradle Wrapper 已生成（Gradle 9.3.1，支持 daemon JVM 自动下载 JDK 25）")

    # ------------------------------------------------------------------
    # 示例脚本
    # ------------------------------------------------------------------
    def _generate_example_scripts(self):
        pkg = "scripts"

        example = f'''package {pkg}

import com.earthforge.klaymore.script.Subscribe
import com.earthforge.klaymore.script.ScriptContainer
import net.minecraft.entity.player.EntityPlayer
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraft.util.ChatComponentText
import cpw.mods.fml.common.gameevent.PlayerEvent.PlayerLoggedInEvent

// =========================================================================
// Klaymore 脚本示例
// -------------------------------------------------------------------------
// 说明:
//   1. @Subscribe(EventClass::class) 标记事件订阅方法
//   2. bindTarget / bindParent / bindContainer 是约定方法（可选实现）
//   3. 本文件 IDE 开发时后缀为 .kt（享受完整 compileClasspath 代码补全）
//      写完后**复制一份改后缀为 .kts**，丢到服务端 scripts/ 目录即可加载
// =================================================================---------

private var target: EntityPlayer? = null
private var parentTarget: ScriptContainer? = null
private var container: ScriptContainer? = null

// ---- 约定方法（Klaymore 自动调用，全部可选）----

fun bindTarget(obj: EntityPlayer) {{
    target = obj
    println("[ExampleScript] bindTarget: $target")
}}

fun bindParent(parent: ScriptContainer) {{
    parentTarget = parent
    println("[ExampleScript] bindParent: $parent")
}}

fun bindContainer(con: ScriptContainer) {{
    container = con
    println("[ExampleScript] bindContainer  OK")
}}

// ---- 事件订阅示例 ----
@Subscribe(event = PlayerLoggedInEvent::class)
fun onPlayerLogin(event: PlayerLoggedInEvent) {{
    val player = event.player ?: return
    println("[ExampleScript] 玩家登录: ${{player.displayName}}")
    if (!player.worldObj.isRemote) {{
        player.addChatMessage(ChatComponentText("§a[Klaymore] §f欢迎回到服务器!"))
    }}
}}

@Subscribe(event = net.minecraftforge.event.world.BlockEvent.HarvestDropsEvent::class)
fun onBlockHarvest(event: net.minecraftforge.event.world.BlockEvent.HarvestDropsEvent) {{
    val harvester = event.harvester ?: return
    val count = (container?.getData("blockBroken") as? Int ?: 0) + 1
    container?.setData("blockBroken", count)
    if (count % 10 == 0) {{
        println("[ExampleScript] ${{harvester.displayName}} 累计破坏方块 = $count")
    }}
}}

// ---- 可通过 ScriptContainer.run() 调用的公共方法 ----

fun sayHello(name: String): String {{
    val msg = "Hello, $name! (from Klaymore Script)"
    println("[ExampleScript] sayHello -> $msg")
    return msg
}}

fun getBlockBrokenCount(): Int = container?.getData("blockBroken") as? Int ?: 0
'''
        (self.output_dir / 'src' / 'main' / 'kotlin' / 'ExampleScript.kt').write_text(example, encoding='utf-8')

        template = f'''package {pkg}

import com.earthforge.klaymore.script.Subscribe
import cpw.mods.fml.common.gameevent.PlayerEvent.PlayerLoggedInEvent
import net.minecraft.entity.player.EntityPlayer

// =========================================================================
// Klaymore 脚本模板（复制此文件开始新脚本）
// -------------------------------------------------------------------------
// IDE 开发时后缀用 .kt（完整吃 compileClasspath，代码补全 / 类型检查全生效）
// 部署到服务端：把 .kt 复制一份改后缀为 .kts，丢到服务端 scripts/ 目录即可
//
// 常用 Forge / FML 事件类：
//   cpw.mods.fml.common.gameevent.PlayerEvent.PlayerLoggedInEvent / PlayerLoggedOutEvent
//   net.minecraftforge.event.entity.player.PlayerEvent.BreakSpeed / HarvestCheck
//   net.minecraftforge.event.world.BlockEvent.BreakEvent / HarvestDropsEvent / PlaceEvent
//   net.minecraftforge.event.entity.living.LivingEvent.LivingUpdateEvent / LivingDeathEvent
//   net.minecraftforge.event.ServerChatEvent
// =================================================================---------

@Subscribe(event = PlayerLoggedInEvent::class)
fun onPlayerJoin(event: PlayerLoggedInEvent) {{
    val player = event.player ?: return
    println("[MyScript] 玩家加入: ${{player.displayName}}")
}}
'''
        (self.output_dir / 'src' / 'main' / 'kotlin' / 'MyScriptTemplate.kt').write_text(template, encoding='utf-8')
        print("[信息] 示例脚本已生成 (ExampleScript.kt / MyScriptTemplate.kt)")

    def _generate_libs_dir_placeholder(self):
        readme = '''此目录用于放置额外的 Mod JAR 文件。

所有放到本目录里的 *.jar 都会被 build.gradle.kts 自动以 rfg.deobf 引入，
相当于：
    rfg.deobf(fileTree("libs") { include("*.jar") })

所以你不想改 config.properties 的话，直接把 JAR 拖到这里即可生效。
'''
        (self.output_dir / 'libs' / 'README.txt').write_text(readme, encoding='utf-8')

    # ------------------------------------------------------------------
    # README
    # ------------------------------------------------------------------
    def _generate_readme(self):
        md = f'''# {self.project_name} - Klaymore 脚本开发环境（RFG 极简版）

> 自动生成时间：{datetime.now().strftime('%Y-%m-%d %H:%M:%S')}
> Minecraft {self.minecraft_version} / Forge {self.forge_version} / MCP {self.mappings_channel}-{self.mappings_version}

## 这是什么？

本项目是**专门用来写 Klaymore 脚本**的独立 Gradle 工程。
- 开发时脚本后缀用 **`.kt`**（标准 Kotlin 源文件，100% 吃 `compileClasspath`，代码补全/类型检查/跳转全正常——解决 `.kts` 吃不到依赖导致外部库不显示的问题）
- 部署到服务端时后缀用 **`.kts`**（Klaymore 运行时加载格式），把 `.kt` 复制一份改后缀为 `.kts` 即可
- 只使用**三件套插件**：RetroFuturaGradle（`rfg.deobf` 反混淆）+ Kotlin JVM + IDEA
- **没有** `gtnhconvention` 那种 Mod 开发模板包袱（不生成 patchedMc/forge 子模块、不挂 Minecraft Facet、不做 modGroup 检查）
- 所有外部 Mod JAR 统一用 **`rfg.deobf(files(...))`** 包裹，混淆 JAR 自动转成 MCP 可读类名
- IDEA 正确识别 `src/main/kotlin/` 为源码根，Minecraft/Forge/Klaymore 全在 External Libraries

## 快速开始

### 1. 用 IntelliJ IDEA 打开项目

1. File → Open → 选择本项目目录（包含 `build.gradle.kts` 的目录）
2. 右下角会提示 "Gradle build script found" → **Load Gradle Project**
3. 等待 IDEA 下载 Gradle、依赖和源码（第一次比较慢；Gradle JVM 选 JDK 25，自动下载即可）
4. 完成后打开 `src/main/kotlin/ExampleScript.kt`，所有 import 都应该是**非红色**

> 导入报错？99% 是下面两种：
> - **Gradle JVM 没选 25**：Settings → Build Tools → Gradle → Gradle JVM 选 Download JDK → Eclipse Temurin 25
> - 配置 `config.properties` 里的 `klaymoreJar` 路径不对 → 修正后重新运行生成器

### 2. 编写脚本

把脚本放在 `src/main/kotlin/` 下（可以建子包），**扩展名必须是 .kt**。
> ❌ 不要直接在 src/main/kotlin 下新建 .kts！.kts 是 Kotlin Script，它的 classpath 不继承项目 `compileClasspath`，会导致 Minecraft/Forge 类全部爆红找不到。

项目内置两个脚本：
- `ExampleScript.kt` - 完整示例（事件订阅、容器数据存储、公共方法调用）
- `MyScriptTemplate.kt` - 极简模板，复制后改文件名即可

脚本包名可以随意（如 `package scripts.myfeature`），不影响 Klaymore 运行时加载。

### 3. 同步到服务端运行

写好脚本后：**复制一份 `.kt` 文件 → 改后缀为 `.kts` → 扔到服务端脚本目录即可**。

```
src/main/kotlin/xxx.kt    (开发时用 —— 完整代码补全 / 类型检查)
           ↓ 复制 + 改后缀
服务端 scripts/xxx.kts    (运行时用 —— Klaymore 加载格式)
```

服务端脚本目录通常是 `mods/Klaymore/scripts/`（具体视 Klaymore 配置而定）。包名、顶层函数名 Klaymore 运行时都不校验，只要后缀是 `.kts` 且语法是标准 Kotlin Script 即可。

### 4. 运行时重载

Klaymore 脚本运行时通常提供重载命令（如 `/klaymore reload`，具体看 Klaymore Mod 内置命令），可以不重启服务端应用脚本修改。

## 如何添加更多 Mod 的 API？

本项目提供 **3 种方式**，全部会用 `rfg.deobf` 自动反混淆：

| 方式 | 做法 | 适用场景 |
|------|------|----------|
| **① 直接丢 JAR** | 把 JAR 拖进本项目的 `libs/` 目录 | 临时、快速 |
| **② 改配置文件** | 回到生成器的 `config.properties`，写 `extraJar.1=xxx.jar`，然后重新运行 `generate.bat` | 长期项目，可版本化配置 |
| **③ Maven 坐标** | 在 `build.gradle.kts` 的 `dependencies` 块手动加（如 `rfg.deobf("curse.maven:...")`） | 需要从 Curse/Maven 中央仓库下载 |

生成器已经预置了以下仓库（GTNH 插件自带 + 自定义）：
- Maven Central
- GTNH Nexus（GregTech、GTNHLib 等 GTNH 生态 Mod）
- Curse Maven（格式 `rfg.deobf("curse.maven:<slug>-<projectId>:<fileId>")`，见 [cursemaven.com](https://cursemaven.com)）
- Modrinth Maven（格式 `rfg.deobf("maven.modrinth:<slug>:<version>")`）
- JitPack（GitHub 项目）
- 本地 `libs/` 目录（flatDir）

## 脚本 API 速查

### 事件订阅 `@Subscribe`
```kotlin
import {self.script_api_package}.Subscribe
import net.minecraftforge.event.entity.player.PlayerEvent

@Subscribe(PlayerEvent.PlayerLoggedInEvent::class)
fun onLogin(event: PlayerEvent.PlayerLoggedInEvent) {{
    val player = event.entityPlayer
    // ...
}}
```
**规则：**
- 只能注解在实例方法上（不能 top-level / 静态）
- 必须有且只有 1 个参数，类型要匹配或兼容注解里的事件类
- 不支持返回值（忽略）

### 约定方法（可选，按需实现）
```kotlin
fun bindTarget(target: Any)          // 脚本绑定的 "target" 对象（如 EntityPlayer、TileEntity 等）
fun bindParent(parent: Any?)         // 父容器绑定的 target
fun bindContainer(c: ScriptContainer) // 脚本自身的容器（用于存数据、run 方法调用）
```

### `ScriptContainer` 常用 API
| 方法 | 说明 |
|------|------|
| `getData(key: String): Any?` | 获取脚本级自定义数据（不跨服务器重启持久化） |
| `setData(key, value)` | 存储自定义数据 |
| `getDataMap(): Map<String, Any?>` | 获取整个数据 map 的只读副本 |
| `getTarget(): Any?` | 获取绑定的 target 对象 |
| `getScriptInstance(): Any?` | 获取脚本实例对象（反射调用时用） |
| `run(methodName, vararg args): Any?` | 调用脚本内的公共方法 |
| `parent: ScriptContainer?` / `children: List<ScriptContainer>` | 容器层级结构 |

### `SubscriberRegistry`（服务端 Mod 侧调用，用于派发事件）
```java
// Java 端派发自定义事件（对应脚本中 @Subscribe(MyEvent::class)）
SubscriberRegistry.dispatch(myEvent, targetObj);
```

## 目录结构

```
{self.project_name}/
├── build.gradle.kts          # 构建脚本（RFG 2.0.2 + Kotlin + IDEA 三件套）
├── settings.gradle.kts
├── gradle.properties         # Minecraft / Forge / MCP 版本 + JDK25 daemon 配置
├── gradlew  /  gradlew.bat   # Gradle Wrapper
├── gradle/wrapper/
├── libs/                     # 【快速】直接丢 JAR 进去，自动 rfg.deobf 引入
└── src/main/
    ├── kotlin/               # ★ 你的脚本放这里，后缀必须是 .kt（吃完整 compileClasspath）
    │   ├── ExampleScript.kt     ← 完整示例（@Subscribe 事件/容器/公共方法）
    │   └── MyScriptTemplate.kt  ← 最简模板
    └── resources/            # 资源（一般脚本不用）
```

## 常见问题 FAQ

**Q: 所有 Minecraft / Forge 类都是红色（Unresolved reference）？**
A:
1. 等 IDE 完成 Gradle 同步（看右侧 Gradle 面板有没有绿色勾）
2. **Gradle JVM 必须选 JDK 25**（Settings → Build Tools → Gradle → Gradle JVM），RFG 2.0.2 不支持更低版本 JVM
3. 确认 `gradle.properties` 里的 `minecraftVersion` / `forgeVersion` 跟服务端一致
4. 手动：File → Invalidate Caches → 全勾 → Invalidate and Restart

**Q: `@Subscribe` 是红色？找不到 Klaymore 注解？**
A: 说明 `klaymoreJar=` 路径不对或未填。**去生成器目录下的 `config.properties` 填对 Klaymore 的 *-dev.jar 路径**，重新运行生成器即可。

**Q: 脚本里写了 `SomeModClass` IDE 识别不到？**
A: 该 Mod JAR 没加入依赖。把那个 Mod 的 JAR 丢进 `libs/` 目录（最快，IDE 重新 Reload Gradle 就生效），或去 `config.properties` 里写 `extraJar.N=`。

**Q: 运行时（服务端）报错找不到类，但 IDE 是好的？**
A: 这是正常现象：IDE 里有完整开发 classpath，但服务端运行时只加载了实际装的 Mod。**脚本里引用的 Mod 必须也在服务端 mods/ 目录中安装。**

**Q: 如何在脚本中引用其他脚本的数据 / 方法？**
A: 通过 `ScriptContainer.run("方法名", 参数)` 或使用 Klaymore 的容器父子层级 API 传递共享对象。

**Q: 为什么脚本后缀是 .kt 不是 .kts？部署时怎么办？**
A: 这是**故意设计的**：
- 开发时用 `.kt` = 标准 Kotlin 源文件，**100% 吃 `compileClasspath`**，所有 Minecraft/Forge/Klaymore 类 + 代码补全 + 类型检查全正常 ✅
- `.kts` = Kotlin Script 模式，默认 classpath 不继承项目 dependencies，所以你之前会遇到"吃不到依赖、Minecraft 爆红"的坑 ❌
- **部署到服务端：** 把 `.kt` 文件复制一份 → **手动把扩展名从 .kt 改成 .kts** → 拷到服务端 `scripts/`（或 `mods/Klaymore/scripts/`）即可（Klaymore 运行时加载 .kts 格式）
'''
        (self.output_dir / 'README.md').write_text(md, encoding='utf-8')
        print("[信息] README.md 已生成（包含完整使用说明）")

    # ------------------------------------------------------------------
    # .gitignore（忽略 IDE/Gradle 本地临时数据；Storage inconsistency 时可直接删 .idea）
    # ------------------------------------------------------------------
    def _generate_gitignore(self):
        gi = r'''# -------- Gradle --------
.gradle/
build/
out/

# -------- IDEA / JetBrains --------
# 如果出现 Storage inconsistency / Hard reference broken，可关闭 IDEA 后删除本目录
.idea/
*.iml
*.iws
*.ipr

# -------- Eclipse / VSCode --------
.classpath
.project
.settings/
.vscode/

# -------- 系统垃圾 --------
.DS_Store
Thumbs.db
*.log

# -------- 临时脚本输出 --------
scripts/*.kts
!scripts/.keep
libs/*.jar
!libs/README.txt
'''
        (self.output_dir / '.gitignore').write_text(gi, encoding='utf-8')
        (self.output_dir / 'scripts' / '.keep').write_text("", encoding='utf-8')
        print("[信息] .gitignore 已生成")

    # ------------------------------------------------------------------
    # 可选：ZIP 打包
    # ------------------------------------------------------------------
    def create_zip(self) -> Path:
        zip_path = self.output_dir.parent / f"{self.project_name}.zip"
        print(f"[信息] 正在打包 -> {zip_path}")
        with zipfile.ZipFile(zip_path, 'w', zipfile.ZIP_DEFLATED) as zf:
            for f in self.output_dir.rglob('*'):
                if f.is_file():
                    # ZIP 文件名强制用 POSIX 斜杠 + 相对路径
                    arcname = f.relative_to(self.output_dir).as_posix()
                    zf.write(f, arcname)
        print(f"[信息] ZIP 完成 OK")
        return zip_path


# ======================================================================
# main
# ======================================================================
def main():
    # Windows 控制台默认 GBK，print 含 →/✓ 等 Unicode 会报 gbk can't encode
    if sys.platform.startswith('win'):
        try:
            sys.stdout.reconfigure(encoding='utf-8')
            sys.stderr.reconfigure(encoding='utf-8')
        except Exception:
            import io
            sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
            sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding='utf-8', errors='replace')

    print("=" * 64)
    print("  Klaymore 脚本项目生成器  (RFG 极简版 / rfg.deobf)")
    print("=" * 64)
    print()

    config_file = SCRIPT_DIR / 'config.properties'

    # 首次运行：如果 config.properties 不存在就生成模板
    if not config_file.exists():
        _write_default_config(config_file)
        print(f"[信息] 已生成默认配置文件: {config_file}")
        print("请打开它填写 klaymoreJar 路径和其他 Mod JAR 路径后重新运行。")
        return

    config = PropertiesParser.parse(config_file)
    make_zip = '--zip' in sys.argv

    gen = KlaymoreProjectGenerator(config)
    gen.generate()
    if make_zip:
        gen.create_zip()


def _write_default_config(p: Path):
    """生成默认 config.properties（首次运行）"""
    content = '''# =========================================================================
# Klaymore 脚本项目生成器 - 唯一配置文件
# =========================================================================
# 【重要】你只需要修改这一个文件！全部填写 JAR 路径即可，不需要懂 Gradle 语法。
# 修改后重新运行 generate.bat（Windows）或 ./generate.sh 生成项目。
#
# 路径说明：
#   - 推荐使用绝对路径（如 F:/MCRPG/mods/xxx.jar 或 /home/user/mods/xxx.jar）
#   - Windows 路径用正斜杠 / 或双反斜杠 \\\\
#   - 路径中有空格没问题，无需引号
# =========================================================================

# -------------------------------------------------------------------------
# 1. 项目基本信息（一般不用改）
# -------------------------------------------------------------------------
projectName=klaymore-scripts
outputDir=generated-project
modGroup=com.earthforge.klaymore
scriptApiPackage=com.earthforge.klaymore.script

# -------------------------------------------------------------------------
# 2. 版本信息（必须与你运行服务端的版本一致）
# -------------------------------------------------------------------------
minecraftVersion=1.7.10
forgeVersion=10.13.4.1614
mappingsChannel=stable
mappingsVersion=12
kotlinVersion=2.2.21

# -------------------------------------------------------------------------
# 3. Klaymore Mod JAR 路径【必须填写】
# -------------------------------------------------------------------------
# 填 Klaymore 构建产物中的 *-dev.jar（已反混淆版本）
# Windows 示例: klaymoreJar=F:/MCRPG/Klaymore/build/libs/klaymore-1.0.0-dev.jar
# Linux   示例: klaymoreJar=/home/user/klaymore/build/libs/klaymore-1.0.0-dev.jar
klaymoreJar=

# -------------------------------------------------------------------------
# 4. 额外 Mod JAR【可选，按需填写】
# -------------------------------------------------------------------------
# 你的脚本需要引用的其他 Mod API（IC2 / GregTech / NEI / ...）
# 所有填在这里的 JAR 都会被自动用 rfg.deobf(files("...")) 包裹引入。
#
# 方式 A：逗号分隔一行写多个
#extraJars=
#
# 方式 B：编号多行（推荐，更清晰，1、2、3... 依次写下去）
#extraJar.1=
#extraJar.2=
#extraJar.3=

# -------------------------------------------------------------------------
# 5. Minecraft / Forge Dev JAR【可选，一般不填】
# -------------------------------------------------------------------------
# GTNH 插件会按上面的版本号自动配置 Minecraft / Forge，通常不用填。
# 离线 / 需要强制用本地 JAR 的场景再填。
#
#minecraftDevJar=
#forgeJar=
'''
    p.write_text(content, encoding='utf-8')


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        print("\n[中断] 用户取消")
        sys.exit(130)
    except Exception as e:
        print(f"\n[错误] 发生异常: {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)
