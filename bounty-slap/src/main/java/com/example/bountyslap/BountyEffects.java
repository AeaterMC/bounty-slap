package com.example.bountyslap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Two things live here:
 *  1. The poster "slap" (3 seconds max; a newer bounty restarts it from scratch).
 *  2. Small notifications in the top-right corner with the player's head beside the text.
 * No full-screen flashing is used anywhere.
 */
final class BountyEffects {
    static final long SLAP_MS = 3000L;             // hard maximum for the poster animation
    private static final long IMPACT_MS = 140L;    // poster hits the screen
    private static final long OUT_START_MS = 2700L; // poster starts peeling away
    private static final long TOAST_MS = 4000L;
    private static final int MAX_TOASTS = 5;
    private static final int TEX_W = 384, TEX_H = 512;

    private static final Identifier POSTER = Identifier.fromNamespaceAndPath(
            BountySlapClient.MOD_ID, "textures/gui/bounty_poster.png");
    private static final Identifier MONEY = Identifier.fromNamespaceAndPath(
            BountySlapClient.MOD_ID, "textures/gui/money.png");
    private static final Random RNG = new Random();

    // ---- poster state ----
    private static long slapStart = -1L;
    private static String slapName = "";
    private static String slapAmount = "";
    private static float slapTilt;
    private static boolean slapSoundPlayed;
    private static boolean slapRing;

    // ---- toast state ----
    private static final class Toast {
        String name;
        String amount;
        long born;
    }

    private static final List<Toast> toasts = new ArrayList<>();

    private BountyEffects() { }

    // ------------------------------------------------------------------ entry points

    static void onBounty(String name, double amount) {
        String amt = BountySlapClient.fmtAmount(amount);
        if (BountySlapClient.toastsEnabled) addToast(name, amt);
        if (BountySlapClient.slapEnabled && amount >= BountySlapClient.SLAP_THRESHOLD) {
            startSlap(name, amt, amount >= BountySlapClient.RING_THRESHOLD);
        }
    }

    static void clearSlap() {
        slapStart = -1L;
    }

    static void clearToasts() {
        toasts.clear();
    }

    private static void startSlap(String name, String amt, boolean ring) {
        // Overwrites any slap that is still playing: the newest bounty always wins.
        slapName = name;
        slapAmount = amt;
        slapStart = System.currentTimeMillis();
        slapTilt = -0.04f + (RNG.nextFloat() - 0.5f) * 0.10f;
        slapSoundPlayed = false;
        slapRing = ring;
    }

    private static void addToast(String name, String amt) {
        Toast t = new Toast();
        t.name = name;
        t.amount = amt;
        t.born = System.currentTimeMillis();
        toasts.add(0, t);
        while (toasts.size() > MAX_TOASTS) toasts.remove(toasts.size() - 1);
    }

    static void render(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        renderSlap(g, mc);
        renderToasts(g, mc);
    }

    // ------------------------------------------------------------------ poster slap

