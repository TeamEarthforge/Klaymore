package com.earthforge.klaymore.command;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;

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
        return "/klaymore <reload [scriptName]|reloadclient|list>";
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.addChatMessage(new ChatComponentText("§e用法: " + getCommandUsage(sender)));
            sender.addChatMessage(new ChatComponentText("§e  reload [name]  - 热重载服务端脚本（保留数据）"));
            sender.addChatMessage(new ChatComponentText("§e  reloadclient   - 重新编译所有客户端脚本"));
            sender.addChatMessage(new ChatComponentText("§e  list           - 列出当前服务端所有脚本容器"));
            return;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("reload")) {
            handleReload(sender, args);
        } else if (sub.equals("reloadclient")) {
            handleReloadClient(sender);
        } else if (sub.equals("list")) {
            handleList(sender);
        } else {
            sender.addChatMessage(new ChatComponentText("§c未知子命令: " + args[0]));
            sender.addChatMessage(new ChatComponentText("§e用法: " + getCommandUsage(sender)));
        }
    }

    private static final String[] SUB_COMMANDS = { "reload", "reloadclient", "list" };

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, SUB_COMMANDS);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("reload")) {
            return getListOfStringsMatchingLastWord(args, collectServerScriptNames());
        }
        return null;
    }

    /** 收集可重载的服务端脚本名（顶层 + server/ 目录的 .kt，排除 common/）。 */
    private String[] collectServerScriptNames() {
        File scriptDir = PersistenceStorage.getScriptDirectory();
        if (scriptDir == null || !scriptDir.isDirectory()) return new String[0];
        File commonDir = com.earthforge.klaymore.MinecraftDirectory.getCommonScriptDirectory();
        java.util.Set<String> names = new java.util.LinkedHashSet<String>();
        collectKtNames(scriptDir, names, commonDir, false);
        File serverDir = new File(scriptDir, "server");
        collectKtNames(serverDir, names, commonDir, true);
        return names.toArray(new String[0]);
    }

    private void collectKtNames(File dir, java.util.Set<String> names, File commonDir, boolean recursive) {
        if (dir == null || !dir.isDirectory()) return;
        if (commonDir != null) {
            try {
                if (dir.getCanonicalPath()
                    .startsWith(commonDir.getCanonicalPath())) return;
            } catch (Throwable ignored) {}
        }
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile() && f.getName()
                .toLowerCase()
                .endsWith(".kt")) {
                names.add(f.getName());
            } else if (recursive && f.isDirectory()) {
                collectKtNames(f, names, commonDir, true);
            }
        }
    }

    private void handleReload(ICommandSender sender, String[] args) {
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

    /** 重新编译 client/ 目录下所有脚本（清除缓存后重新编译）。 */
    private void handleReloadClient(ICommandSender sender) {
        File scriptDir = PersistenceStorage.getScriptDirectory();
        if (scriptDir == null || !scriptDir.exists() || !scriptDir.isDirectory()) {
            sender.addChatMessage(new ChatComponentText("§c脚本目录不存在: " + scriptDir));
            return;
        }

        File clientDir = new File(scriptDir, "client");
        if (!clientDir.exists() || !clientDir.isDirectory()) {
            sender.addChatMessage(new ChatComponentText("§e客户端脚本目录不存在: " + clientDir.getAbsolutePath()));
            return;
        }

        File[] clientScripts = clientDir.listFiles(
            (dir, name) -> name.toLowerCase()
                .endsWith(".kt"));
        if (clientScripts == null || clientScripts.length == 0) {
            sender.addChatMessage(new ChatComponentText("§e客户端脚本目录下没有 .kt 文件: " + clientDir.getAbsolutePath()));
            return;
        }

        sender.addChatMessage(new ChatComponentText("§b客户端脚本目录: " + clientDir.getAbsolutePath()));
        sender.addChatMessage(new ChatComponentText("§e开始重新编译 " + clientScripts.length + " 个客户端脚本 ..."));

        // 清除所有客户端脚本的缓存，强制重新编译
        for (File f : clientScripts) {
            ScriptLoader.invalidateCache(f);
        }
        PersistenceStorage.resetPrecompileMarkers();

        final AtomicInteger success = new AtomicInteger(0);
        final AtomicInteger failed = new AtomicInteger(0);
        final AtomicInteger done = new AtomicInteger(0);
        final int total = clientScripts.length;

        for (final File f : clientScripts) {
            ScriptLoader
                .loadScriptAsync(f, new java.util.function.Consumer<kotlin.script.experimental.api.CompiledScript>() {

                    @Override
                    public void accept(kotlin.script.experimental.api.CompiledScript compiled) {
                        if (compiled != null) {
                            success.incrementAndGet();
                        } else {
                            failed.incrementAndGet();
                            String err = ScriptErrorReporter.getLastError();
                            sender.addChatMessage(
                                new ChatComponentText("§c编译失败 " + f.getName() + (err != null ? ": " + err : "")));
                        }
                        int n = done.incrementAndGet();
                        if (n == total) {
                            sender.addChatMessage(
                                new ChatComponentText(
                                    "§a客户端脚本重新编译完成: " + success.get() + " 成功, " + failed.get() + " 失败"));
                        }
                    }
                });
        }
    }

    /** 列出当前服务端所有脚本容器。 */
    private void handleList(ICommandSender sender) {
        List<ScriptContainer> all = ScriptBindingManager.getContainers();
        // 只列服务端容器
        java.util.List<ScriptContainer> serverContainers = new java.util.ArrayList<ScriptContainer>();
        for (ScriptContainer c : all) {
            if (c.getSide() == null || c.getSide()
                .isServer()) {
                serverContainers.add(c);
            }
        }

        if (serverContainers.isEmpty()) {
            sender.addChatMessage(new ChatComponentText("§e当前没有服务端脚本容器。"));
            return;
        }

        sender.addChatMessage(new ChatComponentText("§b=== 服务端脚本容器 (" + serverContainers.size() + ") ==="));
        int idx = 1;
        for (ScriptContainer c : serverContainers) {
            String scriptName = c.getScriptName();
            String path = c.getPath();
            Object target = c.getTarget();
            String targetDesc = target == null ? "<null>"
                : target.getClass()
                    .getSimpleName();
            // 尝试显示 target 的更友好描述（如玩家名）
            if (target instanceof net.minecraft.entity.player.EntityPlayer) {
                targetDesc = "Player:" + ((net.minecraft.entity.player.EntityPlayer) target).getDisplayName();
            } else if (target instanceof net.minecraft.entity.Entity) {
                net.minecraft.entity.Entity e = (net.minecraft.entity.Entity) target;
                targetDesc = "Entity:" + e.getClass()
                    .getSimpleName() + "#" + e.getEntityId();
            } else if (target instanceof com.earthforge.klaymore.script.Dummy) {
                targetDesc = "Dummy:" + ((com.earthforge.klaymore.script.Dummy) target).getId();
            }

            StringBuilder sb = new StringBuilder();
            sb.append("§f")
                .append(idx++)
                .append(". ");
            sb.append("§a")
                .append(scriptName);
            sb.append(" §7-> ");
            sb.append("target=")
                .append(targetDesc);
            if (path != null && !path.isEmpty()) {
                sb.append(" §7path=§e")
                    .append(path);
            }
            sender.addChatMessage(new ChatComponentText(sb.toString()));
        }
    }

    private void reloadSpecificScript(final ICommandSender sender, final String rawScriptName) {
        // 支持省略 .kt 扩展名
        final String scriptName = rawScriptName.toLowerCase()
            .endsWith(".kt") ? rawScriptName : rawScriptName + ".kt";

        final File scriptFile = PersistenceStorage.resolveScriptFile(scriptName);
        if (scriptFile == null || !scriptFile.exists() || !scriptFile.isFile()) {
            sender.addChatMessage(
                new ChatComponentText(
                    "Script file not found: " + scriptName
                        + " (请放入 "
                        + PersistenceStorage.getScriptDirectory()
                            .getAbsolutePath()
                        + " 或其子目录 server/)"));
            return;
        }

        // ⭐ common/ 目录下的脚本是注册脚本（物品/方块等），只在 preInit 阶段执行一次，
        // 运行时重新编译会破坏已注册对象的类引用，因此禁止热重载。
        File commonDir = com.earthforge.klaymore.MinecraftDirectory.getCommonScriptDirectory();
        if (commonDir != null) {
            try {
                if (scriptFile.getCanonicalPath()
                    .startsWith(commonDir.getCanonicalPath())) {
                    sender.addChatMessage(new ChatComponentText("§c禁止重载 common/ 目录下的注册脚本: " + scriptName));
                    sender.addChatMessage(new ChatComponentText("§ecommon/ 脚本仅在游戏启动时执行注册，请重启游戏生效。"));
                    return;
                }
            } catch (Throwable ignored) {}
        }

        final List<ScriptContainer> containers = ScriptBindingManager.findByScriptName(scriptName);

        if (containers.isEmpty()) {
            ScriptLoader.invalidateCache(scriptFile);
            sender.addChatMessage(new ChatComponentText("§e未找到绑定此脚本的容器，仅清除了编译缓存: " + scriptName));
            return;
        }

        sender.addChatMessage(
            new ChatComponentText("§e开始异步热重载脚本: " + scriptName + " (绑定 " + containers.size() + " 个目标) ..."));

        ScriptLoader.invalidateCache(scriptFile);

        ScriptLoader.loadScriptAsync(
            scriptFile,
            new java.util.function.Consumer<kotlin.script.experimental.api.CompiledScript>() {

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
                            Object newInstance = ScriptContainerFactory
                                .instantiateScriptForReload(newCompiled, scriptName);
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
                        sender.addChatMessage(
                            new ChatComponentText("§a热重载完成: " + scriptName + " (" + success + " 个容器成功, 数据未丢失)"));
                    } else {
                        sender.addChatMessage(
                            new ChatComponentText(
                                "§e热重载完成: " + scriptName + " (" + success + " 成功, " + failed + " 失败)"));
                    }
                }
            });
    }

    /** 收集目录下的 .kt 文件到 list，跳过 common/ 目录。recursive 为 true 时递归子目录。 */
    private static void collectKtFiles(File dir, List<File> list, File commonDir, boolean recursive) {
        if (dir == null || !dir.isDirectory()) return;
        if (commonDir != null) {
            try {
                if (dir.getCanonicalPath()
                    .startsWith(commonDir.getCanonicalPath())) return;
            } catch (Throwable ignored) {}
        }
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile() && f.getName()
                .toLowerCase()
                .endsWith(".kt")) {
                list.add(f);
            } else if (recursive && f.isDirectory()) {
                collectKtFiles(f, list, commonDir, true);
            }
        }
    }

    private void reloadAllScripts(final ICommandSender sender, File scriptDir) {
        // 收集可重载的服务端脚本：顶层 .kt + server/ 下所有 .kt，严格排除 common/
        File commonDir = com.earthforge.klaymore.MinecraftDirectory.getCommonScriptDirectory();
        final List<File> scriptFiles = new ArrayList<File>();
        collectKtFiles(scriptDir, scriptFiles, commonDir, false);
        File serverDir = new File(scriptDir, "server");
        collectKtFiles(serverDir, scriptFiles, commonDir, true);

        if (scriptFiles.isEmpty()) {
            sender.addChatMessage(
                new ChatComponentText(
                    "No reloadable .kt script files found in: " + scriptDir.getAbsolutePath()
                        + " (common/ is excluded from reload)"));
            return;
        }

        sender.addChatMessage(
            new ChatComponentText("§e开始异步热重载所有服务端脚本 (" + scriptFiles.size() + " 个文件, common/ 已跳过) ..."));

        final AtomicInteger totalSuccess = new AtomicInteger(0);
        final AtomicInteger totalFailed = new AtomicInteger(0);
        final AtomicInteger totalContainers = new AtomicInteger(0);
        final AtomicInteger filesDone = new AtomicInteger(0);
        final int totalFiles = scriptFiles.size();

        for (final File scriptFile : scriptFiles) {
            final String scriptName = scriptFile.getName();
            final List<ScriptContainer> containers = ScriptBindingManager.findByScriptName(scriptName);

            ScriptLoader.invalidateCache(scriptFile);

            if (containers.isEmpty()) {
                // 没被绑定的脚本：直接编译它（为了下次绑定时走缓存），完成后不算入容器统计
                ScriptLoader.loadScriptAsync(
                    scriptFile,
                    new java.util.function.Consumer<kotlin.script.experimental.api.CompiledScript>() {

                        @Override
                        public void accept(kotlin.script.experimental.api.CompiledScript o) {
                            int done = filesDone.incrementAndGet();
                            if (done == totalFiles) {
                                sender.addChatMessage(
                                    new ChatComponentText(
                                        "§a热重载全部完成: " + totalSuccess.get()
                                            + " 容器成功, "
                                            + totalFailed.get()
                                            + " 失败, 共涉及 "
                                            + totalContainers.get()
                                            + " 个容器"));
                            }
                        }
                    });
                continue;
            }

            totalContainers.addAndGet(containers.size());

            ScriptLoader.loadScriptAsync(
                scriptFile,
                new java.util.function.Consumer<kotlin.script.experimental.api.CompiledScript>() {

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
                                    Object newInstance = ScriptContainerFactory
                                        .instantiateScriptForReload(newCompiled, scriptName);
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
                            sender.addChatMessage(
                                new ChatComponentText(
                                    "§a热重载全部完成: " + totalSuccess.get()
                                        + " 容器成功, "
                                        + totalFailed.get()
                                        + " 失败, 共涉及 "
                                        + totalContainers.get()
                                        + " 个容器"));
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
