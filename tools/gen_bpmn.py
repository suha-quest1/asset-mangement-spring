#!/usr/bin/env python3
"""Generates bpmn/capital-call.bpmn and bpmn/lp-response.bpmn (incl. diagram
layout) for Camunda 8. Re-run after editing:  python3 tools/gen_bpmn.py
The generated files can be opened/edited in Camunda Desktop/Web Modeler."""
import os
from xml.sax.saxutils import escape


def attr(s):
    return escape(s, {'"': "&quot;"})

OUT = os.path.join(os.path.dirname(__file__), "..", "bpmn")
SIZES = {"start": (36, 36), "end": (36, 36), "task": (100, 80), "xor": (50, 50), "and": (50, 50),
         "ebg": (50, 50), "msgcatch": (36, 36), "timercatch": (36, 36), "boundary": (36, 36)}


class P:
    def __init__(self, pid, name):
        self.pid, self.name = pid, name
        self.nodes, self.edges = [], []  # nodes: dict

    def n(self, id, kind, name, x, y, parent=None, w=None, h=None, **kw):
        sw, sh = SIZES.get(kind, (100, 80))
        self.nodes.append(dict(id=id, kind=kind, name=name, x=x, y=y, w=w or sw, h=h or sh, parent=parent, **kw))
        return id

    def e(self, id, s, t, pts, name=None, cond=None, default=False, parent=None):
        self.edges.append(dict(id=id, s=s, t=t, pts=pts, name=name, cond=cond, parent=parent))
        if default:
            for nd in self.nodes:
                if nd["id"] == s:
                    nd["default"] = id

    def refs(self, id):
        r = "".join(f"<bpmn:incoming>{e['id']}</bpmn:incoming>" for e in self.edges if e["t"] == id)
        return r + "".join(f"<bpmn:outgoing>{e['id']}</bpmn:outgoing>" for e in self.edges if e["s"] == id)

    def task_xml(self, nd):
        ext = nd.get("ext", "")
        tag = nd.get("tag", "serviceTask")
        attrs = f' id="{nd["id"]}" name="{escape(nd["name"])}"'
        if nd.get("default"):
            attrs += f' default="{nd["default"]}"'
        inner = f"<bpmn:extensionElements>{ext}</bpmn:extensionElements>" if ext else ""
        inner += self.refs(nd["id"]) + nd.get("inner", "")
        return f"<bpmn:{tag}{attrs}>{inner}</bpmn:{tag}>"

    def node_xml(self, nd):
        k, id, name = nd["kind"], nd["id"], escape(nd["name"] or "")
        R = self.refs(id)
        dflt = f' default="{nd["default"]}"' if nd.get("default") else ""
        if k == "start":
            return f'<bpmn:startEvent id="{id}" name="{name}">{R}</bpmn:startEvent>'
        if k == "end":
            inner = nd.get("inner", "")
            return f'<bpmn:endEvent id="{id}" name="{name}">{R}{inner}</bpmn:endEvent>'
        if k == "xor":
            return f'<bpmn:exclusiveGateway id="{id}" name="{name}"{dflt}>{R}</bpmn:exclusiveGateway>'
        if k == "and":
            return f'<bpmn:parallelGateway id="{id}" name="{name}">{R}</bpmn:parallelGateway>'
        if k == "ebg":
            return f'<bpmn:eventBasedGateway id="{id}" name="{name}">{R}</bpmn:eventBasedGateway>'
        if k == "msgcatch":
            ext = nd.get("ext", "")
            ext = f"<bpmn:extensionElements>{ext}</bpmn:extensionElements>" if ext else ""
            return f'<bpmn:intermediateCatchEvent id="{id}" name="{name}">{ext}{R}<bpmn:messageEventDefinition messageRef="{nd["msg"]}"/></bpmn:intermediateCatchEvent>'
        if k == "timercatch":
            return f'<bpmn:intermediateCatchEvent id="{id}" name="{name}">{R}<bpmn:timerEventDefinition><bpmn:timeDuration xsi:type="bpmn:tFormalExpression">{escape(nd["dur"])}</bpmn:timeDuration></bpmn:timerEventDefinition></bpmn:intermediateCatchEvent>'
        if k == "boundary":
            ca = "" if nd.get("interrupting", True) else ' cancelActivity="false"'
            if "msg" in nd:
                ed = f'<bpmn:messageEventDefinition messageRef="{nd["msg"]}"/>'
            else:
                ed = f'<bpmn:timerEventDefinition><bpmn:timeDuration xsi:type="bpmn:tFormalExpression">{escape(nd["dur"])}</bpmn:timeDuration></bpmn:timerEventDefinition>'
            ext = nd.get("ext", "")
            ext = f"<bpmn:extensionElements>{ext}</bpmn:extensionElements>" if ext else ""
            return f'<bpmn:boundaryEvent id="{id}" name="{name}" attachedToRef="{nd["attached"]}"{ca}>{ext}{R}{ed}</bpmn:boundaryEvent>'
        if k == "task":
            return self.task_xml(nd)
        if k == "sub":
            children = "".join(self.node_xml(c) for c in self.nodes if c["parent"] == id)
            children += "".join(self.edge_xml(e) for e in self.edges if e["parent"] == id)
            ext = nd.get("ext", "")
            ext = f"<bpmn:extensionElements>{ext}</bpmn:extensionElements>" if ext else ""
            return f'<bpmn:subProcess id="{id}" name="{name}">{ext}{R}{nd.get("inner","")}{children}</bpmn:subProcess>'
        raise ValueError(k)

    def edge_xml(self, e):
        name = f' name="{escape(e["name"])}"' if e["name"] else ""
        cond = f'<bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">{escape(e["cond"])}</bpmn:conditionExpression>' if e["cond"] else ""
        return f'<bpmn:sequenceFlow id="{e["id"]}"{name} sourceRef="{e["s"]}" targetRef="{e["t"]}">{cond}</bpmn:sequenceFlow>'

    def di(self):
        out = []
        for nd in self.nodes:
            extra = ' isExpanded="true"' if nd["kind"] == "sub" else ""
            marker = ' isMarkerVisible="true"' if nd["kind"] == "xor" else ""
            out.append(f'<bpmndi:BPMNShape id="{nd["id"]}_di" bpmnElement="{nd["id"]}"{extra}{marker}><dc:Bounds x="{nd["x"]}" y="{nd["y"]}" width="{nd["w"]}" height="{nd["h"]}"/>'
                       + (f'<bpmndi:BPMNLabel><dc:Bounds x="{nd.get("lbl", (nd["x"] - 20, 0))[0]}" y="{nd.get("lbl", (0, nd["y"] + nd["h"] + 4))[1]}" width="{nd["w"] + 40}" height="27"/></bpmndi:BPMNLabel>' if nd["kind"] in ("start", "end", "xor", "and", "ebg", "msgcatch", "timercatch", "boundary") else "")
                       + "</bpmndi:BPMNShape>")
        for e in self.edges:
            wps = "".join(f'<di:waypoint x="{x}" y="{y}"/>' for x, y in e["pts"])
            out.append(f'<bpmndi:BPMNEdge id="{e["id"]}_di" bpmnElement="{e["id"]}">{wps}</bpmndi:BPMNEdge>')
        return "".join(out)

    def xml(self, messages, extra_root=""):
        top = "".join(self.node_xml(nd) for nd in self.nodes if nd["parent"] is None)
        top += "".join(self.edge_xml(e) for e in self.edges if e["parent"] is None)
        msgs = "".join(f'<bpmn:message id="{mid}" name="{mname}"><bpmn:extensionElements><zeebe:subscription correlationKey="{attr(key)}"/></bpmn:extensionElements></bpmn:message>'
                       for mid, mname, key in messages)
        return f'''<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" xmlns:di="http://www.omg.org/spec/DD/20100524/DI" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" xmlns:modeler="http://camunda.org/schema/modeler/1.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" id="Definitions_{self.pid}" targetNamespace="http://bpmn.io/schema/bpmn" exporter="gen_bpmn.py" exporterVersion="1.0" modeler:executionPlatform="Camunda Cloud" modeler:executionPlatformVersion="8.10.0">
<bpmn:process id="{self.pid}" name="{escape(self.name)}" isExecutable="true">{top}</bpmn:process>
{msgs}{extra_root}
<bpmndi:BPMNDiagram id="Diagram_{self.pid}"><bpmndi:BPMNPlane id="Plane_{self.pid}" bpmnElement="{self.pid}">{self.di()}</bpmndi:BPMNPlane></bpmndi:BPMNDiagram>
</bpmn:definitions>
'''


