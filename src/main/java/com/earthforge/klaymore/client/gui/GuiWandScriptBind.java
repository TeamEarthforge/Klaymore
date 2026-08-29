package com.earthforge.klaymore.client.gui;

import com.earthforge.klaymore.Klaymore;
import com.earthforge.klaymore.network.KlaymoreNetwork;
import com.earthforge.klaymore.wand.WandBindPacket;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiSlot;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

@SideOnly(Side.CLIENT)
public class GuiWandScriptBind extends GuiScreen {

    private static final String UP_LABEL = ".. <UP> ..";

    private final EntityPlayer player;
    private final int targetEntityId;
    private Entity targetEntity;

    private static final int GUI_WIDTH = 280;
    private static final int GUI_HEIGHT = 260;

    private int guiLeft;
    private int guiTop;

    private GuiTextField scriptNameField;
    private GuiButton btnRefresh;
    private GuiButton btnBind;
    private GuiButton btnUnbind;
    private GuiButton btnCancel;

    private FileSlot fileSlot;

    private File rootScriptDir;
    private File currentDir;
    private List<FileEntry> currentEntries = new ArrayList<FileEntry>();
    private String selectedRelative = "";
    private int selectedIndex = -1;
    private long lastDblClickAt = 0L;
    private int lastDblClickIdx = -1;

    private String lastStatus = "";
    private long statusExpireAt = 0;

    public GuiWandScriptBind(EntityPlayer player, int targetEntityId) {
        this.player = player;
        this.targetEntityId = targetEntityId;
        if (player != null && player.worldObj != null) {
            this.targetEntity = player.worldObj.getEntityByID(targetEntityId);
        }
        File worldDir = getWorldDirectorySafe();
        if (worldDir != null) {
            this.rootScriptDir = new File(worldDir, "klaymore");
        } else {
            this.rootScriptDir = new File(".", "klaymore");
        }
        if (!this.rootScriptDir.exists()) {
            if (!this.rootScriptDir.mkdirs()) {
                System.err.println("[Klaymore Wand] WARN: cannot mkdir: " + this.rootScriptDir.getAbsolutePath());
            }
        }
        this.currentDir = this.rootScriptDir;
        refreshEntries();
    }

    private static File getWorldDirectorySafe() {
        try {
            net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
            if (server == null) return null;
            net.minecraft.world.World world = server.getEntityWorld();
            if (world != null) {
                net.minecraft.world.storage.ISaveHandler sh = world.getSaveHandler();
                if (sh != null) {
                    File dir = sh.getWorldDirectory();
                    if (dir != null) return dir;
                }
            }
            net.minecraft.world.World[] worlds = server.worldServers;
            if (worlds != null && worlds.length > 0 && worlds[0] != null) {
                net.minecraft.world.storage.ISaveHandler sh = worlds[0].getSaveHandler();
                if (sh != null) {
                    File dir = sh.getWorldDirectory();
                    if (dir != null) return dir;
                }
            }
        } catch (Throwable t) {
            System.err.println("[Klaymore Wand] WARN getWorldDirectorySafe: " + t.getMessage());
        }
        return null;
    }

    // ---------- 目录扫描 ----------

    private void refreshEntries() {
        List<FileEntry> list = new ArrayList<FileEntry>();
        if (currentDir != null && currentDir.isDirectory()
            && !isSameFile(currentDir, rootScriptDir.getParentFile())) {
            list.add(new FileEntry(true, null, UP_LABEL));
        }
        if (currentDir != null && currentDir.isDirectory()) {
            File[] files = currentDir.listFiles();
            if (files != null) {
                List<File> dirs = new ArrayList<File>();
                List<File> scripts = new ArrayList<File>();
                for (File f : files) {
                    if (f.isDirectory()) dirs.add(f);
                    else if (f.isFile() && f.getName().toLowerCase().endsWith(".kts")) scripts.add(f);
                }
                Collections.sort(dirs, FILENAME_COMPARATOR);
                Collections.sort(scripts, FILENAME_COMPARATOR);
                for (File d : dirs) list.add(new FileEntry(true, d, d.getName()));
                for (File s : scripts) list.add(new FileEntry(false, s, s.getName()));
            }
        }
        currentEntries = list;
        selectedIndex = -1;
        if (fileSlot != null) {
            fileSlot.scrollBy(-Integer.MAX_VALUE);
        }
    }

