package com.earthforge.klaymore.command;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;

import com.earthforge.klaymore.script.CompiledScriptCompat;
import com.earthforge.klaymore.script.PersistenceStorage;
import com.earthforge.klaymore.script.ScriptBindingManager;
import com.earthforge.klaymore.script.ScriptContainer;
import com.earthforge.klaymore.script.ScriptContainerFactory;
import com.earthforge.klaymore.script.ScriptErrorReporter;
import com.earthforge.klaymore.script.ScriptLoader;

public class KlaymoreCommand extends CommandBase {

    @Override
    public String getCommandName() {
        return "klaymore";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/klaymore reload [scriptName] - Hot-reload script(s) without losing data";
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length < 1 || !args[0].equalsIgnoreCase("reload")) {
            sender.addChatMessage(new ChatComponentText("Usage: " + getCommandUsage(sender)));
            return;
        }

        // ⭐ 脚本目录是全局共享的：.minecraft/klaymore （与 saves/ 同级）
        File scriptDir = PersistenceStorage.getScriptDirectory();
        if (scriptDir == null || !scriptDir.exists() || !scriptDir.isDirectory()) {
            String where = scriptDir == null ? "<unknown>" : scriptDir.getAbsolutePath();
            sender.addChatMessage(new ChatComponentText("Script directory not found: " + where));
            return;
        }

        sender.addChatMessage(new ChatComponentText("§b脚本目录 (全局): " + scriptDir.getAbsolutePath()));

