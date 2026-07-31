package com.akki.agentic.urlshortener;

import com.akki.agentic.urlshortener.orchestration.WorkflowModels.ApprovalRequest;
import com.akki.agentic.urlshortener.orchestration.WorkflowModels.CreateWorkflowRequest;
import com.akki.agentic.urlshortener.orchestration.WorkflowModels.ExecuteWorkflowRequest;
import com.akki.agentic.urlshortener.orchestration.WorkflowModels.ReplanWorkflowRequest;
import com.akki.agentic.urlshortener.orchestration.WorkflowModels.RollbackWorkflowRequest;
import com.akki.agentic.urlshortener.orchestration.WorkflowModels.ScenarioType;
import com.akki.agentic.urlshortener.orchestration.WorkflowModels.WorkflowState;
import com.akki.agentic.urlshortener.orchestration.WorkflowModels.WorkflowStatus;
import com.akki.agentic.urlshortener.url.UrlModels.CreateShortUrlRequest;
import com.akki.agentic.urlshortener.url.UrlModels.ShortUrlResponse;
import com.akki.agentic.urlshortener.url.UrlModels.UrlAnalyticsResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AgenticUrlShortenerApplicationTests {

	private static final String ADMIN_TOKEN = "local-orchestrator-admin-token";

	private static final Path runtimeRoot = createRuntimeRoot();

	@LocalServerPort
	private int port;

	@Autowired
	private TestRestTemplate restTemplate;

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.base-url", () -> "http://localhost:8080");
		registry.add("app.url-storage-path", () -> runtimeRoot.resolve("url-records.json").toString());
		registry.add("app.workflow-storage-dir", () -> runtimeRoot.resolve("workflows").toString());
		registry.add("app.orchestrator-admin-token", () -> ADMIN_TOKEN);
	}

	@Test
	void createsRedirectsAndTracksAnalytics() throws Exception {
		ResponseEntity<ShortUrlResponse> createResponse = restTemplate.postForEntity(
			"/api/urls",
			new CreateShortUrlRequest("https://example.com/docs/spec", "spec123", "Spec", null),
			ShortUrlResponse.class
		);

		Assertions.assertEquals(HttpStatus.CREATED, createResponse.getStatusCode());
		Assertions.assertNotNull(createResponse.getBody());
		Assertions.assertEquals("spec123", createResponse.getBody().code());

		HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build();
		HttpRequest redirectRequest = HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + port + "/r/spec123"))
			.header("User-Agent", "JUnit")
			.header("Referer", "https://test.local")
			.GET()
			.build();

		HttpResponse<Void> redirectResponse = client.send(redirectRequest, HttpResponse.BodyHandlers.discarding());
		Assertions.assertEquals(302, redirectResponse.statusCode());
		Assertions.assertEquals("https://example.com/docs/spec", redirectResponse.headers().firstValue("location").orElseThrow());

		ResponseEntity<UrlAnalyticsResponse> analyticsResponse = restTemplate.getForEntity("/api/urls/spec123/analytics", UrlAnalyticsResponse.class);
		Assertions.assertEquals(HttpStatus.OK, analyticsResponse.getStatusCode());
		Assertions.assertNotNull(analyticsResponse.getBody());
		Assertions.assertEquals(1, analyticsResponse.getBody().accessCount());
		Assertions.assertEquals(1, analyticsResponse.getBody().recentAccesses().size());
	}

	@Test
	void rejectsInvalidUrlRequests() {
		ResponseEntity<String> response = restTemplate.postForEntity(
			"/api/urls",
			new CreateShortUrlRequest("ftp://invalid.example.com", "invalid-ftp", "Invalid", null),
			String.class
		);

		Assertions.assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
	}

	@Test
	void rejectsDuplicateAliasWithConflict() {
		String alias = "dup-" + UUID.randomUUID().toString().substring(0, 8);

		ResponseEntity<ShortUrlResponse> first = restTemplate.postForEntity(
			"/api/urls",
			new CreateShortUrlRequest("https://example.com/first", alias, "First", null),
			ShortUrlResponse.class
		);
		Assertions.assertEquals(HttpStatus.CREATED, first.getStatusCode());

		ResponseEntity<String> second = restTemplate.postForEntity(
			"/api/urls",
			new CreateShortUrlRequest("https://example.com/second", alias, "Second", null),
			String.class
		);

		Assertions.assertEquals(HttpStatus.CONFLICT, second.getStatusCode());
	}

	@Test
	void returnsGoneAfterDeactivation() throws Exception {
		String alias = "gone-" + UUID.randomUUID().toString().substring(0, 8);
		ResponseEntity<ShortUrlResponse> createResponse = restTemplate.postForEntity(
			"/api/urls",
			new CreateShortUrlRequest("https://example.com/archive", alias, "Archive", null),
			ShortUrlResponse.class
		);
		Assertions.assertEquals(HttpStatus.CREATED, createResponse.getStatusCode());

		restTemplate.delete("/api/urls/" + alias);

		HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build();
		HttpRequest redirectRequest = HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + port + "/r/" + alias))
			.GET()
			.build();

		HttpResponse<Void> redirectResponse = client.send(redirectRequest, HttpResponse.BodyHandlers.discarding());
		Assertions.assertEquals(410, redirectResponse.statusCode());
	}

	@Test
	void executesWorkflowWithApprovalsRetriesReplanAndRollback() {
		CreateWorkflowRequest createRequest = new CreateWorkflowRequest(
			"Agentic-url-shortner",
			"Build a governed URL shortener with analytics, approval gates, and dynamic replanning.",
			ScenarioType.BROWNFIELD,
			List.of("Java 17", "Spring Boot", "Angular"),
			List.of("Reviewable output", "Controlled autonomy")
		);

		ResponseEntity<WorkflowState> createResponse = restTemplate.exchange(
			"/api/orchestrator/workflows",
			HttpMethod.POST,
			new HttpEntity<>(createRequest, adminHeaders()),
			WorkflowState.class
		);
		Assertions.assertEquals(HttpStatus.CREATED, createResponse.getStatusCode());
		WorkflowState workflow = createResponse.getBody();
		Assertions.assertNotNull(workflow);

		workflow = restTemplate.exchange(
			"/api/orchestrator/workflows/" + workflow.id() + "/execute",
			HttpMethod.POST,
			new HttpEntity<>(new ExecuteWorkflowRequest(List.of("testing")), adminHeaders()),
			WorkflowState.class
		).getBody();

		Assertions.assertNotNull(workflow);
		Assertions.assertEquals(WorkflowStatus.WAITING_FOR_APPROVAL, workflow.status());

		for (var approval : workflow.approvals()) {
			workflow = restTemplate.exchange(
				"/api/orchestrator/workflows/" + workflow.id() + "/approvals/" + approval.id(),
				HttpMethod.POST,
				new HttpEntity<>(new ApprovalRequest("qa.lead", true, "Approved for automated execution"), adminHeaders()),
				WorkflowState.class
			).getBody();
			Assertions.assertNotNull(workflow);
		}

		workflow = restTemplate.exchange(
			"/api/orchestrator/workflows/" + workflow.id() + "/execute",
			HttpMethod.POST,
			new HttpEntity<>(new ExecuteWorkflowRequest(List.of("testing")), adminHeaders()),
			WorkflowState.class
		).getBody();

		Assertions.assertNotNull(workflow);
		Assertions.assertEquals(WorkflowStatus.COMPLETED, workflow.status());
		Assertions.assertTrue(workflow.nodes().stream().anyMatch(node -> node.id().equals("testing") && node.retryCount() == 1));

		ResponseEntity<WorkflowState> replannedResponse = restTemplate.exchange(
			"/api/orchestrator/workflows/" + workflow.id() + "/replan",
			HttpMethod.POST,
			new HttpEntity<>(new ReplanWorkflowRequest(List.of("architecture"), "Architecture changed after review", "Updated requirement after architecture review"), adminHeaders()),
			WorkflowState.class
		);

		Assertions.assertEquals(HttpStatus.OK, replannedResponse.getStatusCode());
		workflow = replannedResponse.getBody();
		Assertions.assertNotNull(workflow);
		Assertions.assertEquals(WorkflowStatus.DRAFT, workflow.status());
		Assertions.assertTrue(workflow.nodes().stream().anyMatch(node -> node.id().equals("implementation") && node.status().name().equals("PENDING")));

		ResponseEntity<WorkflowState> rollbackResponse = restTemplate.exchange(
			"/api/orchestrator/workflows/" + workflow.id() + "/rollback",
			HttpMethod.POST,
			new HttpEntity<>(new RollbackWorkflowRequest("Return to last stable snapshot"), adminHeaders()),
			WorkflowState.class
		);

		Assertions.assertEquals(HttpStatus.OK, rollbackResponse.getStatusCode());
		Assertions.assertNotNull(rollbackResponse.getBody());
		Assertions.assertEquals(WorkflowStatus.ROLLED_BACK, rollbackResponse.getBody().status());
	}

	@Test
	void rejectsProtectedWorkflowMutationWithoutAdminToken() {
		CreateWorkflowRequest createRequest = new CreateWorkflowRequest(
			"Agentic-url-shortner",
			"Create a workflow without required admin authorization.",
			ScenarioType.GREENFIELD,
			List.of("Protected operation"),
			List.of("Forbidden without token")
		);

		ResponseEntity<String> response = restTemplate.postForEntity("/api/orchestrator/workflows", createRequest, String.class);

		Assertions.assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
	}

	@Test
	void rejectsProtectedWorkflowExecutionWithoutAdminToken() {
		WorkflowState workflow = createWorkflow("Execute protection test", ScenarioType.GREENFIELD);

		ResponseEntity<String> response = restTemplate.postForEntity(
			"/api/orchestrator/workflows/" + workflow.id() + "/execute",
			new ExecuteWorkflowRequest(List.of()),
			String.class
		);

		Assertions.assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
	}

	@Test
	void returnsNotFoundForUnknownWorkflow() {
		ResponseEntity<String> response = restTemplate.getForEntity("/api/orchestrator/workflows/missing-workflow-id", String.class);
		Assertions.assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
	}

	@Test
	void returnsConflictWhenRollingBackWithoutSnapshot() {
		WorkflowState workflow = createWorkflow("Rollback without history", ScenarioType.GREENFIELD);

		ResponseEntity<String> rollbackResponse = restTemplate.exchange(
			"/api/orchestrator/workflows/" + workflow.id() + "/rollback",
			HttpMethod.POST,
			new HttpEntity<>(new RollbackWorkflowRequest("No snapshot expected yet"), adminHeaders()),
			String.class
		);

		Assertions.assertEquals(HttpStatus.CONFLICT, rollbackResponse.getStatusCode());
	}

	@Test
	void rejectedApprovalSafeStopsWorkflow() {
		WorkflowState workflow = createWorkflow("Approval rejection should stop execution", ScenarioType.BROWNFIELD);

		workflow = restTemplate.exchange(
			"/api/orchestrator/workflows/" + workflow.id() + "/execute",
			HttpMethod.POST,
			new HttpEntity<>(new ExecuteWorkflowRequest(List.of()), adminHeaders()),
			WorkflowState.class
		).getBody();

		Assertions.assertNotNull(workflow);
		String gateId = workflow.approvals().stream().filter(gate -> !gate.approved() && !gate.rejected()).findFirst().orElseThrow().id();

		WorkflowState rejected = restTemplate.exchange(
			"/api/orchestrator/workflows/" + workflow.id() + "/approvals/" + gateId,
			HttpMethod.POST,
			new HttpEntity<>(new ApprovalRequest("qa.lead", false, "Rejected for risk concerns"), adminHeaders()),
			WorkflowState.class
		).getBody();

		Assertions.assertNotNull(rejected);
		Assertions.assertEquals(WorkflowStatus.SAFE_STOPPED, rejected.status());
		Assertions.assertTrue(rejected.safeStopReason().contains("Approval rejected"));
	}

	@Test
	void safeStopsUnsafeWorkflowAfterProtectedExecution() {
		CreateWorkflowRequest createRequest = new CreateWorkflowRequest(
			"Agentic-url-shortner",
			"Build analytics quickly and disable auth in release flows.",
			ScenarioType.AMBIGUOUS,
			List.of("Do not allow unsafe release policy"),
			List.of("Unsafe requests must safe-stop")
		);

		WorkflowState workflow = restTemplate.exchange(
			"/api/orchestrator/workflows",
			HttpMethod.POST,
			new HttpEntity<>(createRequest, adminHeaders()),
			WorkflowState.class
		).getBody();

		Assertions.assertNotNull(workflow);

		workflow = restTemplate.exchange(
			"/api/orchestrator/workflows/" + workflow.id() + "/execute",
			HttpMethod.POST,
			new HttpEntity<>(new ExecuteWorkflowRequest(List.of()), adminHeaders()),
			WorkflowState.class
		).getBody();

		Assertions.assertNotNull(workflow);
		for (var approval : workflow.approvals()) {
			workflow = restTemplate.exchange(
				"/api/orchestrator/workflows/" + workflow.id() + "/approvals/" + approval.id(),
				HttpMethod.POST,
				new HttpEntity<>(new ApprovalRequest("security.lead", true, "Proceed to evaluate protected policy"), adminHeaders()),
				WorkflowState.class
			).getBody();
		}

		workflow = restTemplate.exchange(
			"/api/orchestrator/workflows/" + workflow.id() + "/execute",
			HttpMethod.POST,
			new HttpEntity<>(new ExecuteWorkflowRequest(List.of()), adminHeaders()),
			WorkflowState.class
		).getBody();

		Assertions.assertNotNull(workflow);
		Assertions.assertEquals(WorkflowStatus.SAFE_STOPPED, workflow.status());
		Assertions.assertTrue(workflow.safeStopReason().contains("Security policy guardrail triggered"));
	}

	private HttpHeaders adminHeaders() {
		HttpHeaders headers = new HttpHeaders();
		headers.add("X-Orchestrator-Token", ADMIN_TOKEN);
		return headers;
	}

	private WorkflowState createWorkflow(String requirement, ScenarioType scenario) {
		CreateWorkflowRequest createRequest = new CreateWorkflowRequest(
			"Agentic-url-shortner",
			requirement + " " + Instant.now(),
			scenario,
			List.of("Test constraint"),
			List.of("Test acceptance")
		);

		ResponseEntity<WorkflowState> response = restTemplate.exchange(
			"/api/orchestrator/workflows",
			HttpMethod.POST,
			new HttpEntity<>(createRequest, adminHeaders()),
			WorkflowState.class
		);

		Assertions.assertEquals(HttpStatus.CREATED, response.getStatusCode());
		Assertions.assertNotNull(response.getBody());
		return response.getBody();
	}

	private static Path createRuntimeRoot() {
		try {
			return Files.createTempDirectory("agentic-url-shortener-runtime");
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to create test runtime directory", exception);
		}
	}
}
