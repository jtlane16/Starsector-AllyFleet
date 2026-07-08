package allyfleet;

import allyfleet.ai.AllyFleetAI;
import allyfleet.controllers.AllyFleetController;
import allyfleet.listeners.AllyFleetMonthlyListener;
import allyfleet.plugins.AllyFleetCampaignPlugin;
import allyfleet.ui.AllyFleetOverlayWidget;
import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.thoughtworks.xstream.XStream;
import org.apache.log4j.Logger;

import java.util.HashMap;

public class AllyFleetModPlugin extends BaseModPlugin {

    public static Logger log = Global.getLogger(AllyFleetModPlugin.class);

    public static final String MOD_ID = "allyfleet";
    public static final String ALLY_FLEET_DATA_KEY = "allyfleet_data";

    // Single overlay widget instance used as script + listener
    private AllyFleetOverlayWidget overlayWidget;

    @Override
    public void configureXStream(XStream x) {
        x.alias("allyfleet.AllyFleet", AllyFleet.class);
    }

    @Override
    public void onApplicationLoad() throws Exception {
        log.info("Ally Fleet mod loading...");
    }

    @Override
    public void onGameLoad(boolean newGame) {
        SectorAPI sector = Global.getSector();

        initPersistentData(sector);

        if (!newGame) {
            AllyFleetController.loadFleets();
            log.info("Loaded " + AllyFleetController.getFleetCount() + " ally fleet(s)");
        }

        registerScripts(sector);
        registerOverlay(sector);
    }

    @Override
    public void onEnabled(boolean wasEnabledBefore) {
        if (wasEnabledBefore) return;

        SectorAPI sector = Global.getSector();
        initPersistentData(sector);
        registerScripts(sector);
        registerOverlay(sector);

        log.info("Ally Fleet mod enabled");
    }

    @Override
    public void beforeGameSave() {
        AllyFleetController.saveAll();
    }

    @Override
    public void onNewGame() {}

    @SuppressWarnings("unchecked")
    private void initPersistentData(SectorAPI sector) {
        if (!sector.getPersistentData().containsKey(ALLY_FLEET_DATA_KEY)) {
            sector.getPersistentData().put(ALLY_FLEET_DATA_KEY, new HashMap<String, HashMap<String, Object>>());
        }
    }

    private void registerScripts(SectorAPI sector) {
        sector.registerPlugin(new AllyFleetCampaignPlugin());

        if (!sector.hasTransientScript(AllyFleetAI.class)) {
            sector.addTransientScript(new AllyFleetAI());
        }

        sector.addScript(new AllyFleetMonthlyListener());
    }

    private void registerOverlay(SectorAPI sector) {
        if (overlayWidget != null) return;
        overlayWidget = new AllyFleetOverlayWidget();

        // Persistent registration — transient listeners/scripts get
        // cleaned up immediately, causing the 'flash then vanish' behavior.
        sector.getListenerManager().addListener(overlayWidget, false);
        sector.addScript(overlayWidget);
    }
}
