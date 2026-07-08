package allyfleet.ai;

import allyfleet.AllyAction;
import allyfleet.AllyFleet;
import allyfleet.controllers.AllyFleetController;
import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Main AI script that runs every frame and manages all ally fleets.
 * Decides what each fleet should be doing based on priority weights.
 * Also handles respawn timers for defeated fleets.
 */
public class AllyFleetAI implements EveryFrameScript {

    public static Logger log = Global.getLogger(AllyFleetAI.class);

    /** How often (in days) the AI re-evaluates its decisions */
    private static final float UPDATE_INTERVAL_DAYS = 1f;

    /** How long (in days) before a defeated fleet respawns */
    private static final float RESPAWN_DELAY_DAYS = 7f;

    /** How long (in days) before resupplying */
    private static final float RESUPPLY_INTERVAL_DAYS = 3f;

    private IntervalUtil updateTimer = new IntervalUtil(UPDATE_INTERVAL_DAYS * 0.75f, UPDATE_INTERVAL_DAYS * 1.25f);
    private IntervalUtil respawnTimer = new IntervalUtil(UPDATE_INTERVAL_DAYS, UPDATE_INTERVAL_DAYS);
    private IntervalUtil resupplyTimer = new IntervalUtil(RESUPPLY_INTERVAL_DAYS, RESUPPLY_INTERVAL_DAYS);

    /**
     * Keys stored in each ally fleet's memory for AI state.
     */
    public static final String KEY_LAST_ACTION = "$ally_lastAction";
    public static final String KEY_CURRENT_TARGET = "$ally_currentTarget";
    public static final String KEY_RETURNING_HOME = "$ally_returningHome";

    @Override
    public boolean isDone() {
        return false;
    }

    @Override
    public boolean runWhilePaused() {
        return false;
    }

    @Override
    public void advance(float amount) {
        float days = Global.getSector().getClock().convertToDays(amount);
        if (days <= 0) return;

        updateTimer.advance(days);
        respawnTimer.advance(days);
        resupplyTimer.advance(days);

        boolean shouldUpdate = updateTimer.intervalElapsed();
        boolean shouldResupply = resupplyTimer.intervalElapsed();

        for (AllyFleet ally : AllyFleetController.getFleets()) {
            CampaignFleetAPI fleet = ally.getFleet();

            // Clean up dead fleet references
            if (fleet != null && !fleet.isAlive()) {
                ally.setFleet(null);
                ally.setAlive(false);
                fleet = null;
            }

            // Handle defeated fleets - respawn timer
            if (!ally.isAlive()) {
                if (respawnTimer.intervalElapsed()) {
                    AllyFleetController.respawnFleet(ally);
                }
                continue;
            }

            if (fleet == null) continue;

            // Periodic resupply
            if (shouldResupply) {
                AllyFleetController.resupplyFleet(ally);
            }

            // Main AI decision loop
            if (shouldUpdate) {
                decideAction(ally, fleet);
            }
        }
    }

    /**
     * Core AI decision: pick the best action based on priority weights and current situation.
     */
    private void decideAction(AllyFleet ally, CampaignFleetAPI fleet) {
        // Get the player fleet
        CampaignFleetAPI playerFleet = Global.getSector().getPlayerFleet();
        if (playerFleet == null) return;

        MemoryAPI mem = fleet.getMemoryWithoutUpdate();

        // Check if we're returning home (don't override)
        if (mem.getBoolean(KEY_RETURNING_HOME)) {
            return;
        }

        // Collect weighted actions
        List<WeightedAction> actions = new ArrayList<>();

        // FOLLOW: weight = priority + distance bonus (closer to player = more likely)
        if (ally.getPriorityFollow() > 0) {
            float dist = Misc.getDistance(fleet.getLocation(), playerFleet.getLocation());
            float distFactor = Math.max(0.1f, 1f - (dist / 10000f));
            float weight = ally.getPriorityFollow() * distFactor;
            actions.add(new WeightedAction(AllyAction.FOLLOW, weight, playerFleet));
        }

        // DEFEND: find player colonies under threat
        if (ally.getPriorityDefend() > 0) {
            MarketAPI threatened = findMostThreatenedPlayerColony();
            if (threatened != null) {
                float weight = ally.getPriorityDefend() * 1.5f; // Priority multiplier for defense
                actions.add(new WeightedAction(AllyAction.DEFEND, weight, threatened.getPrimaryEntity()));
            }
        }

        // PATROL: patrol home system or nearest friendly system
        if (ally.getPriorityPatrol() > 0) {
            MarketAPI home = ally.getHomeMarket();
            if (home != null && home.getStarSystem() != null) {
                float weight = ally.getPriorityPatrol();
                actions.add(new WeightedAction(AllyAction.PATROL, weight, home.getStarSystem().getCenter()));
            }
        }

        // ATTACK: find enemy fleets nearby
        if (ally.getPriorityAttack() > 0) {
            CampaignFleetAPI target = findNearestEnemyFleet(fleet, ally);
            if (target != null) {
                float weight = ally.getPriorityAttack();
                actions.add(new WeightedAction(AllyAction.ATTACK, weight, target));
            }
        }

        // TRADE: travel to market and trade
        if (ally.getPriorityTrade() > 0) {
            MarketAPI tradeTarget = findBestTradeMarket(ally);
            if (tradeTarget != null) {
                float weight = ally.getPriorityTrade();
                actions.add(new WeightedAction(AllyAction.TRADE, weight, tradeTarget.getPrimaryEntity()));
            }
        }

        // Pick the best action
        if (actions.isEmpty()) {
            // Default: follow the player if nothing else to do
            giveFollowAssignment(fleet, playerFleet);
            mem.set(KEY_LAST_ACTION, AllyAction.FOLLOW.name());
            return;
        }

        // Sort by weight descending and pick the top
        Collections.sort(actions);
        WeightedAction best = actions.get(0);

        executeAction(ally, fleet, playerFleet, best);
    }

