package exerelin.campaign.events;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseOnMessageDeliveryScript;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.PlayerMarketTransaction;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.comm.CommMessageAPI;
import com.fs.starfarer.api.campaign.comm.MessagePriority;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.events.CampaignEventPlugin;
import com.fs.starfarer.api.campaign.events.CampaignEventTarget;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.events.BaseEventPlugin;
import com.fs.starfarer.api.impl.campaign.events.RecentUnrestEvent;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Conditions;
import com.fs.starfarer.api.impl.campaign.ids.Events;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.FleetTypes;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.util.IntervalUtil;
import exerelin.campaign.AllianceManager;
import exerelin.campaign.CovertOpsManager;
import exerelin.campaign.SectorManager;
import exerelin.campaign.covertops.InstigateRebellion;
import exerelin.utilities.ExerelinUtilsFleet;
import exerelin.utilities.ExerelinUtilsMarket;
import exerelin.utilities.ExerelinUtilsReputation;
import exerelin.utilities.StringHelper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.log4j.Logger;
import org.lazywizard.lazylib.MathUtils;

/* TODO:
	Random rebellion event
	Rebel suppression fleets
*/
public class RebellionEvent extends BaseEventPlugin {
	
	public static final float MAX_DAYS = 180;
	public static final float VALUE_WEAPONS = 0.03f;
	public static final float VALUE_SUPPLIES = 0.02f;
	public static final float VALUE_MARINES = 0.2f;
	//public static final float VALUE_PER_CREDIT = 0.01f * 0.01f;
	public static final float REBEL_TRADE_MULT = 2f;
	public static final float MARINE_DEMAND = 50f;
	public static final float WEAPONS_DEMAND = 200f;
	public static final float SUPPLIES_DEMAND = 100f;
	public static final float STRENGTH_CHANGE_MULT = 0.5f;
	public static final float SUPPRESSION_FLEET_INTERVAL = 45f;
	
	protected static Logger log = Global.getLogger(RebellionEvent.class);
	
	protected Map<String, Object> params;
	
	protected int stage = 0;
	protected boolean ended = false;
	protected float age = 0;
	protected float suppressionFleetCountdown = SUPPRESSION_FLEET_INTERVAL * MathUtils.getRandomNumberInRange(0.3f, 0.5f);
	
	protected String govtFactionId = null;	// for token substition, if market is liberated
	protected String rebelFactionId = null;
	protected float govtStrength = 1;
	protected float rebelStrength = 1;
	protected float govtTradePoints = 0;
	protected float rebelTradePoints = 0;
	
	protected float intensity = 0;
	protected int stabilityPenalty = 1;
	protected RebellionResult result = null;
	
	protected String conditionToken = null;
	
	protected MessagePriority priority = MessagePriority.SECTOR;
	
	protected IntervalUtil interval = new IntervalUtil(1, 1);
	
	@Override
	public void init(String type, CampaignEventTarget eventTarget) {
		super.init(type, eventTarget);
		params = new HashMap<>();
		
		int size = market.getSize();
		if (size <= 3) priority = MessagePriority.CLUSTER;
		else if (size <= 5) priority = MessagePriority.SYSTEM;
	}
	
	@Override
	public void setParam(Object param) {
		params = (HashMap)param;
		rebelFactionId = (String)params.get("rebelFactionId");
		age = -(Float)params.get("delay");
	}
	
	@Override
	public void startEvent() {
		super.startEvent();
		if (market == null) {
			endEvent(RebellionResult.OTHER);
			return;
		}
		govtFactionId = market.getFactionId();
		setInitialStrengths();
	}
	
	protected float getSizeMod(int size)
	{
		return (float)Math.pow(2, size - 2);
	}
	
	/**
	 * Gets an exponent-of-two value based on the market size.
	 * @return
	 */
	protected float getSizeMod()
	{
		return getSizeMod(market.getSize());
	}
	
	protected void setInitialStrengths()
	{
		float stability = market.getStabilityValue();
		float sizeMult = getSizeMod();
		govtStrength = (5 + stability * 1.25f) * sizeMult;
		rebelStrength = (3 + (10 - stability)) * sizeMult;
	}
	
	protected float getNormalizedStrength(boolean rebel)
	{
		float numerator = rebel ? rebelStrength : govtStrength;
		return numerator/(govtStrength + rebelStrength);
	}
	
