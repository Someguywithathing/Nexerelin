package data.scripts.world.exerelin;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import data.scripts.world.BaseSpawnPoint;
import data.scripts.world.exerelin.commandQueue.CommandAddCargo;
import data.scripts.world.exerelin.diplomacy.DiplomacyRecord;
import org.lazywizard.lazylib.MathUtils;

import java.awt.*;
import java.util.List;

public class StationRecord
{
	private SystemStationManager systemStationManager;

	private SectorEntityToken stationToken;
	private CargoAPI stationCargo;
	private String planetType;
	private DiplomacyRecord owningFaction;
	private Float efficiency = 1f;
	private StarSystemAPI system;

	private int numStationsTargeting;
    private Boolean isBeingBoarded;
    private long lastBoardAttemptTime;
	private StationRecord targetStationRecord;
	private StationRecord assistStationRecord;
	private SectorEntityToken targetAsteroid;
	private SectorEntityToken targetGasGiant;

	// Spawnpoints
	private AttackFleetSpawnPoint attackSpawn;
	private DefenseFleetSpawnPoint defenseSpawn;
	private PatrolFleetSpawnPoint patrolSpawn;
	private InSystemStationAttackShipSpawnPoint stationAttackFleetSpawn;
	private InSystemSupplyConvoySpawnPoint inSystemSupplyConvoySpawn;
	private AsteroidMiningFleetSpawnPoint asteroidMiningFleetSpawnPoint;
	private GasMiningFleetSpawnPoint gasMiningFleetSpawnPoint;

    // Extra spawnpoints for player skill (kind of ugly, should just replace all spawnpoints with fleets)
    private AsteroidMiningFleetSpawnPoint asteroidMiningFleetSpawnPoint2;
    private GasMiningFleetSpawnPoint gasMiningFleetSpawnPoint2;

	public StationRecord(SectorAPI sector, StarSystemAPI inSystem, SystemStationManager manager, SectorEntityToken token)
	{
		system = inSystem;

		stationToken = token;
		stationCargo = token.getCargo();
		planetType = this.derivePlanetType(token);

		attackSpawn = new AttackFleetSpawnPoint(sector, system, 1000000, 1, token);
		defenseSpawn = new DefenseFleetSpawnPoint(sector, system, 1000000, 1, token);
		patrolSpawn = new PatrolFleetSpawnPoint(sector, system, 1000000, 2, token);
		stationAttackFleetSpawn = new InSystemStationAttackShipSpawnPoint(sector, system, 1000000, 1, token);
		inSystemSupplyConvoySpawn = new InSystemSupplyConvoySpawnPoint(sector, system, 1000000, 1, token);
		asteroidMiningFleetSpawnPoint = new AsteroidMiningFleetSpawnPoint(sector,  system,  1000000, 1, token);
		gasMiningFleetSpawnPoint = new GasMiningFleetSpawnPoint(sector,  system,  1000000, 1, token);
        asteroidMiningFleetSpawnPoint2 = new AsteroidMiningFleetSpawnPoint(sector,  system,  1000000, 1, token);
        gasMiningFleetSpawnPoint2 = new GasMiningFleetSpawnPoint(sector,  system,  1000000, 1, token);

        isBeingBoarded = false;
        lastBoardAttemptTime = 0;

		systemStationManager = manager;
	}

	public DiplomacyRecord getOwner()
	{
		return owningFaction;
	}

