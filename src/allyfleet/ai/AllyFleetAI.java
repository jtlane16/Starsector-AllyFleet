package allyfleet.ai;

import allyfleet.AllyAction;
import allyfleet.AllyFleet;
import allyfleet.controllers.AllyFleetController;
import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import org.apache.log4j.Logger;
import org.lwjgl.util.vector.Vector2f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AllyFleetAI implements EveryFrameScript {

    public static Logger log = Global.getLogger(AllyFleetAI.class);

    private static final float UPDATE_INTERVAL_DAYS = 0.5f;
    private static final float FOLLOW_REISSUE_DIST = 600f;
    private static final float FOLLOW_CLOSE_DIST = 300f;

    private IntervalUtil updateTimer = new IntervalUtil(UPDATE_INTERVAL_DAYS * 0.75f, UPDATE_INTERVAL_DAYS * 1.25f);
    private IntervalUtil respawnTimer = new IntervalUtil(7f, 7f);
    private IntervalUtil resupplyTimer = new IntervalUtil(5f, 5f);

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
                ally.setFleet(null); ally.setAlive(false); fleet = null;
            }
            if (!ally.isAlive()) {
                if (respawnTimer.intervalElapsed()) AllyFleetController.respawnFleet(ally);
                continue;
            }
            if (fleet == null) continue;

            // Auto-set default follow priority if nothing configured
            if (ally.getPriorityFollow() == 0 && ally.getPriorityDefend() == 0 &&
                ally.getPriorityTrade() == 0 && ally.getPriorityPatrol() == 0 &&
                ally.getPriorityAttack() == 0) {
                ally.setPriority(AllyAction.FOLLOW, 75);
            }

            MemoryAPI mem = fleet.getMemoryWithoutUpdate();
            if (mem.getBoolean(KEY_RETURNING_HOME)) continue;

            // Determine if we should be following
            boolean shouldFollow = ally.getPriorityFollow() > 0 &&
                ally.getHighestPriorityAction() == AllyAction.FOLLOW;

            if (shouldFollow && fleet.getBattle() == null) {
                LocationAPI playerLoc = playerFleet.getContainingLocation();
                LocationAPI myLoc = fleet.getContainingLocation();

                // Same system: override movement to close distance
                if (myLoc == playerLoc) {
                    float dist = Misc.getDistance(fleet.getLocation(), playerFleet.getLocation());
                    if (dist > FOLLOW_CLOSE_DIST) {
                        fleet.setMoveDestinationOverride(
                            playerFleet.getLocation().x, playerFleet.getLocation().y);
                    }
                    if (dist > FOLLOW_REISSUE_DIST) {
                        giveFollowAssignment(fleet, playerFleet);
                    }
                }
            }

            if (shouldResupply) AllyFleetController.resupplyFleet(ally);
            if (shouldUpdate) decideAction(ally, fleet, playerFleet);
        }
    }

    private void giveFollowAssignment(CampaignFleetAPI fleet, CampaignFleetAPI target) {
        fleet.clearAssignments();
        fleet.addAssignment(FleetAssignment.FOLLOW, target, 1000000f, "following you");
    }

    private void decideAction(AllyFleet ally, CampaignFleetAPI fleet, CampaignFleetAPI playerFleet) {
        MemoryAPI mem = fleet.getMemoryWithoutUpdate();
        if (mem.getBoolean(KEY_RETURNING_HOME)) return;

        List<WeightedAction> actions = new ArrayList<>();

        if (ally.getPriorityFollow() > 0)
            actions.add(new WeightedAction(AllyAction.FOLLOW, ally.getPriorityFollow(), playerFleet));
        if (ally.getPriorityDefend() > 0) {
            MarketAPI t = findMostThreatenedPlayerColony();
            if (t != null) actions.add(new WeightedAction(AllyAction.DEFEND, ally.getPriorityDefend() * 1.5f, t.getPrimaryEntity()));
        }
        if (ally.getPriorityPatrol() > 0) {
            MarketAPI h = ally.getHomeMarket();
            if (h != null && h.getStarSystem() != null)
                actions.add(new WeightedAction(AllyAction.PATROL, ally.getPriorityPatrol(), h.getStarSystem().getCenter()));
        }
        if (ally.getPriorityAttack() > 0) {
            CampaignFleetAPI t = findNearestEnemyFleet(fleet, ally);
            if (t != null) actions.add(new WeightedAction(AllyAction.ATTACK, ally.getPriorityAttack(), t));
        }
        if (ally.getPriorityTrade() > 0) {
            MarketAPI t = findBestTradeMarket(ally);
            if (t != null) actions.add(new WeightedAction(AllyAction.TRADE, ally.getPriorityTrade(), t.getPrimaryEntity()));
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
            case FOLLOW: giveFollowAssignment(fleet, playerFleet); break;
            case DEFEND:
                fleet.clearAssignments();
                fleet.addAssignment(FleetAssignment.DEFEND_LOCATION, best.target, 7f, "defending");
                fleet.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE, best.target, 3f, "orbiting");
                break;
            case PATROL:
                fleet.clearAssignments();
                fleet.addAssignment(FleetAssignment.PATROL_SYSTEM, best.target, 10f, "patrolling");
                break;
            case ATTACK:
                if (best.target instanceof CampaignFleetAPI) {
                    fleet.clearAssignments();
                    fleet.addAssignment(FleetAssignment.INTERCEPT, best.target, 5f, "intercepting");
                    fleet.addAssignment(FleetAssignment.ATTACK_LOCATION, best.target, 5f, "attacking");
                }
                break;
            case TRADE:
                fleet.clearAssignments();
                fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, best.target, 1000f, "traveling");
                fleet.addAssignment(FleetAssignment.ORBIT_PASSIVE, best.target, 2f, "trading");
                break;
        }
    }

    private MarketAPI findMostThreatenedPlayerColony() {
        MarketAPI worst = null; float ws = Float.MAX_VALUE;
        for (MarketAPI m : Misc.getPlayerMarkets(true))
            if (!m.isHidden() && m.getStabilityValue() < ws) { ws = m.getStabilityValue(); worst = m; }
        return worst;
    }

    private CampaignFleetAPI findNearestEnemyFleet(CampaignFleetAPI f, AllyFleet ally) {
        FactionAPI fac = ally.getFaction();
        if (fac == null) return null;
        float bd = Float.MAX_VALUE;
        CampaignFleetAPI best = null;
        for (CampaignFleetAPI o : Misc.getNearbyFleets(f, 2000)) {
            if (o.isPlayerFleet() || o.getFaction() == null || !fac.isHostileTo(o.getFaction())) continue;
            if (o.getFleetPoints() > f.getFleetPoints() * 1.5f) continue;
            float d = Misc.getDistance(f.getLocation(), o.getLocation());
            if (d < bd) { bd = d; best = o; }
        }
        return best;
    }

    private MarketAPI findBestTradeMarket(AllyFleet ally) {
        CampaignFleetAPI f = ally.getFleet();
        if (f == null) return null;
        float bd = Float.MAX_VALUE;
        MarketAPI best = null;
        for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
            if (m.isHidden() || !m.hasSpaceport() || m.getFaction().isHostileTo(ally.getFaction())) continue;
            float d = Misc.getDistance(f.getLocationInHyperspace(), m.getLocationInHyperspace());
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
