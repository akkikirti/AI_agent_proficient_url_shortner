package com.akki.agentic.urlshortener.orchestration;

import com.akki.agentic.urlshortener.config.ApplicationProperties;
import com.akki.agentic.urlshortener.orchestration.WorkflowModels.ApprovalGate;
import com.akki.agentic.urlshortener.orchestration.WorkflowModels.ApprovalRequest;
import com.akki.agentic.urlshortener.orchestration.WorkflowModels.CreateWorkflowRequest;
import com.akki.agentic.urlshortener.orchestration.WorkflowModels.DecisionRecord;
import com.akki.agentic.urlshortener.orchestration.WorkflowModels.ExecuteWorkflowRequest;
import com.akki.agentic.urlshortener.orchestration.WorkflowModels.NodeStatus;
import com.akki.agentic.urlshortener.orchestration.WorkflowModels.NodeType;
import com.akki.agentic.urlshortener.orchestration.WorkflowModels.ObservabilityMetrics;
import com.akki.agentic.urlshortener.orchestration.WorkflowModels.ReplanWorkflowRequest;
import com.akki.agentic.urlshortener.orchestration.WorkflowModels.RiskRecord;
import com.akki.agentic.urlshortener.orchestration.WorkflowModels.RollbackWorkflowRequest;
import com.akki.agentic.urlshortener.orchestration.WorkflowModels.ScenarioType;
import com.akki.agentic.urlshortener.orchestration.WorkflowModels.StateTransition;
import com.akki.agentic.urlshortener.orchestration.WorkflowModels.WorkflowNode;
import com.akki.agentic.urlshortener.orchestration.WorkflowModels.WorkflowSnapshot;
import com.akki.agentic.urlshortener.orchestration.WorkflowModels.WorkflowState;
import com.akki.agentic.urlshortener.orchestration.WorkflowModels.WorkflowStatus;
import com.akki.agentic.urlshortener.orchestration.WorkflowModels.WorkflowSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WorkflowService {

	private final ApplicationProperties properties;
	private final ObjectMapper objectMapper;
	private final RequirementSafetyPolicy requirementSafetyPolicy;
	private final Clock clock = Clock.systemUTC();
	private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
	private final Map<String, WorkflowState> workflows = new LinkedHashMap<>();

	private Path workflowStorageDir;

	public WorkflowService(ApplicationProperties properties, ObjectMapper objectMapper, RequirementSafetyPolicy requirementSafetyPolicy) {
		this.properties = properties;
		this.objectMapper = objectMapper;
		this.requirementSafetyPolicy = requirementSafetyPolicy;
	}

	@PostConstruct
	void initialize() {
		workflowStorageDir = Path.of(properties.getWorkflowStorageDir());
		try {
			Files.createDirectories(workflowStorageDir);
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to create workflow storage directory", exception);
		}
		loadWorkflows();
	}

	public WorkflowState createWorkflow(CreateWorkflowRequest request) {
		lock.writeLock().lock();
		try {
			Instant now = Instant.now(clock);
			String workflowId = UUID.randomUUID().toString();
			List<WorkflowNode> nodes = buildNodes(request.scenario());
			WorkflowState workflow = new WorkflowState(
				workflowId,
				request.projectName().trim(),
				request.requirement().trim(),
				request.scenario(),
				safeList(request.constraints()),
				safeList(request.acceptanceCriteria()),
				WorkflowStatus.DRAFT,
				now,
				now,
				nodes,
				buildApprovalGates(nodes),
				seedRisks(request.scenario()),
				List.of(initialDecision(request)),
				List.of(new StateTransition(workflowId, null, WorkflowStatus.DRAFT.name(), "Workflow created", now)),
				List.of(),
				emptyMetrics(),
				List.of("runtime/workflows/" + workflowId + ".json"),
				null
			);
			WorkflowState persisted = persistWithMetrics(workflow);
			workflows.put(workflowId, persisted);
			return persisted;
		} finally {
			lock.writeLock().unlock();
		}
	}

	public List<WorkflowSummary> listWorkflows() {
		lock.readLock().lock();
		try {
			return workflows.values().stream()
				.map(this::toSummary)
				.sorted((left, right) -> right.updatedAt().compareTo(left.updatedAt()))
				.toList();
		} finally {
			lock.readLock().unlock();
		}
	}

	public WorkflowState getWorkflow(String workflowId) {
		lock.readLock().lock();
		try {
			return requireWorkflow(workflowId);
		} finally {
			lock.readLock().unlock();
		}
	}

	public WorkflowState executeWorkflow(String workflowId, ExecuteWorkflowRequest request) {
		lock.writeLock().lock();
		try {
			WorkflowState current = requireWorkflow(workflowId);
			WorkflowState state = withSnapshot(current);
			state = updateWorkflowStatus(state, WorkflowStatus.RUNNING, "Workflow execution started");
			Set<String> failOnceNodeIds = new LinkedHashSet<>(safeList(request == null ? null : request.failOnceNodeIds()));

			boolean progressed;
			do {
				progressed = false;
				List<WorkflowNode> nodes = new ArrayList<>(state.nodes());
				for (WorkflowNode node : nodes) {
					if (node.status() == NodeStatus.COMPLETED || node.status() == NodeStatus.ROLLED_BACK) {
						continue;
					}
					if (!dependenciesSatisfied(node, state.nodes())) {
						continue;
					}

					WorkflowNode readyNode = updateNodeStatus(node, NodeStatus.READY, node.summary());
					state = replaceNode(state, readyNode, "Dependencies satisfied");

					if (readyNode.approvalRequired()) {
						ApprovalGate gate = requireApprovalGate(state, readyNode.id());
						if (gate.rejected()) {
							state = safeStop(state, "Approval rejected for node " + readyNode.name());
							break;
						}
						if (!gate.approved()) {
							state = replaceNode(state, updateNodeStatus(readyNode, NodeStatus.WAITING_FOR_APPROVAL, "Awaiting human approval"), "Approval required");
							state = updateWorkflowStatus(state, WorkflowStatus.WAITING_FOR_APPROVAL, "Waiting for approval on " + readyNode.name());
							continue;
						}
					}

					try {
						if (failOnceNodeIds.contains(readyNode.id()) && readyNode.retryCount() == 0) {
							throw new TransientWorkflowException("Simulated transient failure");
						}

						WorkflowNode executedNode = executeNode(updateNodeStatus(readyNode, NodeStatus.RUNNING, "Execution started"), state);
						state = replaceNode(state, updateNodeStatus(executedNode, NodeStatus.COMPLETED, executedNode.summary()), "Node execution completed");
						progressed = true;
					} catch (UnsafeWorkflowException exception) {
						state = safeStop(state, exception.getMessage());
						break;
					} catch (TransientWorkflowException exception) {
						WorkflowNode retriedNode = incrementRetry(readyNode, exception.getMessage());
						if (retriedNode.retryCount() <= retriedNode.maxRetries()) {
							state = replaceNode(state, updateNodeStatus(retriedNode, NodeStatus.READY, "Retry scheduled after transient failure"), "Retry scheduled");
							progressed = true;
						} else {
							WorkflowNode fallbackNode = withFallback(retriedNode);
							state = replaceNode(state, fallbackNode, "Fallback executed after max retries");
							state = appendDecision(state, new DecisionRecord(
								decisionId(),
								"Workflow Recovery Agent",
								retriedNode.name(),
								"Reduced scope fallback applied",
								exception.getMessage(),
								"Fallback accepted",
								"approved",
								"execution/recovery/" + state.id() + "-" + retriedNode.id() + ".md",
								Instant.now(clock)
							));
							progressed = true;
						}
					}
				}
			} while (progressed && state.status() != WorkflowStatus.SAFE_STOPPED);

			state = finalizeWorkflowState(state);
			WorkflowState persisted = persistWithMetrics(state);
			workflows.put(workflowId, persisted);
			return persisted;
		} finally {
			lock.writeLock().unlock();
		}
	}

	public WorkflowState approve(String workflowId, String gateId, ApprovalRequest request) {
		lock.writeLock().lock();
		try {
			WorkflowState state = withSnapshot(requireWorkflow(workflowId));
			Instant now = Instant.now(clock);
			List<ApprovalGate> approvals = state.approvals().stream()
				.map(gate -> {
					if (!gate.id().equals(gateId)) {
						return gate;
					}
					return new ApprovalGate(gate.id(), gate.nodeId(), gate.title(), request.approved(), !request.approved(), request.approver(), now, normalizeText(request.notes()));
				})
				.toList();

			WorkflowState updated = new WorkflowState(
				state.id(),
				state.projectName(),
				state.requirement(),
				state.scenario(),
				state.constraints(),
				state.acceptanceCriteria(),
				request.approved() ? WorkflowStatus.RUNNING : WorkflowStatus.SAFE_STOPPED,
				state.createdAt(),
				now,
				state.nodes(),
				approvals,
				state.risks(),
				appendDecisionList(state.decisions(), new DecisionRecord(
					decisionId(),
					"Governance Agent",
					gateId,
					request.approved() ? "Approval granted" : "Approval rejected",
					Objects.requireNonNullElse(request.notes(), "Manual approval checkpoint processed"),
					request.approved() ? "Approved" : "Rejected",
					request.approved() ? "approved" : "rejected",
					"docs/governance/",
					now
				)),
				appendTransitionList(state.transitions(), new StateTransition(gateId, null, request.approved() ? "APPROVED" : "REJECTED", "Human approval processed", now)),
				state.history(),
				state.metrics(),
				state.artifactIndex(),
				request.approved() ? null : "Approval rejected for gate " + gateId
			);

			WorkflowState persisted = persistWithMetrics(updated);
			workflows.put(workflowId, persisted);
			return persisted;
		} finally {
			lock.writeLock().unlock();
		}
	}

	public WorkflowState replan(String workflowId, ReplanWorkflowRequest request) {
		lock.writeLock().lock();
		try {
			WorkflowState state = withSnapshot(requireWorkflow(workflowId));
			Set<String> impactedNodeIds = impactedNodes(state.nodes(), safeList(request.changedNodeIds()));
			Instant now = Instant.now(clock);

			List<WorkflowNode> replannedNodes = state.nodes().stream()
				.map(node -> impactedNodeIds.contains(node.id())
					? new WorkflowNode(node.id(), node.name(), node.type(), node.dependsOn(), node.approvalRequired(), NodeStatus.PENDING, 0, node.maxRetries(), node.assignedAgent(), "Marked for re-execution after replanning", List.of(), node.risks())
					: node)
				.toList();

			List<ApprovalGate> approvals = state.approvals().stream()
				.map(gate -> impactedNodeIds.contains(gate.nodeId())
					? new ApprovalGate(gate.id(), gate.nodeId(), gate.title(), false, false, null, null, null)
					: gate)
				.toList();

			WorkflowState replanned = new WorkflowState(
				state.id(),
				state.projectName(),
				Optional.ofNullable(request.updatedRequirement()).filter(value -> !value.isBlank()).orElse(state.requirement()),
				state.scenario(),
				state.constraints(),
				state.acceptanceCriteria(),
				WorkflowStatus.DRAFT,
				state.createdAt(),
				now,
				replannedNodes,
				approvals,
				state.risks(),
				appendDecisionList(state.decisions(), new DecisionRecord(
					decisionId(),
					"Replanning Agent",
					String.join(",", impactedNodeIds),
					"Impacted nodes reset for re-execution",
					Objects.requireNonNullElse(request.reason(), "Upstream changes detected"),
					"Replan approved",
					"approved",
					"execution/workflow/",
					now
				)),
				appendTransitionList(state.transitions(), new StateTransition(state.id(), state.status().name(), WorkflowStatus.DRAFT.name(), "Workflow replanned", now)),
				state.history(),
				state.metrics(),
				state.artifactIndex(),
				null
			);

			WorkflowState persisted = persistWithMetrics(replanned);
			workflows.put(workflowId, persisted);
			return persisted;
		} finally {
			lock.writeLock().unlock();
		}
	}

	public WorkflowState rollback(String workflowId, RollbackWorkflowRequest request) {
		lock.writeLock().lock();
		try {
			WorkflowState state = requireWorkflow(workflowId);
			if (state.history().isEmpty()) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "No rollback snapshot available");
			}

			WorkflowSnapshot snapshot = state.history().get(state.history().size() - 1);
			Instant now = Instant.now(clock);
			WorkflowState rolledBack = new WorkflowState(
				state.id(),
				state.projectName(),
				state.requirement(),
				state.scenario(),
				state.constraints(),
				state.acceptanceCriteria(),
				WorkflowStatus.ROLLED_BACK,
				state.createdAt(),
				now,
				snapshot.nodes().stream().map(node -> updateNodeStatus(node, NodeStatus.ROLLED_BACK, node.summary())).toList(),
				snapshot.approvals(),
				snapshot.risks(),
				appendDecisionList(snapshot.decisions(), new DecisionRecord(
					decisionId(),
					"Workflow Recovery Agent",
					state.id(),
					"Workflow rolled back to previous snapshot",
					Objects.requireNonNullElse(request.reason(), "Manual rollback requested"),
					"Rollback executed",
					"approved",
					"execution/recovery/",
					now
				)),
				appendTransitionList(state.transitions(), new StateTransition(state.id(), state.status().name(), WorkflowStatus.ROLLED_BACK.name(), "Rollback applied", now)),
				state.history(),
				snapshot.metrics(),
				state.artifactIndex(),
				null
			);

			WorkflowState persisted = persistWithMetrics(rolledBack);
			workflows.put(workflowId, persisted);
			return persisted;
		} finally {
			lock.writeLock().unlock();
		}
	}

	private WorkflowNode executeNode(WorkflowNode node, WorkflowState state) {
		Instant now = Instant.now(clock);
		if (node.type() == NodeType.SECURITY && requirementSafetyPolicy.containsUnsafeDirective(state.requirement())) {
			throw new UnsafeWorkflowException("Security policy guardrail triggered for unsafe requirement content");
		}

		List<String> artifacts = artifactsFor(state.id(), node.type());
		List<String> risks = node.type() == NodeType.SECURITY
			? List.of("security-review-required")
			: node.type() == NodeType.RELEASE ? List.of("release-approval-required") : List.of();

		WorkflowNode executed = new WorkflowNode(
			node.id(),
			node.name(),
			node.type(),
			node.dependsOn(),
			node.approvalRequired(),
			NodeStatus.COMPLETED,
			node.retryCount(),
			node.maxRetries(),
			node.assignedAgent(),
			summaryFor(node.type(), state.scenario(), state.requirement()),
			artifacts,
			risks
		);

		return executed;
	}

	private WorkflowState finalizeWorkflowState(WorkflowState state) {
		if (state.safeStopReason() != null) {
			return updateWorkflowStatus(state, WorkflowStatus.SAFE_STOPPED, state.safeStopReason());
		}

		boolean allCompleted = state.nodes().stream().allMatch(node -> node.status() == NodeStatus.COMPLETED || node.status() == NodeStatus.ROLLED_BACK);
		if (allCompleted) {
			return appendDecision(updateWorkflowStatus(state, WorkflowStatus.COMPLETED, "Workflow completed successfully"), new DecisionRecord(
				decisionId(),
				"Final Summary Agent",
				state.id(),
				"Final engineering package ready",
				"All mandatory nodes completed",
				"Ready for review",
				"approved",
				"execution/reports/",
				Instant.now(clock)
			));
		}

		boolean awaitingApproval = state.nodes().stream().anyMatch(node -> node.status() == NodeStatus.WAITING_FOR_APPROVAL);
		if (awaitingApproval) {
			return updateWorkflowStatus(state, WorkflowStatus.WAITING_FOR_APPROVAL, "Waiting for approvals");
		}

		boolean blocked = state.nodes().stream().anyMatch(node -> node.status() == NodeStatus.BLOCKED || node.status() == NodeStatus.FAILED);
		if (blocked) {
			return updateWorkflowStatus(state, WorkflowStatus.FAILED, "Workflow contains blocked nodes");
		}

		return state;
	}

	private WorkflowState safeStop(WorkflowState state, String reason) {
		WorkflowState stopped = new WorkflowState(
			state.id(),
			state.projectName(),
			state.requirement(),
			state.scenario(),
			state.constraints(),
			state.acceptanceCriteria(),
			WorkflowStatus.SAFE_STOPPED,
			state.createdAt(),
			Instant.now(clock),
			state.nodes(),
			state.approvals(),
			state.risks(),
			appendDecisionList(state.decisions(), new DecisionRecord(
				decisionId(),
				"Governance Agent",
				state.id(),
				reason,
				"Safe-stop criteria met",
				"Execution halted",
				"rejected",
				"docs/governance/",
				Instant.now(clock)
			)),
			appendTransitionList(state.transitions(), new StateTransition(state.id(), state.status().name(), WorkflowStatus.SAFE_STOPPED.name(), reason, Instant.now(clock))),
			state.history(),
			state.metrics(),
			state.artifactIndex(),
			reason
		);
		return stopped;
	}

	private WorkflowState withSnapshot(WorkflowState state) {
		List<WorkflowSnapshot> history = appendSnapshot(state.history(), new WorkflowSnapshot(
			Instant.now(clock),
			state.status(),
			copyNodes(state.nodes()),
			List.copyOf(state.approvals()),
			List.copyOf(state.risks()),
			List.copyOf(state.decisions()),
			state.metrics()
		));
		return new WorkflowState(
			state.id(),
			state.projectName(),
			state.requirement(),
			state.scenario(),
			state.constraints(),
			state.acceptanceCriteria(),
			state.status(),
			state.createdAt(),
			state.updatedAt(),
			state.nodes(),
			state.approvals(),
			state.risks(),
			state.decisions(),
			state.transitions(),
			history,
			state.metrics(),
			state.artifactIndex(),
			state.safeStopReason()
		);
	}

	private WorkflowState updateWorkflowStatus(WorkflowState state, WorkflowStatus status, String reason) {
		Instant now = Instant.now(clock);
		return new WorkflowState(
			state.id(),
			state.projectName(),
			state.requirement(),
			state.scenario(),
			state.constraints(),
			state.acceptanceCriteria(),
			status,
			state.createdAt(),
			now,
			state.nodes(),
			state.approvals(),
			state.risks(),
			state.decisions(),
			appendTransitionList(state.transitions(), new StateTransition(state.id(), state.status().name(), status.name(), reason, now)),
			state.history(),
			state.metrics(),
			state.artifactIndex(),
			state.safeStopReason()
		);
	}

	private WorkflowState replaceNode(WorkflowState state, WorkflowNode replacement, String reason) {
		List<WorkflowNode> nodes = state.nodes().stream()
			.map(node -> node.id().equals(replacement.id()) ? replacement : node)
			.toList();
		List<String> artifactIndex = new ArrayList<>(state.artifactIndex());
		for (String artifact : replacement.artifacts()) {
			if (!artifactIndex.contains(artifact)) {
				artifactIndex.add(artifact);
			}
		}
		return new WorkflowState(
			state.id(),
			state.projectName(),
			state.requirement(),
			state.scenario(),
			state.constraints(),
			state.acceptanceCriteria(),
			state.status(),
			state.createdAt(),
			Instant.now(clock),
			nodes,
			state.approvals(),
			state.risks(),
			state.decisions(),
			appendTransitionList(state.transitions(), new StateTransition(replacement.id(), null, replacement.status().name(), reason, Instant.now(clock))),
			state.history(),
			state.metrics(),
			artifactIndex,
			state.safeStopReason()
		);
	}

	private WorkflowState appendDecision(WorkflowState state, DecisionRecord decision) {
		return new WorkflowState(
			state.id(),
			state.projectName(),
			state.requirement(),
			state.scenario(),
			state.constraints(),
			state.acceptanceCriteria(),
			state.status(),
			state.createdAt(),
			Instant.now(clock),
			state.nodes(),
			state.approvals(),
			state.risks(),
			appendDecisionList(state.decisions(), decision),
			state.transitions(),
			state.history(),
			state.metrics(),
			state.artifactIndex(),
			state.safeStopReason()
		);
	}

	private WorkflowState persistWithMetrics(WorkflowState state) {
		WorkflowState withMetrics = new WorkflowState(
			state.id(),
			state.projectName(),
			state.requirement(),
			state.scenario(),
			state.constraints(),
			state.acceptanceCriteria(),
			state.status(),
			state.createdAt(),
			Instant.now(clock),
			state.nodes(),
			state.approvals(),
			state.risks(),
			state.decisions(),
			state.transitions(),
			state.history(),
			calculateMetrics(state),
			state.artifactIndex(),
			state.safeStopReason()
		);

		try {
			objectMapper.writerWithDefaultPrettyPrinter().writeValue(workflowPath(state.id()).toFile(), withMetrics);
		} catch (IOException exception) {
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to persist workflow state", exception);
		}
		return withMetrics;
	}

	private void loadWorkflows() {
		try {
			if (!Files.exists(workflowStorageDir)) {
				return;
			}
			Files.list(workflowStorageDir)
				.filter(path -> path.getFileName().toString().endsWith(".json"))
				.forEach(path -> {
					try {
						WorkflowState state = objectMapper.readValue(path.toFile(), WorkflowState.class);
						workflows.put(state.id(), state);
					} catch (IOException exception) {
						throw new IllegalStateException("Unable to load workflow state from " + path, exception);
					}
				});
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to enumerate workflow states", exception);
		}
	}

	private WorkflowState requireWorkflow(String workflowId) {
		WorkflowState state = workflows.get(workflowId);
		if (state == null) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow not found");
		}
		return state;
	}

	private ApprovalGate requireApprovalGate(WorkflowState state, String nodeId) {
		return state.approvals().stream()
			.filter(gate -> gate.nodeId().equals(nodeId))
			.findFirst()
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Approval gate not found for node " + nodeId));
	}

	private boolean dependenciesSatisfied(WorkflowNode node, List<WorkflowNode> nodes) {
		Map<String, NodeStatus> statuses = nodes.stream().collect(Collectors.toMap(WorkflowNode::id, WorkflowNode::status));
		return node.dependsOn().stream().allMatch(dependencyId -> statuses.getOrDefault(dependencyId, NodeStatus.BLOCKED) == NodeStatus.COMPLETED);
	}

	private WorkflowNode updateNodeStatus(WorkflowNode node, NodeStatus status, String summary) {
		return new WorkflowNode(
			node.id(),
			node.name(),
			node.type(),
			node.dependsOn(),
			node.approvalRequired(),
			status,
			node.retryCount(),
			node.maxRetries(),
			node.assignedAgent(),
			summary,
			node.artifacts(),
			node.risks()
		);
	}

	private WorkflowNode incrementRetry(WorkflowNode node, String reason) {
		return new WorkflowNode(
			node.id(),
			node.name(),
			node.type(),
			node.dependsOn(),
			node.approvalRequired(),
			NodeStatus.READY,
			node.retryCount() + 1,
			node.maxRetries(),
			node.assignedAgent(),
			reason,
			node.artifacts(),
			node.risks()
		);
	}

	private WorkflowNode withFallback(WorkflowNode node) {
		return new WorkflowNode(
			node.id(),
			node.name(),
			node.type(),
			node.dependsOn(),
			node.approvalRequired(),
			NodeStatus.COMPLETED,
			node.retryCount(),
			node.maxRetries(),
			node.assignedAgent(),
			"Fallback completed with reduced scope after retry exhaustion",
			appendArtifact(node.artifacts(), "execution/recovery/" + node.id() + "-fallback.md"),
			appendArtifact(node.risks(), "reduced-scope-delivery")
		);
	}

	private List<WorkflowNode> buildNodes(ScenarioType scenario) {
		List<WorkflowNode> nodes = new ArrayList<>();
		int maxRetries = properties.getMaxRetries();
		nodes.add(node("requirements", "Requirement Understanding", NodeType.REQUIREMENT, List.of(), false, maxRetries, "Requirement Analysis Agent"));
		nodes.add(node("ambiguity", "Ambiguity Resolution", NodeType.AMBIGUITY, List.of("requirements"), false, maxRetries, "Ambiguity Resolution Agent"));
		nodes.add(node("decomposition", "Task Decomposition", NodeType.DECOMPOSITION, List.of("requirements"), false, maxRetries, "Task Decomposition Agent"));
		nodes.add(node("architecture", "Architecture Design", NodeType.ARCHITECTURE, List.of("requirements", "decomposition"), true, maxRetries, "Architecture Agent"));
		nodes.add(node("api-schema", "API and Schema Design", NodeType.API_SCHEMA, List.of("architecture"), true, maxRetries, "API & Schema Agent"));
		if (scenario == ScenarioType.GREENFIELD) {
			nodes.add(node("greenfield", "Greenfield Development", NodeType.GREENFIELD, List.of("api-schema"), false, maxRetries, "Greenfield Development Agent"));
		}
		if (scenario == ScenarioType.BROWNFIELD) {
			nodes.add(node("brownfield", "Brownfield Analysis", NodeType.BROWNFIELD, List.of("api-schema"), true, maxRetries, "Brownfield Analysis Agent"));
		}
		nodes.add(node("implementation", "Implementation Execution", NodeType.IMPLEMENTATION, implementationDependencies(scenario), false, maxRetries, "Implementation Agent"));
		nodes.add(node("testing", "Testing Execution", NodeType.TESTING, List.of("implementation"), false, maxRetries, "Testing Agent"));
		nodes.add(node("documentation", "Documentation", NodeType.DOCUMENTATION, List.of("implementation"), false, maxRetries, "Documentation Agent"));
		nodes.add(node("security", "Security Validation", NodeType.SECURITY, List.of("testing"), true, maxRetries, "Security Agent"));
		nodes.add(node("performance", "Performance Review", NodeType.PERFORMANCE, List.of("testing"), false, maxRetries, "Performance Agent"));
		nodes.add(node("reliability", "Reliability Review", NodeType.RELIABILITY, List.of("testing"), false, maxRetries, "Reliability Agent"));
		nodes.add(node("release", "Release Readiness", NodeType.RELEASE, List.of("documentation", "security", "performance", "reliability"), true, maxRetries, "Release Readiness Agent"));
		nodes.add(node("governance", "Governance Review", NodeType.GOVERNANCE, List.of("release"), true, maxRetries, "Governance Agent"));
		nodes.add(node("audit", "Audit Review", NodeType.AUDIT, List.of("governance"), false, maxRetries, "Audit Agent"));
		nodes.add(node("final-summary", "Final Summary", NodeType.FINAL_SUMMARY, List.of("audit"), false, maxRetries, "Final Summary Agent"));
		return nodes;
	}

	private List<String> implementationDependencies(ScenarioType scenario) {
		if (scenario == ScenarioType.GREENFIELD) {
			return List.of("greenfield");
		}
		if (scenario == ScenarioType.BROWNFIELD) {
			return List.of("brownfield");
		}
		return List.of("api-schema", "ambiguity");
	}

	private WorkflowNode node(String id, String name, NodeType type, List<String> dependsOn, boolean approvalRequired, int maxRetries, String assignedAgent) {
		return new WorkflowNode(id, name, type, dependsOn, approvalRequired, NodeStatus.PENDING, 0, maxRetries, assignedAgent, "Not started", List.of(), List.of());
	}

	private List<ApprovalGate> buildApprovalGates(List<WorkflowNode> nodes) {
		return nodes.stream()
			.filter(WorkflowNode::approvalRequired)
			.map(node -> new ApprovalGate("approval-" + node.id(), node.id(), node.name() + " approval", false, false, null, null, null))
			.toList();
	}

	private List<RiskRecord> seedRisks(ScenarioType scenario) {
		List<RiskRecord> risks = new ArrayList<>();
		risks.add(new RiskRecord("risk-alias-collision", "technical", "medium", "medium", "Short-code collision can break routing", "Use uniqueness checks and retries", "Fallback to regenerated code"));
		risks.add(new RiskRecord("risk-approval-latency", "operational", "medium", "high", "Approval gates can delay workflow completion", "Track approval wait time and notify owner", "Escalate to human approver"));
		if (scenario == ScenarioType.BROWNFIELD) {
			risks.add(new RiskRecord("risk-regression", "technical", "high", "medium", "Existing behavior can regress during change", "Require impact analysis and regression plan", "Rollback to last stable snapshot"));
		}
		if (scenario == ScenarioType.AMBIGUOUS) {
			risks.add(new RiskRecord("risk-ambiguity", "business", "high", "high", "Requirement ambiguity can produce stale outputs", "Force ambiguity resolution before implementation", "Replan impacted nodes when assumptions change"));
		}
		return risks;
	}

	private DecisionRecord initialDecision(CreateWorkflowRequest request) {
		return new DecisionRecord(
			decisionId(),
			"Requirement Analysis Agent",
			request.requirement(),
			"Requirement normalized into explicit workflow state",
			"Created baseline workflow with bounded autonomy",
			"Accepted",
			"approved",
			"docs/requirements/",
			Instant.now(clock)
		);
	}

	private ObservabilityMetrics calculateMetrics(WorkflowState state) {
		long completedNodes = state.nodes().stream().filter(node -> node.status() == NodeStatus.COMPLETED).count();
		int totalNodes = state.nodes().size();
		int totalRetries = state.nodes().stream().mapToInt(WorkflowNode::retryCount).sum();
		int rollbackCount = (int) state.transitions().stream().filter(transition -> WorkflowStatus.ROLLED_BACK.name().equals(transition.toState())).count();
		int fallbackCount = (int) state.decisions().stream().filter(decision -> decision.output().contains("fallback") || decision.output().contains("Fallback")).count();
		long workflowDuration = Duration.between(state.createdAt(), Instant.now(clock)).toMillis();
		long averageTaskDuration = completedNodes == 0 ? 0 : workflowDuration / completedNodes;
		long approvalWait = state.approvals().stream().filter(ApprovalGate::approved).map(ApprovalGate::decidedAt).filter(Objects::nonNull).mapToLong(decidedAt -> Math.max(0, Duration.between(state.createdAt(), decidedAt).toMillis())).sum();
		long validationLatency = nodeCompletionIndex(state, "testing") > 0 && nodeCompletionIndex(state, "implementation") > 0 ? averageTaskDuration : 0;
		long mttr = totalRetries == 0 ? 0 : averageTaskDuration;
		double agentSuccessRate = totalNodes == 0 ? 0 : (double) completedNodes / totalNodes;
		double workflowSuccessRate = state.status() == WorkflowStatus.COMPLETED ? 1.0 : 0.0;

		return new ObservabilityMetrics(
			workflowSuccessRate,
			agentSuccessRate,
			totalRetries,
			rollbackCount,
			fallbackCount,
			mttr,
			averageTaskDuration,
			workflowDuration,
			approvalWait,
			validationLatency,
			workflowDuration
		);
	}

	private int nodeCompletionIndex(WorkflowState state, String nodeId) {
		for (int index = 0; index < state.nodes().size(); index++) {
			WorkflowNode node = state.nodes().get(index);
			if (node.id().equals(nodeId) && node.status() == NodeStatus.COMPLETED) {
				return index + 1;
			}
		}
		return 0;
	}

	private WorkflowSummary toSummary(WorkflowState state) {
		int completedNodes = (int) state.nodes().stream().filter(node -> node.status() == NodeStatus.COMPLETED).count();
		return new WorkflowSummary(state.id(), state.projectName(), state.scenario(), state.status(), state.updatedAt(), completedNodes, state.nodes().size());
	}

	private Path workflowPath(String workflowId) {
		return workflowStorageDir.resolve(workflowId + ".json");
	}

	private Set<String> impactedNodes(List<WorkflowNode> nodes, List<String> changedNodeIds) {
		Set<String> impacted = new LinkedHashSet<>(changedNodeIds);
		ArrayDeque<String> queue = new ArrayDeque<>(changedNodeIds);
		while (!queue.isEmpty()) {
			String current = queue.removeFirst();
			for (WorkflowNode node : nodes) {
				if (node.dependsOn().contains(current) && impacted.add(node.id())) {
					queue.addLast(node.id());
				}
			}
		}
		return impacted;
	}

	private List<String> artifactsFor(String workflowId, NodeType nodeType) {
		return switch (nodeType) {
			case REQUIREMENT -> List.of("docs/requirements/" + workflowId + "-requirements.md");
			case AMBIGUITY -> List.of("docs/requirements/" + workflowId + "-ambiguity.md");
			case DECOMPOSITION -> List.of("execution/workflow/" + workflowId + "-dag.json");
			case ARCHITECTURE -> List.of("docs/architecture/" + workflowId + "-architecture.md");
			case API_SCHEMA -> List.of("openapi/url-shortener.yaml", "schemas/url-record.schema.json");
			case GREENFIELD -> List.of("docs/reviews/" + workflowId + "-greenfield.md");
			case BROWNFIELD -> List.of("docs/reviews/" + workflowId + "-brownfield.md");
			case IMPLEMENTATION -> List.of("backend/src/main/java/");
			case TESTING -> List.of("tests/integration/" + workflowId + "-testing.md");
			case DOCUMENTATION -> List.of("docs/validation/" + workflowId + "-documentation.md");
			case SECURITY -> List.of("docs/governance/" + workflowId + "-security.md");
			case PERFORMANCE -> List.of("docs/observability/" + workflowId + "-performance.md");
			case RELIABILITY -> List.of("execution/recovery/" + workflowId + "-reliability.md");
			case RELEASE -> List.of("docs/validation/" + workflowId + "-release.md");
			case GOVERNANCE -> List.of("docs/governance/" + workflowId + "-approval-matrix.md");
			case AUDIT -> List.of("docs/traceability/" + workflowId + "-audit.md");
			case FINAL_SUMMARY -> List.of("execution/reports/" + workflowId + "-final-summary.md");
		};
	}

	private String summaryFor(NodeType nodeType, ScenarioType scenario, String requirement) {
		String requirementSnippet = requirement.length() > 120 ? requirement.substring(0, 120) + "..." : requirement;
		return switch (nodeType) {
			case REQUIREMENT -> "Requirement normalized into a reviewable engineering problem for scenario " + scenario;
			case AMBIGUITY -> "Assumptions and clarifications captured for requirement: " + requirementSnippet;
			case DECOMPOSITION -> "Workflow decomposed into explicit dependency-aware execution nodes";
			case ARCHITECTURE -> "Architecture boundaries, data flow, and governance checkpoints defined";
			case API_SCHEMA -> "API contract and schema artifacts prepared for implementation";
			case GREENFIELD -> "Greenfield development plan prepared for new service delivery";
			case BROWNFIELD -> "Brownfield impact, rollback, and regression plan prepared";
			case IMPLEMENTATION -> "Implementation execution prepared with repository-first outputs";
			case TESTING -> "Validation strategy includes unit, integration, and workflow checks";
			case DOCUMENTATION -> "Setup, architecture, and runbook artifacts synchronized";
			case SECURITY -> "Security review executed against policy guardrails and release readiness";
			case PERFORMANCE -> "Performance validation plan completed with observability hooks";
			case RELIABILITY -> "Reliability strategy covers retry, fallback, rollback, and recovery";
			case RELEASE -> "Production readiness status consolidated across validation inputs";
			case GOVERNANCE -> "Approval matrix and controlled autonomy posture recorded";
			case AUDIT -> "Execution lineage, transitions, and artifact traceability recorded";
			case FINAL_SUMMARY -> "Final engineering package assembled for human review";
		};
	}

	private ObservabilityMetrics emptyMetrics() {
		return new ObservabilityMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
	}

	private List<String> safeList(List<String> values) {
		return values == null ? List.of() : values.stream().filter(Objects::nonNull).map(String::trim).filter(value -> !value.isBlank()).toList();
	}

	private String normalizeText(String value) {
		return value == null ? null : value.trim();
	}

	private String decisionId() {
		return "decision-" + UUID.randomUUID();
	}

	private List<WorkflowNode> copyNodes(List<WorkflowNode> nodes) {
		return nodes.stream()
			.map(node -> new WorkflowNode(node.id(), node.name(), node.type(), List.copyOf(node.dependsOn()), node.approvalRequired(), node.status(), node.retryCount(), node.maxRetries(), node.assignedAgent(), node.summary(), List.copyOf(node.artifacts()), List.copyOf(node.risks())))
			.toList();
	}

	private List<DecisionRecord> appendDecisionList(List<DecisionRecord> existing, DecisionRecord decision) {
		List<DecisionRecord> decisions = new ArrayList<>(existing);
		decisions.add(decision);
		return decisions;
	}

	private List<StateTransition> appendTransitionList(List<StateTransition> existing, StateTransition transition) {
		List<StateTransition> transitions = new ArrayList<>(existing);
		transitions.add(transition);
		return transitions;
	}

	private List<WorkflowSnapshot> appendSnapshot(List<WorkflowSnapshot> existing, WorkflowSnapshot snapshot) {
		List<WorkflowSnapshot> snapshots = new ArrayList<>(existing);
		snapshots.add(snapshot);
		return snapshots;
	}

	private List<String> appendArtifact(List<String> existing, String artifact) {
		List<String> artifacts = new ArrayList<>(existing);
		artifacts.add(artifact);
		return artifacts;
	}

	private static final class TransientWorkflowException extends RuntimeException {

		private TransientWorkflowException(String message) {
			super(message);
		}
	}

	private static final class UnsafeWorkflowException extends RuntimeException {

		private UnsafeWorkflowException(String message) {
			super(message);
		}
	}
}