	public void setOwner(String newOwnerFactionId, Boolean displayMessage, Boolean updateRelationship)
	{
		if(newOwnerFactionId == null)
		{
			// Set station back to abandoned
			stationToken.setFaction("abandonded");
			owningFaction = null;
			stationCargo.setFreeTransfer(false);
			return;
		}
		String originalOwnerId = "";
		if(owningFaction != null)
			originalOwnerId = owningFaction.getFactionId();

		if(displayMessage)
		{
			if(newOwnerFactionId.equalsIgnoreCase(ExerelinData.getInstance().getPlayerFaction()))
				Global.getSector().addMessage(stationToken.getFullName() + " taken over by " + Global.getSector().getFaction(newOwnerFactionId).getDisplayName(), Color.magenta);
			else if(this.getOwner() != null && this.getOwner().getFactionId().equalsIgnoreCase(ExerelinData.getInstance().getPlayerFaction()))
				Global.getSector().addMessage(stationToken.getFullName() + " taken over by " + Global.getSector().getFaction(newOwnerFactionId).getDisplayName(), Color.magenta);
			else
				Global.getSector().addMessage(stationToken.getFullName() + " taken over by " + Global.getSector().getFaction(newOwnerFactionId).getDisplayName());

			System.out.println(stationToken.getFullName() + " taken over by " + Global.getSector().getFaction(newOwnerFactionId).getDisplayName());
		}

		stationToken.setFaction(newOwnerFactionId);

		attackSpawn.setFaction(newOwnerFactionId);
		defenseSpawn.setFaction(newOwnerFactionId);
		patrolSpawn.setFaction(newOwnerFactionId);
		stationAttackFleetSpawn.setFaction(newOwnerFactionId);
		inSystemSupplyConvoySpawn.setFaction(newOwnerFactionId);
		asteroidMiningFleetSpawnPoint.setFaction(newOwnerFactionId);
		gasMiningFleetSpawnPoint.setFaction(newOwnerFactionId);
        asteroidMiningFleetSpawnPoint2.setFaction(newOwnerFactionId);
        gasMiningFleetSpawnPoint2.setFaction(newOwnerFactionId);

		owningFaction = ExerelinData.getInstance().getSectorManager().getDiplomacyManager().getRecordForFaction(newOwnerFactionId);

		if(newOwnerFactionId.equalsIgnoreCase(ExerelinData.getInstance().getPlayerFaction()))
			stationToken.getCargo().setFreeTransfer(ExerelinData.getInstance().playerOwnedStationFreeTransfer);
		else
			stationToken.getCargo().setFreeTransfer(false);

		// Update relationship
		if(!originalOwnerId.equalsIgnoreCase("") && updateRelationship)
			ExerelinData.getInstance().getSectorManager().getDiplomacyManager().updateRelationshipOnEvent(originalOwnerId, newOwnerFactionId, "LostStation");

        // Update FactionDirectors
        FactionDirector.updateAllFactionDirectors();
	}

	public void setEfficiency(float value)
	{
		efficiency = value;
	}

	public SectorEntityToken getStationToken()
	{
		return stationToken;
	}

	public int getNumAttacking()
	{
		return numStationsTargeting;
	}

	public StationRecord getTargetStationRecord()
	{
		return targetStationRecord;
	}

	public void startTargeting()
	{
		numStationsTargeting++;
	}

	public void stopTargeting()
	{
		numStationsTargeting--;
		if(numStationsTargeting <= 0)
		{
			numStationsTargeting = 0;
		}
	}

    public void deriveTargets()
    {
        if(this.owningFaction == null)
            return;

        deriveClosestEnemyTarget();
        deriveStationToAssist();
        deriveClosestAsteroid();
        deriveClosestGasGiant();
    }

	public void updateFleets()
	{
        removeDeadOrRebelFleets(attackSpawn);
        removeDeadOrRebelFleets(stationAttackFleetSpawn);
        removeDeadOrRebelFleets(defenseSpawn);
        removeDeadOrRebelFleets(patrolSpawn);
        removeDeadOrRebelFleets(inSystemSupplyConvoySpawn);
        removeDeadOrRebelFleets(asteroidMiningFleetSpawnPoint);
        removeDeadOrRebelFleets(gasMiningFleetSpawnPoint);
        removeDeadOrRebelFleets(asteroidMiningFleetSpawnPoint2);
        removeDeadOrRebelFleets(gasMiningFleetSpawnPoint2);

        if(checkIsBeingBoarded())
            return; // Don't spawn fleets if being boarded

		setFleetTargets();

		inSystemSupplyConvoySpawn.spawnFleet();

        float resourceMultiplier = 1.0f;
        if(this.getOwner().getFactionId().equalsIgnoreCase(ExerelinData.getInstance().getPlayerFaction()))
            resourceMultiplier = ExerelinUtilsPlayer.getPlayerStationResourceLimitMultiplier();

		if(stationCargo.getSupplies() < (6400*resourceMultiplier))
        {
			asteroidMiningFleetSpawnPoint.spawnFleet();
            if(ExerelinUtilsPlayer.getPlayerDeployExtraMiningFleets())
                asteroidMiningFleetSpawnPoint2.spawnFleet();
        }
		if(stationCargo.getFuel() < (1600*resourceMultiplier))
        {
			gasMiningFleetSpawnPoint.spawnFleet();
            if(ExerelinUtilsPlayer.getPlayerDeployExtraMiningFleets())
                gasMiningFleetSpawnPoint2.spawnFleet();
        }


		if(ExerelinUtils.getRandomInRange(0, 1) == 0
                || (targetStationRecord != null && targetStationRecord.getOwner() == null))
			stationAttackFleetSpawn.spawnFleet();


        for(int i = defenseSpawn.getFleets().size(); i < defenseSpawn.getMaxFleets(); i++)
            defenseSpawn.spawnFleet();

        for(int i = patrolSpawn.getFleets().size(); i < patrolSpawn.getMaxFleets(); i++)
            patrolSpawn.spawnFleet();

		for(int i = attackSpawn.getFleets().size(); i < attackSpawn.getMaxFleets(); i++)
			attackSpawn.spawnFleet();
	}

