package com.example.bountyslap;

import com.mojang.logging.LogUtils;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Top 3 single bounty additions ever seen (saved in the config folder), and the code that draws
 * the full-screen leaderboard. Real additions are recorded; /bountytest never touches the board.
 */
final class BountyBoard {
    static final class Entry {
        final String name;
        final double amount;
        PlayerInfo info;           // for the head; may be null (then looked up in the tab list when drawn)

        Entry(String name, double amount, PlayerInfo info) {
            this.name = name;
            this.amount = amount;
            this.info = info;
        }
    }

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Identifier BOARD = Identifier.fromNamespaceAndPath(
            BountySlapClient.MOD_ID, "textures/gui/leaderboard.png");
    private static final int IMG_W = 666, IMG_H = 375;

    // Box positions in leaderboard.png pixels {x0, y0, x1, y1}, measured from the reference image.
    // Slot 0 = #1 (center podium), 1 = #2 (left podium), 2 = #3 (right podium).
    private static final int[][] HEAD = {{297, 180, 361, 243}, {181, 201, 245, 265}, {414, 213, 481, 277}};
    private static final int[][] NAME = {{297, 251, 361, 267}, {179, 271, 246, 290}, {414, 282, 481, 300}};
    private static final int BOX_GRAY = 0xFFC8D1DA;

    private static final List<Entry> top = new ArrayList<>();
    private static boolean pendingOpen;
    private static boolean pendingDemo;

    private BountyBoard() { }

    // ------------------------------------------------------------------ data

    static void record(String name, double amount, PlayerInfo info) {
        top.add(new Entry(name, amount, info));
        sortAndTrim();
        save();
    }

    private static void sortAndTrim() {
        top.sort((a, b) -> Double.compare(b.amount, a.amount));
        while (top.size() > 3) top.remove(top.size() - 1);
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("bountyslap_leaderboard.txt");
    }

