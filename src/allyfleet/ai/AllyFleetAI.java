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
import org.lwjgl.util.vector.Vector2f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AI script running every frame for all ally fleets.
 * Handles behavior selection, smooth following, and respawn.
 */
public class AllyFleetAI implements EveryFrameScript {

    public static Logger log = Global.getLogger(AllyFleetAI.class);

    private static final float UPDATE_INTERVAL_DAYS = 0.5f;
    private static final float RESPAWN_DELAY_DAYS = 7f;
    private static final float RESUPPLY_INTERVAL_DAYS = 5f;
    private static final float FOLLOW_CLOSE_DIST = 250f;

    private IntervalUtil updateTimer = new IntervalUtil(UPDATE_INTERVAL_DAYS * 0.75f, UPDATE_INTERVAL_DAYS * 1.25f);
    private IntervalUtil respawnTimer = new IntervalUtil(RESPAWN_DELAY_DAYS, RESPAWN_DELAY_DAYS);
    private IntervalUtil resupplyTimer = new IntervalUtil(RESUPPLY_INTERVAL_DAYS, RESUPPLY_INTERVAL_DAYS);

    public static final String KEY_LAST_ACTION = "$ally_lastAction";
    public static final String KEY_RETURNING_HOME = "$ally_returningHome";

    @Override
    public boolean isDone() { return false; }

    @Override
    public boolean runWhilePaused() { return false; }

    @Override
    public void advance(float amount) {
        float days = Global.getSector().getClock().convertToDays(amount);
        if (days <= 0) return;

        updateTimer.advance(days);
        respawnTimer.advance(days);
        resupplyTimer.advance(days);

        boolean shouldUpdate = updateTimer.intervalElapsed();
        boolean shouldResupply = resupplyTimer.intervalElapsed();
        CampaignFleetAPI playerFleet = Global.getSector().getPlayerFleet();
        if (playerFleet == null) return;

        for (AllyFleet ally : AllyFleetController.getFleets()) {
            CampaignFleetAPI fleet = ally.getFleet();

            if (fleet != null && !fleet.isAlive()) {
                ally.setFleet(null);
                ally.setAlive(false);
                fleet = null;
            }

            if (!ally.isAlive()) {
                if (respawnTimer.intervalElapsed()) {
                    AllyFleetController.respawnFleet(ally);
                }
                continue;
            }
            if (fleet == null) continue;

            // Per-frame smooth following: if following player, override move dest to close distance
            if (ally.getHighestPriorityAction() == AllyAction.FOLLOW && fleet.getBattle() == null) {
                smoothFollow(fleet, playerFleet);
            }

            if (shouldResupply) AllyFleetController.resupplyFleet(ally);
            if (shouldUpdate) decideAction(ally, fleet, playerFleet);
        }
    }

    /** Per-frame: steer towards a point behind the player so we form up smoothly */
    private void smoothFollow(CampaignFleetAPI fleet, CampaignFleetAPI player) {
        if (fleet.getContainingLocation() != player.getContainingLocation()) return;

        Vector2f playerPos = player.getLocation();
        Vector2f myPos = fleet.getLocation();
        float dist = Misc.getDistance(myPos, playerPos);

        if (dist > FOLLOW_CLOSE_DIST) {
            // Move towards player
            Vector2f diff = new Vector2f(playerPos.x - myPos.x, playerPos.y - myPos.y);
            float len = diff.length();
            diff.scale(1f / len); // normalize
            fleet.setMoveDestinationOverride(
                    myPos.x + diff.x * (dist - 200f),
                    myPos.y + diff.y * (dist - 200f));
        }
    }

    // ── Decision logic ──────────────────────────────────────────────

