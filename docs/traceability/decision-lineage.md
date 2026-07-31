# Decision Lineage Report

## Decision model

Each workflow records explicit decision entries with these fields:

- decision id
- agent name
- input
- output
- reasoning
- engineer decision
- approval status
- repository reference
- creation timestamp

## Example decision flow

1. Requirement Analysis Agent normalizes the raw requirement into an execution-ready problem statement.
2. Architecture Agent defines component and API boundaries.
3. Governance Agent records approval or rejection outcomes.
4. Workflow Recovery Agent records fallback or rollback actions.
5. Final Summary Agent records release-readiness closure.

## Why this matters

- Reviewers can reconstruct why the workflow changed state.
- Replanning preserves prior history rather than overwriting it.
- Fallback and rollback actions remain auditable.

## Prototype repository references

- Product implementation: `backend/src/main/java/com/akki/agentic/urlshortener/`
- API contract: `openapi/url-shortener.yaml`
- Architecture overview: `docs/architecture/system-overview.md`
- Validation summary: `docs/validation/final-engineering-summary.md`

## Review guidance

- Validate that every workflow conclusion is supported by at least one recorded decision.
- Check that approval-required nodes have human decisions before completion.
- Check that rollback and fallback decisions include a clear reason.