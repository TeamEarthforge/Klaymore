package com.earthforge.klaymore.command;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.World;

import com.earthforge.klaymore.script.CompiledScriptCompat;
import com.earthforge.klaymore.script.ScriptBindingManager;
import com.earthforge.klaymore.script.ScriptContainer;
import com.earthforge.klaymore.script.ScriptContainerFactory;
import com.earthforge.klaymore.script.ScriptErrorReporter;
import com.earthforge.klaymore.script.ScriptLoader;

public class KlaymoreCommand extends CommandBase {

    private static final String SCRIPT_DIR = "klaymore";

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

        File scriptDir = getScriptDirectory(sender);
        if (!scriptDir.exists() || !scriptDir.isDirectory()) {
            sender.addChatMessage(new ChatComponentText("Script directory not found: " + scriptDir.getAbsolutePath()));
            return;
        }

        if (args.length >= 2) {
            String scriptName = args[1];
            reloadSpecificScript(sender, scriptDir, scriptName);
        } else {
            reloadAllScripts(sender, scriptDir);
        }
    }

    private void reloadSpecificScript(ICommandSender sender, File scriptDir, String scriptName) {
        File scriptFile = new File(scriptDir, scriptName);
        if (!scriptFile.exists() || !scriptFile.isFile()) {
            sender.addChatMessage(new ChatComponentText("Script file not found: " + scriptName));
            return;
        }

        List<ScriptContainer> containers = ScriptBindingManager.findByScriptName(scriptName);

        if (containers.isEmpty()) {
            ScriptLoader.invalidateCache(scriptFile);
            sender.addChatMessage(new ChatComponentText(
                "§e未找到绑定此脚本的容器，仅清除了编译缓存: " + scriptName));
            return;
        }

        sender.addChatMessage(new ChatComponentText(
            "§e开始热重载脚本: " + scriptName + " (绑定 " + containers.size() + " 个目标)"));

        ScriptLoader.invalidateCache(scriptFile);
        Object newCompiled = ScriptLoader.loadScript(scriptFile);
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
                    CompiledScriptCompat.cast(newCompiled), scriptName);
                if (newInstance == null) {
                    failed++;
                    continue;
                }
                container.replaceScriptInstance(newInstance, CompiledScriptCompat.cast(newCompiled));
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

    private void reloadAllScripts(ICommandSender sender, File scriptDir) {
        File[] scriptFiles = scriptDir.listFiles((dir, name) -> name.endsWith(".kts"));
        if (scriptFiles == null || scriptFiles.length == 0) {
            sender
                .addChatMessage(new ChatComponentText("No .kts script files found in: " + scriptDir.getAbsolutePath()));
            return;
        }

        sender.addChatMessage(new ChatComponentText("§e开始热重载所有脚本 (" + scriptFiles.length + " 个文件) ..."));

        AtomicInteger totalSuccess = new AtomicInteger(0);
        AtomicInteger totalFailed = new AtomicInteger(0);
        AtomicInteger totalContainers = new AtomicInteger(0);

        for (File scriptFile : scriptFiles) {
            String scriptName = scriptFile.getName();
            List<ScriptContainer> containers = ScriptBindingManager.findByScriptName(scriptName);

            ScriptLoader.invalidateCache(scriptFile);

            if (containers.isEmpty()) {
                continue;
            }

            totalContainers.addAndGet(containers.size());

            Object newCompiled = ScriptLoader.loadScript(scriptFile);
            if (newCompiled == null) {
                totalFailed.addAndGet(containers.size());
                String lastError = ScriptErrorReporter.getLastError();
                if (lastError != null) {
                    sender.addChatMessage(new ChatComponentText("§c编译失败 " + scriptName + ": " + lastError));
                }
                continue;
            }

            for (ScriptContainer container : containers) {
                try {
                    Object newInstance = ScriptContainerFactory.instantiateScriptForReload(
                        CompiledScriptCompat.cast(newCompiled), scriptName);
                    if (newInstance == null) {
                        totalFailed.incrementAndGet();
                        continue;
                    }
                    container.replaceScriptInstance(newInstance, CompiledScriptCompat.cast(newCompiled));
                    totalSuccess.incrementAndGet();
                } catch (Exception e) {
                    ScriptErrorReporter.reportStatic("热重载容器失败: " + scriptName + " - " + e.getMessage());
                    totalFailed.incrementAndGet();
                }
            }
        }

        sender.addChatMessage(new ChatComponentText(
            "§a热重载全部完成: " + totalSuccess.get() + " 容器成功, " +
            totalFailed.get() + " 失败, 共涉及 " + totalContainers.get() + " 个容器"));
    }

    private File getScriptDirectory(ICommandSender sender) {
        World world = sender.getEntityWorld();
        if (world != null) {
            File saveDirectory = world.getSaveHandler()
                .getWorldDirectory();
            return new File(saveDirectory, SCRIPT_DIR);
        }
        return new File(".", SCRIPT_DIR);
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }
}
