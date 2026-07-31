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
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AgenticUrlShortenerApplicationTests {

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
	void executesWorkflowWithApprovalsRetriesReplanAndRollback() {
		CreateWorkflowRequest createRequest = new CreateWorkflowRequest(
			"Agentic-url-shortner",
			"Build a governed URL shortener with analytics, approval gates, and dynamic replanning.",
			ScenarioType.BROWNFIELD,
			List.of("Java 17", "Spring Boot", "Angular"),
			List.of("Reviewable output", "Controlled autonomy")
		);

		ResponseEntity<WorkflowState> createResponse = restTemplate.postForEntity("/api/orchestrator/workflows", createRequest, WorkflowState.class);
		Assertions.assertEquals(HttpStatus.CREATED, createResponse.getStatusCode());
		WorkflowState workflow = createResponse.getBody();
		Assertions.assertNotNull(workflow);

		workflow = restTemplate.postForEntity(
			"/api/orchestrator/workflows/" + workflow.id() + "/execute",
			new ExecuteWorkflowRequest(List.of("testing")),
			WorkflowState.class
		).getBody();

		Assertions.assertNotNull(workflow);
		Assertions.assertEquals(WorkflowStatus.WAITING_FOR_APPROVAL, workflow.status());

		for (var approval : workflow.approvals()) {
			workflow = restTemplate.postForEntity(
				"/api/orchestrator/workflows/" + workflow.id() + "/approvals/" + approval.id(),
				new ApprovalRequest("qa.lead", true, "Approved for automated execution"),
				WorkflowState.class
			).getBody();
			Assertions.assertNotNull(workflow);
		}

		workflow = restTemplate.postForEntity(
			"/api/orchestrator/workflows/" + workflow.id() + "/execute",
			new ExecuteWorkflowRequest(List.of("testing")),
			WorkflowState.class
		).getBody();

		Assertions.assertNotNull(workflow);
		Assertions.assertEquals(WorkflowStatus.COMPLETED, workflow.status());
		Assertions.assertTrue(workflow.nodes().stream().anyMatch(node -> node.id().equals("testing") && node.retryCount() == 1));

		ResponseEntity<WorkflowState> replannedResponse = restTemplate.postForEntity(
			"/api/orchestrator/workflows/" + workflow.id() + "/replan",
			new ReplanWorkflowRequest(List.of("architecture"), "Architecture changed after review", "Updated requirement after architecture review"),
			WorkflowState.class
		);

		Assertions.assertEquals(HttpStatus.OK, replannedResponse.getStatusCode());
		workflow = replannedResponse.getBody();
		Assertions.assertNotNull(workflow);
		Assertions.assertEquals(WorkflowStatus.DRAFT, workflow.status());
		Assertions.assertTrue(workflow.nodes().stream().anyMatch(node -> node.id().equals("implementation") && node.status().name().equals("PENDING")));

		ResponseEntity<WorkflowState> rollbackResponse = restTemplate.postForEntity(
			"/api/orchestrator/workflows/" + workflow.id() + "/rollback",
			new RollbackWorkflowRequest("Return to last stable snapshot"),
			WorkflowState.class
		);

		Assertions.assertEquals(HttpStatus.OK, rollbackResponse.getStatusCode());
		Assertions.assertNotNull(rollbackResponse.getBody());
		Assertions.assertEquals(WorkflowStatus.ROLLED_BACK, rollbackResponse.getBody().status());
	}

	private static Path createRuntimeRoot() {
		try {
			return Files.createTempDirectory("agentic-url-shortener-runtime");
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to create test runtime directory", exception);
		}
	}
}
