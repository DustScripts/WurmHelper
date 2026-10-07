package net.ildar.wurm.bot;

import com.wurmonline.client.game.World;
import com.wurmonline.client.game.inventory.InventoryMetaItem;
import com.wurmonline.mesh.Tiles;
import com.wurmonline.shared.constants.PlayerAction;
import net.ildar.wurm.BotRegistration;
import net.ildar.wurm.Mod;
import net.ildar.wurm.Utils;

import java.util.List;

/** Sends exactly one paving action for each explicit paving command. */
public class PavingBot extends Bot {
    public static BotRegistration getRegistration() {
        return new BotRegistration(PavingBot.class,
                "Sends one tile-paving or corner-paving action per command. Actions never repeat automatically.",
                "pv");
    }

    public PavingBot() {
        registerInputHandler(InputKey.pave, this::pave);
        registerInputHandler(InputKey.corner, this::paveCorner);
    }

    @Override
    public void work() throws Exception {
        Utils.consolePrint("PavingBot is ready. It acts only when you enter a pave or corner command.");
        while (isActive()) {
            waitOnPause();
            sleep(timeout);
        }
    }

    private void pave(String[] input) {
        if (input == null || input.length != 1) {
            printInputKeyUsageString(InputKey.pave);
            return;
        }
        TileTarget target = getTileTarget(input[0]);
        InventoryMetaItem material;
        if (target == null || (material = getSelectedSource()) == null)
            return;
        sendOneAction(material, Tiles.getTileId(target.x, target.y, 0), PlayerAction.PAVE,
                "Pave " + target.description);
    }

    private void paveCorner(String[] input) {
        if (input == null || input.length != 2) {
            printInputKeyUsageString(InputKey.corner);
            return;
        }
        TileTarget target = getTileTarget(input[0]);
        if (target == null)
            return;
        Corner corner = Corner.from(input[1]);
        if (corner == null) {
            Utils.consolePrint("Unknown corner '" + input[1] + "'. Use nw, ne, sw, or se.");
            return;
        }
        InventoryMetaItem material = getSelectedSource();
        if (material == null)
            return;
        long cornerId = Tiles.getTileCornerId(target.x + corner.xOffset, target.y + corner.yOffset, 0, (byte) 0);
        sendOneAction(material, cornerId, PlayerAction.PAVE_CORNER,
                "Pave " + target.description + " " + corner.name().toLowerCase() + " corner");
    }

    private InventoryMetaItem getSelectedSource() {
        List<InventoryMetaItem> selected = Utils.getSelectedItems();
        if (selected == null || selected.isEmpty()) {
            Utils.consolePrint("Select one paving material in the player inventory first.");
            return null;
        }
        if (selected.size() > 1)
            Utils.consolePrint("More than one item is selected; only '" + selected.get(0).getBaseName() + "' will be used.");
        return selected.get(0);
    }

    private void sendOneAction(InventoryMetaItem source, long target, PlayerAction action, String description) {
        Mod.hud.getWorld().getServerConnection().sendAction(source.getId(), new long[]{target}, action);
        Utils.consolePrint(description + " requested once using '" + source.getBaseName() + "'.");
    }

    private TileTarget getTileTarget(String value) {
        World world = Mod.hud.getWorld();
        int x = world.getPlayerCurrentTileX();
        int y = world.getPlayerCurrentTileY();
        if ("under".equalsIgnoreCase(value) || "below".equalsIgnoreCase(value))
            return new TileTarget(x, y, "tile under player");
        if (!"front".equalsIgnoreCase(value)) {
            Utils.consolePrint("Unknown tile target '" + value + "'. Use under or front.");
            return null;
        }
        switch (Math.round(world.getPlayerRotX() / 90f) & 3) {
            case 1: x++; break;
            case 2: y++; break;
            case 3: x--; break;
            default: y--; break;
        }
        return new TileTarget(x, y, "tile in front of player");
    }

    private static class TileTarget {
        final int x;
        final int y;
        final String description;

        TileTarget(int x, int y, String description) {
            this.x = x;
            this.y = y;
            this.description = description;
        }
    }

    private enum Corner {
        NW(0, 0), NE(1, 0), SW(0, 1), SE(1, 1);

        final int xOffset;
        final int yOffset;

        Corner(int xOffset, int yOffset) {
            this.xOffset = xOffset;
            this.yOffset = yOffset;
        }

        static Corner from(String value) {
            try {
                return Corner.valueOf(value.toUpperCase());
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    private enum InputKey implements Bot.InputKey {
        pave("Send one Pave action to the tile under or in front of the player using the selected inventory item.",
                "under|front"),
        corner("Send one Pave corner action using the selected inventory item. Corners are world directions.",
                "under|front nw|ne|sw|se");

        private final String description;
        private final String usage;

        InputKey(String description, String usage) {
            this.description = description;
            this.usage = usage;
        }

        public String getName() { return name(); }
        public String getDescription() { return description; }
        public String getUsage() { return usage; }
    }
}
