package net.ildar.wurm.bot;

import com.wurmonline.client.comm.ServerConnectionListenerClass;
import com.wurmonline.client.game.inventory.InventoryMetaItem;
import com.wurmonline.client.game.World;
import com.wurmonline.client.renderer.PickableUnit;
import com.wurmonline.client.renderer.cell.GroundItemCellRenderable;
import com.wurmonline.mesh.Tiles;
import com.wurmonline.shared.constants.PlayerAction;
import net.ildar.wurm.BotRegistration;
import net.ildar.wurm.Mod;
import net.ildar.wurm.Utils;
import org.gotti.wurmunlimited.modloader.ReflectionUtil;

import java.util.List;
import java.util.Map;

/**
 * Sends individual paving and catseye actions on explicit console commands.
 *
 * Unlike most bots, this class intentionally has no automatic work cycle. One
 * command sends one action so a bad target or action cannot produce a retry
 * loop or fill the player's action queue.
 */
public class PavingBot extends Bot {
    private volatile short movePlaceActionId = 1698;
    private volatile InventoryMetaItem pendingCatseye;
    private volatile boolean catseyeBusy;
    private volatile boolean catseyePlacementComplete;
    private volatile int pendingLineTiles;
    private volatile boolean stopLine;

    public static BotRegistration getRegistration() {
        return new BotRegistration(PavingBot.class,
                "Provides one-shot paving and catseye commands plus a bounded, failure-stopping catseye line mode.",
                "pv");
    }

    public PavingBot() {
        registerInputHandler(InputKey.pave, this::pave);
        registerInputHandler(InputKey.corner, this::paveCorner);
        registerInputHandler(InputKey.catseye, this::plantCatseye);
        registerInputHandler(InputKey.line, this::startOrStopLine);
        registerInputHandler(InputKey.action, this::setMovePlaceActionId);
        registerEventProcessor(message -> message.contains("You move the")
                        && message.contains("catseye to the corner of the tile"),
                () -> catseyePlacementComplete = true);
    }

    @Override
    public void work() throws Exception {
        Utils.consolePrint("PavingBot is ready. It acts only on explicit commands; line mode runs for the requested tile count.");
        while (isActive()) {
            waitOnPause();
            InventoryMetaItem catseye = pendingCatseye;
            if (catseye != null) {
                pendingCatseye = null;
                catseyeBusy = true;
                try {
                    dropAndPlaceCatseye(catseye);
                } catch (Exception e) {
                    Utils.consolePrint("Catseye placement failed: " + e.getMessage());
                } finally {
                    catseyeBusy = false;
                }
            }
            int lineTiles = pendingLineTiles;
            if (lineTiles > 0) {
                pendingLineTiles = 0;
                catseyeBusy = true;
                stopLine = false;
                try {
                    runCatseyeLine(lineTiles);
                } catch (Exception e) {
                    Utils.consolePrint("Catseye line stopped by an error: " + e.getMessage());
                } finally {
                    catseyeBusy = false;
                    stopLine = false;
                }
            }
            sleep(100);
        }
    }

    private void pave(String[] input) {
        if (input == null || input.length != 1) {
            printInputKeyUsageString(InputKey.pave);
            return;
        }

        TileTarget target = getTileTarget(input[0]);
        if (target == null)
            return;

        InventoryMetaItem material = getSelectedSource("paving material");
        if (material == null)
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

        InventoryMetaItem material = getSelectedSource("paving material");
        if (material == null)
            return;

        long cornerId = Tiles.getTileCornerId(target.x + corner.xOffset, target.y + corner.yOffset, 0, (byte) 0);
        sendOneAction(material, cornerId, PlayerAction.PAVE_CORNER,
                "Pave " + target.description + " " + corner.name().toLowerCase() + " corner");
    }

