package com.earthforge.klaymore.command;

import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.World;

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
        return "/klaymore reload [scriptName] - Recompile script(s)";
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

        // 清除编译缓存，强制重新编译
        ScriptLoader.clearCache();

        if (args.length >= 2) {
            // 重编译指定脚本（异步）
            String scriptName = args[1];
            reloadSpecificScriptAsync(sender, scriptDir, scriptName);
        } else {
            // 重编译所有脚本（异步）
            reloadAllScriptsAsync(sender, scriptDir);
        }
    }

    /**
     * 异步重编译指定脚本
     */
    private void reloadSpecificScriptAsync(ICommandSender sender, File scriptDir, String scriptName) {
        File scriptFile = new File(scriptDir, scriptName);
        if (!scriptFile.exists() || !scriptFile.isFile()) {
            sender.addChatMessage(new ChatComponentText("Script file not found: " + scriptName));
            return;
        }

        // 清除该文件的缓存
        ScriptLoader.invalidateCache(scriptFile);

        // 发送开始编译的消息
        sender.addChatMessage(new ChatComponentText("§e正在后台编译: " + scriptName + " ..."));

        // 异步编译，回调返回主线程
        ScriptLoader.loadScriptAsync(scriptFile, compiled -> {
            if (compiled != null) {
                sender.addChatMessage(new ChatComponentText("§a成功编译: " + scriptName));
            } else {
                sender.addChatMessage(new ChatComponentText("§c编译失败: " + scriptName));
                String lastError = ScriptErrorReporter.getLastError();
                if (lastError != null) {
                    sender.addChatMessage(new ChatComponentText("§c错误: " + lastError));
                }
            }
        });
    }

    /**
     * 异步重编译所有脚本
     */
    private void reloadAllScriptsAsync(ICommandSender sender, File scriptDir) {
        File[] scriptFiles = scriptDir.listFiles((dir, name) -> name.endsWith(".kts"));
        if (scriptFiles == null || scriptFiles.length == 0) {
            sender
                .addChatMessage(new ChatComponentText("No .kts script files found in: " + scriptDir.getAbsolutePath()));
            return;
        }

        // 发送开始编译的消息
        sender.addChatMessage(new ChatComponentText("§e正在后台编译 " + scriptFiles.length + " 个脚本 ..."));

        // 使用计数器跟踪完成状态
        AtomicInteger completed = new AtomicInteger(0);
        AtomicInteger success = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);

        for (File scriptFile : scriptFiles) {
            ScriptLoader.invalidateCache(scriptFile);

            ScriptLoader.loadScriptAsync(scriptFile, compiled -> {
                if (compiled != null) {
                    success.incrementAndGet();
                } else {
                    failed.incrementAndGet();
                    String lastError = ScriptErrorReporter.getLastError();
                    if (lastError != null) {
                        sender
                            .addChatMessage(new ChatComponentText("§c编译失败 " + scriptFile.getName() + ": " + lastError));
                    }
                }

                // 检查是否所有脚本都完成了
                if (completed.incrementAndGet() == scriptFiles.length) {
                    sender.addChatMessage(
                        new ChatComponentText("§a重编译完成: " + success.get() + " 成功, " + failed.get() + " 失败."));
                }
            });
        }
    }

    /**
     * 获取存档目录下的 klaymore 脚本文件夹
     */
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
