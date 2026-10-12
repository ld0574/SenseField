package com.openkhub.sensefield;

import org.json.JSONArray;
import org.json.JSONObject;

/** Structured, local evidence shared by the live diagnostics and replay producer. */
final class Match3RuleDiagnostics {
    static JSONObject action(Match3MoveValue move) {
        JSONArray events=new JSONArray(),conditions=new JSONArray();
        for(Match3RuleEngine.Event event:move.result.events)events.put(DiagnosticRecorder.object(
                "type",event.type.name(),"kind",event.kind.name(),"event_mode",event.mode().name(),
                "units",event.units,"cell",event.cell,"object_id",event.objectId,"round",event.round,
                "rule_id",event.ruleId,"rule_version",event.ruleVersion,"detail",event.detail));
        Match3Board.Swap s=move.swap;
        for(int p:new int[]{s.fromRow*move.result.before.cols+s.fromCol,s.toRow*move.result.before.cols+s.toCol}) {
            Match3Position.Cell cell=move.result.before.cell(p);
            conditions.put(DiagnosticRecorder.object("cell",p,"kind",cell.kind.name(),"cover",cell.cover.name(),
                    "cover_layers",cell.coverLayers,"layers",cell.layers,"floor_ice",cell.iceLayers,"object_id",cell.objectId,
                    "move",cell.swapPermission.name(),"match",cell.matchPermission.name(),"activate",cell.activationPermission.name(),
                    "effect",cell.effect.name(),"direction",cell.direction,"charge",cell.charge,"capacity",cell.capacity,
                    "gate",cell.gate.name(),"rule_id",cell.ruleId,"rule_version",cell.ruleVersion));
            if(move.action.type==Match3Action.Type.ACTIVATE)break;
        }
        Match3Search.Evidence search=move.search;
        return DiagnosticRecorder.object("from_row",s.fromRow,"from_col",s.fromCol,"to_row",s.toRow,"to_col",s.toCol,
                "action_type",move.action.type.name(),"rule_id",move.action.ruleId,"rule_version",move.action.version,
                "rule_catalog",Match3Rules.CATALOG_ID,"reason",move.reason,"direct_units",move.directUnits,
                "cookie_contact_cells",move.cookieContactCells,"cookie_contact_scope","adjacent_clears_not_task_units",
                "physical_clear_lower_bound",move.physicalClears,"preconditions",conditions,"events",events,
                "unknown_reasons",new JSONArray(move.result.unknowns),"successor_known",move.result.successorKnown,
                "search",DiagnosticRecorder.object("depth",search.depth,"nodes",search.nodes,"stop",search.stopped,
                        "projected_units",search.units,"next_action",search.nextAction),"evidence",move.evidence());
    }
    private Match3RuleDiagnostics() { }
}