    private static final Comparator<File> FILENAME_COMPARATOR = new Comparator<File>() {
        @Override
        public int compare(File a, File b) {
            return a.getName().compareToIgnoreCase(b.getName());
        }
    };

    private static boolean isSameFile(File a, File b) {
        if (a == null || b == null) return a == b;
        try {
            return a.getCanonicalFile().equals(b.getCanonicalFile());
        } catch (Throwable ignored) {
            return a.getAbsoluteFile().equals(b.getAbsoluteFile());
        }
    }

    private String relativePathOf(File file) {
        if (file == null || rootScriptDir == null) return "";
        try {
            String root = rootScriptDir.getCanonicalPath();
            String target = file.getCanonicalPath();
            if (!target.startsWith(root)) return "";
            String rel = target.substring(root.length());
            rel = rel.replace('\\', '/');
            if (rel.startsWith("/")) rel = rel.substring(1);
            return rel;
        } catch (Throwable ignored) {
            return file.getName();
        }
    }

    // ---------- GUI 初始化 ----------

    @Override
    public void initGui() {
        super.initGui();
        Keyboard.enableRepeatEvents(true);

        guiLeft = (width - GUI_WIDTH) / 2;
        guiTop = (height - GUI_HEIGHT) / 2;

        int listX = guiLeft + 10;
        int listY = guiTop + 50;
        int listW = GUI_WIDTH - 20;
        int listH = 128;
        fileSlot = new FileSlot(mc, listW, listH, listY, listY + listH, listX, 16);

        scriptNameField = new GuiTextField(fontRendererObj,
            guiLeft + 10, guiTop + 200, GUI_WIDTH - 114, 18);
        scriptNameField.setMaxStringLength(160);
        scriptNameField.setFocused(true);
        scriptNameField.setText(selectedRelative);

        buttonList.clear();

        btnRefresh = new GuiButton(3, guiLeft + GUI_WIDTH - 98, guiTop + 199, 88, 18,
            I18n.format("gui.klaymore.refresh"));
        buttonList.add(btnRefresh);

        int btnWidth = 80;
        int btnY = guiTop + 228;
        int gap = 6;
        int total = btnWidth * 3 + gap * 2;
        int startX = guiLeft + (GUI_WIDTH - total) / 2;

        btnUnbind = new GuiButton(1, startX, btnY, btnWidth, 20,
            I18n.format("gui.klaymore.unbind"));
        buttonList.add(btnUnbind);

        btnBind = new GuiButton(0, startX + btnWidth + gap, btnY, btnWidth, 20,
            I18n.format("gui.klaymore.bind"));
        buttonList.add(btnBind);

        btnCancel = new GuiButton(2, startX + (btnWidth + gap) * 2, btnY, btnWidth, 20,
            I18n.format("gui.cancel"));
        buttonList.add(btnCancel);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (scriptNameField != null) scriptNameField.updateCursorCounter();
    }

    @Override
    protected void actionPerformed(GuiButton btn) {
        if (btn == null || !btn.enabled) return;
        if (btn.id == 2) {
            mc.displayGuiScreen(null);
            return;
        }
        if (btn.id == 3) {
            refreshEntries();
            flashStatus(EnumChatFormatting.AQUA + I18n.format("gui.klaymore.refreshed"));
            return;
        }
        if (btn.id == 0) {
            doBind();
            return;
        }
        if (btn.id == 1) {
            doUnbind();
            return;
        }
    }

    // ---------- 操作 ----------

