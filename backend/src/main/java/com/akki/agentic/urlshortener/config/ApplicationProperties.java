package com.akki.agentic.urlshortener.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "app")
public class ApplicationProperties {

	@NotBlank
	private String baseUrl;

	@NotBlank
	private String urlStoragePath;

	@NotBlank
	private String workflowStorageDir;

	@NotBlank
	private String orchestratorAdminToken;

	private List<String> unsafeRequirementDirectives = List.of(
		"disable auth",
		"bypass security",
		"drop production",
		"store passwords",
		"unsafe output"
	);

	@Min(1)
	private int maxRetries = 3;

	public String getBaseUrl() {
		return baseUrl;
	}

	public void setBaseUrl(String baseUrl) {
		this.baseUrl = baseUrl;
	}

	public String getUrlStoragePath() {
		return urlStoragePath;
	}

	public void setUrlStoragePath(String urlStoragePath) {
		this.urlStoragePath = urlStoragePath;
	}

	public String getWorkflowStorageDir() {
		return workflowStorageDir;
	}

	public void setWorkflowStorageDir(String workflowStorageDir) {
		this.workflowStorageDir = workflowStorageDir;
	}

	public String getOrchestratorAdminToken() {
		return orchestratorAdminToken;
	}

	public void setOrchestratorAdminToken(String orchestratorAdminToken) {
		this.orchestratorAdminToken = orchestratorAdminToken;
	}

	public List<String> getUnsafeRequirementDirectives() {
		return unsafeRequirementDirectives;
	}

	public void setUnsafeRequirementDirectives(List<String> unsafeRequirementDirectives) {
		this.unsafeRequirementDirectives = unsafeRequirementDirectives;
	}

	public int getMaxRetries() {
		return maxRetries;
	}

	public void setMaxRetries(int maxRetries) {
		this.maxRetries = maxRetries;
	}
}