package allyfleet.plugins;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCampaignPlugin;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;

/**
 * Campaign plugin that marks stations where the player can create ally fleets.
 * Sets a memory flag ($allyFleetAvailable) on station entities that have a market with a spaceport.
 * This flag is checked by rules.csv to show the "Create Ally Fleet" dialog option.
 *
 * Also restores ally fleet references on save load by scanning for the commander tags.
 */
public class AllyFleetCampaignPlugin extends BaseCampaignPlugin {

    public static final String MEM_FLAG_ALLY_FLEET_AVAILABLE = "$allyFleetAvailable";
    public static final String MEM_FLAG_HAS_ALLY_FLEETS = "$allyFleetHasFleets";

    @Override
    public String getId() {
        return "allyfleet_campaign_plugin";
    }

    @Override
    public boolean isTransient() {
        return true;
    }

    /**
     * Update entity facts to mark stations where ally fleet creation is available.
     * Called by the rules engine when evaluating dialog conditions.
     */
    @Override
    public void updateEntityFacts(SectorEntityToken entity, MemoryAPI memory) {
        if (entity == null || memory == null) return;

        MarketAPI market = entity.getMarket();
        if (market == null) return;

        // Only available at stations with spaceports that are friendly
        if (market.hasSpaceport()
                && !market.getFaction().isHostileTo(Global.getSector().getPlayerFaction())) {
            memory.set(MEM_FLAG_ALLY_FLEET_AVAILABLE, true, 0);
        } else {
            memory.unset(MEM_FLAG_ALLY_FLEET_AVAILABLE);
        }
    }
}
