package exerelin.campaign.intel.specialforces;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager;
import com.fs.starfarer.api.impl.campaign.procgen.themes.RouteFleetAssignmentAI;
import exerelin.campaign.intel.specialforces.SpecialForcesRouteAI.SpecialForcesTask;
import exerelin.campaign.intel.specialforces.SpecialForcesRouteAI.TaskType;
import exerelin.utilities.StringHelper;

public class SpecialForcesAssignmentAI extends RouteFleetAssignmentAI {
	
	protected SpecialForcesIntel intel;
	
	public SpecialForcesAssignmentAI(SpecialForcesIntel intel, CampaignFleetAPI fleet, 
			RouteManager.RouteData route, FleetActionDelegate delegate) 
	{
		super(fleet, route, delegate);
		this.intel = intel;
	}
	
	// no automatic return-to-location-and-despawn, do it only if event is over
	@Override
	public void advance(float amount) {
		if (gaveReturnAssignments == null && (intel.isEnding() || intel.isEnded())) 
		{
			fleet.clearAssignments();
			fleet.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, intel.origin.getPrimaryEntity(), 1000f,
								StringHelper.getFleetAssignmentString("returningTo", intel.origin.getName()));
			gaveReturnAssignments = true;
			return;
		}
		
		if (fleet.getCurrentAssignment() == null) {
			pickNext();
		}
	}
	
	@Override
	protected void addLocalAssignment(RouteManager.RouteSegment current, boolean justSpawned) 
	{
		SpecialForcesTask task = intel.routeAI.currentTask;
		if (task == null) {
			super.addLocalAssignment(current, justSpawned);
			return;			
		}
		switch (task.type) {
			case REBUILD:
				fleet.addAssignment(FleetAssignment.ORBIT_PASSIVE, current.from,	
						current.daysMax - current.elapsed, getInSystemActionText(current),
						goNextScript(current));
				break;
			case PATROL:
			case DEFEND_RAID:
				fleet.addAssignment(FleetAssignment.PATROL_SYSTEM, current.from,
						current.daysMax - current.elapsed, getInSystemActionText(current),
						goNextScript(current));
				break;
			default:
				super.addLocalAssignment(current, justSpawned);
				break;
		}
	}
	
	@Override
	protected void addStartingAssignment(RouteManager.RouteSegment current, boolean justSpawned) 
	{
		if (intel == null) {	// giveInitialAssignments() gets called in superclass constructor, so this may not have been set yet
			super.addStartingAssignment(current, justSpawned);
			return;
		}
		
		// fixes the issue where it idles if told to patrol own origin market
		if (intel.routeAI.currentTask != null && intel.routeAI.currentTask.type != TaskType.ASSEMBLE) 
		{
			addLocalAssignment(current, justSpawned);
			return;
		}
		super.addStartingAssignment(current, justSpawned);
	}
	
	@Override
	protected String getInSystemActionText(RouteManager.RouteSegment segment) 
	{
		SpecialForcesTask task = intel.routeAI.currentTask;
		if (task == null) return super.getInSystemActionText(segment);
		
		// "fake" travel action text
		// is this needed?
		/*
		LocationAPI dest = task.system;
		if (dest != fleet.getContainingLocation())
			return StringHelper.getFleetAssignmentString("travellingToStarSystem", dest.getName());
		*/
		
		switch (task.type) {
			case ASSEMBLE:
				return StringHelper.getFleetAssignmentString("orbiting", 
						task.market.getName());
			case RAID:
			case ASSIST_RAID:
				return StringHelper.getFleetAssignmentString("attackingAroundStarSystem", 
						fleet.getContainingLocation().getName());
			case DEFEND_RAID:
			case PATROL:
				return StringHelper.getFleetAssignmentString("patrollingStarSystem", 
						fleet.getContainingLocation().getName());
			case REBUILD:
				return StringHelper.getFleetAssignmentString("reconstituting", 
						task.market.getName());
			default:
				return super.getInSystemActionText(segment);
		}
	}
}