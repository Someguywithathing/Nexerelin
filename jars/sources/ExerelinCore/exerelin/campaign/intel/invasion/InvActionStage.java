package exerelin.campaign.intel.invasion;

import com.fs.starfarer.api.Global;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.ai.CampaignFleetAIAPI.ActionType;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.DebugFlags;
import com.fs.starfarer.api.impl.campaign.MilitaryResponseScript;
import com.fs.starfarer.api.impl.campaign.MilitaryResponseScript.MilitaryResponseParams;
import com.fs.starfarer.api.impl.campaign.command.WarSimScript;
import com.fs.starfarer.api.impl.campaign.econ.impl.OrbitalStation;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteData;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteSegment;
import com.fs.starfarer.api.impl.campaign.intel.raid.ActionStage;
import com.fs.starfarer.api.impl.campaign.intel.raid.RaidIntel.RaidStageStatus;
import com.fs.starfarer.api.impl.campaign.procgen.themes.BaseAssignmentAI.FleetActionDelegate;
import com.fs.starfarer.api.impl.campaign.rulecmd.Nex_MarketCMD;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.MarketCMD.BombardType;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import exerelin.campaign.InvasionRound;
import exerelin.campaign.intel.InvasionIntel;
import exerelin.campaign.intel.InvasionIntel.InvasionOutcome;

public class InvActionStage extends ActionStage implements FleetActionDelegate {
	
	protected MarketAPI target;
	protected boolean playerTargeted = false;
	protected List<MilitaryResponseScript> scripts = new ArrayList<MilitaryResponseScript>();
	protected boolean gaveOrders = true; // will be set to false in updateRoutes()
	protected float untilAutoresolve = 30f;
	
	public InvActionStage(InvasionIntel invasion, MarketAPI target) {
		super(invasion);
		this.target = target;
		playerTargeted = target.isPlayerOwned();
		
		untilAutoresolve = 15f + 5f * (float) Math.random();
	}
	

	@Override
	public void advance(float amount) {
		super.advance(amount);
		
		float days = Misc.getDays(amount);
		untilAutoresolve -= days;
		if (DebugFlags.PUNITIVE_EXPEDITION_DEBUG) {
			untilAutoresolve -= days * 100f;
		}
		
		if (!gaveOrders) {
			gaveOrders = true;
		
			removeMilScripts();

			// getMaxDays() is always 1 here
			// scripts get removed anyway so we don't care about when they expire naturally
			// just make sure they're around for long enough
			float duration = 100f;
			
			MilitaryResponseParams params = new MilitaryResponseParams(ActionType.HOSTILE, 
					"inv_" + Misc.genUID() + target.getId(), 
					intel.getFaction(),
					target.getPrimaryEntity(),
					1f,
					duration);
			MilitaryResponseScript script = new MilitaryResponseScript(params);
			target.getContainingLocation().addScript(script);
			scripts.add(script);
			
			MilitaryResponseParams defParams = new MilitaryResponseParams(ActionType.HOSTILE, 
					"defInv_" + Misc.genUID() + target.getId(), 
					target.getFaction(),
					target.getPrimaryEntity(),
					1f,
					duration);
			MilitaryResponseScript defScript = new MilitaryResponseScript(defParams);
			target.getContainingLocation().addScript(defScript);
			scripts.add(defScript);
		}
	}

	protected void removeMilScripts() {
		if (scripts != null) {
			for (MilitaryResponseScript s : scripts) {
				s.forceDone();
			}
		}
	}
	
	@Override
	protected void updateStatus() {
//		if (true) {
//			status = RaidStageStatus.SUCCESS;
//			return;
//		}
		
		abortIfNeededBasedOnFP(true);
		abortIfOutOfMarines(true);
		if (status != RaidStageStatus.ONGOING) return;
		
		boolean inSpawnRange = RouteManager.isPlayerInSpawnRange(target.getPrimaryEntity());
		if (!inSpawnRange && untilAutoresolve <= 0){
			autoresolve();
			return;
		}
		
		if (!target.isInEconomy() || !target.getFaction().isHostileTo(this.intel.getFaction())) {
			status = RaidStageStatus.FAILURE;
			((InvasionIntel)intel).setOutcome(InvasionOutcome.NO_LONGER_HOSTILE);
			removeMilScripts();
			giveReturnOrdersToStragglers(getRoutes());
			return;
		}
	}
	
