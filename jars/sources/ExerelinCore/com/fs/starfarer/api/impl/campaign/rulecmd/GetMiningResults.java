package com.fs.starfarer.api.impl.campaign.rulecmd;

import java.awt.Color;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.TextPanelAPI;
import com.fs.starfarer.api.campaign.econ.EconomyAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.Misc.Token;
import exerelin.utilities.StringHelper;
import exerelin.campaign.MiningHelper;
import exerelin.campaign.MiningHelper.CacheResult;
import exerelin.campaign.MiningHelper.CacheType;
import exerelin.campaign.MiningHelper.MiningResult;
import java.util.Iterator;


public class GetMiningResults extends BaseCommandPlugin {

	@Override
	public boolean execute(String ruleId, InteractionDialogAPI dialog, List<Token> params, Map<String, MemoryAPI> memoryMap) {
		if (dialog == null) return false;
		SectorEntityToken target = (SectorEntityToken) dialog.getInteractionTarget();
		TextPanelAPI text = dialog.getTextPanel();

		if (!MiningHelper.canMine(target)) return false;
		
		Color hl = Misc.getHighlightColor();
		Color red = Misc.getNegativeHighlightColor();
		
		CampaignFleetAPI playerFleet = Global.getSector().getPlayerFleet();
		MiningResult results = MiningHelper.getMiningResults(playerFleet, target, 1, true);
		Map<String, Float> resources = results.resources;
		EconomyAPI economy = Global.getSector().getEconomy();
		
		//text.addParagraph(Misc.ucFirst(StringHelper.getString("exerelin_mining", "miningReport")));
		
		text.setFontVictor();
		text.setFontSmallInsignia();

		text.addParagraph("-----------------------------------------------------------------------------");
		
		String headerStr = Misc.ucFirst(StringHelper.getString("exerelin_mining", "resourcesExtracted"));
		text.addParagraph(headerStr);
		text.highlightInLastPara(hl, headerStr);
		Iterator<String> iter = resources.keySet().iterator();
		while (iter.hasNext())
		{
			String res = iter.next();
			int amount = (int)(float)resources.get(res);
			
			//String amountStr = String.format("%.0f", amount);
			String resName = economy.getCommoditySpec(res).getName();
			text.addParagraph("  " + resName + ": " + amount);
			text.highlightInLastPara(hl, resName);
		}
		
		if (!results.cachesFound.isEmpty())
		{

			headerStr = StringHelper.getString("exerelin_mining", "cacheFound");
			text.addParagraph(headerStr);
			text.highlightInLastPara(hl, headerStr);
			
			for (CacheResult cache: results.cachesFound)
			{
				String displayStr = cache.name;
				if (cache.def.type != CacheType.FRIGATE)
				{
					displayStr += (" x " + cache.numItems);
				}
				text.addParagraph("  " + displayStr);
				text.highlightInLastPara(hl, cache.name);
			}
		}
		if (results.accidents != null)
		{
			headerStr = StringHelper.getString("exerelin_mining", "accidentsOccured");
			text.addParagraph(headerStr);
			text.highlightInLastPara(red, headerStr);
			
			for (FleetMemberAPI ship : results.accidents.shipsDestroyed)
			{
				String displayStr = StringHelper.getStringAndSubstituteToken("exerelin_mining", "shipDestroyed", "$ship", ship.getShipName());
				text.addParagraph(displayStr);
				text.highlightInLastPara(red, ship.getShipName());
			}
			
			Iterator<FleetMemberAPI> iterDmg = results.accidents.damage.keySet().iterator();
			while (iterDmg.hasNext())
			{
				FleetMemberAPI ship = iterDmg.next();
				int damage = (int)(float)results.accidents.damage.get(ship);
				String displayStr = StringHelper.getString("exerelin_mining", "shipDamaged");
				displayStr = StringHelper.substituteToken(displayStr, "$ship", ship.getShipName());
				displayStr = StringHelper.substituteToken(displayStr, "$damage", damage + "");
				text.addParagraph("  " + displayStr);
				text.highlightInLastPara(red, ship.getShipName(), damage+"");
			}
			
			Iterator<FleetMemberAPI> iterCRLoss = results.accidents.crLost.keySet().iterator();
			while (iterCRLoss.hasNext())
			{
				FleetMemberAPI ship = iterCRLoss.next();
				int crLost = (int)((float)results.accidents.crLost.get(ship)*100);
				String crLostStr = crLost + "%";
				String displayStr = StringHelper.getString("exerelin_mining", "shipLostCR");
				displayStr = StringHelper.substituteToken(displayStr, "$ship", ship.getShipName());
				displayStr = StringHelper.substituteToken(displayStr, "$crLost", crLostStr);
				text.addParagraph("  " + displayStr);
				text.highlightInLastPara(red, ship.getShipName(), crLostStr);
			}
			
			int crewLost = (int)results.accidents.crewLost.getTotalCrew();
			if (crewLost > 0)
			{
				String displayStr = Misc.ucFirst(StringHelper.getString("exerelin_mining", "crewLost"));
				text.addParagraph("  " + displayStr + ": " + crewLost);
				text.highlightInLastPara(red, crewLost+"");
			}
		}
 
		text.addParagraph("-----------------------------------------------------------------------------");
		text.setFontInsignia();

		return true;
	}
}