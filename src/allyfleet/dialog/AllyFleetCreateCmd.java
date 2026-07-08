package allyfleet.dialog;

import allyfleet.AllyFleet;
import allyfleet.controllers.AllyFleetController;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import org.apache.log4j.Logger;
import org.lazywizard.console.BaseCommand;
import org.lazywizard.console.CommonStrings;
import org.lazywizard.console.Console;

import java.util.Map;

/**
 * Console command to test ally fleet creation.
 * Usage: allyfleet_create <fleetName>
 * Requires Console Commands mod.
 */
public class AllyFleetCreateCmd implements BaseCommand {

    public static Logger log = Global.getLogger(AllyFleetCreateCmd.class);
    public static final String ALLY_FLEET_CMDER_TAG = "allyfleet_cmder";

    @Override
    public CommandResult runCommand(String args, CommandContext context) {
        if (!context.isInCampaign()) {
            Console.showMessage("This command can only be used in campaign mode.");
            return CommandResult.WRONG_CONTEXT;
        }

        CampaignFleetAPI playerFleet = Global.getSector().getPlayerFleet();
        if (playerFleet == null) {
            Console.showMessage("No player fleet found.");
            return CommandResult.ERROR;
        }

        String fleetName = args != null && !args.trim().isEmpty() ? args.trim() : "My Ally Fleet";

        // Find nearest market
        MarketAPI nearestMarket = findNearestMarket(playerFleet);
        if (nearestMarket == null) {
            Console.showMessage("Could not find a nearby market to create the fleet at.");
            return CommandResult.ERROR;
        }

        String factionId = nearestMarket.getFaction().getId();

        AllyFleet ally = AllyFleetController.createFleet(fleetName, nearestMarket, factionId);
        if (ally != null) {
            Console.showMessage("Ally fleet '" + fleetName + "' created at " + nearestMarket.getName()
                    + " with $" + (int)ally.getCredits() + " credits.");
            Console.showMessage("Use allyfleet_priorities to set their behavior.");
            return CommandResult.SUCCESS;
        } else {
            Console.showMessage("Failed to create ally fleet.");
            return CommandResult.ERROR;
        }
    }

    private MarketAPI findNearestMarket(CampaignFleetAPI fleet) {
        MarketAPI nearest = null;
        float nearestDist = Float.MAX_VALUE;

        for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
            if (market.isHidden() || !market.hasSpaceport()) continue;
            if (market.getFaction().isHostileTo(Global.getSector().getPlayerFaction())) continue;

            float dist = com.fs.starfarer.api.util.Misc.getDistance(
                    fleet.getLocationInHyperspace(), market.getLocationInHyperspace());
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = market;
            }
        }
        return nearest;
    }
}
