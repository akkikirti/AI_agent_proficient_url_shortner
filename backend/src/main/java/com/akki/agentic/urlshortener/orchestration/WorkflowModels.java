package com.akki.agentic.urlshortener.orchestration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

public final class WorkflowModels {

	private WorkflowModels() {
	}

	public enum ScenarioType {
		GREENFIELD,
		BROWNFIELD,
		AMBIGUOUS
	}

	public enum WorkflowStatus {
		DRAFT,
		RUNNING,
		WAITING_FOR_APPROVAL,
		COMPLETED,
		FAILED,
		SAFE_STOPPED,
		ROLLED_BACK
	}

	public enum NodeStatus {
		PENDING,
		READY,
		RUNNING,
		WAITING_FOR_APPROVAL,
		COMPLETED,
		FAILED,
		BLOCKED,
		ROLLED_BACK
	}

	public enum NodeType {
		REQUIREMENT,
		AMBIGUITY,
		DECOMPOSITION,
		ARCHITECTURE,
		API_SCHEMA,
		GREENFIELD,
		BROWNFIELD,
		IMPLEMENTATION,
		TESTING,
		DOCUMENTATION,
		SECURITY,
		PERFORMANCE,
		RELIABILITY,
		RELEASE,
		GOVERNANCE,
		AUDIT,
		FINAL_SUMMARY
	}

	public record CreateWorkflowRequest(
		@NotBlank String projectName,
		@NotBlank String requirement,
		@NotNull ScenarioType scenario,
		List<String> constraints,
		List<String> acceptanceCriteria
	) {
	}

	public record ExecuteWorkflowRequest(List<String> failOnceNodeIds) {
	}

	public record ApprovalRequest(
		@NotBlank String approver,
		boolean approved,
		String notes
	) {
	}

	public record ReplanWorkflowRequest(
		List<String> changedNodeIds,
		String reason,
		String updatedRequirement
	) {
	}

	public record RollbackWorkflowRequest(String reason) {
	}

	public record WorkflowNode(
		String id,
		String name,
		NodeType type,
		List<String> dependsOn,
		boolean approvalRequired,
		NodeStatus status,
		int retryCount,
		int maxRetries,
		String assignedAgent,
		String summary,
		List<String> artifacts,
		List<String> risks
	) {
	}

	public record ApprovalGate(
		String id,
		String nodeId,
		String title,
		boolean approved,
		boolean rejected,
		String approver,
		Instant decidedAt,
		String notes
	) {
	}

	public record RiskRecord(
		String id,
		String category,
		String severity,
		String probability,
		String impact,
		String mitigation,
		String contingency
	) {
	}

	public record DecisionRecord(
		String id,
		String agent,
		String input,
		String output,
		String reasoning,
		String engineerDecision,
		String approvalStatus,
		String repositoryReference,
		Instant createdAt
	) {
	}

	public record StateTransition(
		String entityId,
		String fromState,
		String toState,
		String reason,
		Instant changedAt
	) {
	}

	public record ObservabilityMetrics(
		double workflowSuccessRate,
		double agentSuccessRate,
		int totalRetries,
		int rollbackCount,
		int fallbackCount,
		long mttrMillis,
		long averageTaskDurationMillis,
		long workflowDurationMillis,
		long approvalWaitMillis,
		long validationLatencyMillis,
		long endToEndLatencyMillis
	) {
	}

	public record WorkflowSnapshot(
		Instant capturedAt,
		WorkflowStatus status,
		List<WorkflowNode> nodes,
		List<ApprovalGate> approvals,
		List<RiskRecord> risks,
		List<DecisionRecord> decisions,
		ObservabilityMetrics metrics
	) {
	}

	public record WorkflowState(
		String id,
		String projectName,
		String requirement,
		ScenarioType scenario,
		List<String> constraints,
		List<String> acceptanceCriteria,
		WorkflowStatus status,
		Instant createdAt,
		Instant updatedAt,
		List<WorkflowNode> nodes,
		List<ApprovalGate> approvals,
		List<RiskRecord> risks,
		List<DecisionRecord> decisions,
		List<StateTransition> transitions,
		List<WorkflowSnapshot> history,
		ObservabilityMetrics metrics,
		List<String> artifactIndex,
		String safeStopReason
	) {
	}

	public record WorkflowSummary(
		String id,
		String projectName,
		ScenarioType scenario,
		WorkflowStatus status,
		Instant updatedAt,
		int completedNodes,
		int totalNodes
	) {
	}
}