    static void load() {
        top.clear();
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String[] p = line.split("\t");
                if (p.length != 2) continue;
                try {
                    top.add(new Entry(p[0], Double.parseDouble(p[1]), null));
                } catch (NumberFormatException ignored) {
                    // skip a damaged line
                }
            }
            sortAndTrim();
        } catch (IOException e) {
            LOGGER.warn("[bountyslap] could not read the leaderboard file", e);
        }
    }

    private static void save() {
        try {
            List<String> lines = new ArrayList<>();
            for (Entry e : top) {
                lines.add(e.name + "\t" + String.format(Locale.ROOT, "%.0f", e.amount));
            }
            Files.write(file(), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("[bountyslap] could not save the leaderboard file", e);
        }
    }

    // ------------------------------------------------------------------ opening

    /**
     * Asks for the leaderboard to open on the next tick. (Opening it straight from a chat command
     * would be undone, because the chat screen closes itself right after running the command.)
     */
    static void requestOpen(boolean demo) {
        pendingOpen = true;
        pendingDemo = demo;
    }

    static void tick(Minecraft mc) {
        if (pendingOpen && mc.screen == null && mc.player != null) {
            pendingOpen = false;
            mc.setScreen(new BountyBoardScreen(pendingDemo ? demoEntries(mc) : top));
        }
    }

    private static List<Entry> demoEntries(Minecraft mc) {
        String me = mc.player != null ? mc.player.getName().getString() : "Steve";
        List<Entry> l = new ArrayList<>();
        l.add(new Entry(me, 1_300_000_000d, null));
        l.add(new Entry("Steve", 250_000_000d, null));
        l.add(new Entry("Alex", 50_000_000d, null));
        return l;
    }

    // ------------------------------------------------------------------ drawing

    static void render(GuiGraphics g, int w, int h, List<Entry> entries) {
        Minecraft mc = Minecraft.getInstance();
        g.blit(RenderPipelines.GUI_TEXTURED, BOARD, 0, 0, 0f, 0f, w, h, IMG_W, IMG_H, IMG_W, IMG_H);  // whole screen

        float sx = w / (float) IMG_W, sy = h / (float) IMG_H;

        for (int slot = 0; slot < 3; slot++) {
            Entry e = slot < entries.size() ? entries.get(slot) : null;

            int hx0 = Math.round(HEAD[slot][0] * sx), hy0 = Math.round(HEAD[slot][1] * sy);
            int hx1 = Math.round(HEAD[slot][2] * sx), hy1 = Math.round(HEAD[slot][3] * sy);
            int nx0 = Math.round(NAME[slot][0] * sx), ny0 = Math.round(NAME[slot][1] * sy);
            int nx1 = Math.round(NAME[slot][2] * sx), ny1 = Math.round(NAME[slot][3] * sy);
            int hw = hx1 - hx0, hh = hy1 - hy0, nw = nx1 - nx0, nh = ny1 - ny0;


            PlayerInfo info = e != null ? infoFor(mc, e) : null;
            if (info != null) {
                int size = Math.min(hw, hh);          // head fills the whole box
                int hx = hx0 + (hw - size) / 2;
                int hy = hy0 + (hh - size) / 2;
                PlayerFaceRenderer.draw(g, info.getSkin(), hx, hy, size);
            } else {
                drawCentered(g, mc, "?", hx0 + hw / 2f, hy0 + hh * 0.5f,
                        hw * 0.5f, hh * 0.5f, 8f, 0xFF5A3A12);
            }

            if (e != null) {
                drawCentered(g, mc, BountySlapClient.fmtAmount(e.amount), nx0 + nw / 2f, ny1 + nh * 0.9f + 4f,
                        nw * 1.1f, nh * 0.9f, 2.4f, 0xFF14702B);   // amount just under the name tag
                drawCentered(g, mc, e.name, nx0 + nw / 2f, ny0 + nh / 2f, nw * 0.94f, nh * 0.80f, 2.2f, 0xFF3A2406);
            } else {
                drawCentered(g, mc, "---", nx0 + nw / 2f, ny0 + nh / 2f, nw * 0.94f, nh * 0.80f, 2.2f, 0xFF3A2406);
            }
        }
    }

    /** The big red X in the top-right corner. */
    static void drawCloseX(GuiGraphics g, int x, int y, int size, boolean hover) {
        g.fill(x, y, x + size, y + size, hover ? 0xFFFF5A5A : 0xFFD23B3B);
        g.fill(x, y, x + size, y + 2, 0xFF7A1010);
        g.fill(x, y + size - 2, x + size, y + size, 0xFF7A1010);
        g.fill(x, y, x + 2, y + size, 0xFF7A1010);
        g.fill(x + size - 2, y, x + size, y + size, 0xFF7A1010);

        int cx = x + size / 2, cy = y + size / 2;
        int len = (int) (size * 0.34f);
        int th = Math.max(2, size / 12);
        for (int dir = -1; dir <= 1; dir += 2) {
            g.pose().pushMatrix();
            g.pose().translate(cx, cy);
            g.pose().rotate(dir * 0.7853982f);
            g.fill(-len, -th, len, th, 0xFFFFFFFF);
            g.pose().popMatrix();
        }
    }

    private static PlayerInfo infoFor(Minecraft mc, Entry e) {
        if (e.info != null) return e.info;
        PlayerInfo i = mc.getConnection() != null ? mc.getConnection().getPlayerInfo(e.name) : null;
        if (i != null) e.info = i;
        return i;
    }

    /** Text centered at (cx, cy), scaled to fit inside maxW x maxH (never bigger than maxScale). */
    private static void drawCentered(GuiGraphics g, Minecraft mc, String s, float cx, float cy,
                                     float maxW, float maxH, float maxScale, int color) {
        int tw = Math.max(1, mc.font.width(s));
        float scale = Math.min(maxScale, Math.min(maxW / tw, maxH / 8f));
        g.pose().pushMatrix();
        g.pose().translate(cx, cy);
        g.pose().scale(scale, scale);
        g.drawString(mc.font, s, -tw / 2, -4, color);
        g.pose().popMatrix();
    }
}
