package com.akki.agentic.urlshortener.orchestration;

import com.akki.agentic.urlshortener.config.ApplicationProperties;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class RequirementSafetyPolicy {

	private final ApplicationProperties properties;

	public RequirementSafetyPolicy(ApplicationProperties properties) {
		this.properties = properties;
	}

	public boolean containsUnsafeDirective(String requirement) {
		if (requirement == null || requirement.isBlank()) {
			return false;
		}
		String normalizedRequirement = requirement.toLowerCase(Locale.ROOT);
		List<String> directives = properties.getUnsafeRequirementDirectives();
		if (directives == null || directives.isEmpty()) {
			return false;
		}
		return directives.stream()
			.filter(Objects::nonNull)
			.map(value -> value.trim().toLowerCase(Locale.ROOT))
			.filter(value -> !value.isBlank())
			.anyMatch(normalizedRequirement::contains);
	}
}
