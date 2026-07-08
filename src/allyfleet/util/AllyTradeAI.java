package allyfleet.util;

import allyfleet.AllyFleet;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.econ.CommodityOnMarketAPI;
import com.fs.starfarer.api.campaign.econ.CommoditySpecAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.util.Misc;
import org.apache.log4j.Logger;

import java.util.*;

/**
 * Handles the Trade objective for the ally fleet.
 * Buys low at surplus markets, sells high at deficit markets.
 */
public class AllyTradeAI {

    public static Logger log = Global.getLogger(AllyTradeAI.class);

    // Commodities worth trading — bulk goods with stable demand
    private static final String[] TRADE_GOODS = {
            "supplies", "fuel", "food", "domestic_goods", "machinery",
            "ore", "metals", "rare_ore", "rare_metals",
            "organics", "volatiles", "transplutonics",
            "heavy_machinery"
    };

    // Memory keys
    private static final String KEY_BUY_MARKET = "$ally_trade_buyAt";
    private static final String KEY_SELL_MARKET = "$ally_trade_sellAt";
    private static final String KEY_CARGO_GOOD = "$ally_trade_good";
    private static final String KEY_CARGO_QTY = "$ally_trade_qty";

    /** Reserve credits for supplies/fuel */
    private static final float RESERVE_CREDITS = 50000f;

    /** Minimum profit to bother travelling */
    private static final float MIN_PROFIT_PER_UNIT = 20f;

    /**
     * Called periodically (every ~5-8 days) to advance trade logic.
     */
    public static void doTradeTick(AllyFleet ally, CampaignFleetAPI fleet) {
        if (ally == null || fleet == null || !fleet.isAlive()) return;

        CargoAPI cargo = fleet.getCargo();
        float credits = ally.getCredits();

        // ── 1. Sell cargo at current market if profitable ────────
        MarketAPI currentMarket = findCurrentMarket(fleet);
        if (currentMarket != null) {
            for (String good : TRADE_GOODS) {
                float qty = cargo.getCommodityQuantity(good);
                if (qty <= 0) continue;
                CommodityOnMarketAPI com = currentMarket.getCommodityData(good);
                int deficit = com.getDeficitQuantity();
                if (deficit > 0 && qty > 0) {
                    float sellQty = Math.min(qty, deficit);
                    float sellPrice = getSellPrice(com);
                    float revenue = sellQty * sellPrice;
                    ally.addCredits(revenue);
                    cargo.removeCommodity(good, sellQty);
                    com.removeFromStockpile(-sellQty); // market buys from us

                    log.info(ally.getFleetName() + " sold " + (int)sellQty + " " + good
                            + " at " + currentMarket.getName() + " for $" + (int)revenue);
                    Global.getSector().getCampaignUI().addMessage(
                            ally.getFleetName() + ": sold " + (int)sellQty + " " + good
                            + " for $" + (int)revenue, Misc.getPositiveHighlightColor());

                    // Clear sell target after selling
                    fleet.getMemoryWithoutUpdate().unset(KEY_SELL_MARKET);
                    fleet.getMemoryWithoutUpdate().unset(KEY_CARGO_GOOD);
                    fleet.getMemoryWithoutUpdate().unset(KEY_CARGO_QTY);
                    return;
                }
            }
        }

        // ── 2. If holding cargo, go sell it ───────────────────────
        for (String good : TRADE_GOODS) {
            float qty = cargo.getCommodityQuantity(good);
            if (qty <= 0) continue;

            MarketAPI sellMarket = findBestSellMarket(good, qty, fleet);
            if (sellMarket != null && sellMarket != currentMarket) {
                goToMarket(fleet, sellMarket, "selling " + good);
                return;
            }
        }

        // ── 3. Buy goods at current market if available ───────────
        if (currentMarket != null) {
            float available = fleet.getCargo().getSpaceLeft();
            float spendable = Math.max(0, credits - RESERVE_CREDITS);
            if (available > 10 && spendable > 1000) {
                for (String good : TRADE_GOODS) {
                    CommodityOnMarketAPI com = currentMarket.getCommodityData(good);
                    int excess = com.getExcessQuantity();
                    if (excess <= 0) continue;

                    float buyPrice = getBuyPrice(com);
                    int maxBuy = Math.min(excess, (int)(available / com.getCommodity().getCargoSpace()));
                    maxBuy = Math.min(maxBuy, (int)(spendable / Math.max(1, buyPrice)));

                    if (maxBuy <= 0) continue;

                    // Check if there's a market to sell to
                    if (findBestSellMarket(good, maxBuy, fleet) == null) continue;

                    float cost = maxBuy * buyPrice;
                    if (ally.spendCredits(cost)) {
                        cargo.addCommodity(good, maxBuy);
                        com.addToStockpile(-maxBuy); // market sells to us

                        log.info(ally.getFleetName() + " bought " + maxBuy + " " + good
                                + " at " + currentMarket.getName() + " for $" + (int)cost);
                        Global.getSector().getCampaignUI().addMessage(
                                ally.getFleetName() + ": bought " + maxBuy + " " + good
                                + " for $" + (int)cost, Misc.getNegativeHighlightColor());

                        // Go sell it
                        MarketAPI sellDest = findBestSellMarket(good, maxBuy, fleet);
                        if (sellDest != null) goToMarket(fleet, sellDest, "selling " + good);
                        return;
                    }
                }
            }
        }

        // ── 4. No trade to do — go to a nearby market to look ────
        if (cargo.getSpaceLeft() > 10 && credits > RESERVE_CREDITS + 10000) {
            MarketAPI target = findBestBuyMarket(fleet);
            if (target != null && target != currentMarket) {
                goToMarket(fleet, target, "seeking trade goods");
            }
        }
    }

