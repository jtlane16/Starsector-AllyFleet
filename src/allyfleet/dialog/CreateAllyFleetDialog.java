package allyfleet.dialog;

import allyfleet.AllyFleet;
import allyfleet.AllyAction;
import allyfleet.controllers.AllyFleetController;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.util.Misc;
import org.apache.log4j.Logger;

import java.util.Map;

/**
 * Interaction dialog plugin for creating and managing ally fleets.
 * Shows at stations when the player selects "Ally Fleet Administration".
 */
public class CreateAllyFleetDialog implements InteractionDialogPlugin {

    public static Logger log = Global.getLogger(CreateAllyFleetDialog.class);

    private static enum DState { INIT, CONFIRM, CREATED, FLEET_SELECT, PRIORITY_UI, DISMISS }

    private InteractionDialogAPI dialog;
    private TextPanelAPI textPanel;
    private OptionPanelAPI options;
    private DState state = DState.INIT;
    private AllyFleet selectedFleet;

    private static final float CREATION_COST = 500000f;
    private static int nameCounter = 1;

    @Override
    public void init(InteractionDialogAPI dialog) {
        this.dialog = dialog;
        this.textPanel = dialog.getTextPanel();
        this.options = dialog.getOptionPanel();
        showMainMenu();
    }

    @Override
    public void optionSelected(String optionText, Object optionData) {
        if (optionData instanceof DState) {
            state = (DState) optionData;
        } else if (optionData instanceof AllyFleet) {
            selectedFleet = (AllyFleet) optionData;
            state = DState.PRIORITY_UI;
        } else if (optionData instanceof String) {
            handleCmd((String) optionData);
            return;
        }

        switch (state) {
            case INIT: showMainMenu(); break;
            case CONFIRM: showConfirmation(); break;
            case CREATED: showSuccess(); break;
            case FLEET_SELECT: showFleetSelection(); break;
            case PRIORITY_UI:
                if (selectedFleet != null) showPriorityUI(selectedFleet);
                else showFleetSelection();
                break;
            case DISMISS: dialog.dismiss(); break;
        }
    }

    private void handleCmd(String cmd) {
        switch (cmd) {
            case "inc_follow": adjPrio(AllyAction.FOLLOW); break;
            case "inc_defend": adjPrio(AllyAction.DEFEND); break;
            case "inc_trade": adjPrio(AllyAction.TRADE); break;
            case "inc_patrol": adjPrio(AllyAction.PATROL); break;
            case "inc_attack": adjPrio(AllyAction.ATTACK); break;
            case "disband": disband(); break;
            default: state = DState.INIT; showMainMenu(); return;
        }
        if (selectedFleet != null) showPriorityUI(selectedFleet);
    }

    private void adjPrio(AllyAction a) {
        if (selectedFleet == null) return;
        int[] levels = {0, 25, 50, 75, 100};
        int curr = selectedFleet.getPriority(a);
        int next = 0;
        for (int i = 0; i < levels.length - 1; i++) {
            if (curr == levels[i]) { next = levels[i + 1]; break; }
        }
        selectedFleet.setPriority(a, next);
    }

    private void disband() {
        if (selectedFleet != null) {
            AllyFleetController.removeFleet(selectedFleet);
            selectedFleet = null;
        }
        state = DState.INIT;
        showMainMenu();
    }

    private void showMainMenu() {
        textPanel.clear();
        options.clearOptions();
        int count = AllyFleetController.getFleetCount();
        float credits = Global.getSector().getPlayerFleet().getCargo().getCredits().get();

        textPanel.addParagraph("Station Administrator");
        textPanel.addParagraph("Welcome, Captain. How can I assist you?");
        if (count > 0) textPanel.addParagraph("Active ally fleets: " + count);
        textPanel.addParagraph("");

        if (credits >= CREATION_COST) {
            options.addOption("Create an Ally Fleet ($" + Misc.getFormat().format(CREATION_COST) + ")",
                    DState.CONFIRM);
        } else {
            options.addOption("Create an Ally Fleet ($" + Misc.getFormat().format(CREATION_COST)
                    + ") [Insufficient Funds]", null);
            options.setEnabled(null, false);
        }
        if (count > 0) options.addOption("Manage Ally Fleets", DState.FLEET_SELECT);
        options.addOption("Leave", DState.DISMISS);
    }

