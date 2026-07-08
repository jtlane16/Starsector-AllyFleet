package allyfleet.controllers;

import allyfleet.AllyFleet;
import allyfleet.util.AllyFleetFactory;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import org.apache.log4j.Logger;

import java.util.*;

import static allyfleet.AllyFleetModPlugin.ALLY_FLEET_DATA_KEY;

/**
 * Central controller for all ally fleets.
 * Handles creation, persistence, and lifecycle management.
 */
public class AllyFleetController {

    public static Logger log = Global.getLogger(AllyFleetController.class);

    /** All active ally fleets, keyed by fleet ID */
    private static HashMap<String, AllyFleet> fleets = new HashMap<>();

    // ---- Fleet Management ----

    /**
     * Create a new ally fleet at the specified market.
     * Only one ally fleet is allowed at a time — replaces any existing fleet.
     *
     * @param name      The fleet's display name
     * @param market    The market where the fleet is created
     * @param factionId The faction the fleet belongs to
     * @return The newly created AllyFleet, or null if creation failed
     */
    public static AllyFleet createFleet(String name, MarketAPI market, String factionId) {
        if (market == null) return null;
        if (factionId == null) factionId = Global.getSector().getPlayerFaction().getId();

        // Remove existing fleet if any (only one allowed)
        for (AllyFleet existing : new ArrayList<>(fleets.values())) {
            removeFleet(existing);
        }

        // Generate a unique ID
        String id = Global.getSector().genUID();

        // Create the AllyFleet data object
        AllyFleet allyFleet = new AllyFleet(id, name, factionId, market.getId());

        // Create the in-game fleet
        CampaignFleetAPI fleet = AllyFleetFactory.createFleetForAlly(allyFleet, market);
        if (fleet == null) {
            log.warn("Failed to create campaign fleet for ally: " + name);
            return null;
        }

        // Store the fleet reference
        allyFleet.setFleet(fleet);

        // Register
        fleets.put(id, allyFleet);
        log.info("Created ally fleet: " + name + " at " + market.getName());

        return allyFleet;
    }

    /**
     * Remove an ally fleet (despawns the fleet and cleans up data).
     */
    public static void removeFleet(AllyFleet allyFleet) {
        if (allyFleet == null) return;

        // Despawn the campaign fleet if it exists
        CampaignFleetAPI fleet = allyFleet.getFleet();
        if (fleet != null && fleet.isAlive()) {
            fleet.despawn();
        }

        fleets.remove(allyFleet.getFleetId());
        log.info("Removed ally fleet: " + allyFleet.getFleetName());
    }

    /**
     * Called when an ally fleet's campaign fleet is destroyed in battle.
     * Starts the respawn timer instead of dead reckoning.
     */
    public static void onFleetDefeated(AllyFleet allyFleet) {
        if (allyFleet == null) return;
        allyFleet.setAlive(false);
        allyFleet.setFleet(null);
        log.info("Ally fleet defeated, awaiting respawn: " + allyFleet.getFleetName());
    }

    /**
     * Respawn an ally fleet at its home market.
     */
    public static boolean respawnFleet(AllyFleet allyFleet) {
        if (allyFleet == null || allyFleet.isAlive()) return false;

        MarketAPI home = allyFleet.getHomeMarket();
        if (home == null) {
            log.warn("Cannot respawn ally fleet - no home market: " + allyFleet.getFleetName());
            return false;
        }

        CampaignFleetAPI fleet = AllyFleetFactory.createFleetForAlly(allyFleet, home);
        if (fleet == null) return false;

        allyFleet.setFleet(fleet);
        allyFleet.setAlive(true);

        log.info("Respawned ally fleet: " + allyFleet.getFleetName());
        return true;
    }

    /**
     * Resupply an ally fleet with supplies and fuel from its credits.
     */
    public static void resupplyFleet(AllyFleet allyFleet) {
        CampaignFleetAPI fleet = allyFleet.getFleet();
        if (fleet == null || !fleet.isAlive()) return;

        // Auto-buy supplies and fuel using ally's credits
        float neededSupplies = fleet.getCargo().getMaxCapacity() - fleet.getCargo().getSupplies();
        float neededFuel = fleet.getCargo().getMaxFuel() - fleet.getCargo().getFuel();

        float supplyCost = neededSupplies * 50f;  // ~50 credits per supply
        float fuelCost = neededFuel * 10f;         // ~10 credits per fuel

        float totalCost = supplyCost + fuelCost;
        if (totalCost > 0 && allyFleet.getCredits() >= totalCost) {
            allyFleet.spendCredits(totalCost);
            fleet.getCargo().addSupplies((int) neededSupplies);
            fleet.getCargo().addFuel((int) neededFuel);
        }
    }

    // ---- Query Methods ----

    public static Collection<AllyFleet> getFleets() {
        return fleets.values();
    }

    public static int getFleetCount() {
        return fleets.size();
    }

    public static AllyFleet getFleetById(String id) {
        return fleets.get(id);
    }

    public static AllyFleet getFleetByCampaignFleet(CampaignFleetAPI fleet) {
        for (AllyFleet af : fleets.values()) {
            if (af.getFleet() == fleet) return af;
        }
        return null;
    }

    // ---- Persistence ----

    /**
     * Save all ally fleet data to persistent storage.
     */
    @SuppressWarnings("unchecked")
    public static void saveAll() {
        Map<String, HashMap<String, Object>> dataMap =
                (Map<String, HashMap<String, Object>>) Global.getSector().getPersistentData().get(ALLY_FLEET_DATA_KEY);
        if (dataMap == null) return;

        dataMap.clear();
        for (AllyFleet ally : fleets.values()) {
            dataMap.put(ally.getFleetId(), (HashMap<String, Object>) ally.toPersistentData());
        }
        log.info("Saved " + fleets.size() + " ally fleet(s)");
    }

    /**
     * Load all ally fleets from persistent storage.
     */
    @SuppressWarnings("unchecked")
    public static void loadFleets() {
        fleets.clear();

        Map<String, HashMap<String, Object>> dataMap =
                (Map<String, HashMap<String, Object>>) Global.getSector().getPersistentData().get(ALLY_FLEET_DATA_KEY);
        if (dataMap == null) return;

        for (Map.Entry<String, HashMap<String, Object>> entry : dataMap.entrySet()) {
            AllyFleet ally = AllyFleet.fromPersistentData(entry.getValue());
            fleets.put(ally.getFleetId(), ally);
        }
    }
}
