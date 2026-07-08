package allyfleet.dialog;

import allyfleet.AllyFleet;
import allyfleet.AllyAction;
import allyfleet.controllers.AllyFleetController;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.util.Misc;
import org.apache.log4j.Logger;

import java.util.Map;

/**
 * Interaction dialog plugin for the "Create Ally Fleet" feature.
 * Shown when the player interacts with a station/market and chooses to create an ally fleet.
 */
public class CreateAllyFleetDialog implements InteractionDialogPlugin {

    public static Logger log = Global.getLogger(CreateAllyFleetDialog.class);

    private static enum DialogState {
        INIT,
        CHOOSE_NAME,
        CONFIRM_CREATION,
        CREATED,
        FLEET_SELECT,
        PRIORITY_UI,
        DISMISS
    }

    private InteractionDialogAPI dialog;
    private TextPanelAPI textPanel;
    private OptionPanelAPI options;

    private DialogState state = DialogState.INIT;
    private String chosenName;
    private AllyFleet selectedFleet;

    private static final float CREATION_COST = 500000f;

    @Override
    public void init(InteractionDialogAPI dialog) {
        this.dialog = dialog;
        this.textPanel = dialog.getTextPanel();
        this.options = dialog.getOptionPanel();
        showMainMenu();
    }

    @Override
    public void optionSelected(String optionText, Object optionData) {
        if (optionData instanceof DialogState) {
            state = (DialogState) optionData;
        } else if (optionData instanceof AllyFleet) {
            selectedFleet = (AllyFleet) optionData;
            state = DialogState.PRIORITY_UI;
        } else if (optionData instanceof String) {
            handleStringCommand((String) optionData);
            return;
        }

        switch (state) {
            case INIT: showMainMenu(); break;
            case CHOOSE_NAME: showNameInput(); break;
            case CONFIRM_CREATION: showConfirmation(); break;
            case CREATED: showSuccess(); break;
            case FLEET_SELECT: showFleetSelection(); break;
            case PRIORITY_UI:
                if (selectedFleet != null) showFleetPriorityUI(selectedFleet);
                else showFleetSelection();
                break;
            case DISMISS: dialog.dismiss(); break;
        }
    }

    private void handleStringCommand(String cmd) {
        switch (cmd) {
            case "inc_follow": adjustPriority(AllyAction.FOLLOW); break;
            case "inc_defend": adjustPriority(AllyAction.DEFEND); break;
            case "inc_trade": adjustPriority(AllyAction.TRADE); break;
            case "inc_patrol": adjustPriority(AllyAction.PATROL); break;
            case "inc_attack": adjustPriority(AllyAction.ATTACK); break;
            case "disband": handleDisband(); break;
            default:
                state = DialogState.INIT;
                showMainMenu();
                return;
        }
        if (selectedFleet != null) showFleetPriorityUI(selectedFleet);
    }

    private void adjustPriority(AllyAction action) {
        if (selectedFleet == null) return;
        int curr = selectedFleet.getPriority(action);
        int[] levels = {0, 25, 50, 75, 100};
        int next = 0;
        for (int i = 0; i < levels.length - 1; i++) {
            if (curr == levels[i]) { next = levels[i + 1]; break; }
        }
        selectedFleet.setPriority(action, next);
    }

    private void handleDisband() {
        if (selectedFleet != null) {
            AllyFleetController.removeFleet(selectedFleet);
            selectedFleet = null;
        }
        state = DialogState.INIT;
        showMainMenu();
    }

    private void showMainMenu() {
        textPanel.clear();
        options.clearOptions();

        int fleetCount = AllyFleetController.getFleetCount();
        float playerCredits = Global.getSector().getPlayerFleet().getCargo().getCredits().get();

        textPanel.addParagraph("Station Administrator");
        textPanel.addParagraph("");
        textPanel.addParagraph("Welcome, Captain. How can I assist you?");
        if (fleetCount > 0) {
            textPanel.addParagraph("Active ally fleets: " + fleetCount);
        }

        if (playerCredits >= CREATION_COST) {
            options.addOption("Create an Ally Fleet ($" + Misc.getFormat().format(CREATION_COST) + ")",
                    DialogState.CHOOSE_NAME);
        } else {
            options.addOption("Create an Ally Fleet ($" + Misc.getFormat().format(CREATION_COST)
                    + ") [Insufficient Funds]", null);
            options.setEnabled(null, false);
        }

        if (fleetCount > 0) {
            options.addOption("Manage Ally Fleets", DialogState.FLEET_SELECT);
        }

        options.addOption("Leave", DialogState.DISMISS);
    }

