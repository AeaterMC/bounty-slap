package com.example.bountyslap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Three things live here:
 *  1. The poster "slap" (3 seconds max; a newer bounty restarts it from scratch), with the
 *     added amount written into the poster's blank box. $100M+ adds the spinning coin ring.
 *  2. The super-rare Pirate Token (0.05% chance on any slap) which replaces the poster for 7.5 seconds.
 *  3. Small notifications in the top-right corner with the player's head beside the text.
 */
final class BountyEffects {
    static final long SLAP_MS = 3000L;               // poster animation
    static final long TOKEN_MS = 7500L;              // Pirate Token animation
    static final float TOKEN_CHANCE = 0.0005f;       // 0.05%
    private static final long IMPACT_MS = 140L;      // poster / token hits the screen
    private static final long FADE_MS = 300L;        // peel-away at the very end
    private static final long TOAST_MS = 4000L;
    private static final int MAX_TOASTS = 5;

    private static final int TEX_W = 480, TEX_H = 640;          // poster texture
    private static final int MONEY_TEX_SIZE = 500;              // spinning coin/revolver ring texture
    private static final int TOKEN_W = 433, TOKEN_H = 577;      // pirate token texture
    private static final float POSTER_SCALE = 0.8f;             // poster is 80% of its old size

    // Where the amount goes on the poster (fractions of the poster image, measured from the reference).
    private static final float AMT_CX = 0.4919f, AMT_CY = 0.9017f, AMT_W = 0.6968f, AMT_H = 0.0734f;
    private static final int AMT_COLOR = 0xFF545454;            // same gray as the poster lettering

    private static final Identifier POSTER = Identifier.fromNamespaceAndPath(
            BountySlapClient.MOD_ID, "textures/gui/bounty_poster.png");
    private static final Identifier MONEY = Identifier.fromNamespaceAndPath(
            BountySlapClient.MOD_ID, "textures/gui/money.png");
    private static final Identifier TOKEN = Identifier.fromNamespaceAndPath(
            BountySlapClient.MOD_ID, "textures/gui/token.png");
    private static final Random RNG = new Random();

    /** When (ms after the slap starts) each Pirate Token sound plays. */
    private static final long[] TOKEN_SOUND_AT = {300L, 900L, 1600L, 2500L, 3500L, 4600L, 5800L};