	protected float updateConflictIntensity()
	{
		float currIntensity = govtStrength + rebelStrength - 0.75f * (govtStrength - rebelStrength);
		currIntensity /= getSizeMod();
		
		// counteracts intensity bleeding as belligerents' strength wears down
		float age = (int)(this.age/3);
		if (age < 0) age = 0;
		currIntensity += Math.sqrt(age);
		
		if (stage == 0) currIntensity *= 0.5f;
		this.intensity = currIntensity;
		debugMessage("  Conflict intensity: " + currIntensity);
		return currIntensity;
	}
	
	/**
	 * Resolves an engagement round between government and rebels.
	 * @return
	 */
	protected boolean battleRound()
	{
		float stability = market.getStabilityValue();
		debugMessage("  Stability: " + stability);
		debugMessage("  Initial force strengths: " + govtStrength + ", " + rebelStrength);
		
		float strG = (float)Math.sqrt(govtStrength);
		float strR = (float)Math.sqrt(rebelStrength);
		
		strG *= 0.75f + 0.5f * Math.random() * (0.5f + 0.5f * stability / 5);
		strR *= 0.75f + 0.5f * Math.random();
		if (stability == 0) strG *= 0.4f;
		else if (stability == 1) strG *= 0.8f;
		if (market.getFactionId().equals("templars")) strG *= 2;
		
		debugMessage("  Government engagement strength: " + strG);
		debugMessage("  Rebel engagement strength: " + strR);
		
		float diff = strG - strR;
		rebelStrength -= (strG - strR/2) * STRENGTH_CHANGE_MULT;
		govtStrength -= (strR - strG/2) * STRENGTH_CHANGE_MULT;
		
		debugMessage("  Updated force strengths: " + govtStrength + ", " + rebelStrength);
		
		return diff > 0;
	}
	
	protected void gatherStrength()
	{
		float stability = market.getStabilityValue();
		debugMessage("  Stability: " + stability);
		debugMessage("  Initial force strengths: " + govtStrength + ", " + rebelStrength);
		
		float mult = market.getDemand(Commodities.HAND_WEAPONS).getFractionMet();
		mult *= market.getDemand(Commodities.MARINES).getFractionMet();
		mult *= market.getDemand(Commodities.SUPPLIES).getFractionMet();
		mult += 0.25f;
		
		govtStrength += (1.5f + stability/10) * mult;
		rebelStrength += (1 + (10 - stability)/10) * mult;
		
		debugMessage("  Updated force strengths: " + govtStrength + ", " + rebelStrength);
	}
	
	protected void updateCommodityDemand()
	{
		String modId = this.getStatModId();
		if (ended)
		{
			market.getDemand(Commodities.MARINES).getDemand().unmodify(modId);
			market.getDemand(Commodities.HAND_WEAPONS).getDemand().unmodify(modId);
			market.getDemand(Commodities.SUPPLIES).getDemand().unmodify(modId);
		
			market.getDemand(Commodities.MARINES).getNonConsumingDemand().unmodify(modId);
			market.getDemand(Commodities.HAND_WEAPONS).getNonConsumingDemand().unmodify(modId);
			market.getDemand(Commodities.SUPPLIES).getNonConsumingDemand().unmodify(modId);
			
			return;
		}
		
		int size = market.getSize();
		if (size < 3) size = 3;
		
		float mult = getSizeMod(size);
		if (stage > 0) 
			mult *= intensity / 20;
		
		market.getDemand(Commodities.MARINES).getDemand().modifyFlat(modId, MARINE_DEMAND * mult);
		market.getDemand(Commodities.HAND_WEAPONS).getDemand().modifyFlat(modId, WEAPONS_DEMAND * mult);
		market.getDemand(Commodities.SUPPLIES).getDemand().modifyFlat(modId, SUPPLIES_DEMAND * mult);
		
		// still stockpiling ahead of conflict; demand is non-consuming
		if (stage == 0)
		{
			market.getDemand(Commodities.MARINES).getNonConsumingDemand().modifyFlat(modId, MARINE_DEMAND * mult);
			market.getDemand(Commodities.HAND_WEAPONS).getNonConsumingDemand().modifyFlat(modId, WEAPONS_DEMAND * mult);
			market.getDemand(Commodities.SUPPLIES).getNonConsumingDemand().modifyFlat(modId, SUPPLIES_DEMAND * mult);
		}
		else
		{
			market.getDemand(Commodities.MARINES).getNonConsumingDemand().modifyFlat(modId, MARINE_DEMAND * mult * 0.6f);
			market.getDemand(Commodities.HAND_WEAPONS).getNonConsumingDemand().unmodify(modId);
			market.getDemand(Commodities.SUPPLIES).getNonConsumingDemand().unmodify(modId);
		}
	}
	