    private void showConfirmation() {
        textPanel.clear();
        options.clearOptions();

        MarketAPI market = dialog.getInteractionTarget().getMarket();
        String defaultName = "Ally Fleet " + nameCounter;

        textPanel.addParagraph("Create Ally Fleet");
        textPanel.addParagraph("Fleet Name: " + defaultName);
        textPanel.addParagraph("Home Station: " + market.getName());
        textPanel.addParagraph("Cost: $" + Misc.getFormat().format(CREATION_COST));
        textPanel.addParagraph("Initial fleet with flagship + escorts, $500,000 starting capital.");
        textPanel.addParagraph("Use the console command 'allyfleet_create <name>' to name your fleet.");
        textPanel.addParagraph("");

        options.addOption("Confirm - Create Fleet", DState.CREATED);
        options.addOption("Cancel", DState.INIT);
    }

    private void showSuccess() {
        textPanel.clear();
        options.clearOptions();

        MarketAPI market = dialog.getInteractionTarget().getMarket();
        String name = "Ally Fleet " + nameCounter;
        nameCounter++;
        String factionId = market.getFaction().getId();

        Global.getSector().getPlayerFleet().getCargo().getCredits().subtract(CREATION_COST);
        AllyFleet ally = AllyFleetController.createFleet(name, market, factionId);

        textPanel.addParagraph("Fleet Created!");
        if (ally != null) {
            textPanel.addParagraph("\"" + name + "\" is ready. Set priorities to guide them.");
        } else {
            textPanel.addParagraph("Failed to create fleet.");
        }
        options.addOption("Set Priorities", DState.FLEET_SELECT);
        options.addOption("Done", DState.DISMISS);
    }

    private void showFleetSelection() {
        textPanel.clear();
        options.clearOptions();
        textPanel.addParagraph("Select an Ally Fleet:");

        for (AllyFleet f : AllyFleetController.getFleets()) {
            String status = f.isAlive() ? "Active" : "Respawning";
            options.addOption(f.getFleetName() + " [" + status, f);
        }
        options.addOption("Back", DState.INIT);
    }

    private void showPriorityUI(AllyFleet fleet) {
        textPanel.clear();
        options.clearOptions();

        textPanel.addParagraph("Managing: " + fleet.getFleetName());
        textPanel.addParagraph("Credits: $" + Misc.getFormat().format(fleet.getCredits()));
        textPanel.addParagraph("Status: " + (fleet.isAlive() ? "Active" : "Respawning"));
        if (fleet.getFleet() != null && fleet.getFleet().isAlive())
            textPanel.addParagraph("Ships: " + fleet.getFleet().getNumShips());
        textPanel.addParagraph("");
        textPanel.addParagraph("--- Priorities (click to cycle 0/25/50/75/100) ---");
        textPanel.addParagraph("");

        for (AllyAction a : AllyAction.values()) {
            int p = fleet.getPriority(a);
            options.addOption(a.getDisplayName() + ": " + p, "inc_" + a.name().toLowerCase());
        }

        textPanel.addParagraph("");
        options.addOption("Disband Fleet (Irreversible!)", "disband");
        options.addOption("Back", DState.FLEET_SELECT);
    }

    @Override public void optionMousedOver(String optionText, Object optionData) {}
    @Override public void advance(float amount) {}
    @Override public void backFromEngagement(EngagementResultAPI battleResult) {}
    @Override public Object getContext() { return null; }
    @Override public Map<String, MemoryAPI> getMemoryMap() { return null; }
}
