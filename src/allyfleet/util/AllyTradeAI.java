package allyfleet.util;

import allyfleet.AllyFleet;
import allyfleet.util.AILog;
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
            "food", "domestic_goods",
            "ore", "metals", "rare_ore", "rare_metals",
            "organics", "volatiles",
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

        // Trade mode: avoid combat
        fleet.setNoEngaging(60f * 60f * 24f * 5f); // 5 days passive

        // ── 0. Log tick start ─────────────────────────────────────
        MarketAPI currentMarket = findCurrentMarket(fleet);
        AILog.logTrade("Tick — credits=$" + (int)credits
                + " cargoSpace=" + (int)cargo.getSpaceLeft()
                + " atMarket=" + (currentMarket != null ? currentMarket.getName() : "NONE")
                + " loc=" + (fleet.getContainingLocation() != null ? fleet.getContainingLocation().getName() : "null"));

        // ── 1. Sell cargo at current market if profitable ────────
        if (currentMarket != null) {
            for (String good : TRADE_GOODS) {
                float qty = cargo.getCommodityQuantity(good);
                if (qty <= 0) continue;
                CommodityOnMarketAPI com = currentMarket.getCommodityData(good);
                float sellPrice = getSellPrice(com);
                float revenue = qty * sellPrice;
                AILog.logTrade("  sell-check: " + good + " qty=" + (int)qty + " price=" + (int)sellPrice);
                if (revenue > 0 && qty > 0) {
                    float sellQty = qty;
                    ally.addCredits(revenue);
                    cargo.removeCommodity(good, sellQty);
                    com.removeFromStockpile(-sellQty);
                    AILog.logTrade("  SOLD " + (int)sellQty + " " + good + " for $" + (int)revenue
                            + " at " + currentMarket.getName());

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
                AILog.logTrade("  go sell " + (int)qty + " " + good + " at " + sellMarket.getName());
                goToMarket(fleet, sellMarket, "selling " + good);
                return;
            } else if (sellMarket == null) {
                AILog.logTrade("  no sell-market for cargo " + good + " (qty=" + (int)qty + ")");
            } else {
                AILog.logTrade("  already at best sell-market for " + good);
            }
        }

        // ── 3. Buy goods at current market if available ───────────
        if (currentMarket != null) {
            float available = fleet.getCargo().getSpaceLeft();
            float spendable = Math.max(0, credits - RESERVE_CREDITS);
            AILog.logTrade("  buy-check: space=" + (int)available + " spendable=" + (int)spendable
                    + " at " + currentMarket.getName());
            if (available > 0 && spendable > 1000) {
                for (String good : TRADE_GOODS) {
                    CommodityOnMarketAPI com = currentMarket.getCommodityData(good);
                    float buyPrice = getBuyPrice(com);
                    int maxBuy = (int)(available / com.getCommodity().getCargoSpace());
                    maxBuy = Math.min(maxBuy, (int)(spendable / Math.max(1, buyPrice)));

                    if (maxBuy <= 0) continue;

                    // Check if a sell destination exists (any market, not just deficit)
                    MarketAPI sellTo = findBestSellMarket(good, maxBuy, fleet);
                    if (sellTo == null || sellTo == currentMarket) {
                        AILog.logTrade("    " + good + ": price=" + (int)buyPrice
                                + " maxBuy=" + maxBuy + " — no sell destination, skipping");
                        continue;
                    }

                    float cost = maxBuy * buyPrice;
                    if (ally.spendCredits(cost)) {
                        cargo.addCommodity(good, maxBuy);
                        com.addToStockpile(-maxBuy);
                        AILog.logTrade("  BOUGHT " + maxBuy + " " + good + " for $" + (int)cost
                                + " at " + currentMarket.getName() + ", selling at " + sellTo.getName());

                        log.info(ally.getFleetName() + " bought " + maxBuy + " " + good
                                + " at " + currentMarket.getName() + " for $" + (int)cost);
                        Global.getSector().getCampaignUI().addMessage(
                                ally.getFleetName() + ": bought " + maxBuy + " " + good
                                + " for $" + (int)cost, Misc.getNegativeHighlightColor());

                        // Go sell it
                        if (sellTo != null) goToMarket(fleet, sellTo, "selling " + good);
                        return;
                    }
                }
            }
        }

        // ── 4. No trade to do — go to a nearby market to look ────
        if (credits > RESERVE_CREDITS + 10000) {
            AILog.logTrade("  seeking market... space=" + (int)cargo.getSpaceLeft() + " credits=" + (int)credits);
            MarketAPI target = findBestBuyMarket(fleet, currentMarket);
            if (target != null && target != currentMarket) {
                AILog.logTrade("  seeking trade at " + target.getName()
                        + " (dist=" + (int) Misc.getDistance(fleet.getLocationInHyperspace(), target.getLocationInHyperspace()) + ")");
                goToMarket(fleet, target, "seeking trade goods");
            } else if (target == null || target == currentMarket) {
                AILog.logTrade("  no buy-market found — trying any other friendly market");
                target = findNearestFriendlyMarket(fleet, currentMarket);
                if (target != null) {
                    AILog.logTrade("  going to " + target.getName() + " (dist="
                            + (int) Misc.getDistance(fleet.getLocationInHyperspace(), target.getLocationInHyperspace()) + ")");
                    goToMarket(fleet, target, "visiting market");
                } else {
                    AILog.logTrade("  no friendly market reachable — brute-forcing ANY non-hostile market");
                    for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
                        if (m.isHidden() || !m.hasSpaceport()) continue;
                        if (m == currentMarket) continue;
                        if (m.getFaction().isHostileTo(fleet.getFaction())) {
                            AILog.logTrade("    hostile: " + m.getName() + " (" + m.getFaction().getDisplayName() + ")");
                            continue;
                        }
                        AILog.logTrade("  brute-force going to " + m.getName());
                        goToMarket(fleet, m, "visiting market");
                        target = m;
                        break;
                    }
                    if (target == null) {
                        AILog.logTrade("  NO FRIENDLY MARKET EXISTS ANYWHERE");
                    }
                }
            }
        } else {
            AILog.logTrade("  no travel: credits=" + (int)credits + " below reserve threshold");
        }

        // ── 5. Consider buying a ship for balanced fleet growth ──
        if (currentMarket != null) {
            tryBuyShip(ally, fleet, currentMarket);
        }
        AILog.logTrade("  tick end — idle");
    }

    /** Attempt to buy a ship that fills a role gap in the fleet. */
    private static void tryBuyShip(AllyFleet ally, CampaignFleetAPI fleet, MarketAPI market) {
        if (market.getFaction().isHostileTo(ally.getFaction())) return;

        float credits = ally.getCredits();
        if (credits < RESERVE_CREDITS + 20000) return; // can't afford anything

        // Count current ships
        int frigates = 0, destroyers = 0, freighters = 0;
        for (com.fs.starfarer.api.fleet.FleetMemberAPI m : fleet.getFleetData().getMembersInPriorityOrder()) {
            if (m.isFighterWing()) continue;
            float dp = m.getUnmodifiedDeploymentPointsCost();
            boolean isFreighter = m.getVariant().hasHullMod("cargoholds") || m.getHullSpec().getHints().contains(com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints.FREIGHTER) || m.getHullSpec().getHints().contains(com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints.TANKER);
            if (isFreighter) freighters++;
            else if (dp <= 8) frigates++;
            else destroyers++;
        }

        // Determine which role to buy
        String targetVariantId = null;
        int total = Math.max(1, frigates + destroyers + freighters);

        // Target: ~40% combat frigates, ~30% combat larger, ~30% freighters
        float frigatePct = frigates / (float)total;
        float destroyerPct = destroyers / (float)total;
        float freighterPct = freighters / (float)total;

        String factionId = ally.getFactionId();

        if (freighterPct < 0.25f) {
            targetVariantId = pickFreighterVariant(factionId);
        } else if (frigatePct < 0.35f) {
            targetVariantId = pickFrigateVariant(factionId);
        } else if (destroyerPct < 0.25f) {
            targetVariantId = pickDestroyerVariant(factionId);
        } else {
            targetVariantId = pickFrigateVariant(factionId); // round out with frigates
        }

        if (targetVariantId == null) return;

        // Calculate cost (750 credits per DP)
        com.fs.starfarer.api.fleet.FleetMemberAPI temp =
                Global.getFactory().createFleetMember(com.fs.starfarer.api.fleet.FleetMemberType.SHIP, targetVariantId);
        float dp = temp.getUnmodifiedDeploymentPointsCost();
        float cost = dp * 750f;

        if (cost > credits - RESERVE_CREDITS) return; // can't afford with reserve

        // Check crew space
        int crew = temp.getCrewComposition().getCrewInt();
        int freeCrew = fleet.getCargo().getFreeCrewSpace();
        if (crew > freeCrew) return;

        // Buy the ship
        if (ally.spendCredits(cost)) {
            fleet.getFleetData().addFleetMember(temp);
            fleet.forceSync();
            log.info(ally.getFleetName() + " bought "
                    + temp.getHullSpec().getHullNameWithDashClass() + " for $" + (int)cost);
            Global.getSector().getCampaignUI().addMessage(
                    ally.getFleetName() + ": bought "
                    + temp.getHullSpec().getHullNameWithDashClass()
                    + " for $" + (int)cost, Misc.getPositiveHighlightColor());
        }
    }

    private static String pickFreighterVariant(String factionId) {
        String[] options = {"buffalo_Standard", "buffalo_FS", "kite_Standard", "kite_Scout"};
        return options[(int)(Math.random() * options.length)];
    }

    private static String pickFrigateVariant(String factionId) {
        String[] options = {"lasher_Standard", "lasher_Assault", "wolf_Standard", "wolf_Assault",
                "vigilance_FS", "tempest_Assault", "centurion_Standard"};
        return options[(int)(Math.random() * options.length)];
    }

    private static String pickDestroyerVariant(String factionId) {
        String[] options = {"hammerhead_Elite", "sunder_Assault", "enforcer_Assault", "medusa_Standard",
                "condor_Standard"};
        return options[(int)(Math.random() * options.length)];
    }

    /** Get buy price per unit (what the fleet would pay buying from this market) */
    private static float getBuyPrice(CommodityOnMarketAPI com) {
        float base = com.getCommodity().getBasePrice();
        return com.getPlayerSupplyPriceMod().computeEffective(base);
    }

    /** Get sell price per unit (what the fleet would receive selling to this market) */
    private static float getSellPrice(CommodityOnMarketAPI com) {
        float base = com.getCommodity().getBasePrice();
        return com.getPlayerDemandPriceMod().computeEffective(base);
    }

    /** Find the market the fleet is currently at (orbiting or very close to) */
    private static MarketAPI findCurrentMarket(CampaignFleetAPI fleet) {
        if (fleet.getContainingLocation() == null) return null;
        MarketAPI nearest = null;
        float bestDist = 400f;
        for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
            if (m.isHidden() || !m.hasSpaceport()) continue;
            // Must be in the same location (same star system or hyperspace)
            if (m.getContainingLocation() != fleet.getContainingLocation()) continue;
            if (m.getPrimaryEntity() == null) continue;
            float dist = Misc.getDistance(fleet.getLocation(), m.getPrimaryEntity().getLocation());
            if (dist < bestDist) { bestDist = dist; nearest = m; }
        }
        return nearest;
    }

    /** Find the nearest friendly/neutral market with a spaceport, optionally skipping one */
    private static MarketAPI findNearestFriendlyMarket(CampaignFleetAPI fleet, MarketAPI skipMarket) {
        MarketAPI best = null;
        float bestDist = Float.MAX_VALUE;
        int total = 0, hidden = 0, noPort = 0, hostile = 0;
        for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
            total++;
            if (m == skipMarket) continue;
            if (m.isHidden()) { hidden++; continue; }
            if (!m.hasSpaceport()) { noPort++; continue; }
            if (m.getFaction().isHostileTo(fleet.getFaction())) { hostile++; continue; }
            float dist = Misc.getDistance(fleet.getLocationInHyperspace(), m.getLocationInHyperspace());
            if (dist < bestDist) { bestDist = dist; best = m; }
        }
        AILog.logTrade("  nearestMarket: total=" + total + " hidden=" + hidden
                + " noPort=" + noPort + " hostile=" + hostile
                + " fleetFaction=" + (fleet.getFaction() != null ? fleet.getFaction().getDisplayName() : "NULL"));
        return best;
    }

    /** Find any friendly market different from the current one — for selling */
    private static MarketAPI findBestSellMarket(String good, float qty, CampaignFleetAPI fleet) {
        MarketAPI best = null;
        float bestPrice = 0;
        for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
            if (m.isHidden() || !m.hasSpaceport()) continue;
            if (m.getFaction().isHostileTo(fleet.getFaction())) continue;
            CommodityOnMarketAPI com = m.getCommodityData(good);
            float sellPrice = com != null ? getSellPrice(com) : 0;
            if (sellPrice > bestPrice || best == null) { bestPrice = sellPrice; best = m; }
        }
        return best;
    }

    /** Find the best market to BUY goods — based on real profit margin with correct pricing */
    private static MarketAPI findBestBuyMarket(CampaignFleetAPI fleet, MarketAPI skipMarket) {
        MarketAPI best = null;
        float bestScore = -999999;
        float spendable = 100000;
        for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
            if (m.isHidden() || !m.hasSpaceport()) continue;
            if (m == skipMarket) continue;
            if (m.getFaction().isHostileTo(fleet.getFaction())) continue;
            float score = 0;
            for (String good : TRADE_GOODS) {
                CommodityOnMarketAPI com = m.getCommodityData(good);
                float buyPrice = com != null ? getBuyPrice(com) : 999999;
                int maxBuy = (int)(spendable / Math.max(1, buyPrice));
                if (maxBuy <= 0) continue;
                MarketAPI sellTo = findBestSellMarket(good, maxBuy, fleet);
                if (sellTo == null || sellTo == m || sellTo == skipMarket) continue;
                float sellPrice = getSellPrice(sellTo.getCommodityData(good));
                float margin = (sellPrice - buyPrice) * maxBuy;
                if (margin > 0) score += margin;
            }
            float distPenalty = Misc.getDistance(fleet.getLocationInHyperspace(), m.getLocationInHyperspace()) / 200f;
            score -= distPenalty;
            if (score > bestScore) { bestScore = score; best = m; }
        }
        return best;
    }

    /** Send the fleet to a market — skips if already assigned there */
    private static void goToMarket(CampaignFleetAPI fleet, MarketAPI market, String action) {
        if (market.getPrimaryEntity() == null) return;
        String memKey = "$ally_trade_target";
        String currentTarget = fleet.getMemoryWithoutUpdate().getString(memKey);
        if (currentTarget != null && currentTarget.equals(market.getName())) {
            return; // already heading there
        }
        fleet.clearAssignments();
        fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, market.getPrimaryEntity(), 1000f, action);
        fleet.addAssignment(FleetAssignment.ORBIT_PASSIVE, market.getPrimaryEntity(), 3f, "trading at " + market.getName());
        fleet.getMemoryWithoutUpdate().set(memKey, market.getName());
    }
}