    // ---- poster / token state ----
    private static long slapStart = -1L;
    private static String slapName = "";
    private static String slapAmount = "";
    private static float slapTilt;
    private static boolean slapSoundPlayed;
    private static boolean slapRing;
    private static boolean slapToken;
    private static int tokenSoundIdx;

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
            boolean token = RNG.nextFloat() < TOKEN_CHANCE;      // 0.05% Pirate Token
            startSlap(name, amt, amount >= BountySlapClient.RING_THRESHOLD, token);
        }
    }

    /** /bountytoken: forces the Pirate Token animation so you can preview it. */
    static void testToken(String name, double amount) {
        startSlap(name, BountySlapClient.fmtAmount(amount), false, true);
    }

    static void clearSlap() {
        slapStart = -1L;
    }

    static void clearToasts() {
        toasts.clear();
    }

    private static void startSlap(String name, String amt, boolean ring, boolean token) {
        // Overwrites any slap that is still playing: the newest bounty always wins.
        slapName = name;
        slapAmount = amt;
        slapStart = System.currentTimeMillis();
        slapTilt = -0.04f + (RNG.nextFloat() - 0.5f) * 0.10f;
        slapSoundPlayed = false;
        slapToken = token;
        slapRing = ring && !token;
        tokenSoundIdx = 0;
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

    // ------------------------------------------------------------------ poster slap / pirate token

    private static void renderSlap(GuiGraphics g, Minecraft mc) {
        if (slapStart < 0L) return;
        long el = System.currentTimeMillis() - slapStart;
        long dur = slapToken ? TOKEN_MS : SLAP_MS;
        if (el >= dur) {
            slapStart = -1L;
            return;
        }
        long outStart = dur - FADE_MS;

        int w = g.guiWidth(), h = g.guiHeight();
        float ph, pw;
        if (slapToken) {
            ph = h * 0.50f;
            pw = ph * TOKEN_W / TOKEN_H;
        } else {
            ph = h * (slapRing ? 0.56f : 0.62f) * POSTER_SCALE;   // a bit smaller so the coin ring fits
            pw = ph * TEX_W / TEX_H;
        }
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
        if (slapToken && el >= IMPACT_MS) {              // the token floats, pulses and sways while it is up
            float t = el / 1000f;
            oy += (float) Math.sin(t * 2.2f) * h * 0.012f;
            scale *= 1f + 0.03f * (float) Math.sin(t * 4.0f);
            rot += (float) Math.sin(t * 1.3f) * 0.05f;
        }
        if (el >= outStart) {
            float u = (el - outStart) / (float) FADE_MS;
            scale *= Math.max(0f, 1f - u * u);
            rot += u * 0.35f;
        }

        // --- impact sound, once (the slap sound is unchanged) ---
        if (!slapSoundPlayed && el >= IMPACT_MS) {
            slapSoundPlayed = true;
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_ATTACK_STRONG, 0.6f, 1.2f));
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.ANVIL_LAND, 1.5f, 0.35f));
        }
        if (slapToken) playTokenSounds(mc, el);

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

        // --- the poster (or the Pirate Token) ---
        g.pose().pushMatrix();
        g.pose().translate(cx + ox, cy + oy);
        g.pose().rotate(rot);
        g.pose().scale(scale, scale);
        if (slapToken) {
            drawSunburst(g, ph, el);
            int tw = (int) pw, th = (int) ph;
            g.blit(RenderPipelines.GUI_TEXTURED, TOKEN,
                    -tw / 2, -th / 2, 0f, 0f, tw, th, TOKEN_W, TOKEN_H, TOKEN_W, TOKEN_H);
        } else {
            if (slapRing) drawMoneyRing(g, pw, ph, el);   // behind the poster, moves/shakes with it
            g.fill((int) (-pw / 2f) + 6, (int) (-ph / 2f) + 8, (int) (pw / 2f) + 6, (int) (ph / 2f) + 8, 0x66000000);
            g.blit(RenderPipelines.GUI_TEXTURED, POSTER,
                    (int) (-pw / 2f), (int) (-ph / 2f), 0f, 0f, (int) pw, (int) ph, TEX_W, TEX_H, TEX_W, TEX_H);
            drawPosterAmount(g, mc, pw, ph);              // the added amount, written into the blank box
        }
        g.pose().popMatrix();

        // --- "<NAME> Added <AMOUNT> to the Bounty!" above ---
        if (el >= IMPACT_MS) {
            String[] parts = {slapName, " Added ", slapAmount, " to the Bounty!"};
            int[] rgb = {0xFFC83A, 0xFFFFFF, 0x55FF55, 0xFFFFFF};
            int total = 0;
            for (String p : parts) total += mc.font.width(p);

            float tp = Math.min(1f, (el - IMPACT_MS) / 120f);
            float ts = Math.min(2.0f, (w * 0.94f) / Math.max(1, total));
            ts *= 1.35f - 0.35f * tp;                      // pops in slightly oversized
            float alphaF = el >= outStart ? Math.max(0f, 1f - (el - outStart) / (float) FADE_MS) : 1f;
            float extra = slapToken ? ph * 0.45f : (slapRing ? ph * 0.3f : 0f);
            float textTop = Math.max(6f, cy - ph / 2f - extra - 10f - 9f * ts);

            g.pose().pushMatrix();
            g.pose().translate(w / 2f, textTop);
            g.pose().scale(ts, ts);
            g.fill(-total / 2 - 6, -3, total / 2 + 6, 11, ((int) (0x99 * alphaF) << 24));
            drawSegments(g, mc, parts, rgb, -total / 2, 0, (int) (255 * alphaF));
            g.pose().popMatrix();

            if (slapToken) drawTokenLabel(g, mc, w, cy + ph / 2f + 6f, el, alphaF);
        }
    }

    /** Writes the amount into the poster's blank box, same spot the "<AMOUNT>" placeholder was. */
    private static void drawPosterAmount(GuiGraphics g, Minecraft mc, float pw, float ph) {
        String s = slapAmount;
        int tw = Math.max(1, mc.font.width(s));
        // letters are ~7 font-pixels tall; never wider than the box
        float scale = Math.min(AMT_H * ph / 7f, AMT_W * pw / (tw + 1f));
        g.pose().pushMatrix();
        g.pose().translate((AMT_CX - 0.5f) * pw, (AMT_CY - 0.5f) * ph);
        g.pose().scale(scale, scale);
        g.drawString(mc.font, s, -tw / 2, -4, AMT_COLOR);
        g.drawString(mc.font, s, -tw / 2 + 1, -4, AMT_COLOR);     // doubled 1px = bolder, like the poster lettering
        g.pose().popMatrix();
    }

    /** Spinning coin / revolver ring ($100M+), drawn inside the poster's transform. */
    private static void drawMoneyRing(GuiGraphics g, float pw, float ph, long el) {
        float size = ph * 1.45f;
        float s = size / MONEY_TEX_SIZE;
        g.pose().pushMatrix();
        g.pose().rotate((el % 2094L) * 0.003f);
        g.pose().scale(s, s);
        g.blit(RenderPipelines.GUI_TEXTURED, MONEY,
                -MONEY_TEX_SIZE / 2, -MONEY_TEX_SIZE / 2, 0f, 0f,
                MONEY_TEX_SIZE, MONEY_TEX_SIZE, MONEY_TEX_SIZE, MONEY_TEX_SIZE);
        g.pose().popMatrix();
    }

    /** Slowly rotating golden rays behind the Pirate Token. */
    private static void drawSunburst(GuiGraphics g, float ph, long el) {
        float spin = el / 1000f * 0.6f;
        int thick = Math.max(2, (int) (ph * 0.035f));
        for (int i = 0; i < 16; i++) {
            float len = (i % 2 == 0) ? 0.95f : 0.72f;
            g.pose().pushMatrix();
            g.pose().rotate(spin + i * (6.2831855f / 16f));
            g.fill((int) (ph * 0.30f), -thick, (int) (ph * len), thick, (0x66 << 24) | 0xFFD040);
            g.pose().popMatrix();
        }
    }

    private static void drawTokenLabel(GuiGraphics g, Minecraft mc, int w, float top, long el, float alphaF) {
        float pulse = 1f + 0.06f * (float) Math.sin(el / 120.0);
        int a = (int) (255 * alphaF);
        String big = "PIRATE TOKEN!";
        String small = "0.05% chance - super rare!";
        int bw = mc.font.width(big);
        int sw = mc.font.width(small);

        g.pose().pushMatrix();
        g.pose().translate(w / 2f, top);
        g.pose().scale(3.2f * pulse, 3.2f * pulse);
        g.fill(-bw / 2 - 5, -3, bw / 2 + 5, 11, ((int) (0x99 * alphaF) << 24));
        g.drawString(mc.font, big, -bw / 2, 0, (a << 24) | 0xFFD23F);
        g.pose().popMatrix();

        g.pose().pushMatrix();
        g.pose().translate(w / 2f, top + 34f);
        g.pose().scale(1.5f, 1.5f);
        g.drawString(mc.font, small, -sw / 2, 0, (a << 24) | 0xFFFFFF);
        g.pose().popMatrix();
    }

    private static void playTokenSounds(Minecraft mc, long el) {
        while (tokenSoundIdx < TOKEN_SOUND_AT.length && el >= TOKEN_SOUND_AT[tokenSoundIdx]) {
            int i = tokenSoundIdx++;
            SoundEvent se;
            float pitch;
            float vol = 0.8f;
            switch (i % 3) {
                case 0 -> { se = SoundEvents.PLAYER_LEVELUP; pitch = 1.0f + 0.1f * i; }
                case 1 -> { se = SoundEvents.FIREWORK_ROCKET_LARGE_BLAST; pitch = 0.9f; vol = 0.6f; }
                default -> { se = SoundEvents.EXPERIENCE_ORB_PICKUP; pitch = 1.4f; }
            }
            mc.getSoundManager().play(SimpleSoundInstance.forUI(se, pitch, vol));
        }
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
