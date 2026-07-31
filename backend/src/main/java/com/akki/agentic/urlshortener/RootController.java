package com.akki.agentic.urlshortener;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RootController {

	@GetMapping("/")
	public Map<String, Object> index() {
		return Map.of(
			"service", "agentic-url-shortener",
			"status", "running",
			"frontend", "http://localhost:4200",
			"health", "/actuator/health",
			"urlApi", "/api/urls",
			"workflowApi", "/api/orchestrator/workflows"
		);
	}
}