	/**
	 * Roll a random chance for newly spawned patrol to join rebel faction.
	 * @return
	 */
	protected boolean shouldTransferPatrol()
	{
		float chance = 0.2f + 0.8f * getNormalizedStrength(true);
		return Math.random() < chance;
	}
	
	public String getRebelFactionId() {
		return rebelFactionId;
	}
	
	
	public int getStabilityPenalty() {
		return stabilityPenalty;
	}
	
	protected void updateStabilityPenalty()
	{
		if (stage <= 0) return;
		
		stabilityPenalty = (int)(intensity/6 + 0.5f);
		if (stabilityPenalty < 1) stabilityPenalty = 1;
		market.reapplyCondition(conditionToken);
	}
	
	/**
	 * Applies a event with market condition to apply lingering, decaying stability penalty after the rebellion ends.
	 * Usually a Recent Unrest event, uses Market Attacked event if rebels win.
	 * @param result
	 * @param amount
	 */
	protected void applyFinalInstability(RebellionResult result, int amount)
	{
		if (result == RebellionResult.OTHER) return;
		
		SectorAPI sector = Global.getSector();
		if (result == RebellionResult.REBEL_VICTORY || result == RebellionResult.MUTUAL_ANNIHILATION)
		{
			CampaignEventPlugin eventSuper = sector.getEventManager().getOngoingEvent(
				new CampaignEventTarget(market), "exerelin_market_attacked");
			if (eventSuper == null) 
				eventSuper = sector.getEventManager().startEvent(new CampaignEventTarget(market), 
						"exerelin_market_attacked", null);
			MarketAttackedEvent event = (MarketAttackedEvent)eventSuper;
			event.setStabilityPenalty(event.getStabilityPenalty() + amount);
		}
		else
		{
			CampaignEventPlugin eventSuper = sector.getEventManager().getOngoingEvent(
				new CampaignEventTarget(market), Events.RECENT_UNREST);
			if (eventSuper == null) 
				eventSuper = sector.getEventManager().startEvent(new CampaignEventTarget(market), 
						Events.RECENT_UNREST, null);
			RecentUnrestEvent event = (RecentUnrestEvent)eventSuper;
			event.setStabilityPenalty(event.getStabilityPenalty() + amount);
		}
	}
	
	/**
	 * Applies reputation gain/loss from aiding government or rebels
	 * This is only done once the event ends, to prevent endless buy/sell exploit
	 */
	protected void applyReputationChange()
	{
		if (result == RebellionResult.OTHER) return;
		
		final FactionAPI govt = Global.getSector().getFaction(govtFactionId);
		final FactionAPI rebs = Global.getSector().getFaction(rebelFactionId);
		
		if (rebelTradePoints > 0)
		{
			final float rep = rebelTradePoints/getSizeMod() * 0.01f;
			debugMessage("  Rebel trade rep: " + rep);
			Global.getSector().reportEventStage(this, "trade_rebs", market.getPrimaryEntity(), 
					MessagePriority.ENSURE_DELIVERY, new BaseOnMessageDeliveryScript() {
				public void beforeDelivery(CommMessageAPI message) {
					ExerelinUtilsReputation.adjustPlayerReputation(rebs, null, rep, message, null);
					ExerelinUtilsReputation.adjustPlayerReputation(govt, null, -rep*1.5f, message, null);
				}
			});	
		}
		if (govtTradePoints > 0)
		{
			final float rep = govtTradePoints/getSizeMod() * 0.01f;
			debugMessage("  Government trade rep: " + rep);
			Global.getSector().reportEventStage(this, "trade_govt", market.getPrimaryEntity(), 
					MessagePriority.ENSURE_DELIVERY, new BaseOnMessageDeliveryScript() {
				public void beforeDelivery(CommMessageAPI message) {
					ExerelinUtilsReputation.adjustPlayerReputation(govt, null, rep, message, null);
					ExerelinUtilsReputation.adjustPlayerReputation(rebs, null, -rep*1.5f, message, null);
				}
			});
		}
	}
	
	/**
	 * Callin for when a market is captured by an invasion fleet.
	 * Checks if should end rebellion (continue it if new owner is also hostile)
	 * @param oldOwnerId
	 * @param newOwnerId
	 */
	public void marketCaptured(String newOwnerId, String oldOwnerId)
	{
		if (ended) return;
		FactionAPI newOwner = Global.getSector().getFaction(newOwnerId);
		if (!newOwner.isHostileTo(rebelFactionId))
		{
			if (stage > 0) endEvent(RebellionResult.LIBERATED);
			else endEvent(RebellionResult.OTHER);
		}
		else
		{
			govtFactionId = newOwnerId;
		}
	}
	
