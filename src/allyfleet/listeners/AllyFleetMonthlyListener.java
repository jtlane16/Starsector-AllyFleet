package allyfleet.listeners;

import allyfleet.AllyFleet;
import allyfleet.controllers.AllyFleetController;
import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import org.apache.log4j.Logger;

/**
 * Runs monthly to process ally fleet income and upkeep.
 * Uses a 30-day timer interval instead of the economy month-end hook.
 */
public class AllyFleetMonthlyListener implements EveryFrameScript {

    public static Logger log = Global.getLogger(AllyFleetMonthlyListener.class);

    /** Monthly income per friendly market */
    private static final float INCOME_PER_MARKET = 15000f;

    /** Monthly ship upkeep per fleet point */
    private static final float UPKEEP_PER_FP = 50f;

    /** Monthly minimum income regardless of markets */
    private static final float BASE_INCOME = 50000f;

    /** Default priority when a new fleet is created */
    public static final int DEFAULT_FOLLOW_PRIORITY = 75;

    private IntervalUtil timer = new IntervalUtil(25f, 35f);

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

        timer.advance(days);
        if (!timer.intervalElapsed()) return;

        // Process each ally fleet
        for (AllyFleet ally : AllyFleetController.getFleets()) {
            if (!ally.isAlive()) continue;

            // Calculate income from friendly markets
            float income = BASE_INCOME;
            for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
                if (market.isHidden()) continue;
                if (!market.getFaction().isHostileTo(ally.getFaction()) && market.hasSpaceport()) {
                    income += INCOME_PER_MARKET;
                }
            }

            // Calculate upkeep cost
            float upkeep = 0f;
            if (ally.getFleet() != null && ally.getFleet().isAlive()) {
                upkeep = ally.getFleet().getFleetPoints() * UPKEEP_PER_FP;
            }

            float net = income - upkeep;
            ally.addCredits(net);

            if (net < -10000f) {
                log.info("Ally fleet " + ally.getFleetName() + " is losing money! Net: " + (int)net);
            }
        }
    }
}
