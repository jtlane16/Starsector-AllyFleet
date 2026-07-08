package allyfleet.ui;

import allyfleet.AllyFleet;
import allyfleet.controllers.AllyFleetController;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.ui.SectorMapAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

import java.awt.Color;

/**
 * Intel entry for the ally fleet — appears in the Intel tab (J key).
 * Clicking it centers the campaign map on the fleet's location.
 */
public class AllyFleetIntel extends BaseIntelPlugin {

    private static final Color COLOR = new Color(0, 180, 255);

    @Override
    public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
        CampaignFleetAPI fleet = getFleet();
        if (fleet == null) {
            info.addPara("Fleet destroyed, respawning...", 10f);
            return;
        }
        info.addPara(getName(), COLOR, 10f);
        info.addPara("Fleet size: " + fleet.getFleetData().getMembersInPriorityOrder().size() + " ships", 5f);
        String loc = fleet.getContainingLocation() != null
                ? fleet.getContainingLocation().getName() : "unknown";
        info.addPara("Location: " + loc, 5f);
        float supplies = fleet.getCargo().getSupplies();
        float fuel = fleet.getCargo().getFuel();
        info.addPara("Supplies: " + (int)supplies + "  Fuel: " + (int)fuel, 5f);
        info.addPara("Double-click to center map on fleet", Misc.getGrayColor(), 10f);
    }

    @Override
    public String getIcon() {
        CampaignFleetAPI fleet = getFleet();
        if (fleet != null && fleet.getFaction() != null && fleet.getFaction().getCrest() != null) {
            return fleet.getFaction().getCrest();
        }
        return Global.getSettings().getSpriteName("intel", "contact_hegemony");
    }

    @Override
    public String getSmallDescriptionTitle() {
        CampaignFleetAPI fleet = getFleet();
        if (fleet == null) return "Ally Fleet (respawning)";
        return fleet.getName();
    }

    @Override
    public SectorEntityToken getIntelLocation(SectorMapAPI map) {
        CampaignFleetAPI fleet = getFleet();
        return fleet;
    }

    @Override
    public String getName() {
        CampaignFleetAPI fleet = getFleet();
        if (fleet == null) return "Ally Fleet (respawning)";
        return fleet.getName();
    }

    @Override
    public FactionAPI getFactionForUIColors() {
        return Global.getSector().getPlayerFaction();
    }

    private CampaignFleetAPI getFleet() {
        java.util.List<AllyFleet> fleets = new java.util.ArrayList<>(AllyFleetController.getFleets());
        if (fleets.isEmpty()) return null;
        return fleets.get(0).getFleet();
    }
}
