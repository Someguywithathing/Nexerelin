package exerelin.plugins;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.CoreScript;
import com.fs.starfarer.api.impl.campaign.fleets.PatrolFleetManager;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.thoughtworks.xstream.XStream;
import data.scripts.campaign.SSP_CoreScript;
import exerelin.ExerelinConstants;
import exerelin.campaign.AllianceManager;
import exerelin.campaign.CovertOpsManager;
import exerelin.campaign.DiplomacyManager;
import exerelin.campaign.DirectoryScreenScript;
import exerelin.campaign.ExerelinCoreScript;
import exerelin.campaign.PlayerFactionStore;
import exerelin.campaign.PlayerStartHandler;
import exerelin.campaign.ReinitScreenScript;
import exerelin.campaign.SectorManager;
import exerelin.campaign.StatsTracker;
import exerelin.campaign.events.RevengeanceFleetEvent;
import exerelin.utilities.*;
import exerelin.campaign.fleets.InvasionFleetManager;
import exerelin.campaign.fleets.MiningFleetManager;
import exerelin.campaign.fleets.ResponseFleetManager;
import exerelin.campaign.missions.ConquestMissionCreator;
import java.util.HashMap;

public class ExerelinModPlugin extends BaseModPlugin
{
    // call order: onNewGame -> onNewGameAfterEconomyLoad -> onEnabled -> onNewGameAfterTimePass -> onGameLoad
    public static final boolean HAVE_SSP;
    public static final boolean HAVE_SSP_LEGACY;
    public static final boolean HAVE_SWP = Global.getSettings().getModManager().isModEnabled("swp");
    public static final boolean HAVE_DYNASECTOR = Global.getSettings().getModManager().isModEnabled("dynasector");
    public static final boolean HAVE_UNDERWORLD = Global.getSettings().getModManager().isModEnabled("underworld");
    public static final boolean HAVE_STELLAR_INDUSTRIALIST = Global.getSettings().getModManager().isModEnabled("stellar_industrialist");
    
    protected static boolean isNewGame = false;
    
    static {
        boolean sspLoaded = Global.getSettings().getModManager().isModEnabled("dr_ssp");
        if (!sspLoaded) {
            HAVE_SSP = false;
            HAVE_SSP_LEGACY = false;
        }
        else {
            HAVE_SSP = true;
            HAVE_SSP_LEGACY = Global.getSettings().getModManager().getModSpec("dr_ssp").getVersion().equals("3.4.0");    // FIXME not optimal (but meh)
        }
    }
    
    protected void applyToExistingSave()
    {
        Global.getLogger(this.getClass()).info("Applying Nexerelin to existing game");
        
        SectorAPI sector = Global.getSector();
        InvasionFleetManager im = InvasionFleetManager.create();
        AllianceManager am = AllianceManager.create();
        sector.removeScriptsOfClass(CoreScript.class);
        if (HAVE_SSP)
            sector.removeScriptsOfClass(SSP_CoreScript.class);
        sector.addScript(new ExerelinCoreScript());
        sector.addScript(SectorManager.create());
        sector.addScript(DiplomacyManager.create());
        sector.addScript(im);
        sector.addScript(ResponseFleetManager.create());
        sector.addScript(MiningFleetManager.create());
        sector.addScript(CovertOpsManager.create());
        sector.addScript(am);
        // debugging
        //im.advance(sector.getClock().getSecondsPerDay() * ExerelinConfig.invasionGracePeriod);
        //am.advance(sector.getClock().getSecondsPerDay() * ExerelinConfig.allianceGracePeriod);
        SectorManager.setSystemToRelayMap(new HashMap<String,String>());
        SectorManager.setPlanetToRelayMap(new HashMap<String,String>());
        
        // replace patrol handling with our own
        for (MarketAPI market : sector.getEconomy().getMarketsCopy())
        {
            market.getPrimaryEntity().removeScriptsOfClass(PatrolFleetManager.class);
        }
        
        StatsTracker.create();
        
        SectorManager.setCorvusMode(true);
        SectorManager.reinitLiveFactions();
        PlayerFactionStore.setPlayerFactionId(ExerelinConstants.PLAYER_NPC_ID);
        sector.getFaction(Factions.PLAYER).setRelationship(ExerelinConstants.PLAYER_NPC_ID, 1);
        ExerelinUtilsReputation.syncFactionRelationshipsToPlayer();
        
        sector.addTransientScript(new ReinitScreenScript());
    }
    
