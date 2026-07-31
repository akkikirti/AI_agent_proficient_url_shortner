package com.akki.agentic.urlshortener.orchestration;

import com.akki.agentic.urlshortener.orchestration.WorkflowModels.ApprovalRequest;
import com.akki.agentic.urlshortener.orchestration.WorkflowModels.CreateWorkflowRequest;
import com.akki.agentic.urlshortener.orchestration.WorkflowModels.ExecuteWorkflowRequest;
import com.akki.agentic.urlshortener.orchestration.WorkflowModels.ReplanWorkflowRequest;
import com.akki.agentic.urlshortener.orchestration.WorkflowModels.RollbackWorkflowRequest;
import com.akki.agentic.urlshortener.orchestration.WorkflowModels.WorkflowState;
import com.akki.agentic.urlshortener.orchestration.WorkflowModels.WorkflowSummary;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orchestrator/workflows")
public class WorkflowController {

	private final WorkflowService workflowService;

	public WorkflowController(WorkflowService workflowService) {
		this.workflowService = workflowService;
	}

	@GetMapping
	public List<WorkflowSummary> listWorkflows() {
		return workflowService.listWorkflows();
	}

	@PostMapping
	public ResponseEntity<WorkflowState> createWorkflow(@Valid @RequestBody CreateWorkflowRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(workflowService.createWorkflow(request));
	}

	@GetMapping("/{workflowId}")
	public WorkflowState getWorkflow(@PathVariable String workflowId) {
		return workflowService.getWorkflow(workflowId);
	}

	@PostMapping("/{workflowId}/execute")
	public WorkflowState executeWorkflow(@PathVariable String workflowId, @RequestBody(required = false) ExecuteWorkflowRequest request) {
		return workflowService.executeWorkflow(workflowId, request == null ? new ExecuteWorkflowRequest(List.of()) : request);
	}

	@PostMapping("/{workflowId}/approvals/{gateId}")
	public WorkflowState approve(@PathVariable String workflowId, @PathVariable String gateId, @Valid @RequestBody ApprovalRequest request) {
		return workflowService.approve(workflowId, gateId, request);
	}

	@PostMapping("/{workflowId}/replan")
	public WorkflowState replan(@PathVariable String workflowId, @RequestBody(required = false) ReplanWorkflowRequest request) {
		ReplanWorkflowRequest safeRequest = request == null ? new ReplanWorkflowRequest(List.of(), "Manual replan requested", null) : request;
		return workflowService.replan(workflowId, safeRequest);
	}

	@PostMapping("/{workflowId}/rollback")
	public WorkflowState rollback(@PathVariable String workflowId, @RequestBody(required = false) RollbackWorkflowRequest request) {
		RollbackWorkflowRequest safeRequest = request == null ? new RollbackWorkflowRequest("Manual rollback requested") : request;
		return workflowService.rollback(workflowId, safeRequest);
	}
}