	// Increase resources in station based off efficiency
	public void increaseResources()
	{
        if(this.getOwner() == null)
            return;

        if(checkIsBeingBoarded())
            return; // Don't increase resources if being boarded

        float resourceMultiplier = 1.0f;
        if(this.getOwner().getFactionId().equalsIgnoreCase(ExerelinData.getInstance().getPlayerFaction()))
            resourceMultiplier = ExerelinUtilsPlayer.getPlayerStationResourceLimitMultiplier();

		if(stationCargo.getFuel() < 1600*resourceMultiplier)
            SectorManager.getCurrentSectorManager().getCommandQueue().addCommandToQueue(new CommandAddCargo(stationCargo, "fuel", CargoAPI.CargoItemType.RESOURCES, 100*efficiency)); // Halved due to mining fleets
		if(stationCargo.getSupplies() < 6400*resourceMultiplier)
            SectorManager.getCurrentSectorManager().getCommandQueue().addCommandToQueue(new CommandAddCargo(stationCargo, "supplies", CargoAPI.CargoItemType.RESOURCES,400*efficiency)); // Halved due to mining fleets
		if(stationCargo.getMarines() < 800*resourceMultiplier)
            SectorManager.getCurrentSectorManager().getCommandQueue().addCommandToQueue(new CommandAddCargo(stationCargo, "marines", CargoAPI.CargoItemType.RESOURCES,(int)(100*efficiency)));
		if(stationCargo.getCrew(CargoAPI.CrewXPLevel.REGULAR) < 1600*resourceMultiplier)
            SectorManager.getCurrentSectorManager().getCommandQueue().addCommandToQueue(new CommandAddCargo(stationCargo, "regular_crew", CargoAPI.CargoItemType.RESOURCES, (int)(200*efficiency)));

		if(planetType.equalsIgnoreCase("gas") && stationCargo.getFuel() < 3200*resourceMultiplier)
            SectorManager.getCurrentSectorManager().getCommandQueue().addCommandToQueue(new CommandAddCargo(stationCargo, "fuel", CargoAPI.CargoItemType.RESOURCES, 100*efficiency)); // Halved due to mining fleets
		if(planetType.equalsIgnoreCase("moon") && stationCargo.getSupplies() < 12800*resourceMultiplier)
            SectorManager.getCurrentSectorManager().getCommandQueue().addCommandToQueue(new CommandAddCargo(stationCargo, "supplies", CargoAPI.CargoItemType.RESOURCES, 400*efficiency)); // Halved due to mining fleets
		if(planetType.equalsIgnoreCase("planet"))
		{
            if(stationCargo.getMarines() < 1600*resourceMultiplier)
                SectorManager.getCurrentSectorManager().getCommandQueue().addCommandToQueue(new CommandAddCargo(stationCargo, "marines", CargoAPI.CargoItemType.RESOURCES, (int)(100*efficiency)));
            if(stationCargo.getCrew(CargoAPI.CrewXPLevel.REGULAR) < 3200*resourceMultiplier)
                SectorManager.getCurrentSectorManager().getCommandQueue().addCommandToQueue(new CommandAddCargo(stationCargo, "regular_crew", CargoAPI.CargoItemType.RESOURCES, (int)(200*efficiency)));
		}

		if(efficiency > 0.6)
		{
			ExerelinUtils.addRandomFactionShipsToCargo(stationCargo, 1, owningFaction.getFactionId(), Global.getSector());
			ExerelinUtils.addWeaponsToCargo(stationCargo, 2, owningFaction.getFactionId(), Global.getSector());
		}

        // Update efficiency
        float baseEfficiency = 1.0f;
        if(this.getOwner().getFactionId().equalsIgnoreCase(ExerelinData.getInstance().getPlayerFaction()))
            baseEfficiency = ExerelinUtilsPlayer.getPlayerStationBaseEfficiency();

        if(efficiency < baseEfficiency)
            efficiency = efficiency + 0.1f;
        else if(efficiency > baseEfficiency)
            efficiency = efficiency - 0.1f;
	}