def svc(jobtype, headers=None, io=""):
    h = ""
    if headers:
        h = "<zeebe:taskHeaders>" + "".join(f'<zeebe:header key="{k}" value="{v}"/>' for k, v in headers.items()) + "</zeebe:taskHeaders>"
    return f'<zeebe:taskDefinition type="{jobtype}" retries="3"/>{h}{io}'


def save(name, content):
    with open(os.path.join(OUT, name), "w") as f:
        f.write(content)
    import xml.dom.minidom
    pretty = xml.dom.minidom.parseString(content.encode()).toprettyxml(indent="  ", encoding="UTF-8").decode()
    with open(os.path.join(OUT, name), "w") as f:
        f.write("\n".join(l for l in pretty.splitlines() if l.strip()) + "\n")
    print("wrote", name)


# ══════════════════════════════ lp-response (child) ══════════════════════════
c = P("lp-response", "LP Response (per LP)")
Y, YM = 300, 150  # main lane (timers) / message lane
c.n("Start_LP", "start", "LP notified", 150, Y - 18)
stages = [  # (index, timer var, follow-up stage label)
    (1, "=waitStage1", "Email reminder (D+3)"),
    (2, "=waitStage2", "Phone call (D+7)"),
    (3, "=waitStage3", "Legal notice (D+10)"),
]
x = 230
prev = "Start_LP"
prev_right = (186, Y)
for i, dur, label in stages:
    g = c.n(f"Gateway_Await{i}", "ebg", "", x, Y - 25)
    c.e(f"Flow_to_Await{i}", prev, g, [prev_right, (x, Y)])
    m = c.n(f"Msg_Commit{i}", "msgcatch", "LP commitment received", x + 72, YM - 18, msg="Message_LPCommitment")
    c.e(f"Flow_Await{i}_Msg", g, m, [(x + 25, Y - 25), (x + 25, YM), (x + 72, YM)])
    t = c.n(f"Timer_Stage{i}", "timercatch", ["", "D+3 elapsed", "D+7 elapsed", "D+10 elapsed"][i], x + 72, Y - 18, dur=dur)
    c.e(f"Flow_Await{i}_Timer", g, t, [(x + 50, Y), (x + 72, Y)])
    f = c.n(f"Task_FollowUp{i}", "task", f"Auto follow-up: {label}", x + 150, Y - 40, ext=svc("autoFollowUp", {"stage": i}))
    c.e(f"Flow_Timer{i}_FollowUp", t, f, [(x + 108, Y), (x + 150, Y)])
    prev, prev_right = f, (x + 250, Y)
    x += 330