	protected void abortIfOutOfMarines(boolean giveReturnOrders) {
		float marines = 0;
		List<RouteData> routes = getRoutes();
		List<RouteManager.RouteData> stragglers = new ArrayList<>();	//getStragglers(routes, to, 1000);
		for (CampaignFleetAPI fleet : ((InvasionIntel)intel).getFleetsThatMadeIt(routes, stragglers))
		{
			marines += fleet.getCargo().getMarines();
		}
		float startingMarines = ((InvasionIntel)intel).getStartingMarines();
		if (marines/startingMarines < 0.4f)
		{
			Global.getLogger(this.getClass()).info("Invasion: Insufficient marines, aborting (" + marines + "/" + startingMarines + ")");
			status = RaidStageStatus.FAILURE;
			((InvasionIntel)intel).setOutcome(InvasionOutcome.FAIL);
			if (giveReturnOrders) {
				giveReturnOrdersToStragglers(routes);
			}
		}
	}
	
	// TODO: only primary invasion fleet should have invasion action text
	// if primary invasion fleets are still a thing
	@Override
	public String getRaidActionText(CampaignFleetAPI fleet, MarketAPI market) {
		return "invading " + market.getName();
	}

	@Override
	public String getRaidApproachText(CampaignFleetAPI fleet, MarketAPI market) {
		return "moving in to invade " + market.getName();
	}
	
	// TODO
	// get attacker strength and defender strength
	// if latter is too much higher than former, bomb target if possible
	// call NPC invade method, await results
	@Override
	public void performRaid(CampaignFleetAPI fleet, MarketAPI market) {
		Global.getLogger(this.getClass()).info("Resolving invasion action: " + (fleet == null));
		
		removeMilScripts();
		
		InvasionIntel intel = ((InvasionIntel)this.intel);
		
		status = RaidStageStatus.SUCCESS;
		
		float atkStrength = InvasionRound.getAttackerStrength(fleet);
		float defStrength = InvasionRound.getDefenderStrength(market, 1);
		
		boolean needBomb = atkStrength < defStrength;
		
		if (needBomb)
		{
			Global.getLogger(this.getClass()).info("\tWant to bomb target");
			float bombCost = Nex_MarketCMD.getBombardmentCost(market, fleet);
			float maxCost = intel.getRaidFP() / intel.getNumFleets() * Misc.FP_TO_BOMBARD_COST_APPROX_MULT;
			if (fleet != null) {
				maxCost = fleet.getCargo().getMaxFuel() * 0.25f;
			}

			if (bombCost <= maxCost) {
				Global.getLogger(this.getClass()).info("\tBombing target");
				new Nex_MarketCMD().doBombardment(intel.getFaction(), BombardType.TACTICAL);
			}
		}
		
		Global.getLogger(this.getClass()).info("\tInvading target");
		boolean success = InvasionRound.npcInvade(fleet, market);
		if (success)
			intel.setOutcome(InvasionOutcome.SUCCESS);
		
		// when FAILURE, gets sent by RaidIntel
		if (intel.getOutcome() != null) {
			if (status == RaidStageStatus.SUCCESS) {
				intel.sendOutcomeUpdate();
			} else {
				removeMilScripts();
				giveReturnOrdersToStragglers(getRoutes());
			}
		}
	}
	
