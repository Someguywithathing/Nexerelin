package com.fs.starfarer.api.impl.campaign.rulecmd;

import java.util.List;
import java.util.Map;

import exerelin.PlayerFactionStore;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.util.Misc.Token;


public class NGCSetPlayerFaction extends BaseCommandPlugin {
        
	public boolean execute(String ruleId, InteractionDialogAPI dialog, List<Token> params, Map<String, MemoryAPI> memoryMap) {
		String factionId = params.get(0).getString(memoryMap);
		PlayerFactionStore.setPlayerFactionId(factionId);
		return true;
	}
}