# final wait until deadline
g = c.n("Gateway_Await4", "ebg", "", x, Y - 25)
c.e("Flow_to_Await4", prev, g, [prev_right, (x, Y)])
c.n("Msg_Commit4", "msgcatch", "LP commitment received", x + 72, YM - 18, msg="Message_LPCommitment")
c.e("Flow_Await4_Msg", g, "Msg_Commit4", [(x + 25, Y - 25), (x + 25, YM), (x + 72, YM)])
c.n("Timer_Deadline", "timercatch", "Call deadline reached", x + 72, Y - 18, dur="=waitFinal")
c.e("Flow_Await4_Timer", g, "Timer_Deadline", [(x + 50, Y), (x + 72, Y)])
c.n("Task_RecordDefault", "task", "Record LP default", x + 150, Y - 40, ext=svc("recordLPResponse", {"status": "defaulted"}))
c.e("Flow_Deadline_Default", "Timer_Deadline", "Task_RecordDefault", [(x + 108, Y), (x + 150, Y)])
c.n("End_Defaulted", "end", "LP defaulted", x + 290, Y - 18)
c.e("Flow_Default_End", "Task_RecordDefault", "End_Defaulted", [(x + 250, Y), (x + 290, Y)])
# commitment merge
mx = x + 140
c.n("Gateway_CommitMerge", "xor", "", mx, YM - 25)
for i in range(1, 5):
    gx = 230 + (i - 1) * 330 + 72
    c.e(f"Flow_Msg{i}_Merge", f"Msg_Commit{i}", "Gateway_CommitMerge", [(gx + 18, YM - 18), (gx + 18, 70), (mx + 25, 70), (mx + 25, YM - 25)])