	public void endEvent(RebellionResult result)
	{
		if (ended) return;
		ended = true;
		this.result = result;
		updateCommodityDemand();
		market.removeSpecificCondition(conditionToken);
		
		applyFinalInstability(result, stabilityPenalty);
		applyReputationChange();
		
		// transfer market depending on rebellion outcome
		if (result == RebellionResult.REBEL_VICTORY)
			SectorManager.transferMarket(market, Global.getSector().getFaction(rebelFactionId), market.getFaction(), 
					false, true, null, 0);
		else if (result == RebellionResult.MUTUAL_ANNIHILATION)
			SectorManager.transferMarket(market, Global.getSector().getFaction(Factions.PIRATES), market.getFaction(), 
					false, true, null, 0);
						
		// report event
		String reportStage = null;
		switch (result) {
			case REBEL_VICTORY:
				reportStage = "end_rebel_win";
				break;
			case GOVERNMENT_VICTORY:
				reportStage = "end_govt_win";
				break;
			case LIBERATED:
				reportStage = "end_liberated";
				if (AllianceManager.areFactionsAllied(market.getFactionId(), rebelFactionId))
					reportStage = "end_liberated_ally";
				break;
			case PEACE:
				reportStage = "end_peace";
				break;
			case MUTUAL_ANNIHILATION:
				reportStage = "end_mutual_annihilation";
				break;
			case TIME_EXPIRED:
				reportStage = "end_timeout";
				break;
		}
		if (reportStage != null)
			Global.getSector().reportEventStage(this, reportStage, market.getPrimaryEntity(), priority);
	}
	
	@Override
	public void advance(float amount) 
	{
		if (ended) return;
		
		float days = Global.getSector().getClock().convertToDays(amount);
		
		if (stage > 0)
		{
			age += days;
			if (age > MAX_DAYS)
			{
				endEvent(RebellionResult.TIME_EXPIRED);
				return;
			}
			
			suppressionFleetCountdown -= days;
			if (suppressionFleetCountdown < 0)
			{
				suppressionFleetCountdown = SUPPRESSION_FLEET_INTERVAL * MathUtils.getRandomNumberInRange(0.75f, 1.25f);
			}
		}
		else
		{
			age += days;
			if (age > 0)
			{
				stage = 1;
				conditionToken = market.addCondition("nex_rebellion_condition", true, this);
				market.reapplyCondition(conditionToken);

				Global.getSector().reportEventStage(this, "start", market.getPrimaryEntity(), priority);
			}
		}
		
		interval.advance(days);
		if (!interval.intervalElapsed()) return;
		
		debugMessage("Updating rebellion on " + market.getName() +": day " + (int)age);
		
		// check if factions involved are still at war
		if (!market.getFaction().isHostileTo(rebelFactionId))
		{
			endEvent(RebellionResult.PEACE);
			return;
		}
		
		if (stage > 0)
			battleRound();
		else
			gatherStrength();
		
		updateConflictIntensity();
		updateCommodityDemand();
		updateStabilityPenalty();
		
		if (govtStrength <= 0)
		{
			if (rebelStrength > intensity)	// rebel win
			{
				endEvent(RebellionResult.REBEL_VICTORY);
			}
			else	// mutual annihilation
			{
				endEvent(RebellionResult.MUTUAL_ANNIHILATION);
			}
		}
		else if (rebelStrength < 0)
		{
			if (govtStrength > intensity)	// government win
			{
				endEvent(RebellionResult.GOVERNMENT_VICTORY);
			}
			else	// mutual annihilation
			{
				endEvent(RebellionResult.MUTUAL_ANNIHILATION);
			}
		}
	}
	
	// Patrol defection
	@Override
	public void reportFleetSpawned(CampaignFleetAPI fleet) 
	{
		if (!started) return;
		if (ended) return;
		if (fleet.getFaction() != market.getFaction())
			return;
		
		MemoryAPI mem = fleet.getMemoryWithoutUpdate();
		if (!mem.contains(MemFlags.MEMORY_KEY_SOURCE_MARKET))
			return;
		String marketId = mem.getString(MemFlags.MEMORY_KEY_SOURCE_MARKET);
		MarketAPI sourceMarket = Global.getSector().getEconomy().getMarket(marketId);
		if (sourceMarket != market) return;
		
		String fleetType = ExerelinUtilsFleet.getFleetType(fleet);
		if (fleetType.equals(FleetTypes.PATROL_SMALL) || fleetType.equals(FleetTypes.PATROL_MEDIUM) || fleetType.equals(FleetTypes.PATROL_LARGE))
		{
			if (shouldTransferPatrol())
				fleet.setFaction(rebelFactionId, true);
		}
	}
	
