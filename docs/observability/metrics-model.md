# Observability Metrics Model

## Objective

The orchestration layer must expose enough operational detail for a reviewer to judge success, reliability, and governance latency.

## Metrics tracked in workflow state

| Metric | Meaning | Current source |
| --- | --- | --- |
| `workflowSuccessRate` | Whether the workflow completed successfully | Derived from workflow status |
| `agentSuccessRate` | Fraction of nodes completed | Derived from node statuses |
| `totalRetries` | Total retry attempts across nodes | Sum of node retry counters |
| `rollbackCount` | Number of workflow rollback transitions | Derived from transition history |
| `fallbackCount` | Number of reduced-scope fallback decisions | Derived from decision log |
| `mttrMillis` | Proxy for mean time to recovery | Derived from retry handling duration approximation |
| `averageTaskDurationMillis` | Proxy for average node completion time | Derived from workflow duration and completed nodes |
| `workflowDurationMillis` | End-to-end workflow duration | Derived from created and updated timestamps |
| `approvalWaitMillis` | Aggregate time spent waiting for approvals | Derived from approval decisions |
| `validationLatencyMillis` | Proxy for validation delay after implementation | Derived from implementation and testing completion |
| `endToEndLatencyMillis` | Full execution latency | Equal to workflow duration in the prototype |

## Event sources

- workflow state transitions
- node retries
- approval gate decisions
- protected endpoint authorization failures
- fallback decision records
- rollback transitions

## Storage model

- Metrics are persisted inside each workflow JSON state file.
- Supporting narrative observability artifacts can be referenced from `artifactIndex`.

## Limitations

- These metrics are derived in-process and are not pushed to Prometheus or OpenTelemetry.
- Timing is coarse because the prototype does not execute asynchronous worker tasks.

## Production evolution

1. Emit structured events for every node transition.
2. Export metrics to Prometheus.
3. Correlate approvals and deployments with trace IDs.
4. Add alerting for safe-stop, repeated retries, and long approval latency.