    private static void renderSlap(GuiGraphics g, Minecraft mc) {
        if (slapStart < 0L) return;
        long el = System.currentTimeMillis() - slapStart;
        if (el >= SLAP_MS) {
            slapStart = -1L;
            return;
        }

        int w = g.guiWidth(), h = g.guiHeight();
        float ph = h * (slapRing ? 0.56f : 0.62f);   // a bit smaller so the money ring fits
        float pw = ph * TEX_W / TEX_H;
        float cx = w / 2f, cy = h * 0.57f;

        // --- motion: slam in from huge, shake + squash on impact, hold, then peel away ---
        float scale, rot, ox = 0f, oy = 0f;
        if (el < IMPACT_MS) {
            float u = el / (float) IMPACT_MS;
            float e = u * u * u;                         // accelerating towards the screen
            scale = 3.2f + (1f - 3.2f) * e;
            rot = slapTilt + (1f - e) * 0.5f;
        } else {
            long a = el - IMPACT_MS;
            float decay = Math.max(0f, 1f - a / 380f);
            float sh = 14f * decay * decay;
            ox = (float) Math.sin(a * 0.09) * sh;
            oy = (float) Math.cos(a * 0.13) * sh * 0.7f;
            scale = 1f + 0.10f * decay * (float) Math.cos(a * 0.05);
            rot = slapTilt;
        }
        if (el >= OUT_START_MS) {
            float u = (el - OUT_START_MS) / (float) (SLAP_MS - OUT_START_MS);
            scale *= Math.max(0f, 1f - u * u);
            rot += u * 0.35f;
        }

        // --- impact sound, once ---
        if (!slapSoundPlayed && el >= IMPACT_MS) {
            slapSoundPlayed = true;
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_ATTACK_STRONG, 0.6f, 1.2f));
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.ANVIL_LAND, 1.5f, 0.35f));
        }

        // --- impact burst lines (behind the poster) ---
        if (el >= IMPACT_MS && el < IMPACT_MS + 260L) {
            float u = (el - IMPACT_MS) / 260f;
            float r0 = ph * (0.42f + 0.30f * u);
            float r1 = r0 + ph * 0.14f * (1f - u);
            int a = (int) (220 * (1f - u));
            for (int i = 0; i < 14; i++) {
                g.pose().pushMatrix();
                g.pose().translate(cx, cy);
                g.pose().rotate(i * (6.2832f / 14f) + 0.2f);
                g.fill((int) r0, -3, (int) r1, 3, (a << 24) | 0xFFE070);
                g.pose().popMatrix();
            }
        }

        // --- the poster ---
        g.pose().pushMatrix();
        g.pose().translate(cx + ox, cy + oy);
        g.pose().rotate(rot);
        g.pose().scale(scale, scale);
        if (slapRing) drawMoneyRing(g, pw, ph, el);   // behind the poster, moves/shakes with it
        g.fill((int) (-pw / 2f) + 6, (int) (-ph / 2f) + 8, (int) (pw / 2f) + 6, (int) (ph / 2f) + 8, 0x66000000);
        g.blit(RenderPipelines.GUI_TEXTURED, POSTER,
                (int) (-pw / 2f), (int) (-ph / 2f), 0f, 0f, (int) pw, (int) ph, TEX_W, TEX_H, TEX_W, TEX_H);
        g.pose().popMatrix();

        // --- "<NAME> Added <AMOUNT> to the Bounty!" above the poster ---
        if (el >= IMPACT_MS) {
            String[] parts = {slapName, " Added ", slapAmount, " to the Bounty!"};
            int[] rgb = {0xFFC83A, 0xFFFFFF, 0x55FF55, 0xFFFFFF};
            int total = 0;
            for (String p : parts) total += mc.font.width(p);

            float tp = Math.min(1f, (el - IMPACT_MS) / 120f);
            float ts = Math.min(2.0f, (w * 0.94f) / Math.max(1, total));
            ts *= 1.35f - 0.35f * tp;                      // pops in slightly oversized
            float alphaF = el >= OUT_START_MS ? Math.max(0f, 1f - (el - OUT_START_MS) / (float) (SLAP_MS - OUT_START_MS)) : 1f;
            float ringExtra = slapRing ? ph * 0.17f : 0f;
            float textTop = Math.max(6f, cy - ph / 2f - ringExtra - 10f - 9f * ts);

            g.pose().pushMatrix();
            g.pose().translate(w / 2f, textTop);
            g.pose().scale(ts, ts);
            g.fill(-total / 2 - 6, -3, total / 2 + 6, 11, ((int) (0x99 * alphaF) << 24));
            drawSegments(g, mc, parts, rgb, -total / 2, 0, (int) (255 * alphaF));
            g.pose().popMatrix();
        }
    }

    // ------------------------------------------------------------------ money ring ($100M+)

    /**
     * Two rings of bills hugging the poster (a rounded rectangle path), travelling in opposite
     * directions. Called inside the poster's transform, so it slams in, shakes and peels away with it.
     * About 60 small textured draws per frame.
     */
    private static void drawMoneyRing(GuiGraphics g, float pw, float ph, long el) {
        float t = el / 1000f;
        float bw = ph * 0.22f;
        float bh = bw / 2f;
        float[] pt = new float[3];
        for (int ring = 0; ring < 2; ring++) {
            float off = bh * (ring == 0 ? 0.30f : 0.95f);
            float size = ring == 0 ? 1f : 0.85f;
            float a = pw / 2f + off, b = ph / 2f + off;
            float r = Math.min(a, b) * 0.35f;
            float total = 2f * (2f * (a - r)) + 2f * (2f * (b - r)) + 4f * 1.5708f * r;
            int n = Math.max(12, Math.round(total / (bw * size * 0.62f)));
            float dir = ring == 0 ? 1f : -1f;
            float shift = dir * t * total * 0.22f;
            int w = Math.max(6, (int) (bw * size));
            int hh = Math.max(3, w / 2);
            for (int i = 0; i < n; i++) {
                ringPoint(i * total / n + shift, a, b, r, pt);
                g.pose().pushMatrix();
                g.pose().translate(pt[0], pt[1]);
                g.pose().rotate(pt[2]);
                g.blit(RenderPipelines.GUI_TEXTURED, MONEY, -w / 2, -hh / 2, 0f, 0f, w, hh, 256, 128, 256, 128);
                g.pose().popMatrix();
            }
        }
    }

    /**
     * Point at distance s along a clockwise rounded-rectangle path (half sizes a, b; corner radius r).
     * out = {x, y, tangent angle in radians}.
     */
    private static void ringPoint(float s, float a, float b, float r, float[] out) {
        final float halfPi = 1.5707964f;
        float lh = 2f * (a - r), lv = 2f * (b - r), la = halfPi * r;
        float total = 2f * lh + 2f * lv + 4f * la;
        s = ((s % total) + total) % total;
        if (s < lh) { out[0] = -(a - r) + s; out[1] = -b; out[2] = 0f; return; }
        s -= lh;
        if (s < la) {
            float f = s / r;
            out[0] = (a - r) + r * (float) Math.sin(f);
            out[1] = -(b - r) - r * (float) Math.cos(f);
            out[2] = f;
            return;
        }
        s -= la;
        if (s < lv) { out[0] = a; out[1] = -(b - r) + s; out[2] = halfPi; return; }
        s -= lv;
        if (s < la) {
            float f = s / r;
            out[0] = (a - r) + r * (float) Math.cos(f);
            out[1] = (b - r) + r * (float) Math.sin(f);
            out[2] = f + halfPi;
            return;
        }
        s -= la;
        if (s < lh) { out[0] = (a - r) - s; out[1] = b; out[2] = 3.1415927f; return; }
        s -= lh;
        if (s < la) {
            float f = s / r;
            out[0] = -(a - r) - r * (float) Math.sin(f);
            out[1] = (b - r) + r * (float) Math.cos(f);
            out[2] = 3.1415927f + f;
            return;
        }
        s -= la;
        if (s < lv) { out[0] = -a; out[1] = (b - r) - s; out[2] = 3f * halfPi; return; }
        s -= lv;
        float f = s / r;
        out[0] = -(a - r) - r * (float) Math.cos(f);
        out[1] = -(b - r) - r * (float) Math.sin(f);
        out[2] = 3f * halfPi + f;
    }

    // ------------------------------------------------------------------ notifications

    private static void renderToasts(GuiGraphics g, Minecraft mc) {
        if (toasts.isEmpty()) return;
        long now = System.currentTimeMillis();
        int w = g.guiWidth();
        int slot = 0;

        for (int i = 0; i < toasts.size(); ) {
            Toast t = toasts.get(i);
            long age = now - t.born;
            if (age >= TOAST_MS) {
                toasts.remove(i);
                continue;
            }
            i++;

            String[] parts = {t.name, " Added ", t.amount, " to the Bounty!"};
            int[] rgb = {0xFFC83A, 0xFFFFFF, 0x55FF55, 0xFFFFFF};
            int textW = 0;
            for (String p : parts) textW += mc.font.width(p);

            final int head = 20, pad = 4, height = 28;
            int width = pad + head + 6 + textW + 8;

            // slide in from the right, slide back out at the end
            float in = Math.min(1f, age / 250f);
            float out = Math.min(1f, (TOAST_MS - age) / 300f);
            float p = Math.min(in, out);
            float e = 1f - (1f - p) * (1f - p) * (1f - p);
            int slide = (int) ((1f - e) * (width + 12));

            int x = w - width - 6 + slide;
            int y = 6 + slot * (height + 4);
            slot++;

            g.fill(x, y, x + width, y + height, 0xE0101018);              // background
            g.fill(x, y, x + width, y + 1, 0xFFFFB400);                    // gold border
            g.fill(x, y + height - 1, x + width, y + height, 0xFFFFB400);
            g.fill(x, y, x + 1, y + height, 0xFFFFB400);
            g.fill(x + width - 1, y, x + width, y + height, 0xFFFFB400);

            drawHead(g, mc, t.name, x + pad, y + pad, head);               // head directly left of the text
            drawSegments(g, mc, parts, rgb, x + pad + head + 6, y + (height - 9) / 2, 255);
        }
    }

    /** The player's face from the tab list; a "?" tile if that player isn't in the tab list. */
    private static void drawHead(GuiGraphics g, Minecraft mc, String name, int x, int y, int size) {
        PlayerInfo info = mc.getConnection() != null ? mc.getConnection().getPlayerInfo(name) : null;
        if (info != null) {
            PlayerFaceRenderer.draw(g, info.getSkin(), x, y, size);
        } else {
            g.fill(x, y, x + size, y + size, 0xFF3A2C1C);
            g.fill(x + 1, y + 1, x + size - 1, y + size - 1, 0xFF8A6A48);
            g.drawCenteredString(mc.font, "?", x + size / 2, y + size / 2 - 4, 0xFFFFFFFF);
        }
    }

    /** Draws consecutive differently-colored pieces of text, left-aligned at x. */
    private static void drawSegments(GuiGraphics g, Minecraft mc, String[] parts, int[] rgb, int x, int y, int alpha) {
        int cx = x;
        for (int i = 0; i < parts.length; i++) {
            g.drawString(mc.font, parts[i], cx, y, (alpha << 24) | rgb[i]);
            cx += mc.font.width(parts[i]);
        }
    }
}
