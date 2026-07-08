package allyfleet.util;

import allyfleet.AllyFleet;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.ai.ModularFleetAIAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.impl.campaign.fleets.FleetFactory;
import com.fs.starfarer.api.impl.campaign.ids.Abilities;
import com.fs.starfarer.api.impl.campaign.ids.FleetTypes;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import org.apache.log4j.Logger;

/**
 * Creates and manages the in-game campaign fleets for ally fleets.
 */
public class AllyFleetFactory {

    public static Logger log = Global.getLogger(AllyFleetFactory.class);

    /** Base fleet points for a new ally fleet */
    public static final float BASE_FLEET_FP = 60f;

    /**
     * Create a campaign fleet for an ally fleet at the given market.
     * The fleet starts with a small combat-oriented force near the market.
     *
     * @param ally   The ally fleet data
     * @param market The market where the fleet spawns
     * @return The created campaign fleet, or null on failure
     */
    public static CampaignFleetAPI createFleetForAlly(AllyFleet ally, MarketAPI market) {
        if (ally == null || market == null) return null;

        LocationAPI location = market.getContainingLocation();
        if (location == null) {
            location = Global.getSector().getHyperspace();
        }

        // Create an empty fleet
        CampaignFleetAPI fleet = Global.getFactory().createEmptyFleet(ally.getFactionId(),
                ally.getFleetName(), true);
        fleet.setNoFactionInName(true);
        fleet.setAIMode(true);
        fleet.setNoAutoDespawn(true);
        fleet.setCommander(ally.getCommander());

        // Add some basic ships using the faction's ship roster
        String factionId = ally.getFactionId();

        // Start with a destroyer or cruiser flagship
        fleet.getFleetData().addFleetMember(
                Global.getFactory().createFleetMember(FleetMemberType.SHIP, pickFlagshipVariant(factionId)));

        // Add a couple of escorts
        fleet.getFleetData().addFleetMember(
                Global.getFactory().createFleetMember(FleetMemberType.SHIP, pickEscortVariant(factionId)));
        fleet.getFleetData().addFleetMember(
                Global.getFactory().createFleetMember(FleetMemberType.SHIP, pickEscortVariant(factionId)));

        // Add some fuel and supplies
        fleet.getCargo().addSupplies(200);
        fleet.getCargo().addFuel(150);

        // Sync the fleet
        FleetFactory.finishAndSync(fleet);

        // Add to the location
        location.addEntity(fleet);

        // Position near the market
        float offX = market.getPrimaryEntity().getLocation().x + 200f + (float) Math.random() * 200f;
        float offY = market.getPrimaryEntity().getLocation().y + 200f + (float) Math.random() * 200f;
        fleet.setLocation(offX, offY);

        // Set memory flags so it behaves like a proper AI fleet
        fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_WAR_FLEET, true);
        fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_SOURCE_MARKET, market.getId());

        // Give it transverse jump so it can follow the player
        fleet.addAbility(Abilities.TRANSVERSE_JUMP);

        // Attach battle listener for defeat detection
        fleet.addEventListener(new allyfleet.listeners.AllyFleetBattleListener());

        return fleet;
    }

    /**
     * Pick a flagship variant for the ally fleet based on its faction.
     */
    private static String pickFlagshipVariant(String factionId) {
        // Pick a cruiser or heavy destroyer based on faction
        switch (factionId) {
            case "hegemony":
                return "dominator_Outdated";
            case "persean":
                return "fury_Assault";
            case "tritachyon":
                return "aurora_Assault";
            case "sindrian_diktat":
                return "eagle_Assault";
            case "luddic_church":
                return "dominion_Outdated";
            case "pirates":
                return "venture_Standard";
            case "independent":
            default:
                String[] options = {"hammerhead_Elite", "sunder_Assault", "enforcer_Assault", "centurion_Standard"};
                return options[(int)(Math.random() * options.length)];
        }
    }

    /**
     * Pick an escort variant for the ally fleet.
     */
    private static String pickEscortVariant(String factionId) {
        String[] options = {
                "lasher_Standard", "lasher_Assault",
                "wolf_Standard", "wolf_Assault",
                "vigilance_FS", "vigilance_Standard",
                "tempest_Assault", "tempest_Standard",
                "omen_Standard", "omen_Assault"
        };
        return options[(int)(Math.random() * options.length)];
    }
}