    private void plantCatseye(String[] input) {
        if (input != null && input.length == 1 && "move".equalsIgnoreCase(input[0])) {
            moveHoveredCatseye();
            return;
        }
        if (input != null && input.length != 0) {
            printInputKeyUsageString(InputKey.catseye);
            return;
        }

        if (pendingCatseye != null || catseyeBusy) {
            Utils.consolePrint("A catseye drop-and-place request is already pending.");
            return;
        }

        InventoryMetaItem catseye = getCatseyeFromInventory();
        if (catseye == null) {
            Utils.consolePrint("No catseye was found. Select one in the player inventory or keep one in the opened inventory.");
            return;
        }

        pendingCatseye = catseye;
        Utils.consolePrint("Queued one drop-and-place request for '" + catseye.getBaseName() + "'.");
    }

    private void moveHoveredCatseye() {
        PickableUnit target = null;
        try {
            target = ReflectionUtil.getPrivateField(Mod.hud.getSelectBar(),
                    ReflectionUtil.getField(Mod.hud.getSelectBar().getClass(), "selectedUnit"));
        } catch (Exception ignored) {
        }
        if (target == null)
            target = Mod.hud.getWorld().getCurrentHoveredObject();
        if (target == null) {
            Utils.consolePrint("Hover the mouse over a dropped glowing catseye first.");
            return;
        }
        if (!(target instanceof GroundItemCellRenderable)
                || target.getHoverName() == null
                || !target.getHoverName().toLowerCase().contains("catseye")) {
            Utils.consolePrint("The hovered ground item is not a catseye; no action was sent.");
            return;
        }

        Mod.hud.sendAction(getMovePlaceAction(), target.getId());
        Utils.consolePrint("Move > Place action " + movePlaceActionId + " requested once for hovered '" + target.getHoverName() + "'.");
    }

    private InventoryMetaItem getCatseyeFromInventory() {
        List<InventoryMetaItem> selected = Utils.getSelectedItems();
        if (selected != null) {
            for (InventoryMetaItem item : selected) {
                if (item.getBaseName().toLowerCase().contains("catseye"))
                    return item;
            }
        }
        return Utils.getInventoryItem("catseye");
    }

    private boolean dropAndPlaceCatseye(InventoryMetaItem catseye) throws Exception {
        long catseyeId = catseye.getId();
        Mod.hud.sendAction(PlayerAction.DROP, catseyeId);
        Utils.consolePrint("Drop requested once for '" + catseye.getBaseName() + "'. Waiting for that item on the ground.");

        long deadline = System.currentTimeMillis() + 30000;
        while (isActive() && System.currentTimeMillis() < deadline) {
            waitOnPause();
            if (isGroundItem(catseyeId)) {
                catseyePlacementComplete = false;
                Mod.hud.sendAction(getMovePlaceAction(), catseyeId);
                Utils.consolePrint("Move > Place action " + movePlaceActionId + " requested once for the newly dropped catseye.");
                long placementDeadline = System.currentTimeMillis() + 20000;
                while (isActive() && !stopLine && System.currentTimeMillis() < placementDeadline) {
                    waitOnPause();
                    if (catseyePlacementComplete)
                        return true;
                    sleep(200);
                }
                Utils.consolePrint("Catseye Move > Place was not confirmed within 20 seconds.");
                return false;
            }
            sleep(200);
        }
        Utils.consolePrint("The dropped catseye did not appear within 30 seconds; Move > Place was not sent.");
        return false;
    }

    private boolean isGroundItem(long itemId) throws Exception {
        ServerConnectionListenerClass listener = Mod.hud.getWorld().getServerConnection().getServerConnectionListener();
        Map<Long, GroundItemCellRenderable> groundItems = ReflectionUtil.getPrivateField(listener,
                ReflectionUtil.getField(listener.getClass(), "groundItems"));
        return groundItems.containsKey(itemId);
    }

    private PlayerAction getMovePlaceAction() {
        return new PlayerAction("Move > Place", movePlaceActionId, PlayerAction.ANYTHING);
    }