    private void doBind() {
        String name = scriptNameField == null ? "" : scriptNameField.getText().trim();
        if (name.isEmpty()) {
            flashStatus(EnumChatFormatting.RED + I18n.format("gui.klaymore.err.empty"));
            return;
        }
        String normalized = normalizeScriptName(name);
        if (!normalized.toLowerCase().endsWith(".kts")) {
            normalized = normalized + ".kts";
        }
        try {
            KlaymoreNetwork.CHANNEL.sendToServer(new WandBindPacket(targetEntityId, normalized));
            mc.displayGuiScreen(null);
        } catch (Throwable t) {
            flashStatus(EnumChatFormatting.RED + "Packet send failed: " + t.getMessage());
        }
    }

    private void doUnbind() {
        try {
            KlaymoreNetwork.CHANNEL.sendToServer(new WandBindPacket(targetEntityId, ""));
            mc.displayGuiScreen(null);
        } catch (Throwable t) {
            flashStatus(EnumChatFormatting.RED + "Packet send failed: " + t.getMessage());
        }
    }

    private static String normalizeScriptName(String raw) {
        String s = raw.trim().replace('\\', '/');
        while (s.contains("../")) s = s.replace("../", "");
        while (s.startsWith("/")) s = s.substring(1);
        return s;
    }

    private void flashStatus(String msg) {
        lastStatus = msg;
        statusExpireAt = System.currentTimeMillis() + 3000L;
    }

    private void onEntryChosen(int idx) {
        if (idx < 0 || idx >= currentEntries.size()) return;
        FileEntry entry = currentEntries.get(idx);
        long now = System.currentTimeMillis();
        boolean dblClick = (now - lastDblClickAt) < 350L && idx == lastDblClickIdx;
        lastDblClickAt = now;
        lastDblClickIdx = idx;

        if (entry.isUpLabel) {
            File parent = currentDir.getParentFile();
            if (parent != null && (isSameFile(parent, rootScriptDir)
                || isAncestorOf(rootScriptDir, parent))) {
                currentDir = parent;
                refreshEntries();
            }
            return;
        }
        if (entry.isDir && entry.file != null) {
            currentDir = entry.file;
            refreshEntries();
            return;
        }
        if (entry.file != null) {
            selectedRelative = relativePathOf(entry.file);
            if (scriptNameField != null) scriptNameField.setText(selectedRelative);
            if (dblClick) doBind();
        }
    }