    protected void refreshTariffs()
    {
        for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy())
        {
            ExerelinUtilsMarket.setTariffs(market);
        }
    }
    
    @Override
    public void beforeGameSave()
    {
    }

    @Override
    public void onNewGame() {
        Global.getLogger(this.getClass()).info("New game");
        isNewGame = true;
        //ExerelinSetupData.resetInstance();
        ExerelinConfig.loadSettings();
        //ExerelinCheck.checkModCompatability();
        addScriptsIfNeeded();
    }
    
    protected void reverseCompatibility()
    {
    
    }
    
    @Override
    public void onEnabled(boolean wasEnabledBefore) {
        Global.getLogger(this.getClass()).info("On enabled; " + wasEnabledBefore);
        if (!isNewGame && !wasEnabledBefore)
        {
            Global.getLogger(this.getClass()).info(!isNewGame + ", " + !wasEnabledBefore);
            applyToExistingSave();
        }
    }
    
    protected void addScriptsIfNeeded() {
        SectorAPI sector = Global.getSector();
        if (!sector.hasScript(ConquestMissionCreator.class)) {
            sector.addScript(new ConquestMissionCreator());
        }
    }
    
    @Override
    public void onGameLoad(boolean newGame) {
        Global.getLogger(this.getClass()).info("Game load; " + SectorManager.isSectorManagerSaved());
        isNewGame = newGame;
        
        addScriptsIfNeeded();
        
        ExerelinConfig.loadSettings();
        SectorManager.create();
        DiplomacyManager.create();
        InvasionFleetManager.create();
        ResponseFleetManager.create();
        MiningFleetManager.create();
        CovertOpsManager.create();
        AllianceManager.create();
        
        SectorAPI sector = Global.getSector();
        
        if (!sector.getEventManager().isOngoing(null, "exerelin_faction_salary")) {
            sector.getEventManager().startEvent(null, "exerelin_faction_salary", null);
        }
        if (!sector.getEventManager().isOngoing(null, "exerelin_faction_insurance")) {
            sector.getEventManager().startEvent(null, "exerelin_faction_insurance", null);
        }
        if (ExerelinUtilsFaction.isExiInCorvus() && !sector.getEventManager().isOngoing(null, "exerelin_exigency_respawn")) {
            sector.getEventManager().startEvent(null, "exerelin_exigency_respawn", null);
        }
        if (RevengeanceFleetEvent.getOngoingEvent() == null) {
            sector.getEventManager().startEvent(null, "exerelin_revengeance_fleet", null);
        }
        
        reverseCompatibility();
        refreshTariffs();
        
        sector.registerPlugin(new ExerelinCoreCampaignPlugin());
        sector.addTransientScript(new DirectoryScreenScript());
    }
    
    @Override
    public void onApplicationLoad() throws Exception
    {
        //ExerelinConfig.loadSettings();
    }
    
    @Override
    public void onNewGameAfterTimePass() {
        Global.getLogger(this.getClass()).info("New game after time pass; " + isNewGame);
        PlayerStartHandler.execute();
    }

    @Override
    public void onNewGameAfterEconomyLoad() {
        Global.getLogger(this.getClass()).info("New game after economy load; " + isNewGame);
        
        if (SectorManager.getCorvusMode())
        {
            SectorManager.reinitLiveFactions();
            DiplomacyManager.initFactionRelationships(false);    // the mod factions set their own relationships, so we have to re-randomize if needed afterwards
        }
        
        //SectorAPI sector = Global.getSector();
        //for (int i=0; i<OmniFacSettings.getNumberOfFactories(); i++) // TODO: use Omnifactory's numberOfFactories setting when it's supported
        //    PlayerStartHandler.addOmnifactory(sector, i);
    }
    
    @Override
    public void configureXStream(XStream x) {
        XStreamConfig.configureXStream(x);
    }
}