    private void decideAction(AllyFleet ally, CampaignFleetAPI fleet, CampaignFleetAPI playerFleet) {
        MemoryAPI mem = fleet.getMemoryWithoutUpdate();
        if (mem.getBoolean(KEY_RETURNING_HOME)) return;

        List<WeightedAction> actions = new ArrayList<>();

        if (ally.getPriorityFollow() > 0) {
            actions.add(new WeightedAction(AllyAction.FOLLOW, ally.getPriorityFollow(), playerFleet));
        }
        if (ally.getPriorityDefend() > 0) {
            MarketAPI threat = findMostThreatenedPlayerColony();
            if (threat != null)
                actions.add(new WeightedAction(AllyAction.DEFEND, ally.getPriorityDefend() * 1.5f, threat.getPrimaryEntity()));
        }
        if (ally.getPriorityPatrol() > 0) {
            MarketAPI home = ally.getHomeMarket();
            if (home != null && home.getStarSystem() != null)
                actions.add(new WeightedAction(AllyAction.PATROL, ally.getPriorityPatrol(), home.getStarSystem().getCenter()));
        }
        if (ally.getPriorityAttack() > 0) {
            CampaignFleetAPI target = findNearestEnemyFleet(fleet, ally);
            if (target != null)
                actions.add(new WeightedAction(AllyAction.ATTACK, ally.getPriorityAttack(), target));
        }
        if (ally.getPriorityTrade() > 0) {
            MarketAPI target = findBestTradeMarket(ally);
            if (target != null)
                actions.add(new WeightedAction(AllyAction.TRADE, ally.getPriorityTrade(), target.getPrimaryEntity()));
        }

        if (actions.isEmpty()) {
            giveFollowAssignment(fleet, playerFleet);
            mem.set(KEY_LAST_ACTION, AllyAction.FOLLOW.name());
            return;
        }

        Collections.sort(actions);
        WeightedAction best = actions.get(0);
        mem.set(KEY_LAST_ACTION, best.action.name());

        switch (best.action) {
            case FOLLOW:
                giveFollowAssignment(fleet, playerFleet);
                break;
            case DEFEND:
                if (best.target != null) {
                    fleet.clearAssignments();
                    fleet.addAssignment(FleetAssignment.DEFEND_LOCATION, best.target, 7f, "defending");
                    fleet.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE, best.target, 3f, "orbiting");
                }
                break;
            case PATROL:
                if (best.target != null) {
                    fleet.clearAssignments();
                    fleet.addAssignment(FleetAssignment.PATROL_SYSTEM, best.target, 10f, "patrolling");
                }
                break;
            case ATTACK:
                if (best.target instanceof CampaignFleetAPI) {
                    fleet.clearAssignments();
                    fleet.addAssignment(FleetAssignment.INTERCEPT, best.target, 5f, "intercepting");
                    fleet.addAssignment(FleetAssignment.ATTACK_LOCATION, best.target, 5f, "attacking");
                }
                break;
            case TRADE:
                if (best.target != null) {
                    fleet.clearAssignments();
                    fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, best.target, 1000f, "traveling");
                    fleet.addAssignment(FleetAssignment.ORBIT_PASSIVE, best.target, 2f, "trading");
                }
                break;
        }
    }

    private void giveFollowAssignment(CampaignFleetAPI fleet, CampaignFleetAPI playerFleet) {
        fleet.clearAssignments();
        fleet.addAssignment(FleetAssignment.FOLLOW, playerFleet, 1000000f, "following you");
    }

    private MarketAPI findMostThreatenedPlayerColony() {
        List<MarketAPI> markets = Misc.getPlayerMarkets(true);
        MarketAPI worst = null;
        float ws = Float.MAX_VALUE;
        for (MarketAPI m : markets) {
            if (!m.isHidden()) {
                float s = m.getStabilityValue();
                if (s < ws) { ws = s; worst = m; }
            }
        }
        return worst;
    }

    private CampaignFleetAPI findNearestEnemyFleet(CampaignFleetAPI fleet, AllyFleet ally) {
        FactionAPI faction = ally.getFaction();
        if (faction == null) return null;
        List<CampaignFleetAPI> nearby = Misc.getNearbyFleets(fleet, 2000);
        CampaignFleetAPI best = null;
        float bd = Float.MAX_VALUE;
        for (CampaignFleetAPI other : nearby) {
            if (other.isPlayerFleet() || other.getFaction() == null) continue;
            if (!faction.isHostileTo(other.getFaction())) continue;
            if (other.getFleetPoints() > fleet.getFleetPoints() * 1.5f) continue;
            float d = Misc.getDistance(fleet.getLocation(), other.getLocation());
            if (d < bd) { bd = d; best = other; }
        }
        return best;
    }

    private MarketAPI findBestTradeMarket(AllyFleet ally) {
        CampaignFleetAPI fleet = ally.getFleet();
        if (fleet == null) return null;
        float bd = Float.MAX_VALUE;
        MarketAPI best = null;
        for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
            if (m.isHidden() || !m.hasSpaceport()) continue;
            if (m.getFaction().isHostileTo(ally.getFaction())) continue;
            float d = Misc.getDistance(fleet.getLocationInHyperspace(), m.getLocationInHyperspace());
            if (d < bd) { bd = d; best = m; }
        }
        return best;
    }

    private static class WeightedAction implements Comparable<WeightedAction> {
        final AllyAction action;
        final float weight;
        final SectorEntityToken target;
        WeightedAction(AllyAction a, float w, SectorEntityToken t) { action = a; weight = w; target = t; }
        @Override public int compareTo(WeightedAction o) { return Float.compare(o.weight, weight); }
    }
}
