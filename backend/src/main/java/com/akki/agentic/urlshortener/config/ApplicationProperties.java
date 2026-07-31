package com.akki.agentic.urlshortener.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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

	public int getMaxRetries() {
		return maxRetries;
	}

	public void setMaxRetries(int maxRetries) {
		this.maxRetries = maxRetries;
	}
}