    /** Get buy price per unit (approximate, includes supply-side modifier) */
    private static float getBuyPrice(CommodityOnMarketAPI com) {
        float base = com.getCommodity().getBasePrice();
        float mod = com.getPlayerSupplyPriceMod().computeEffective(0f);
        return base * (1f + mod);
    }

    /** Get sell price per unit (approximate, includes demand-side modifier) */
    private static float getSellPrice(CommodityOnMarketAPI com) {
        float base = com.getCommodity().getBasePrice();
        float mod = com.getPlayerDemandPriceMod().computeEffective(0f);
        return base * (1f + mod);
    }

    /** Find the market the fleet is currently at (or nearest to) */
    private static MarketAPI findCurrentMarket(CampaignFleetAPI fleet) {
        MarketAPI nearest = null;
        float bestDist = 300f; // within range to be "at" a market
        for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
            if (m.isHidden() || !m.hasSpaceport()) continue;
            float dist = Misc.getDistance(fleet.getLocationInHyperspace(), m.getLocationInHyperspace());
            if (dist < bestDist) { bestDist = dist; nearest = m; }
        }
        return nearest;
    }

    /** Find the best market to SELL a given commodity */
    private static MarketAPI findBestSellMarket(String good, float qty, CampaignFleetAPI fleet) {
        MarketAPI best = null;
        float bestProfit = 0;
        for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
            if (m.isHidden() || !m.hasSpaceport()) continue;
            if (m.getFaction().isHostileTo(fleet.getFaction())) continue;
            CommodityOnMarketAPI com = m.getCommodityData(good);
            int deficit = com.getDeficitQuantity();
            if (deficit <= 0) continue;
            float sellPrice = getSellPrice(com);
            float profit = Math.min(qty, deficit) * sellPrice;
            if (profit > bestProfit) { bestProfit = profit; best = m; }
        }
        return best;
    }

    /** Find the best market to BUY goods (has surplus of something we can carry) */
    private static MarketAPI findBestBuyMarket(CampaignFleetAPI fleet) {
        MarketAPI best = null;
        float bestScore = 0;
        float spendable = 100000;
        for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
            if (m.isHidden() || !m.hasSpaceport()) continue;
            if (m.getFaction().isHostileTo(fleet.getFaction())) continue;
            float score = 0;
            for (String good : TRADE_GOODS) {
                CommodityOnMarketAPI com = m.getCommodityData(good);
                int excess = com.getExcessQuantity();
                if (excess <= 0) continue;
                float buyPrice = getBuyPrice(com);
                int maxBuy = Math.min(excess, (int)(spendable / Math.max(1, buyPrice)));
                if (maxBuy <= 0) continue;
                MarketAPI sellTo = findBestSellMarket(good, maxBuy, fleet);
                if (sellTo == null) continue;
                float sellPrice = getSellPrice(sellTo.getCommodityData(good));
                float margin = (sellPrice - buyPrice) * maxBuy;
                if (margin > 0) score += margin;
            }
            float distPenalty = Misc.getDistance(fleet.getLocationInHyperspace(), m.getLocationInHyperspace()) / 1000f;
            score -= distPenalty;
            if (score > bestScore) { bestScore = score; best = m; }
        }
        return best;
    }

    /** Send the fleet to a market */
    private static void goToMarket(CampaignFleetAPI fleet, MarketAPI market, String action) {
        if (market.getPrimaryEntity() == null) return;
        fleet.clearAssignments();
        fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, market.getPrimaryEntity(), 1000f, action);
        fleet.addAssignment(FleetAssignment.ORBIT_PASSIVE, market.getPrimaryEntity(), 2f, "trading at " + market.getName());
    }
}