	// Clear cargo and ships from station
	public void clearCargo()
	{
		stationCargo.clear();
		ExerelinUtils.removeRandomWeaponStacksFromCargo(stationCargo,  9999);
		ExerelinUtils.removeRandomShipsFromCargo(stationCargo,  9999);
	}


	private void deriveClosestEnemyTarget()
	{
		String[] enemies = owningFaction.getEnemyFactions();
		Float bestDistance = 99999999999f;
		StationRecord bestTarget = null;

        SystemStationManager targetSystemStationManager;
        if(!ExerelinUtils.doesSystemHaveEntityForFaction(this.system, this.owningFaction.getFactionId(), -100000f, -0.01f))
        {
            FactionDirector factionDirector = FactionDirector.getFactionDirectorForFactionId(this.owningFaction.getFactionId());
            if(factionDirector.getTargetSystem() != null)
            {
                targetSystemStationManager = SystemManager.getSystemManagerForAPI(factionDirector.getTargetSystem()).getSystemStationManager();
                bestTarget = targetSystemStationManager.getStationRecordForToken(factionDirector.getTargetSectorEntityToken());
                //System.out.println(owningFaction.getFactionId() + ", sending fleets to " + bestTarget.getStationToken().getName());
            }
        }
        else
        {
            targetSystemStationManager = this.systemStationManager;
            for(int i = 0; i < targetSystemStationManager.getStationRecords().length; i++)
            {
                StationRecord possibleTarget = targetSystemStationManager.getStationRecords()[i];

                if(possibleTarget.getStationToken().getFullName().equalsIgnoreCase(this.getStationToken().getFullName()))
                    continue;

                if(targetSystemStationManager.getStationRecords()[i].getOwner() == null)
                {
                    Float distance = MathUtils.getDistanceSquared(possibleTarget.getStationToken(), this.getStationToken());
                    if(distance < bestDistance)
                    {
                        bestDistance = distance;
                        bestTarget = possibleTarget;
                    }
                }
                else
                {
                    for(int j = 0; j < enemies.length; j++)
                    {
                        if(targetSystemStationManager.getStationRecords()[i].getOwner().getFactionId().equalsIgnoreCase(enemies[j]))
                        {
                            Float distance = MathUtils.getDistanceSquared(possibleTarget.getStationToken(), this.getStationToken());
                            if(distance < bestDistance)
                            {
                                bestDistance = distance;
                                bestTarget = possibleTarget;
                            }
                        }
                    }
                }
            }
        }

		if(targetStationRecord != null)
			targetStationRecord.stopTargeting();
		if(bestTarget != null && bestTarget.getOwner() != null)
			bestTarget.startTargeting();

		targetStationRecord = bestTarget;
	}

	private void deriveStationToAssist()
	{
		StationRecord assistStation = null;

		// Check if we are under attack first
		if(getNumAttacking() > 0)
		{
			this.assistStationRecord = this;
			return;
		}

		for(int i = 0; i < systemStationManager.getStationRecords().length; i++)
		{
			StationRecord possibleAssist = systemStationManager.getStationRecords()[i];

			if(possibleAssist.getOwner() == null)
				continue;

			if(possibleAssist.getOwner().getFactionId().equalsIgnoreCase(this.getOwner().getFactionId()) || possibleAssist.getOwner().getGameRelationship(this.getOwner().getFactionId()) >= 1)
			{
				// Check severity of attack
				if((assistStation != null && possibleAssist.numStationsTargeting > assistStation.getNumAttacking()) || (assistStation == null && possibleAssist.numStationsTargeting > 0))
					assistStation = possibleAssist;
			}
		}

		//if(assistStation != null)
			//System.out.println(this.getStationToken().getFullName() + " is assisting " + assistStation.getStationToken().getFullName() + " which is targeted " + assistStation.getNumAttacking());

        if(assistStation == null)
        {
            StarSystemAPI starSystemAPI = FactionDirector.getFactionDirectorForFactionId(this.owningFaction.getFactionId()).getSupportSystem();
            if(starSystemAPI != null)
            {
                systemStationManager = SystemManager.getSystemManagerForAPI(starSystemAPI).getSystemStationManager();
                SectorEntityToken assistToken = FactionDirector.getFactionDirectorForFactionId(this.owningFaction.getFactionId()).getSupportSectorEntityToken();
                assistStation = systemStationManager.getStationRecordForToken(assistToken);
            }
        }

		this.assistStationRecord = assistStation;
	}

