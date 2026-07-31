# Approval Matrix

## Purpose

This matrix defines where autonomous execution must pause for human oversight in the prototype orchestration model.

## Approval gates

| Gate | Workflow node | Trigger | Required approver | Decision options | Rollback owner |
| --- | --- | --- | --- | --- | --- |
| Architecture Review | `architecture` | Design boundaries or component responsibilities change | Solution architect | approve, reject, request replan | Engineering lead |
| API and Schema Review | `api-schema` | Public API or persisted schema changes | API owner | approve, reject, request revision | Backend owner |
| Brownfield Change Review | `brownfield` | Existing behavior could regress | Technical lead | approve, reject, request impact analysis | Change owner |
| Security Review | `security` | Security controls or risk posture updated | Security reviewer | approve, reject, safe-stop | Security lead |
| Release Readiness | `release` | Workflow is ready to exit validation | Release manager | approve, reject, hold | Release owner |
| Governance Review | `governance` | Final controlled-autonomy package ready | Human reviewer | approve, reject, rollback | Program owner |

## Decision recording rules

- Every decision must capture approver, timestamp, notes, and resulting state.
- Protected workflow mutations require the orchestration admin token before a human decision can be recorded.
- Rejections transition the workflow into `SAFE_STOPPED` until a human decides whether to replan or abandon the run.
- Approvals are persisted in the workflow state file and surfaced through the orchestration API.

## Escalation rules

- Architecture disagreement triggers replanning.
- Schema rejection blocks implementation.
- Security rejection triggers safe-stop.
- Release rejection preserves artifacts but prevents final summary closure.

## Ownership model

- Agents generate artifacts and recommendations.
- Humans approve high-impact transitions, and the API rejects protected operations that do not present the configured admin token.
- Final quality signoff stays with the reviewer, not the agent.