    private void setMovePlaceActionId(String[] input) {
        if (input == null || input.length == 0) {
            Utils.consolePrint("Current Move > Place action ID is " + movePlaceActionId + ".");
            return;
        }
        if (input.length != 1) {
            printInputKeyUsageString(InputKey.action);
            return;
        }
        try {
            int actionId = Integer.parseInt(input[0]);
            if (actionId < 1 || actionId > Short.MAX_VALUE) {
                Utils.consolePrint("Action ID must be between 1 and " + Short.MAX_VALUE + ".");
                return;
            }
            movePlaceActionId = (short) actionId;
            Utils.consolePrint("Move > Place action ID set to " + movePlaceActionId + " for this bot session.");
        } catch (NumberFormatException e) {
            Utils.consolePrint("Invalid action ID '" + input[0] + "'.");
        }
    }

    private void startOrStopLine(String[] input) {
        if (input == null || input.length != 1) {
            printInputKeyUsageString(InputKey.line);
            return;
        }
        if ("stop".equalsIgnoreCase(input[0])) {
            stopLine = true;
            pendingLineTiles = 0;
            Utils.consolePrint("Catseye line stop requested.");
            return;
        }
        if (catseyeBusy || pendingCatseye != null || pendingLineTiles > 0) {
            Utils.consolePrint("A catseye request is already running or pending.");
            return;
        }
        try {
            int tiles = Integer.parseInt(input[0]);
            if (tiles < 1) {
                Utils.consolePrint("The line length must be at least one tile.");
                return;
            }
            pendingLineTiles = tiles;
            Utils.consolePrint("Queued a catseye line for " + tiles + " tile(s).");
        } catch (NumberFormatException e) {
            printInputKeyUsageString(InputKey.line);
        }
    }

    private void runCatseyeLine(int tiles) throws Exception {
        // Preserve the player's exact position within the tile. Move > Place
        // chooses a corner from the dropped item's location, so centering the
        // player here can select a farther/forward corner unexpectedly.
        Utils.stabilizeLook();
        float direction = Math.round(Mod.hud.getWorld().getPlayerRotX() / 90f) * 90f;
        Utils.consolePrint("Starting catseye line for " + tiles + " tile(s) at direction " + direction + " degrees.");

        int completed = 0;
        for (int i = 0; i < tiles && isActive() && !stopLine; i++) {
            Utils.turnPlayer(direction, 0);
            InventoryMetaItem catseye = getCatseyeFromInventory();
            if (catseye == null) {
                Utils.consolePrint("Catseye line stopped: no catseye remains in the opened player inventory.");
                break;
            }
            Utils.consolePrint("Catseye line tile " + (i + 1) + " of " + tiles + ".");
            if (!dropAndPlaceCatseye(catseye)) {
                Utils.consolePrint("Catseye line stopped because placement was not confirmed.");
                break;
            }
            completed++;
            if (stopLine)
                break;
            Utils.movePlayerBySteps(4, 5, 1000);
        }
        Utils.consolePrint("Catseye line ended after " + completed + " successful placement(s).");
    }

    private InventoryMetaItem getSelectedSource(String purpose) {
        List<InventoryMetaItem> selected = Utils.getSelectedItems();
        if (selected == null || selected.isEmpty()) {
            Utils.consolePrint("Select one " + purpose + " item in an inventory first.");
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

        int direction = Math.round(world.getPlayerRotX() / 90f) & 3;
        switch (direction) {
            case 1:
                x++;
                break;
            case 2:
                y++;
                break;
            case 3:
                x--;
                break;
            default:
                y--;
                break;
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
        NW(0, 0),
        NE(1, 0),
        SW(0, 1),
        SE(1, 1);

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
                "under|front nw|ne|sw|se"),
        catseye("With no parameter, drop one selected/inventoried catseye and send the configured Move > Place action once after it appears on the ground. Use 'move' to move an already dropped catseye under the mouse pointer.",
                "[move]"),
        line("Place one catseye on each tile and move forward one tile after every confirmed placement. The route is bounded and stops on any failure.",
                "tile_count|stop"),
        action("Show or change the server-specific Move > Place action ID. The default is 1698 and changes last for the current bot session.",
                "[action_id]");

        private final String description;
        private final String usage;

        InputKey(String description, String usage) {
            this.description = description;
            this.usage = usage;
        }

        @Override
        public String getName() {
            return name();
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public String getUsage() {
            return usage;
        }
    }
}