	private void deriveClosestAsteroid()
	{
		List asteroids = system.getAsteroids();
		SectorEntityToken closestAsteroid = null;
		float closestDistance = 999999999f;

		if(targetAsteroid != null && ExerelinUtils.getRandomInRange(0,2) != 0)
			return; // Don't recalc every time

		// Only check every 4th asteroid as they are fairly close normally
		for(int i = 0; i < asteroids.size(); i = i + 4)
		{
			SectorEntityToken asteroid = (SectorEntityToken)asteroids.get(i);

			Float distance = MathUtils.getDistanceSquared(this.stationToken, asteroid) ;
			if(distance < closestDistance)
			{
				closestAsteroid = asteroid;
				closestDistance = distance;
			}
		}

		targetAsteroid = closestAsteroid;
	}

	private void deriveClosestGasGiant()
	{
		List planets = system.getPlanets();
		SectorEntityToken closestPlanet = null;
		float closestDistance = 999999999f;

		if(targetGasGiant != null && ExerelinUtils.getRandomInRange(0,4) != 0)
			return; // Don't recalc each time

		for(int i = 0; i < planets.size(); i++)
		{
			SectorEntityToken planet = (SectorEntityToken)planets.get(i);

			if(!derivePlanetType(planet).equalsIgnoreCase("gas"))
				continue;

			Float distance = MathUtils.getDistanceSquared(this.stationToken,  planet) ;
			if(distance < closestDistance)
			{
				closestPlanet = planet;
				closestDistance = distance;
			}
		}
		targetGasGiant = closestPlanet;
	}

	private void setFleetTargets()
	{
		stationAttackFleetSpawn.setTarget(targetStationRecord);
		attackSpawn.setTarget(targetStationRecord, assistStationRecord);
		patrolSpawn.setDefendStation(assistStationRecord);
		inSystemSupplyConvoySpawn.setFriendlyStation(assistStationRecord);
		asteroidMiningFleetSpawnPoint.setTargetAsteroid(targetAsteroid);
		gasMiningFleetSpawnPoint.setTargetPlanet(targetGasGiant);
        asteroidMiningFleetSpawnPoint2.setTargetAsteroid(targetAsteroid);
        gasMiningFleetSpawnPoint2.setTargetPlanet(targetGasGiant);
	}

	private String derivePlanetType(SectorEntityToken token)
	{
		String name = token.getFullName();
		if(name.contains("Gaseous") && !(name.contains(" I") || name.contains(" II") || name.contains(" III")))
			return "gas";
		if(name.contains(" I") || name.contains(" II") || name.contains(" III"))
			return "moon";
		else
			return "planet";
	}

	private void removeDeadOrRebelFleets(BaseSpawnPoint spawnPoint)
	{
		int i = 0;
		while(i < spawnPoint.getFleets().size())
		{
			CampaignFleetAPI fleet = (CampaignFleetAPI)spawnPoint.getFleets().get(i);
			if(!fleet.isAlive() || !fleet.getFaction().getId().equalsIgnoreCase(this.owningFaction.getFactionId()))
				spawnPoint.getFleets().remove(i);
			else
				i++;
		}
	}