c.n("Task_RecordCommit", "task", "Record LP commitment", mx + 100, YM - 40, ext=svc("recordLPResponse", {"status": "committed"}))
c.e("Flow_Merge_Record", "Gateway_CommitMerge", "Task_RecordCommit", [(mx + 50, YM), (mx + 100, YM)])
c.n("End_Committed", "end", "LP committed", mx + 240, YM - 18)
c.e("Flow_Record_End", "Task_RecordCommit", "End_Committed", [(mx + 200, YM), (mx + 240, YM)])
save("lp-response.bpmn", c.xml([("Message_LPCommitment", "lpCommitment", '=callId + ":" + lp.lpId')]))

# ══════════════════════════════ capital-call (parent) ════════════════════════
p = P("capital-call", "Intelligent Capital Call & Liquidity Orchestration")
Y = 300
p.n("Start_Call", "start", "GP issues capital call", 150, Y - 18)
p.n("Task_Issue", "task", "Issue capital call", 230, Y - 40, ext=svc("issueCapitalCall"))
p.e("Flow_Start_Issue", "Start_Call", "Task_Issue", [(186, Y), (230, Y)])
p.n("Task_Notify", "task", "Notify LPs (SES batch)", 380, Y - 40, ext=svc("notifyLPs"))
p.e("Flow_Issue_Notify", "Task_Issue", "Task_Notify", [(330, Y), (380, Y)])

ca_ext = ('<zeebe:calledElement processId="lp-response" propagateAllChildVariables="false"/>'
          '<zeebe:ioMapping><zeebe:input source="=null" target="lpResponse"/><zeebe:output source="=lpResponse" target="lpResponse"/></zeebe:ioMapping>')
ca_inner = ('<bpmn:multiInstanceLoopCharacteristics><bpmn:extensionElements>'
            '<zeebe:loopCharacteristics inputCollection="=lpList" inputElement="lp" outputCollection="lpResponses" outputElement="=lpResponse"/>'
            '</bpmn:extensionElements></bpmn:multiInstanceLoopCharacteristics>')
p.n("Call_LPResponses", "task", "Collect LP responses (one LP Response process per LP)", 530, Y - 40, w=140,
    tag="callActivity", ext=ca_ext, inner=ca_inner)
p.e("Flow_Notify_Collect", "Task_Notify", "Call_LPResponses", [(480, Y), (530, Y)])
# boundary events on the multi-instance
p.n("Boundary_Deadline", "boundary", "Hard deadline", 552, Y + 22, attached="Call_LPResponses", dur="=callDeadline", lbl=(490, Y + 60))
p.n("Boundary_Force", "boundary", "GP forces settlement", 612, Y + 22, attached="Call_LPResponses", msg="Message_ForceSettlement", lbl=(652, Y + 60))
p.n("Boundary_Cancel", "boundary", "GP cancels call", 622, Y - 58, attached="Call_LPResponses", msg="Message_CancelCall", lbl=(560, Y - 90))
p.n("Task_Finalize", "task", "Close response window (default outstanding LPs)", 550, Y + 110,
    ext=svc("finalizeResponses", {"reason": "force-settlement"}))
p.n("Task_FinalizeDeadline", "task", "Close response window (deadline)", 400, Y + 110,
    ext=svc("finalizeResponses", {"reason": "hard-deadline"}))
p.e("Flow_Deadline_Finalize", "Boundary_Deadline", "Task_FinalizeDeadline", [(570, Y + 58), (570, Y + 80), (450, Y + 80), (450, Y + 110)])
p.e("Flow_Force_Finalize", "Boundary_Force", "Task_Finalize", [(630, Y + 58), (630, Y + 110)])
p.n("Task_Cancel", "task", "Mark call cancelled", 700, Y - 200, ext=svc("markCallCancelled"))
p.e("Flow_Cancel_Task", "Boundary_Cancel", "Task_Cancel", [(640, Y - 58), (640, Y - 160), (700, Y - 160)])
p.n("End_Cancelled", "end", "Capital call cancelled", 850, Y - 178)
p.e("Flow_CancelTask_End", "Task_Cancel", "End_Cancelled", [(800, Y - 160), (850, Y - 160)])

