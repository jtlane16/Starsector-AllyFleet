package allyfleet.ai;

import allyfleet.AllyAction;
import allyfleet.AllyFleet;
import allyfleet.controllers.AllyFleetController;
import allyfleet.util.AILog;
import allyfleet.util.AllyFleetSupplyManager;
import allyfleet.util.AllyTradeAI;
import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;

/**
 * AI for the single ally fleet — Follow and Trade objectives.
 * Supply consumption runs every frame; auto-resupply only happens in Trade mode.
 */
public class AllyFleetAI implements EveryFrameScript {

    private static final float UPDATE_INTERVAL_DAYS = 0.25f;
    private static final float RESP_DELAY = 7f;
    private static final float FOLLOW_CLOSE = 300f;
    private static final float FOLLOW_REISSUE = 600f;

    private IntervalUtil timer = new IntervalUtil(UPDATE_INTERVAL_DAYS * 0.75f, UPDATE_INTERVAL_DAYS * 1.25f);
    private IntervalUtil respawnTimer = new IntervalUtil(RESP_DELAY, RESP_DELAY);
    private IntervalUtil tradeTimer = new IntervalUtil(0.5f, 1.5f);

    public static final String KEY_ACTION = "$ally_action";
    public static final String KEY_RETURNING = "$ally_returningHome";

    @Override public boolean isDone() { return false; }
    @Override public boolean runWhilePaused() { return false; }

    @Override
    public void advance(float amount) {
        float days = Global.getSector().getClock().convertToDays(amount);
        if (days <= 0) return;

        timer.advance(days);
        respawnTimer.advance(days);
        tradeTimer.advance(days);

        CampaignFleetAPI player = Global.getSector().getPlayerFleet();
        if (player == null) return;

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

            // Auto-set Follow if nothing configured
            if (ally.getPriorityFollow() == 0 && ally.getPriorityTrade() == 0)
                ally.setPriority(AllyAction.FOLLOW, 75);

            MemoryAPI mem = fleet.getMemoryWithoutUpdate();
            if (mem.getBoolean(KEY_RETURNING)) continue;

            AllyAction action = currentObjective(ally);

            // ── Supply consumption (every frame) ──────────────────
            AllyFleetSupplyManager.process(ally, fleet, days);

            // ── FOLLOW: per-frame override ────────────────────────
            if (action == AllyAction.FOLLOW && fleet.getBattle() == null) {
                if (fleet.getContainingLocation() == player.getContainingLocation()) {
                    float dist = Misc.getDistance(fleet.getLocation(), player.getLocation());
                    if (dist > FOLLOW_CLOSE)
                        fleet.setMoveDestinationOverride(player.getLocation().x, player.getLocation().y);
                    if (dist > FOLLOW_REISSUE) {
                        fleet.clearAssignments();
                        fleet.addAssignment(FleetAssignment.FOLLOW, player, 1000000f, "following you");
                    }
                }
            }

            // ── TRADE: periodic + supply resupply ─────────────
            if (action == AllyAction.TRADE && tradeTimer.intervalElapsed()) {
                AILog.logAI("Trade tick firing — fleet=" + (fleet != null ? fleet.getName() : "null")
                        + " loc=" + (fleet.getContainingLocation() != null ? fleet.getContainingLocation().getName() : "null"));
                AllyFleetSupplyManager.autoResupply(ally, fleet);
                AllyTradeAI.doTradeTick(ally, fleet);
            }
        }
    }

    private AllyAction currentObjective(AllyFleet fleet) {
        for (AllyAction a : AllyAction.values())
            if (fleet.getPriority(a) > 0) return a;
        return AllyAction.FOLLOW;
    }
}
