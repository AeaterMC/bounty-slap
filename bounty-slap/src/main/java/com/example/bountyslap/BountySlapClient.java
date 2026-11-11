package com.example.bountyslap;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BountySlapClient implements ClientModInitializer {
    public static final String MOD_ID = "bountyslap";
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Bounty additions of at least this many dollars get the poster slap. */
    public static final double SLAP_THRESHOLD = 1_000_000d;
    /** Additions of at least this many dollars also get the big money ring around the poster. */
    public static final double RING_THRESHOLD = 100_000_000d;

    /** F9 (rebindable): the poster slap. */
    public static boolean slapEnabled = true;
    /** F4 (rebindable): the small top-right notifications. */
    public static boolean toastsEnabled = true;
    /** /bountydebug: echo bounty-looking chat lines so the matcher can be tuned. */
    public static boolean debug = false;
    /** Guards against our own echoed messages being treated as new incoming messages. */
    private static boolean echoing = false;

    private static KeyMapping slapKey;
    private static KeyMapping toastKey;
    private static KeyMapping boardKey;

    // ---- message matching -------------------------------------------------------------
    // Anchored at the START of the line (after optional [Rank] tags) so another player
    // can't fake a bounty by typing a line in public chat.
    private static final String NAME_PREFIX =
            "^[^A-Za-z0-9_]*(?:\\[[^\\]]*\\][\\s\\u00A0]*)*(\\w{3,16})[\\s\\u00A0]+(?:has[\\s\\u00A0]+)?";
    private static final String AMOUNT =
            "\\$?[\\s\\u00A0]*([\\d,]+(?:\\.\\d+)?)[\\s\\u00A0]*([KMBT]?)(?:illion)?\\b";

    /** "Steve added $5M to your bounty", "Steve has added $ 2,500,000 to the bounty!" */
    private static final Pattern ADDED = Pattern.compile(
            NAME_PREFIX + "(?:added|add|placed|put|raised|increased)[\\s\\u00A0]+" + AMOUNT + ".*\\bbount",
            Pattern.CASE_INSENSITIVE);

    /** "Steve increased your bounty by $5M" */
    private static final Pattern INCREASED = Pattern.compile(
            NAME_PREFIX + "(?:increased|raised|boosted|upped)[\\s\\u00A0]+(?:your|the)[\\s\\u00A0]+bounty"
                    + "[\\s\\u00A0]+(?:by|with|to)?[\\s\\u00A0]*" + AMOUNT,
            Pattern.CASE_INSENSITIVE);

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(MOD_ID, "main"));
        slapKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.bountyslap.toggle_slap", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F9, category));
        toastKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.bountyslap.toggle_toasts", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F4, category));

        boardKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.bountyslap.open_board", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F10, category));
        BountyBoard.load();

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            BountyBoard.tick(mc);
            while (boardKey.consumeClick()) {
                BountyBoard.requestOpen(false);
            }
            while (slapKey.consumeClick()) {
                slapEnabled = !slapEnabled;
                if (!slapEnabled) BountyEffects.clearSlap();
                say(mc, "Bounty Slap poster: " + (slapEnabled ? "ON" : "OFF"), true);
            }
            while (toastKey.consumeClick()) {
                toastsEnabled = !toastsEnabled;
                if (!toastsEnabled) BountyEffects.clearToasts();
                say(mc, "Bounty notifications: " + (toastsEnabled ? "ON" : "OFF"), true);
            }
        });

        // Server system messages (how plugins usually send these).
        ClientReceiveMessageEvents.GAME.register((message, overlay) ->
                handle(message.getString(), overlay ? "actionbar" : "system"));

        // Server messages sent as "disguised chat" have no signed player message, so other
        // players can't type them -> safe to accept. Real signed player chat is ignored.
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, timestamp) -> {
            if (signedMessage == null) {
                handle(message.getString(), "disguised-chat");
            }
        });

        HudElementRegistry.attachElementAfter(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(MOD_ID, "bounty_slap"),
                (graphics, delta) -> BountyEffects.render(graphics));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            // /bountytest <amount> [name]   e.g.  /bountytest 5M   /bountytest 250M   /bountytest 1B
            dispatcher.register(ClientCommandManager.literal("bountytest")
                    .then(ClientCommandManager.argument("amount", StringArgumentType.word())
                            .executes(ctx -> runTest(StringArgumentType.getString(ctx, "amount"), null))
                            .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                    .executes(ctx -> runTest(
                                            StringArgumentType.getString(ctx, "amount"),
                                            StringArgumentType.getString(ctx, "name"))))));

            // /bountyboard (opens the leaderboard)  /bountyboard demo (sample data)
            dispatcher.register(ClientCommandManager.literal("bountyboard")
                    .executes(ctx -> {
                        BountyBoard.requestOpen(false);
                        return 1;
                    })
                    .then(ClientCommandManager.literal("demo").executes(ctx -> {
                        BountyBoard.requestOpen(true);
                        return 1;
                    })));

            // /bountytoken [amount] -> force the Pirate Token animation for previewing
            dispatcher.register(ClientCommandManager.literal("bountytoken")
                    .executes(ctx -> runToken("1M"))
                    .then(ClientCommandManager.argument("amount", StringArgumentType.word())
                            .executes(ctx -> runToken(StringArgumentType.getString(ctx, "amount")))));

            // /bountydebug -> echo bounty-looking lines + whether they matched
            dispatcher.register(ClientCommandManager.literal("bountydebug").executes(ctx -> {
                debug = !debug;
                say(Minecraft.getInstance(), "[Bounty] debug " + (debug ? "ON" : "OFF"), false);
                return 1;
            }));
        });
    }

    /** Runs a fake bounty addition. With no name given, your own username is used so your head shows. */
    private static int runTest(String rawAmount, String nameOrNull) {
        Minecraft mc = Minecraft.getInstance();
        if (rawAmount.equalsIgnoreCase("token")) {      // /bountytest token [name]
            String tn = nameOrNull != null ? nameOrNull
                    : (mc.player != null ? mc.player.getName().getString() : "Steve");
            mc.execute(() -> BountyEffects.testToken(tn, 1_000_000d));
            return 1;
        }
        Matcher m = Pattern.compile("^([\\d,]*\\.?\\d+)([kmbtKMBT]?)$").matcher(rawAmount);
        if (!m.matches()) {
            say(mc, "[Bounty] amount looks like 1M, 2.5M, 100M, 1B or 1000000 (or: token)", false);
            return 0;
        }
        double amount = toDollars(m.group(1), m.group(2));
        String name = nameOrNull != null ? nameOrNull
                : (mc.player != null ? mc.player.getName().getString() : "Steve");
        mc.execute(() -> BountyEffects.onBounty(name, amount));
        return 1;
    }

    private static int runToken(String rawAmount) {
        Minecraft mc = Minecraft.getInstance();
        Matcher m = Pattern.compile("^([\\d,]*\\.?\\d+)([kmbtKMBT]?)$").matcher(rawAmount);
        if (!m.matches()) {
            say(mc, "[Bounty] amount looks like 1M, 2.5M, 100M, 1B or 1000000", false);
            return 0;
        }
        double amount = toDollars(m.group(1), m.group(2));
        String name = mc.player != null ? mc.player.getName().getString() : "Steve";
        mc.execute(() -> BountyEffects.testToken(name, amount));
        return 1;
    }

    private static void say(Minecraft mc, String text, boolean actionBar) {
        if (mc.player != null && !echoing) {
            echoing = true;
            try {
                mc.player.displayClientMessage(Component.literal(text), actionBar);
            } finally {
                echoing = false;
            }
        }
    }

    static void handle(String line, String source) {
        // Ignore anything we echoed ourselves.
        if (echoing || line.startsWith("[Bounty")) return;
        String lower = line.toLowerCase(Locale.ROOT);
        if (!lower.contains("bounty")) return;

        LOGGER.info("[bountyslap] bounty line ({}): {}", source, line);
        Minecraft mc = Minecraft.getInstance();
        if (debug) say(mc, "[Bounty debug] (" + source + ") raw: " + line, false);

        Matcher m = ADDED.matcher(line);
        if (!m.find()) {
            m = INCREASED.matcher(line);
            if (!m.find()) {
                if (debug) say(mc, "[Bounty debug] no match - format not recognized", false);
                return;
            }
        }

        String name = m.group(1);
        double amount;
        try {
            amount = toDollars(m.group(2), m.group(3));
        } catch (NumberFormatException e) {
            return;
        }
        if (debug) {
            say(mc, "[Bounty debug] parsed " + name + " -> " + fmtAmount(amount)
                    + (amount >= SLAP_THRESHOLD ? " (SLAP + notification)" : " (notification only)"), false);
        }
        final String fname = name;
        final double famount = amount;
        mc.execute(() -> {
            // Ignore all incoming bounty additions while a Pirate Token animation is active.
            // This protects the token, its associated notification, and the leaderboard from
            // being replaced/updated by additions received during the 7.5-second animation.
            if (BountyEffects.isTokenAnimationActive()) return;

            // Remember the addition for the leaderboard (with the locally cached player skin, if available).
            BountyBoard.record(fname, famount,
                    mc.getConnection() != null ? mc.getConnection().getPlayerInfo(fname) : null);
            BountyEffects.onBounty(fname, famount);
        });
    }

    static double toDollars(String num, String suffix) {
        double v = Double.parseDouble(num.replace(",", ""));
        switch (suffix.toUpperCase(Locale.ROOT)) {
            case "K" -> v *= 1_000d;
            case "M" -> v *= 1_000_000d;
            case "B" -> v *= 1_000_000_000d;
            case "T" -> v *= 1_000_000_000_000d;
            default -> { }
        }
        return v;
    }

    /** 2500000 -> "$2.5M", 1300000000 -> "$1.3B", 750 -> "$750". */
    static String fmtAmount(double a) {
        if (a >= 1e12) return "$" + trim(a / 1e12) + "T";
        if (a >= 1e9) return "$" + trim(a / 1e9) + "B";
        if (a >= 1e6) return "$" + trim(a / 1e6) + "M";
        if (a >= 1e3) return "$" + trim(a / 1e3) + "K";
        return "$" + trim(a);
    }

    private static String trim(double v) {
        String s = String.format(Locale.ROOT, "%.2f", v);
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return s;
    }
}