p.n("Gateway_Responses", "xor", "", 720, Y - 25)
p.e("Flow_Collect_Merge", "Call_LPResponses", "Gateway_Responses", [(670, Y), (720, Y)])
p.e("Flow_Finalize_Merge", "Task_Finalize", "Gateway_Responses", [(650, Y + 150), (745, Y + 150), (745, Y + 25)])
p.e("Flow_FinalizeDeadline_Merge", "Task_FinalizeDeadline", "Gateway_Responses", [(450, Y + 190), (450, Y + 220), (745, Y + 220), (745, Y + 25)])

p.n("Task_Aggregate", "task", "Aggregate liquidity", 820, Y - 40, ext=svc("aggregateLiquidity"))
p.e("Flow_Merge_Aggregate", "Gateway_Responses", "Task_Aggregate", [(770, Y), (820, Y)])
p.n("Task_Predict", "task", "Predict LP default risk (ML)", 970, Y - 40, ext=svc("predictDefaultRisk"))
p.e("Flow_Aggregate_Predict", "Task_Aggregate", "Task_Predict", [(920, Y), (970, Y)])
p.n("Task_Portfolio", "task", "Score LP portfolio (concentration)", 1120, Y - 40, ext=svc("scoreLPPortfolio"))
p.e("Flow_Predict_Portfolio", "Task_Predict", "Task_Portfolio", [(1070, Y), (1120, Y)])
p.n("Gateway_Split", "and", "", 1270, Y - 25)
p.e("Flow_Portfolio_Split", "Task_Portfolio", "Gateway_Split", [(1220, Y), (1270, Y)])

# Branch A: liquidity bridge (y=130)
YA = 130
p.n("Gateway_Gap", "xor", "Liquidity gap > 10%?", 1370, YA - 25)
p.e("Flow_Split_Gap", "Gateway_Split", "Gateway_Gap", [(1295, Y - 25), (1295, YA), (1370, YA)])
p.n("Task_Bridge", "task", "Trigger bridge (credit line drawdown)", 1500, YA - 40, ext=svc("triggerBridge"))
p.e("Flow_Gap_Yes", "Gateway_Gap", "Task_Bridge", [(1420, YA), (1500, YA)], name="yes", cond="=gapPercent > 10")
p.n("Gateway_GapMerge", "xor", "", 2160, YA - 25)
p.e("Flow_Bridge_Merge", "Task_Bridge", "Gateway_GapMerge", [(1600, YA), (2160, YA)])
p.e("Flow_Gap_No", "Gateway_Gap", "Gateway_GapMerge", [(1395, YA - 25), (1395, 40), (2185, 40), (2185, YA - 25)], name="no", default=True)

# Branch B: GP escalation (y=470)
YB = 470
p.n("Gateway_HighRisk", "xor", "High-risk LPs (score > 0.7)?", 1370, YB - 25)
p.e("Flow_Split_HighRisk", "Gateway_Split", "Gateway_HighRisk", [(1295, Y + 25), (1295, YB), (1370, YB)])
sub_ext = ('<zeebe:ioMapping><zeebe:input source="=null" target="gpAction"/><zeebe:input source="=null" target="gpName"/>'
           '<zeebe:input source="=null" target="gpComment"/></zeebe:ioMapping>')
sub_inner = ('<bpmn:multiInstanceLoopCharacteristics><bpmn:extensionElements>'
             '<zeebe:loopCharacteristics inputCollection="=highRiskLPs" inputElement="highRiskLp" outputCollection="gpDecisions" '
             'outputElement="={lpId: highRiskLp.lpId, riskScore: highRiskLp.riskScore, action: gpAction, gpName: gpName}"/>'
             '</bpmn:extensionElements></bpmn:multiInstanceLoopCharacteristics>')