    private void showNameInput() {
        textPanel.clear();
        textPanel.addParagraph("Create Ally Fleet");
        textPanel.addParagraph("");
        textPanel.addParagraph("Enter a name for your new ally fleet.");

        dialog.showTextInput("Ally Fleet Name", "Enter fleet name...", new TextInputListener() {
            @Override
            public void reportSubmitted(String text) {
                chosenName = text.trim();
                dialog.dismissTextInput();
                if (chosenName.isEmpty()) chosenName = "Ally Fleet";
                state = DialogState.CONFIRM_CREATION;
                showConfirmation();
            }
            @Override
            public void reportCancelled() {
                dialog.dismissTextInput();
                state = DialogState.INIT;
                showMainMenu();
            }
        });
    }

    private void showConfirmation() {
        textPanel.clear();
        options.clearOptions();

        MarketAPI market = dialog.getInteractionTarget().getMarket();
        textPanel.addParagraph("Confirm Creation");
        textPanel.addParagraph("Fleet: " + chosenName);
        textPanel.addParagraph("Home: " + market.getName());
        textPanel.addParagraph("Cost: $" + Misc.getFormat().format(CREATION_COST));
        textPanel.addParagraph("Initial fleet, starting capital of $500,000.");
        textPanel.addParagraph("Fleet operates semi-autonomously based on your priorities.");
        options.addOption("Confirm - Create Fleet", DialogState.CREATED);
        options.addOption("Cancel", DialogState.INIT);
    }

    private void showSuccess() {
        textPanel.clear();
        options.clearOptions();

        MarketAPI market = dialog.getInteractionTarget().getMarket();
        String factionId = market.getFaction().getId();

        Global.getSector().getPlayerFleet().getCargo().getCredits().subtract(CREATION_COST);
        AllyFleet ally = AllyFleetController.createFleet(chosenName, market, factionId);

        textPanel.addParagraph("Fleet Created!");
        if (ally != null) {
            textPanel.addParagraph("The " + chosenName + " is ready. Use priorities to guide them.");
        } else {
            textPanel.addParagraph("Failed to create fleet.");
        }
        options.addOption("Set Priorities", DialogState.FLEET_SELECT);
        options.addOption("Done", DialogState.DISMISS);
    }

    private void showFleetSelection() {
        textPanel.clear();
        options.clearOptions();
        textPanel.addParagraph("Select an Ally Fleet to Manage:");

        for (AllyFleet f : AllyFleetController.getFleets()) {
            String status = f.isAlive() ? "Active" : "Respawning";
            options.addOption(f.getFleetName() + " [" + status + "]", f);
        }
        options.addOption("Back", DialogState.INIT);
    }

    private void showFleetPriorityUI(AllyFleet fleet) {
        textPanel.clear();
        options.clearOptions();

        textPanel.addParagraph("Managing: " + fleet.getFleetName());
        textPanel.addParagraph("Credits: $" + Misc.getFormat().format(fleet.getCredits()));
        textPanel.addParagraph("Status: " + (fleet.isAlive() ? "Active" : "Respawning"));
        textPanel.addParagraph("Ships: " + (fleet.getFleet() != null ? fleet.getFleet().getNumShips() : 0));
        textPanel.addParagraph("");
        textPanel.addParagraph("--- Priorities (0-100) ---");
        textPanel.addParagraph("Click to cycle: 0 -> 25 -> 50 -> 75 -> 100 -> 0");

        for (AllyAction a : AllyAction.values()) {
            int p = fleet.getPriority(a);
            options.addOption(a.getDisplayName() + ": " + p, "inc_" + a.name().toLowerCase());
        }

        textPanel.addParagraph("");
        options.addOption("Disband Fleet (Irreversible!)", "disband");
        options.addOption("Back", DialogState.FLEET_SELECT);
    }

    @Override
    public void optionMousedOver(String optionText, Object optionData) {}

    @Override
    public void advance(float amount) {}

    @Override
    public void backFromEngagement(EngagementResultAPI battleResult) {}

    @Override
    public Object getContext() { return null; }

    @Override
    public Map<String, MemoryAPI> getMemoryMap() { return null; }
}
