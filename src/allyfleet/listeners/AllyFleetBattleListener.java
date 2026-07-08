package allyfleet.listeners;

import allyfleet.AllyFleet;
import allyfleet.controllers.AllyFleetController;
import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignEventListener;
import com.fs.starfarer.api.campaign.CampaignEventListener.FleetDespawnReason;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.listeners.FleetEventListener;
import com.fs.starfarer.api.util.Misc;

/**
 * Listens for battles involving ally fleets and handles defeat/recovery logic.
 * Each ally fleet gets this as a listener when spawned.
 */
public class AllyFleetBattleListener implements FleetEventListener {

    @Override
    public void reportFleetDespawnedToListener(CampaignFleetAPI fleet, FleetDespawnReason reason, Object param) {
        if (reason == FleetDespawnReason.DESTROYED_BY_BATTLE) {
            AllyFleet ally = AllyFleetController.getFleetByCampaignFleet(fleet);
            if (ally != null) {
                AllyFleetController.onFleetDefeated(ally);
            }
        }
    }

    @Override
    public void reportBattleOccurred(CampaignFleetAPI fleet, CampaignFleetAPI primaryWinner, BattleAPI battle) {
        AllyFleet ally = AllyFleetController.getFleetByCampaignFleet(fleet);
        if (ally == null) return;

        // If fleet lost too many ships, return to base
        float currentFP = fleet.getFleetPoints();
        if (currentFP < fleet.getFleetPoints() * 0.33f && !Misc.isFleetReturningToDespawn(fleet)) {
            ally.setFleet(null);
            AllyFleetController.onFleetDefeated(ally);
        }
    }
}
