package com.fs.starfarer.api.impl.campaign.rulecmd;

import allyfleet.dialog.CreateAllyFleetDialog;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.util.Misc;

import java.util.List;
import java.util.Map;

/**
 * Rule command plugin called from rules.csv when the player selects
 * "Ally Fleet Administration" at a station.
 *
 * Switches the current dialog to the CreateAllyFleetDialog so the player
 * can create or manage ally fleets.
 */
public class AllyFleetDialogCmd extends BaseCommandPlugin {

    @Override
    public boolean execute(String ruleId, InteractionDialogAPI dialog, List<Misc.Token> params, Map<String, MemoryAPI> memoryMap) {
        if (dialog == null) return false;

        // Replace the current dialog with our ally fleet management dialog
        CreateAllyFleetDialog allyDialog = new CreateAllyFleetDialog();
        dialog.setPlugin(allyDialog);
        allyDialog.init(dialog);

        return true;
    }
}