	public float getNetCommoditySold(PlayerMarketTransaction transaction, String commodityId)
	{
		return transaction.getQuantitySold(commodityId) - transaction.getQuantityBought(commodityId);
	}
	
	// TODO: handle ship sales as well?
	@Override
	public void reportPlayerMarketTransaction(PlayerMarketTransaction transaction) {
		if (transaction.getMarket() != market)
			return;
		debugMessage("Reporting player transaction");
		
		float points = 0;
		
		float marinePoints = getNetCommoditySold(transaction, Commodities.MARINES) * VALUE_MARINES;
		float weaponPoints = getNetCommoditySold(transaction, Commodities.HAND_WEAPONS) * VALUE_WEAPONS;
		float supplyPoints = getNetCommoditySold(transaction, Commodities.SUPPLIES) * VALUE_SUPPLIES;
		debugMessage("  Marine points: " + marinePoints);
		debugMessage("  Weapon points: " + weaponPoints);
		debugMessage("  Supply points: " + supplyPoints);
		
		points += marinePoints;
		points += getNetCommoditySold(transaction, Commodities.HAND_WEAPONS) * VALUE_WEAPONS;
		points += getNetCommoditySold(transaction, Commodities.SUPPLIES) * VALUE_SUPPLIES;
		
		if (transaction.getSubmarket().getPlugin().isBlackMarket())
		{
			float points2 = points * REBEL_TRADE_MULT;
			rebelStrength += points2;
			rebelTradePoints += points2;			
		}
		else
		{
			govtStrength += points;
			govtTradePoints += points;
		}
	}
	
	@Override
	public boolean isDone() {
		return ended;
	}
	
	@Override
	public Map<String, String> getTokenReplacements() {
		Map<String, String> map = super.getTokenReplacements();
		StringHelper.addFactionNameTokensCustom(map, "faction", Global.getSector().getFaction(govtFactionId));
		addFactionNameTokens(map, "rebel", Global.getSector().getFaction(rebelFactionId));
		if (result == RebellionResult.LIBERATED || result == RebellionResult.MUTUAL_ANNIHILATION)
		{
			addFactionNameTokens(map, "new", market.getFaction());
		}
		
		return map;
	}
	
	@Override
	public String[] getHighlights(String stageId) {
		List<String> highlights = new ArrayList<>();
		if (stageId.equals("start"))
			addTokensToList(highlights, "$theRebelFaction");
			
		return highlights.toArray(new String[0]);
	}
	
	/*
	@Override
	public Color[] getHighlightColors(String stageId) {
		List<Color> colors = new ArrayList<>();
		if (stageId.equals("start"))
		{
			Color color = Global.getSector().getFaction(rebelFactionId).getColor();
			//for (int i=0; i<4; i++)
				colors.add(color);
		}
		
		return colors.toArray(new Color[0]);
	}
	*/
	
	@Override
	public String getCurrentMessageIcon() {
		return "graphics/icons/intel/faction_conflict.png";
	}
	
	@Override
	public String getEventIcon() {
		return "graphics/icons/intel/faction_conflict.png";
	}
	
	@Override
	public String getEventName() {
		return StringHelper.getStringAndSubstituteToken("exerelin_events", "rebellion", "$market", market.getName());
	}
	
	protected void debugMessage(String message)
	{
		log.info(message);
		Global.getSector().getCampaignUI().addMessage(message);
	}
	
	public static void startDebugEvent()
	{
		SectorEntityToken jangala = Global.getSector().getEntityById("jangala");
		if (jangala != null)
		{
			InstigateRebellion rebel = new InstigateRebellion(jangala.getMarket(), 
					Global.getSector().getFaction(Factions.PERSEAN), jangala.getFaction(), false, null);
			rebel.setResult(CovertOpsManager.CovertActionResult.SUCCESS_DETECTED);
			rebel.onSuccess();
		}
	}
	
	public static boolean canRebel(MarketAPI market)
	{
		int stability = (int)market.getStabilityValue();
		int size = market.getSize();
		
		if (market.hasCondition(Conditions.DISSIDENT))
			stability -= 1;
		if (!ExerelinUtilsMarket.isWithOriginalOwner(market))
			stability -= 1;
		
		return stability < size;
	}
	
	protected enum RebellionResult {
		PEACE, GOVERNMENT_VICTORY, REBEL_VICTORY, MUTUAL_ANNIHILATION, TIME_EXPIRED, LIBERATED, OTHER
	}
}