	public void checkForPlayerItems()
	{
		if(this.getOwner() != null && !this.getOwner().getFactionId().equalsIgnoreCase(ExerelinData.getInstance().getPlayerFaction()))
		{
			float numAgents = stationCargo.getQuantity(CargoAPI.CargoItemType.RESOURCES, "agent");
			float numPrisoners = stationCargo.getQuantity(CargoAPI.CargoItemType.RESOURCES, "prisoner");
            float numSabateurs = stationCargo.getQuantity(CargoAPI.CargoItemType.RESOURCES, "saboteur");

			if(numAgents > 0)
			{
				if(ExerelinUtils.getRandomInRange(0, 9) != 0)
				{
					String otherFactionId = ExerelinData.getInstance().getSectorManager().getDiplomacyManager().getRandomNonEnemyFactionIdForFaction(this.getOwner().getFactionId());
					if(otherFactionId.equalsIgnoreCase(ExerelinData.getInstance().getPlayerFaction()))
						otherFactionId = "";

					if(otherFactionId.equalsIgnoreCase(""))
					{
						Global.getSector().addMessage(Global.getSector().getFaction(ExerelinData.getInstance().getPlayerFaction()).getDisplayName() + " agent has failed in their mission.", Color.magenta);
						System.out.println(Global.getSector().getFaction(ExerelinData.getInstance().getPlayerFaction()).getDisplayName() + " agent has failed in their mission.");
						stationCargo.removeItems(CargoAPI.CargoItemType.RESOURCES, "agent", 1);
						return;
					}

					ExerelinData.getInstance().getSectorManager().getDiplomacyManager().updateRelationshipOnEvent(this.getOwner().getFactionId(), otherFactionId, "agent");

                    if(ExerelinUtils.getRandomInRange(0, 99) <= (-1 + (ExerelinUtilsPlayer.getPlayerDiplomacyObjectReuseChance()*100)))
                        Global.getSector().addMessage("The agent was not discovered and will repeat their mission.", Color.magenta);
                    else
                        stationCargo.removeItems(CargoAPI.CargoItemType.RESOURCES, "agent", 1);
					return;
				}
				else
				{
					ExerelinData.getInstance().getSectorManager().getDiplomacyManager().updateRelationshipOnEvent(this.getOwner().getFactionId(), ExerelinData.getInstance().getPlayerFaction(), "agentCapture");
                    stationCargo.removeItems(CargoAPI.CargoItemType.RESOURCES, "agent", 1);
					return;
				}
			}

			if(numPrisoners > 0)
			{
				ExerelinData.getInstance().getSectorManager().getDiplomacyManager().updateRelationshipOnEvent(this.getOwner().getFactionId(), ExerelinData.getInstance().getPlayerFaction(), "prisoner");
                if(ExerelinUtils.getRandomInRange(0, 99) <= (-1 + (ExerelinUtilsPlayer.getPlayerDiplomacyObjectReuseChance()*100)))
                    Global.getSector().addMessage("The prisoner is extremely valuable and will be interrogated further.", Color.magenta);
                else
                    stationCargo.removeItems(CargoAPI.CargoItemType.RESOURCES, "prisoner", 1);
				return;
			}

            if(numSabateurs > 0)
            {
                Global.getSector().addMessage(Global.getSector().getFaction(ExerelinData.getInstance().getPlayerFaction()).getDisplayName() + " sabateur has caused a station explosion at " + this.getStationToken().getName() + ".", Color.magenta);
                System.out.println(Global.getSector().getFaction(ExerelinData.getInstance().getPlayerFaction()).getDisplayName() + " sabateur has caused a station explosion at " + this.getStationToken().getName() + ".");
                lastBoardAttemptTime = Global.getSector().getClock().getTimestamp();
                this.efficiency = 0.1f;
                ExerelinUtils.removeRandomShipsFromCargo(this.stationCargo, this.stationCargo.getMothballedShips().getCombatReadyMembersListCopy().size());
                ExerelinUtils.removeRandomWeaponStacksFromCargo(this.stationCargo, this.stationCargo.getWeapons().size());
                if(ExerelinUtils.getRandomInRange(0, 99) <= (-1 + (ExerelinUtilsPlayer.getPlayerDiplomacyObjectReuseChance()*100)))
                    Global.getSector().addMessage("The saboteur was not discoverd and will repeat their mission.", Color.magenta);
                else
                    stationCargo.removeItems(CargoAPI.CargoItemType.RESOURCES, "saboteur", 1);
                return;
            }
		}
	}

    // Set the station being boarded
    public void setIsBeingBoarded(Boolean beingBoarded)
    {
        isBeingBoarded = beingBoarded;
        lastBoardAttemptTime = Global.getSector().getClock().getTimestamp();
    }

    // Check if station has been boarded in last 3 days
    private Boolean checkIsBeingBoarded()
    {
        if (!isBeingBoarded)
            return false;
        else if(isBeingBoarded && Global.getSector().getClock().getElapsedDaysSince(lastBoardAttemptTime) > 3)
        {
            isBeingBoarded = false;
            return false;
        }
        else
        {
            return true;
        }
    }

    public float getEfficiency(Boolean highOnly)
    {
        if(efficiency < 1 && highOnly)
            return 1f;
        else
            return efficiency;
    }
}