    private static boolean isAncestorOf(File ancestor, File child) {
        if (ancestor == null || child == null) return false;
        try {
            File cur = child.getCanonicalFile();
            File anc = ancestor.getCanonicalFile();
            while (cur != null) {
                if (cur.equals(anc)) return true;
                cur = cur.getParentFile();
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ---------- 输入 ----------

    @Override
    protected void keyTyped(char c, int keyCode) {
        if (scriptNameField != null && scriptNameField.isFocused()) {
            if (keyCode == Keyboard.KEY_RETURN) { doBind(); return; }
            scriptNameField.textboxKeyTyped(c, keyCode);
        } else {
            if (keyCode == Keyboard.KEY_ESCAPE) { mc.displayGuiScreen(null); return; }
        }
        super.keyTyped(c, keyCode);
    }

    @Override
    protected void mouseClicked(int mx, int my, int btn) {
        try { super.mouseClicked(mx, my, btn); } catch (Throwable ignored) {}
        if (scriptNameField != null) scriptNameField.mouseClicked(mx, my, btn);
    }

    // ---------- 绘制 ----------

    @Override
    public void drawScreen(int mx, int my, float partialTicks) {
        // -------- 阶段 1：完整 GuiSlot.drawScreen（会触发 overlayBackground 画屏幕上下泥土） → 立即精准盖掉【面板范围内】的上下泥土 --------
        if (fileSlot != null) {
            try { fileSlot.drawScreen(mx, my, partialTicks); }
            catch (Throwable ignored) {}
            int t = fileSlot.panelTop;
            int b = fileSlot.panelBottom;
            // 只盖 GUI 面板宽度范围内的泥土（不再延伸到整个屏幕宽度），面板左右两侧保持世界原样
            drawRect(guiLeft, 0, guiLeft + GUI_WIDTH, t, 0xFF000000);
            drawRect(guiLeft, b, guiLeft + GUI_WIDTH, height, 0xFF000000);
        }

        // -------- 阶段 2：面板 + 标题文字 + 输入框 + 按钮（盖在内部泥土之上） --------
        drawPanel(guiLeft, guiTop, GUI_WIDTH, GUI_HEIGHT);

        String title = I18n.format("gui.klaymore.title");
        drawCenteredString(fontRendererObj, title, guiLeft + GUI_WIDTH / 2, guiTop + 8, 0xFFFFFFFF);

        String targetName;
        if (targetEntity != null) {
            try { targetName = targetEntity.getCommandSenderName(); }
            catch (Throwable t) { targetName = "Entity #" + targetEntityId; }
            if (targetName == null || targetName.isEmpty()) targetName = "Entity #" + targetEntityId;
        } else {
            targetName = "Entity #" + targetEntityId + " (missing)";
        }
        String targetLine = I18n.format("gui.klaymore.target", targetName);
        fontRendererObj.drawString(targetLine, guiLeft + 12, guiTop + 26, 0xFFFFFF);

        String curDirLabel;
        if (currentDir == null) curDirLabel = "";
        else curDirLabel = relativePathOf(currentDir);
        if (curDirLabel.isEmpty()) curDirLabel = "(root)";
        String dirLabel = I18n.format("gui.klaymore.dir", curDirLabel);
        fontRendererObj.drawString(dirLabel, guiLeft + 12, guiTop + 38, 0xAAAAFF);

        // -------- 阶段 3：只重绘列表「可见内容 + 滚动条」（跳过 overlayBackground 泥土源） --------
        if (fileSlot != null) {
            try { fileSlot.renderContentOnly(mx, my, partialTicks); }
            catch (Throwable ignored) {}
        }

        fontRendererObj.drawString(I18n.format("gui.klaymore.script"), guiLeft + 12, guiTop + 188, 0xA0A0A0);
        if (scriptNameField != null) scriptNameField.drawTextBox();

        if (lastStatus != null && !lastStatus.isEmpty()
            && System.currentTimeMillis() < statusExpireAt) {
            drawCenteredString(fontRendererObj, lastStatus,
                width / 2, guiTop + GUI_HEIGHT + 6, 0xFFFFFFFF);
        }

        super.drawScreen(mx, my, partialTicks);

        List<String> hover = null;
        if (btnBind != null && mx >= btnBind.xPosition && my >= btnBind.yPosition
            && mx < btnBind.xPosition + btnBind.width && my < btnBind.yPosition + btnBind.height) {
            hover = new ArrayList<String>();
            hover.add(EnumChatFormatting.AQUA + I18n.format("gui.klaymore.bind.tooltip"));
            hover.add(EnumChatFormatting.GRAY + I18n.format("gui.klaymore.bind.tooltip2"));
        } else if (btnUnbind != null && mx >= btnUnbind.xPosition && my >= btnUnbind.yPosition
            && mx < btnUnbind.xPosition + btnUnbind.width && my < btnUnbind.yPosition + btnUnbind.height) {
            hover = new ArrayList<String>();
            hover.add(EnumChatFormatting.YELLOW + I18n.format("gui.klaymore.unbind.tooltip"));
        } else if (btnRefresh != null && mx >= btnRefresh.xPosition && my >= btnRefresh.yPosition
            && mx < btnRefresh.xPosition + btnRefresh.width && my < btnRefresh.yPosition + btnRefresh.height) {
            hover = new ArrayList<String>();
            hover.add(EnumChatFormatting.AQUA + I18n.format("gui.klaymore.refresh.tooltip"));
        }
        if (hover != null && !hover.isEmpty()) drawHoveringText(hover, mx, my, fontRendererObj);
    }

    private void drawPanel(int x, int y, int w, int h) {
        drawRect(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF101010);
        drawRect(x, y, x + w, y + h, 0xFF3C3C3C);
        drawRect(x + 2, y + 2, x + w - 2, y + h - 2, 0xFF4A4A4A);
        GL11.glColor4f(1, 1, 1, 1);
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    public boolean doesGuiPauseGame() { return false; }

    // =================================================================
    // 内部：列表项数据
    // =================================================================
    private static final class FileEntry {
        final boolean isDir;
        final boolean isUpLabel;
        final File file;
        final String label;
        FileEntry(boolean isDir, File file, String label) {
            this.isDir = isDir;
            this.file = file;
            this.label = label;
            this.isUpLabel = UP_LABEL.equals(label);
        }
    }

    // =================================================================
    // 内部：GuiSlot
    // =================================================================
    private final class FileSlot extends GuiSlot {

        final int panelLeft;
        final int panelTop;
        final int panelBottom;
        final int panelWidth;
        final int entryHeight;

        FileSlot(Minecraft mc, int width, int height, int top, int bottom, int left, int entryHeight) {
            super(mc, width, height, top, bottom, entryHeight);
            this.panelLeft = left;
            this.panelTop = top;
            this.panelBottom = bottom;
            this.panelWidth = width;
            this.entryHeight = entryHeight;
            this.left = left;
            this.right = left + width;
        }

        @Override
        public int getListWidth() {
            return panelWidth - 12;
        }

        @Override
        protected int getScrollBarX() {
            return panelLeft + panelWidth - 6;
        }

        public void renderContentOnly(int mx, int my, float partialTicks) {
            Tessellator tessellator = Tessellator.instance;
            int l = getScrollBarX();
            int i1 = l + 6;
            int l1 = this.left + this.width / 2 - getListWidth() / 2 + 2;
            int i2 = this.top + 4 - getAmountScrolled();

            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_FOG);

            drawContainerBackground(tessellator);
            this.drawSelectionBox(l1, i2, mx, my);

            GL11.glDisable(GL11.GL_DEPTH_TEST);
            byte b0 = 4;
            GL11.glEnable(GL11.GL_BLEND);
            net.minecraft.client.renderer.OpenGlHelper.glBlendFunc(770, 771, 0, 1);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glShadeModel(GL11.GL_SMOOTH);
            GL11.glDisable(GL11.GL_TEXTURE_2D);

            tessellator.startDrawingQuads();
            tessellator.setColorRGBA_I(0, 0);
            tessellator.addVertexWithUV((double)this.left, (double)(this.top + b0), 0.0D, 0.0D, 1.0D);
            tessellator.addVertexWithUV((double)this.right, (double)(this.top + b0), 0.0D, 1.0D, 1.0D);
            tessellator.setColorRGBA_I(0, 255);
            tessellator.addVertexWithUV((double)this.right, (double)this.top, 0.0D, 1.0D, 0.0D);
            tessellator.addVertexWithUV((double)this.left, (double)this.top, 0.0D, 0.0D, 0.0D);
            tessellator.draw();

            tessellator.startDrawingQuads();
            tessellator.setColorRGBA_I(0, 255);
            tessellator.addVertexWithUV((double)this.left, (double)this.bottom, 0.0D, 0.0D, 1.0D);
            tessellator.addVertexWithUV((double)this.right, (double)this.bottom, 0.0D, 1.0D, 1.0D);
            tessellator.setColorRGBA_I(0, 0);
            tessellator.addVertexWithUV((double)this.right, (double)(this.bottom - b0), 0.0D, 1.0D, 0.0D);
            tessellator.addVertexWithUV((double)this.left, (double)(this.bottom - b0), 0.0D, 0.0D, 0.0D);
            tessellator.draw();

            int maxScroll = this.func_148135_f();
            if (maxScroll > 0) {
                int track = this.bottom - this.top;
                int thumbHeight = track * track / getContentHeight();
                if (thumbHeight < 32) thumbHeight = 32;
                if (thumbHeight > track - 8) thumbHeight = track - 8;
                int thumbY = getAmountScrolled() * (track - thumbHeight) / maxScroll + this.top;
                if (thumbY < this.top) thumbY = this.top;

                tessellator.startDrawingQuads();
                tessellator.setColorRGBA_I(0, 255);
                tessellator.addVertexWithUV((double)l, (double)this.bottom, 0.0D, 0.0D, 1.0D);
                tessellator.addVertexWithUV((double)i1, (double)this.bottom, 0.0D, 1.0D, 1.0D);
                tessellator.addVertexWithUV((double)i1, (double)this.top, 0.0D, 1.0D, 0.0D);
                tessellator.addVertexWithUV((double)l, (double)this.top, 0.0D, 0.0D, 0.0D);
                tessellator.draw();

                tessellator.startDrawingQuads();
                tessellator.setColorRGBA_I(8421504, 255);
                tessellator.addVertexWithUV((double)l, (double)(thumbY + thumbHeight), 0.0D, 0.0D, 1.0D);
                tessellator.addVertexWithUV((double)i1, (double)(thumbY + thumbHeight), 0.0D, 1.0D, 1.0D);
                tessellator.addVertexWithUV((double)i1, (double)thumbY, 0.0D, 1.0D, 0.0D);
                tessellator.addVertexWithUV((double)l, (double)thumbY, 0.0D, 0.0D, 0.0D);
                tessellator.draw();

                tessellator.startDrawingQuads();
                tessellator.setColorRGBA_I(12632256, 255);
                tessellator.addVertexWithUV((double)l, (double)(thumbY + thumbHeight - 1), 0.0D, 0.0D, 1.0D);
                tessellator.addVertexWithUV((double)(i1 - 1), (double)(thumbY + thumbHeight - 1), 0.0D, 1.0D, 1.0D);
                tessellator.addVertexWithUV((double)(i1 - 1), (double)thumbY, 0.0D, 1.0D, 0.0D);
                tessellator.addVertexWithUV((double)l, (double)thumbY, 0.0D, 0.0D, 0.0D);
                tessellator.draw();
            }

            this.func_148142_b(mx, my);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glShadeModel(GL11.GL_FLAT);
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_BLEND);
        }

        @Override
        protected int getSize() { return currentEntries.size(); }

        @Override
        protected void elementClicked(int idx, boolean doubleClick, int mx, int my) {
            selectedIndex = idx;
            onEntryChosen(idx);
        }

        @Override
        protected boolean isSelected(int idx) { return idx == selectedIndex; }

        @Override
        protected void drawBackground() {
        }

        @Override
        protected void drawContainerBackground(Tessellator tessellator) {
            drawRect(panelLeft + 1, panelTop + 1, panelLeft + panelWidth - 1, panelBottom - 1, 0xFF585858);
            GL11.glColor4f(1, 1, 1, 1);
        }

        @Override
        protected void drawSlot(int idx, int x, int y, int slotHeight, Tessellator tess, int mx, int my) {
            if (idx < 0 || idx >= currentEntries.size()) return;
            FileEntry entry = currentEntries.get(idx);
            int color = 0xE0E0E0;
            String prefix = "  ";
            if (entry.isUpLabel) {
                color = 0xFFC97B;
                prefix = "[..] ";
            } else if (entry.isDir) {
                color = 0x77CCFF;
                prefix = "[DIR] ";
            } else {
                color = 0xFFFFFF;
                prefix = "       ";
            }
            String text = prefix + entry.label;
            int max = panelWidth - 60;
            if (fontRendererObj.getStringWidth(text) > max) {
                text = fontRendererObj.trimStringToWidth(text, max - 10) + "...";
            }
            if (isSelected(idx)) {
                drawRect(panelLeft + 2, y - 2, panelLeft + panelWidth - 8,
                    y + entryHeight - 2, 0xFF2E6DDA);
            }
            GuiWandScriptBind.this.drawString(fontRendererObj, text,
                panelLeft + 6, y + 2, color);

            if (entry.file != null && !entry.isDir && !entry.isUpLabel) {
                String sz = humanSize(entry.file.length());
                int w = fontRendererObj.getStringWidth(sz);
                GuiWandScriptBind.this.drawString(fontRendererObj, sz,
                    panelLeft + panelWidth - 16 - w, y + 2, 0x909090);
            }
        }
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + "K";
        return String.format("%.1fM", bytes / (1024.0 * 1024.0));
    }
}
