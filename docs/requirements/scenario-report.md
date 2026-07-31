# Scenario Report

## Greenfield scenario

### Problem shape

- Empty repository.
- Need a working URL shortener plus a governed orchestration layer.

### Decomposition

1. Establish repo structure and build tooling.
2. Implement URL shortening, redirect, analytics, and persistence.
3. Implement workflow state model and orchestration engine.
4. Add approvals, retries, fallback, rollback, and safe-stop controls.
5. Add frontend dashboard.
6. Add tests, contracts, and documentation.

### Validation

- Spring Boot integration tests.
- Angular production build.
- API contract and schema review.

## Brownfield scenario

### Problem shape

- Requirement changes or feature additions on an existing flow.
- Existing output integrity must be preserved.

### Orchestration behavior

- Include `BROWNFIELD` node in the DAG.
- Require explicit impact analysis and approval for risky changes.
- Reuse rollback snapshots to recover from regression.

### Example in this prototype

- Replan a completed workflow when architecture changes.
- Downstream nodes are marked pending again.
- Rollback restores the previous stable snapshot.

## Ambiguous requirement scenario

### Problem shape

- Requirement contains incomplete assumptions or unclear boundaries.

### Orchestration behavior

- Ambiguity resolution is a first-class node.
- Decisions and assumptions are captured in the workflow record.
- Replanning allows stale downstream outputs to be reset after clarification.

### Example in this prototype

- Create an `AMBIGUOUS` workflow.
- Run until approval or clarification gates block downstream nodes.
- Update the requirement through `replan` and re-execute impacted nodes.

## Risk posture across scenarios

- Greenfield: design drift, missing contracts, and delivery sequencing risk.
- Brownfield: regression risk, hidden dependencies, and rollback quality risk.
- Ambiguous: stale output risk, assumption mismatch, and rework cost risk.

## Human oversight model

- Humans approve architecture, schema, security, release, and governance gates.
- Agents execute bounded tasks once the gate state allows progress.
- Final quality ownership remains with the human reviewer.