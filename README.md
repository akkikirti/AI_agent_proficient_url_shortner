# Agentic URL Shortener

This repository contains a working prototype for the interview assignment: a URL shortener built with Spring Boot and Angular, plus an explicit agentic orchestration layer that models SDLC execution as a governed, stateful workflow.

## What is implemented

- Short URL creation with optional custom alias support.
- Redirect handling with click tracking.
- Per-link analytics with recent access events.
- Workflow orchestration API with:
  - dependency-aware DAG execution
  - sequential and parallel-ready node structure
  - approval gates for architecture, schema, security, release, and governance
  - retry and reduced-scope fallback behavior
  - rollback to prior workflow snapshots
  - safe-stop behavior on policy violations
  - dynamic replanning when upstream nodes change
  - persisted workflow state, decisions, risks, approvals, and metrics
- Angular dashboard for product actions and workflow governance.

## Repository layout

- `backend/`: Spring Boot API and orchestration engine.
- `frontend/`: Angular dashboard.
- `docs/`: assignment artifacts, architecture, validation, governance, and scenario documentation.
- `openapi/`: API contract.
- `schemas/`: JSON schema artifacts.

## Run locally

### Backend

Requirements:

- Java 17

Run:

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

The backend starts on `http://localhost:8080`.

### Frontend

Requirements:

- Node.js 24+

Run:

```powershell
cd frontend
npm.cmd install
npm.cmd start
```

The frontend starts on `http://localhost:4200`.

## Validation

Backend tests:

```powershell
cd backend
.\mvnw.cmd test
```

Frontend build:

```powershell
cd frontend
npm.cmd run build
```

Validated in this workspace:

- Angular production build completed successfully.
- Spring Boot integration tests passed: `2` tests, `0` failures, `0` errors.

## Architecture summary

### Product flow

1. Client submits a destination URL and optional alias.
2. Backend validates the request and persists a `ShortUrl` record in a file-backed store.
3. Redirect requests resolve `/r/{code}` and append access events.
4. Analytics requests expose click volume, last access time, and recent visits.

### Orchestration flow

1. A requirement creates a persisted workflow state.
2. The orchestration service generates scenario-aware workflow nodes.
3. Execution walks the graph only when dependencies are satisfied.
4. Approval-required nodes pause execution until human review is recorded.
5. Transient failures trigger bounded retries; exhausted retries trigger fallback.
6. Unsafe requirements trigger safe-stop.
7. Replanning resets impacted downstream nodes.
8. Rollback restores the previous workflow snapshot.

## Scenarios covered

### Greenfield

- New URL shortener capability and orchestration service built from an empty repository.

### Brownfield

- Workflow mode includes brownfield analysis, impact awareness, rollback planning, and regression posture.

### Ambiguous

- Workflow mode includes ambiguity resolution before implementation and supports replanning when assumptions change.

## Trade-offs

- Persistence is file-backed rather than database-backed to keep the prototype runnable in a constrained environment.
- The orchestration engine is implemented in-process instead of as a distributed worker system.
- OpenAPI and schema artifacts are version-controlled documents, not code-generated sources.

## Next improvements

- Replace file persistence with PostgreSQL or MongoDB.
- Add authentication, rate limiting, and audit signatures.
- Split orchestration execution into asynchronous worker agents.
- Add e2e browser tests for the Angular UI.