p.n("Sub_GPReview", "sub", "GP review per high-risk LP", 1460, YB - 90, w=660, h=200, ext=sub_ext, inner=sub_inner)
p.e("Flow_HighRisk_Yes", "Gateway_HighRisk", "Sub_GPReview", [(1420, YB), (1460, YB)], name="yes", cond="=count(highRiskLPs) > 0")
S = "Sub_GPReview"
p.n("Start_GP", "start", "", 1480, YB - 18, parent=S)
p.n("Task_Escalate", "task", "Escalate to GP (Slack alert)", 1540, YB - 40, parent=S, ext=svc("escalateToGP"))
p.e("Flow_GPStart_Escalate", "Start_GP", "Task_Escalate", [(1516, YB), (1540, YB)], parent=S)
ut_ext = ('<zeebe:userTask/><zeebe:formDefinition formId="gp-approval-form"/>'
          '<zeebe:assignmentDefinition candidateGroups="gp-investment-committee"/>'
          '<zeebe:priorityDefinition priority="80"/>')
p.n("UserTask_GPApproval", "task", "GP approval gate: waive or enforce", 1680, YB - 40, parent=S, tag="userTask", ext=ut_ext)
p.e("Flow_Escalate_Approval", "Task_Escalate", "UserTask_GPApproval", [(1640, YB), (1680, YB)], parent=S)
p.n("Boundary_GPDecision", "boundary", "GP decision via API", 1712, YB + 22, parent=S, attached="UserTask_GPApproval", msg="Message_GPDecision")
p.n("Gateway_Decision", "xor", "", 1820, YB - 25, parent=S)
p.e("Flow_Approval_Merge", "UserTask_GPApproval", "Gateway_Decision", [(1780, YB), (1820, YB)], parent=S)
p.e("Flow_APIDecision_Merge", "Boundary_GPDecision", "Gateway_Decision", [(1730, YB + 58), (1730, YB + 80), (1845, YB + 80), (1845, YB + 25)], parent=S)
p.n("Task_ApplyDecision", "task", "Apply GP decision (enforcement notice if enforce)", 1910, YB - 40, parent=S, ext=svc("applyGPDecision"))
p.e("Flow_Merge_Apply", "Gateway_Decision", "Task_ApplyDecision", [(1870, YB), (1910, YB)], parent=S)
p.n("End_GP", "end", "", 2050, YB - 18, parent=S)
p.e("Flow_Apply_End", "Task_ApplyDecision", "End_GP", [(2010, YB), (2050, YB)], parent=S)

p.n("Gateway_HighRiskMerge", "xor", "", 2160, YB - 25)
p.e("Flow_Sub_Merge", "Sub_GPReview", "Gateway_HighRiskMerge", [(2120, YB), (2160, YB)])
p.e("Flow_HighRisk_No", "Gateway_HighRisk", "Gateway_HighRiskMerge", [(1395, YB + 25), (1395, 620), (2185, 620), (2185, YB + 25)], name="no", default=True)

p.n("Gateway_Join", "and", "", 2260, Y - 25)
p.e("Flow_GapMerge_Join", "Gateway_GapMerge", "Gateway_Join", [(2210, YA), (2285, YA), (2285, Y - 25)])
p.e("Flow_HRMerge_Join", "Gateway_HighRiskMerge", "Gateway_Join", [(2210, YB), (2285, YB), (2285, Y + 25)])
p.n("Task_Settle", "task", "Settle & reconcile (wires + ledger)", 2360, Y - 40, ext=svc("settleAndReconcile"))
p.e("Flow_Join_Settle", "Gateway_Join", "Task_Settle", [(2310, Y), (2360, Y)])
p.n("Task_Report", "task", "Emit liquidity report (PDF + JSON)", 2510, Y - 40, ext=svc("emitLiquidityReport"))
p.e("Flow_Settle_Report", "Task_Settle", "Task_Report", [(2460, Y), (2510, Y)])
p.n("End_Call", "end", "Capital call settled & reported", 2660, Y - 18)
p.e("Flow_Report_End", "Task_Report", "End_Call", [(2610, Y), (2660, Y)])

save("capital-call.bpmn", p.xml([
    ("Message_ForceSettlement", "forceSettlement", "=callId"),
    ("Message_CancelCall", "cancelCall", "=callId"),
    ("Message_GPDecision", "gpDecision", '=callId + ":" + highRiskLp.lpId'),
]))