    private void executeAction(AllyFleet ally, CampaignFleetAPI fleet,
                                CampaignFleetAPI playerFleet, WeightedAction best) {
        MemoryAPI mem = fleet.getMemoryWithoutUpdate();

        switch (best.action) {
            case FOLLOW:
                giveFollowAssignment(fleet, playerFleet);
                break;
            case DEFEND:
                if (best.target != null) {
                    fleet.clearAssignments();
                    fleet.addAssignment(FleetAssignment.DEFEND_LOCATION, best.target, 7f,
                            "defending colony");
                    fleet.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE, best.target, 3f,
                            "orbiting colony");
                }
                break;
            case PATROL:
                if (best.target != null) {
                    fleet.clearAssignments();
                    fleet.addAssignment(FleetAssignment.PATROL_SYSTEM, best.target, 10f,
                            "patrolling system");
                    fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, best.target, 1000f,
                            "traveling to patrol area");
                }
                break;
            case ATTACK:
                if (best.target instanceof CampaignFleetAPI) {
                    CampaignFleetAPI enemy = (CampaignFleetAPI) best.target;
                    fleet.clearAssignments();
                    fleet.addAssignment(FleetAssignment.INTERCEPT, best.target, 5f,
                            "intercepting enemy");
                    fleet.addAssignment(FleetAssignment.ATTACK_LOCATION, best.target, 5f,
                            "attacking enemy");
                }
                break;
            case TRADE:
                if (best.target != null) {
                    fleet.clearAssignments();
                    fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, best.target, 1000f,
                            "traveling to trade");
                    fleet.addAssignment(FleetAssignment.ORBIT_PASSIVE, best.target, 2f,
                            "trading");
                }
                break;
        }

        mem.set(KEY_LAST_ACTION, best.action.name());
        if (best.target != null) {
            mem.set(KEY_CURRENT_TARGET, best.target.getId());
        }
    }

    /**
     * Give a FOLLOW assignment - the fleet will escort the player.
     */
    private void giveFollowAssignment(CampaignFleetAPI fleet, CampaignFleetAPI playerFleet) {
        fleet.clearAssignments();
        fleet.addAssignment(FleetAssignment.FOLLOW, playerFleet, 1000000f,
                "following player fleet");
    }

    /**
     * Find the player colony most in need of defense.
     * Looks at stability (lower = more threatened).
     */
    private MarketAPI findMostThreatenedPlayerColony() {
        List<MarketAPI> playerMarkets = Misc.getPlayerMarkets(true);
        if (playerMarkets.isEmpty()) return null;

        MarketAPI worst = null;
        float worstStability = Float.MAX_VALUE;

        for (MarketAPI market : playerMarkets) {
            if (market.isHidden()) continue;
            float stab = market.getStabilityValue();
            if (stab < worstStability) {
                worstStability = stab;
                worst = market;
            }
        }

        return worst;
    }

    /**
     * Find the nearest hostile fleet to engage.
     */
    private CampaignFleetAPI findNearestEnemyFleet(CampaignFleetAPI fleet, AllyFleet ally) {
        FactionAPI ourFaction = ally.getFaction();
        if (ourFaction == null) return null;

        List<CampaignFleetAPI> nearby = Misc.getNearbyFleets(fleet, 2000);
        CampaignFleetAPI best = null;
        float bestDist = Float.MAX_VALUE;

        for (CampaignFleetAPI other : nearby) {
            if (other.isPlayerFleet()) continue;
            if (other.getFaction() == null) continue;
            if (!ourFaction.isHostileTo(other.getFaction())) continue;
            if (other.getFleetPoints() > fleet.getFleetPoints() * 1.5f) continue; // Don't pick suicide fights

            float dist = Misc.getDistance(fleet.getLocation(), other.getLocation());
            if (dist < bestDist) {
                bestDist = dist;
                best = other;
            }
        }

        return best;
    }

    /**
     * Find the best market to trade at (nearby friendly/neutral market with spaceport).
     */
    private MarketAPI findBestTradeMarket(AllyFleet ally) {
        CampaignFleetAPI fleet = ally.getFleet();
        if (fleet == null) return null;

        List<MarketAPI> markets = Global.getSector().getEconomy().getMarketsCopy();
        MarketAPI best = null;
        float bestDist = Float.MAX_VALUE;

        for (MarketAPI market : markets) {
            if (market.isHidden() || !market.hasSpaceport()) continue;
            if (market.getFaction().isHostileTo(ally.getFaction())) continue;

            float dist = Misc.getDistance(fleet.getLocationInHyperspace(),
                    market.getLocationInHyperspace());
            if (dist < bestDist) {
                bestDist = dist;
                best = market;
            }
        }

        return best;
    }

    /**
     * Internal: a weighted action for the AI decision system.
     */
    private static class WeightedAction implements Comparable<WeightedAction> {
        final AllyAction action;
        final float weight;
        final SectorEntityToken target;

        WeightedAction(AllyAction action, float weight, SectorEntityToken target) {
            this.action = action;
            this.weight = weight;
            this.target = target;
        }

        @Override
        public int compareTo(WeightedAction other) {
            return Float.compare(other.weight, this.weight); // Descending
        }
    }
}