	protected void autoresolve() {
		Global.getLogger(this.getClass()).info("Autoresolving invasion action");
		float str = WarSimScript.getFactionStrength(intel.getFaction(), target.getStarSystem());
		float enemyStr = WarSimScript.getFactionStrength(target.getFaction(), target.getStarSystem());
		
		float defensiveStr = enemyStr + WarSimScript.getStationStrength(target.getFaction(), 
							 target.getStarSystem(), target.getPrimaryEntity());
		InvasionIntel intel = ((InvasionIntel)this.intel);
		
		if (defensiveStr >= str) {
			status = RaidStageStatus.FAILURE;
			removeMilScripts();
			giveReturnOrdersToStragglers(getRoutes());
			
			
			intel.setOutcome(InvasionOutcome.TASK_FORCE_DEFEATED);
			return;
		}
		
		Industry station = Misc.getStationIndustry(target);
		if (station != null) {
			OrbitalStation.disrupt(station);
		}
		
		List<RouteData> routes = getRoutes();
		List<RouteData> stragglers = getStragglers(routes, target.getPrimaryEntity(), 1000);
		for (CampaignFleetAPI fleet : intel.getFleetsThatMadeIt(routes, stragglers))
		{
			performRaid(fleet, target);
		}
	}
	
	
	@Override
	protected void updateRoutes() {
		resetRoutes();
		
		gaveOrders = false;
		
		((InvasionIntel)intel).sendEnteredSystemUpdate();
		
		List<RouteData> routes = RouteManager.getInstance().getRoutesForSource(intel.getRouteSourceId());
		for (RouteData route : routes) {
			if (target.getStarSystem() != null) { // so that fleet may spawn NOT at the target
				route.addSegment(new RouteSegment(Math.min(5f, untilAutoresolve), target.getStarSystem().getCenter()));
			}
			route.addSegment(new RouteSegment(1000f, target.getPrimaryEntity()));
		}
	}
	
	@Override
	public void showStageInfo(TooltipMakerAPI info) {
		int curr = intel.getCurrentStage();
		int index = intel.getStageIndex(this);
		
		Color h = Misc.getHighlightColor();
		Color g = Misc.getGrayColor();
		Color tc = Misc.getTextColor();
		float pad = 3f;
		float opad = 10f;
		
		if (curr < index) return;
		
		if (status == RaidStageStatus.ONGOING && curr == index) {
			info.addPara("The expedition forces are currently in-system.", opad);
			return;
		}
		
		InvasionIntel intel = ((InvasionIntel)this.intel);
		if (intel.getOutcome() != null) {
			switch (intel.getOutcome()) {
			case FAIL:
				info.addPara("The invasion forces have been repelled by the ground defenses of " + target.getName() + ".", opad);
				break;
			case SUCCESS:
				info.addPara("The invasion force has conquered " + target.getName() + ".", opad);
				break;
			case TASK_FORCE_DEFEATED:
				info.addPara("The invasion force has been defeated by the defenders of " +
								target.getName() + ".", opad);
				break;
			case NO_LONGER_HOSTILE:
				info.addPara("As " + target.getName() + " is no longer controlled by a hostile faction (" + 
						target.getFaction().getDisplayName() + "), the invasion has been cancelled.", opad);
			case MARKET_NO_LONGER_EXISTS:
			case OTHER:
				info.addPara("The invasion has been aborted.", opad);
				break;
			
			}
		} else if (status == RaidStageStatus.SUCCESS) {			
			info.addPara("The expeditionary force has succeeded.", opad); // shouldn't happen?
		} else {
			info.addPara("The expeditionary force has failed.", opad); // shouldn't happen?
		}
	}

	@Override
	public boolean canRaid(CampaignFleetAPI fleet, MarketAPI market) {
		InvasionIntel intel = ((InvasionIntel)this.intel);
		if (intel.getOutcome() != null) return false;
		return market == target;
	}
	
	@Override
	public String getRaidPrepText(CampaignFleetAPI fleet, SectorEntityToken from) {
		return "orbiting " + from.getName();
	}
	
	@Override
	public String getRaidInSystemText(CampaignFleetAPI fleet) {
		return "traveling to " + target.getName();
	}
	
	@Override
	public String getRaidDefaultText(CampaignFleetAPI fleet) {
		return "traveling to " + target.getName();		
	}
	
	@Override
	public boolean isPlayerTargeted() {
		return playerTargeted;
	}
}