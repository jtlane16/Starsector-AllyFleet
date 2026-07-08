package allyfleet.util;

import allyfleet.AllyFleet;
import allyfleet.listeners.AllyFleetBattleListener;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.impl.campaign.fleets.FleetFactory;
import com.fs.starfarer.api.impl.campaign.ids.Abilities;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import org.apache.log4j.Logger;

/**
 * Creates and manages the in-game campaign fleets for ally fleets.
 */
public class AllyFleetFactory {

    public static Logger log = Global.getLogger(AllyFleetFactory.class);

    public static CampaignFleetAPI createFleetForAlly(AllyFleet ally, MarketAPI market) {
        if (ally == null || market == null) return null;

        LocationAPI location = market.getContainingLocation();
        if (location == null) location = Global.getSector().getHyperspace();

        // Create a commander if none exists
        PersonAPI commander = ally.getCommander();
        if (commander == null) {
            commander = market.getFaction().createRandomPerson();
            commander.setFaction(market.getFactionId());
            ally.setCommander(commander);
        }

        // Create empty fleet
        CampaignFleetAPI fleet = Global.getFactory().createEmptyFleet(ally.getFactionId(),
                ally.getFleetName(), true);
        fleet.setNoFactionInName(true);
        fleet.setAIMode(true);
        fleet.setNoAutoDespawn(true);
        fleet.setCommander(commander);

        // Add fast ships only — frigates with 8+ burn so they can keep up
        fleet.getFleetData().addFleetMember(
                Global.getFactory().createFleetMember(FleetMemberType.SHIP, "wolf_Assault"));
        fleet.getFleetData().addFleetMember(
                Global.getFactory().createFleetMember(FleetMemberType.SHIP, "lasher_Assault"));
        fleet.getFleetData().addFleetMember(
                Global.getFactory().createFleetMember(FleetMemberType.SHIP, "vigilance_Standard"));

        // Supplies & fuel
        fleet.getCargo().addSupplies(300);
        fleet.getCargo().addFuel(250);

        FleetFactory.finishAndSync(fleet);

        location.addEntity(fleet);
        float offX = market.getPrimaryEntity().getLocation().x + 300f + (float) Math.random() * 100f;
        float offY = market.getPrimaryEntity().getLocation().y + 300f + (float) Math.random() * 100f;
        fleet.setLocation(offX, offY);

        fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_WAR_FLEET, true);
        fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_SOURCE_MARKET, market.getId());

        fleet.addAbility(Abilities.TRANSVERSE_JUMP);
        fleet.addEventListener(new AllyFleetBattleListener());

        return fleet;
    }
}
