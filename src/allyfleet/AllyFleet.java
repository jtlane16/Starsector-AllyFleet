package allyfleet;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import org.apache.log4j.Logger;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents a single ally fleet - its data, state, and behavior configuration.
 * Persistent data is stored in SectorAPI.getPersistentData() for save compatibility.
 */
public class AllyFleet {

    public static Logger log = Global.getLogger(AllyFleet.class);

    // ---- Persisted Fields ----

    /** Unique identifier for this ally fleet (person API ID) */
    private String fleetId;

    /** Display name of the fleet */
    private String fleetName;

    /** ID of the faction this fleet belongs to (usually same as player's faction) */
    private String factionId;

    /** ID of the home market where this fleet was created */
    private String homeMarketId;

    /** Fleet's credits (stored separately from cargo) */
    private float credits;

    /** Whether the fleet is currently alive/active */
    private boolean alive;

    // ---- Priority System (persisted) ----
    // Higher values = higher priority. Range 0-100.

    /** Priority for following and escorting the player */
    private int priorityFollow = 0;

    /** Priority for defending player colonies */
    private int priorityDefend = 0;

    /** Priority for trading (buying/selling goods) */
    private int priorityTrade = 0;

    /** Priority for patrolling systems */
    private int priorityPatrol = 0;

    /** Priority for attacking targets */
    private int priorityAttack = 0;

    // ---- Transient Fields (not saved directly) ----

    /** Reference to the campaign fleet entity. Rebuilt on save load. */
    private transient CampaignFleetAPI fleet;

    /** Reference to the commander person. Rebuilt on save load. */
    private transient PersonAPI commander;

    /** Timestamp of when this fleet was created */
    private long creationTimestamp;

    public AllyFleet() {}

    public AllyFleet(String fleetId, String fleetName, String factionId, String homeMarketId) {
        this.fleetId = fleetId;
        this.fleetName = fleetName;
        this.factionId = factionId;
        this.homeMarketId = homeMarketId;
        this.credits = 500000f; // Starting capital
        this.alive = true;
        this.creationTimestamp = Global.getSector().getClock().getTimestamp();
    }

    // ---- Persistence ----

    /** Save this fleet's data to the persistent data map */
    public Map<String, Object> toPersistentData() {
        Map<String, Object> data = new HashMap<>();
        data.put("fleetId", fleetId);
        data.put("fleetName", fleetName);
        data.put("factionId", factionId);
        data.put("homeMarketId", homeMarketId);
        data.put("credits", credits);
        data.put("alive", alive);
        data.put("priorityFollow", priorityFollow);
        data.put("priorityDefend", priorityDefend);
        data.put("priorityTrade", priorityTrade);
        data.put("priorityPatrol", priorityPatrol);
        data.put("priorityAttack", priorityAttack);
        data.put("creationTimestamp", creationTimestamp);
        return data;
    }

    /** Load this fleet's data from the persistent data map */
    @SuppressWarnings("unchecked")
    public static AllyFleet fromPersistentData(Map<String, Object> data) {
        AllyFleet fleet = new AllyFleet();
        fleet.fleetId = (String) data.get("fleetId");
        fleet.fleetName = (String) data.get("fleetName");
        fleet.factionId = (String) data.get("factionId");
        fleet.homeMarketId = (String) data.get("homeMarketId");
        fleet.credits = ((Number) data.getOrDefault("credits", 0f)).floatValue();
        fleet.alive = (boolean) data.getOrDefault("alive", true);
        fleet.priorityFollow = ((Number) data.getOrDefault("priorityFollow", 0)).intValue();
        fleet.priorityDefend = ((Number) data.getOrDefault("priorityDefend", 0)).intValue();
        fleet.priorityTrade = ((Number) data.getOrDefault("priorityTrade", 0)).intValue();
        fleet.priorityPatrol = ((Number) data.getOrDefault("priorityPatrol", 0)).intValue();
        fleet.priorityAttack = ((Number) data.getOrDefault("priorityAttack", 0)).intValue();
        fleet.creationTimestamp = ((Number) data.getOrDefault("creationTimestamp", 0L)).longValue();
        return fleet;
    }

    // ---- Home Market ----

    public MarketAPI getHomeMarket() {
        if (homeMarketId == null) return null;
        return Global.getSector().getEconomy().getMarket(homeMarketId);
    }

    public SectorEntityToken getHomeEntity() {
        MarketAPI market = getHomeMarket();
        if (market != null && market.getPrimaryEntity() != null) {
            return market.getPrimaryEntity();
        }
        return null;
    }

    // ---- Priority Helpers ----

    /** Get the highest-priority active action, or null if nothing is configured */
    public AllyAction getHighestPriorityAction() {
        AllyAction best = null;
        int bestPrio = 0;
        if (priorityFollow > bestPrio) { best = AllyAction.FOLLOW; bestPrio = priorityFollow; }
        if (priorityTrade > bestPrio) { best = AllyAction.TRADE; bestPrio = priorityTrade; }
        return best;
    }

    /** Get the weight of a specific action for the AI decision system */
    public int getPriority(AllyAction action) {
        switch (action) {
            case FOLLOW: return priorityFollow;
            case TRADE: return priorityTrade;
            default: return 0;
        }
    }

    public void setPriority(AllyAction action, int value) {
        value = Math.max(0, Math.min(100, value));
        switch (action) {
            case FOLLOW: priorityFollow = value; break;
            case TRADE: priorityTrade = value; break;
        }
    }

    // ---- Getters & Setters ----

    public String getFleetId() { return fleetId; }

    public String getFleetName() { return fleetName; }
    public void setFleetName(String fleetName) { this.fleetName = fleetName; }

    public String getFactionId() { return factionId; }

    public FactionAPI getFaction() {
        return Global.getSector().getFaction(factionId);
    }

    public float getCredits() { return credits; }
    public void setCredits(float credits) { this.credits = credits; }
    public void addCredits(float amount) { this.credits += amount; }
    public boolean spendCredits(float amount) {
        if (credits >= amount) {
            credits -= amount;
            return true;
        }
        return false;
    }

    public boolean isAlive() { return alive; }
    public void setAlive(boolean alive) { this.alive = alive; }

    public CampaignFleetAPI getFleet() { return fleet; }
    public void setFleet(CampaignFleetAPI fleet) { this.fleet = fleet; }

    public PersonAPI getCommander() { return commander; }
    public void setCommander(PersonAPI commander) { this.commander = commander; }

    public long getCreationTimestamp() { return creationTimestamp; }

    public int getPriorityFollow() { return priorityFollow; }
    public int getPriorityDefend() { return priorityDefend; }
    public int getPriorityTrade() { return priorityTrade; }
    public int getPriorityPatrol() { return priorityPatrol; }
    public int getPriorityAttack() { return priorityAttack; }

    public static AllyAction[] getAllActions() {
        return AllyAction.values();
    }
}
