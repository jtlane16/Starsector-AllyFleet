package allyfleet.util;

import allyfleet.AllyAction;
import allyfleet.AllyFleet;
import allyfleet.util.AILog;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.impl.campaign.ids.Abilities;
import org.apache.log4j.Logger;

/**
 * Tracks virtual supply/fuel consumption for ally fleet and applies penalties when low.
 *
 * aiMode = true prevents actual CR decay, but we manually drain cargo supplies
 * each frame so the numbers are real. When supplies run critically low we remove
 * TRANSVERSE_JUMP so the fleet can't follow through hyperspace.
 *
 * Auto-resupply only happens in TRADE mode (part of doTradeTick).
 * In FOLLOW mode the fleet relies on the player to keep it supplied.
 */
public class AllyFleetSupplyManager {

    public static Logger log = Global.getLogger(AllyFleetSupplyManager.class);

    /** Thresholds as fraction of 30-day supply need */
    private static final float CRITICAL_THRESHOLD = 0.10f;
    private static final float LOW_THRESHOLD = 0.25f;
    private static final float RESUPPLY_TARGET = 0.60f;
    private static final float RESTORE_THRESHOLD = 0.50f;

    /** Days of supply to use as baseline for target stock */
    private static final int DAYS_TARGET = 30;

    /** Supply cost per unit at market (approximate) */
    private static final float SUPPLY_COST = 50f;

    /** Memory keys */
    private static final String KEY_DAYS_SINCE_RESUPPLY = "$ally_supply_days";
    private static final String KEY_SUPPLY_PENALTY = "$ally_supply_penalty_active";

    /**
     * Called every frame from AllyFleetAI.advance().
     * Deducts virtual supply consumption and applies/removes penalties.
     */
    public static void process(AllyFleet ally, CampaignFleetAPI fleet, float days) {
        if (ally == null || fleet == null || !fleet.isAlive()) return;

        // ── Consume supplies ──────────────────────────────────────
        float dailyUse = fleet.getTotalSupplyCostPerDay();
        float used = dailyUse * days;
        float current = fleet.getCargo().getSupplies();

        if (used > 0 && current > 0) {
            float toRemove = Math.min(used, current);
            fleet.getCargo().removeCommodity("supplies", toRemove);
            current -= toRemove;
        }

        // ── Calculate supply health ───────────────────────────────
        float targetStock = dailyUse * DAYS_TARGET;
        if (targetStock <= 0) { targetStock = 100; } // fallback for tiny fleets

        float ratio = current / targetStock;

        // ── Apply / remove penalties ──────────────────────────────
        boolean inPenalty = fleet.getMemoryWithoutUpdate().getBoolean(KEY_SUPPLY_PENALTY);

        if (ratio < CRITICAL_THRESHOLD) {
            applyCriticalPenalty(fleet, true);
            if (!inPenalty) {
                AILog.logSupply("CRITICAL — supplies=" + (int)current + "/" + (int)targetStock
                        + " dailyUse=" + (int)dailyUse + " daysLeft=" + (int)(current/dailyUse));
                log.info(ally.getFleetName() + " supplies CRITICAL (" + (int)current + "/" + (int)targetStock + ")");
            }
        } else if (ratio < LOW_THRESHOLD) {
            applyLowPenalty(fleet, true);
            if (!inPenalty) {
                AILog.logSupply("LOW — supplies=" + (int)current + "/" + (int)targetStock
                        + " dailyUse=" + (int)dailyUse + " daysLeft=" + (int)(current/dailyUse));
                log.info(ally.getFleetName() + " supplies LOW (" + (int)current + "/" + (int)targetStock + ")");
            }
        } else if (ratio > RESTORE_THRESHOLD && inPenalty) {
            restoreAll(fleet);
            AILog.logSupply("RESTORED — supplies=" + (int)current + "/" + (int)targetStock);
            log.info(ally.getFleetName() + " supplies restored (" + (int)current + "/" + (int)targetStock + ")");
        }
    }

    /**
     * Called from AllyTradeAI when the fleet is at a market in TRADE mode.
     * Buys enough supplies to reach RESUPPLY_TARGET (60% of 30-day need).
     */
    public static boolean autoResupply(AllyFleet ally, CampaignFleetAPI fleet) {
        if (ally == null || fleet == null || !fleet.isAlive()) return false;

        float dailyUse = fleet.getTotalSupplyCostPerDay();
        float targetStock = dailyUse * DAYS_TARGET;
        if (targetStock <= 0) targetStock = 100;

        float current = fleet.getCargo().getSupplies();
        float desired = targetStock * RESUPPLY_TARGET;

        if (current >= desired) return false; // already enough

        float toBuy = desired - current;
        float cost = toBuy * SUPPLY_COST;

        // Keep a minimum operating reserve
        float reserve = Math.max(50000f, dailyUse * 30 * SUPPLY_COST * 0.5f);
        if (ally.getCredits() - cost < reserve) {
            cost = Math.max(0, ally.getCredits() - reserve);
            toBuy = cost / SUPPLY_COST;
            if (toBuy <= 0) return false;
        }

        if (ally.spendCredits(cost)) {
            fleet.getCargo().addSupplies((int)toBuy);
            AILog.logSupply("BOUGHT " + (int)toBuy + " supplies for $" + (int)cost
                    + " (now=" + (int)fleet.getCargo().getSupplies()
                    + " dailyUse=" + (int)dailyUse + ")");
            Global.getSector().getCampaignUI().addMessage(
                    ally.getFleetName() + ": bought " + (int)toBuy + " supplies for $" + (int)cost,
                    Global.getSector().getPlayerFleet().getFaction().getBaseUIColor());
            return true;
        }
        return false;
    }

    // ── Penalty management ────────────────────────────────────────

    private static void applyCriticalPenalty(CampaignFleetAPI fleet, boolean active) {
        if (active && fleet.hasAbility(Abilities.TRANSVERSE_JUMP)) {
            fleet.removeAbility(Abilities.TRANSVERSE_JUMP);
            fleet.getMemoryWithoutUpdate().set(KEY_SUPPLY_PENALTY, true);
        }
        if (!active) restoreAll(fleet);
    }

    private static void applyLowPenalty(CampaignFleetAPI fleet, boolean active) {
        if (active && fleet.hasAbility(Abilities.TRANSVERSE_JUMP)) {
            fleet.removeAbility(Abilities.TRANSVERSE_JUMP);
            fleet.getMemoryWithoutUpdate().set(KEY_SUPPLY_PENALTY, true);
        }
        if (!active) restoreAll(fleet);
    }

    private static void restoreAll(CampaignFleetAPI fleet) {
        if (!fleet.hasAbility(Abilities.TRANSVERSE_JUMP)) {
            fleet.addAbility(Abilities.TRANSVERSE_JUMP);
        }
        fleet.getMemoryWithoutUpdate().unset(KEY_SUPPLY_PENALTY);
    }
}
