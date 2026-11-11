package com.example.bountyslap;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/** The full-screen leaderboard. Click the big red X (or press Esc) to close it. */
public class BountyBoardScreen extends Screen {
    private final List<BountyBoard.Entry> entries;
    private int closeX, closeY, closeSize;

    public BountyBoardScreen(List<BountyBoard.Entry> entries) {
        super(Component.literal("Top Bounty Additions"));
        this.entries = entries;
    }

    @Override
    protected void init() {
        closeSize = Math.max(28, this.height / 12);
        closeX = this.width - closeSize - 8;
        closeY = 8;
        // Invisible click target; the visible big X is drawn in render().
        this.addRenderableWidget(Button.builder(Component.empty(), b -> this.onClose())
                .bounds(closeX, closeY, closeSize, closeSize).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        BountyBoard.render(g, this.width, this.height, entries);
        boolean hover = mouseX >= closeX && mouseX < closeX + closeSize
                && mouseY >= closeY && mouseY < closeY + closeSize;
        BountyBoard.drawCloseX(g, closeX, closeY, closeSize, hover);
    }
}
