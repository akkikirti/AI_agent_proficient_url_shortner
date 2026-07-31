package com.akki.agentic.urlshortener.orchestration;

import com.akki.agentic.urlshortener.config.ApplicationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class OrchestrationSecurity {

	private static final String HEADER_NAME = "X-Orchestrator-Token";

	private final ApplicationProperties properties;

	public OrchestrationSecurity(ApplicationProperties properties) {
		this.properties = properties;
	}

	public void requireAdminToken(String providedToken) {
		if (providedToken == null || !providedToken.equals(properties.getOrchestratorAdminToken())) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Valid orchestrator admin token is required for this operation");
		}
	}

	public String headerName() {
		return HEADER_NAME;
	}
}