        if (args.length >= 2) {
            String scriptName = args[1];
            reloadSpecificScript(sender, scriptName);
        } else {
            // 全量 reload：清预编译标记，稍后全部重新编译
            PersistenceStorage.resetPrecompileMarkers();
            reloadAllScripts(sender, scriptDir);
        }
    }

    private void reloadSpecificScript(final ICommandSender sender, final String scriptName) {
        // ⭐ 新策略：全局优先 / 存档 fallback
        final File scriptFile = PersistenceStorage.resolveScriptFile(scriptName);
        if (scriptFile == null || !scriptFile.exists() || !scriptFile.isFile()) {
            sender.addChatMessage(new ChatComponentText("Script file not found: " + scriptName
                + " (请放入 " + PersistenceStorage.getScriptDirectory().getAbsolutePath() + ")"));
            return;
        }

        final List<ScriptContainer> containers = ScriptBindingManager.findByScriptName(scriptName);

        if (containers.isEmpty()) {
            ScriptLoader.invalidateCache(scriptFile);
            sender.addChatMessage(new ChatComponentText(
                "§e未找到绑定此脚本的容器，仅清除了编译缓存: " + scriptName));
            return;
        }

        sender.addChatMessage(new ChatComponentText(
            "§e开始异步热重载脚本: " + scriptName + " (绑定 " + containers.size() + " 个目标) ..."));

        ScriptLoader.invalidateCache(scriptFile);

        ScriptLoader.loadScriptAsync(scriptFile, new java.util.function.Consumer<kotlin.script.experimental.api.CompiledScript>() {
            @Override
            public void accept(kotlin.script.experimental.api.CompiledScript newCompiled) {
                if (newCompiled == null) {
                    sender.addChatMessage(new ChatComponentText("§c编译失败: " + scriptName));
                    String lastError = ScriptErrorReporter.getLastError();
                    if (lastError != null) {
                        sender.addChatMessage(new ChatComponentText("§c错误: " + lastError));
                    }
                    return;
                }
                int success = 0;
                int failed = 0;
                for (ScriptContainer container : containers) {
                    try {
                        Object newInstance = ScriptContainerFactory.instantiateScriptForReload(
                            newCompiled, scriptName);
                        if (newInstance == null) {
                            failed++;
                            continue;
                        }
                        container.replaceScriptInstance(newInstance, newCompiled);
                        success++;
                    } catch (Exception e) {
                        ScriptErrorReporter.reportStatic("热重载容器失败: " + e.getMessage());
                        failed++;
                    }
                }
                if (failed == 0) {
                    sender.addChatMessage(new ChatComponentText(
                        "§a热重载完成: " + scriptName + " (" + success + " 个容器成功, 数据未丢失)"));
                } else {
                    sender.addChatMessage(new ChatComponentText(
                        "§e热重载完成: " + scriptName + " (" + success + " 成功, " + failed + " 失败)"));
                }
            }
        });
    }

    private void reloadAllScripts(final ICommandSender sender, File scriptDir) {
        final File[] scriptFiles = scriptDir.listFiles((dir, name) -> name.endsWith(".kts"));
        if (scriptFiles == null || scriptFiles.length == 0) {
            sender
                .addChatMessage(new ChatComponentText("No .kts script files found in: " + scriptDir.getAbsolutePath()));
            return;
        }

        sender.addChatMessage(new ChatComponentText("§e开始异步热重载所有脚本 (" + scriptFiles.length + " 个文件) ..."));

        final AtomicInteger totalSuccess = new AtomicInteger(0);
        final AtomicInteger totalFailed = new AtomicInteger(0);
        final AtomicInteger totalContainers = new AtomicInteger(0);
        final AtomicInteger filesDone = new AtomicInteger(0);
        final int totalFiles = scriptFiles.length;

        for (final File scriptFile : scriptFiles) {
            final String scriptName = scriptFile.getName();
            final List<ScriptContainer> containers = ScriptBindingManager.findByScriptName(scriptName);

            ScriptLoader.invalidateCache(scriptFile);

            if (containers.isEmpty()) {
                // 没被绑定的脚本：直接编译它（为了下次绑定时走缓存），完成后不算入容器统计
                ScriptLoader.loadScriptAsync(scriptFile, new java.util.function.Consumer<kotlin.script.experimental.api.CompiledScript>() {
                    @Override
                    public void accept(kotlin.script.experimental.api.CompiledScript o) {
                        int done = filesDone.incrementAndGet();
                        if (done == totalFiles) {
                            sender.addChatMessage(new ChatComponentText(
                                "§a热重载全部完成: " + totalSuccess.get() + " 容器成功, " +
                                totalFailed.get() + " 失败, 共涉及 " + totalContainers.get() + " 个容器"));
                        }
                    }
                });
                continue;
            }

            totalContainers.addAndGet(containers.size());

            ScriptLoader.loadScriptAsync(scriptFile, new java.util.function.Consumer<kotlin.script.experimental.api.CompiledScript>() {
                @Override
                public void accept(kotlin.script.experimental.api.CompiledScript newCompiled) {
                    if (newCompiled == null) {
                        totalFailed.addAndGet(containers.size());
                        String lastError = ScriptErrorReporter.getLastError();
                        if (lastError != null) {
                            sender.addChatMessage(new ChatComponentText("§c编译失败 " + scriptName + ": " + lastError));
                        }
                    } else {
                        for (ScriptContainer container : containers) {
                            try {
                                Object newInstance = ScriptContainerFactory.instantiateScriptForReload(
                                    newCompiled, scriptName);
                                if (newInstance == null) {
                                    totalFailed.incrementAndGet();
                                    continue;
                                }
                                container.replaceScriptInstance(newInstance, newCompiled);
                                totalSuccess.incrementAndGet();
                            } catch (Exception e) {
                                ScriptErrorReporter.reportStatic("热重载容器失败: " + scriptName + " - " + e.getMessage());
                                totalFailed.incrementAndGet();
                            }
                        }
                    }
                    int done = filesDone.incrementAndGet();
                    if (done == totalFiles) {
                        // 收尾：再触发一次预编译扫描（防止本次新加的脚本没被 reloadAll 的 listFiles 覆盖到）
                        PersistenceStorage.precompileAllScriptsNow();
                        sender.addChatMessage(new ChatComponentText(
                            "§a热重载全部完成: " + totalSuccess.get() + " 容器成功, " +
                            totalFailed.get() + " 失败, 共涉及 " + totalContainers.get() + " 个容器"));
                    }
                }
            });
        }
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }
}
