package com.akki.agentic.urlshortener.url;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.List;

public final class UrlModels {

	private UrlModels() {
	}

	public record CreateShortUrlRequest(
		@NotBlank(message = "destinationUrl is required") String destinationUrl,
		@Pattern(regexp = "^[a-zA-Z0-9_-]{0,32}$", message = "alias may contain only letters, numbers, dashes, and underscores") String alias,
		String title,
		Instant expiresAt
	) {
	}

	public record ShortUrl(
		String code,
		String destinationUrl,
		String title,
		Instant createdAt,
		Instant expiresAt,
		long accessCount,
		Instant lastAccessedAt,
		boolean active,
		List<AccessEvent> recentAccesses
	) {

		public boolean isExpired(Instant now) {
			return expiresAt != null && now.isAfter(expiresAt);
		}

		public ShortUrl recordAccess(AccessEvent accessEvent, int retainedEvents) {
			List<AccessEvent> nextEvents = recentAccesses == null
				? List.of(accessEvent)
				: java.util.stream.Stream.concat(java.util.stream.Stream.of(accessEvent), recentAccesses.stream())
					.limit(retainedEvents)
					.toList();

			return new ShortUrl(
				code,
				destinationUrl,
				title,
				createdAt,
				expiresAt,
				accessCount + 1,
				accessEvent.accessedAt(),
				active,
				nextEvents
			);
		}

		public ShortUrl deactivate() {
			return new ShortUrl(code, destinationUrl, title, createdAt, expiresAt, accessCount, lastAccessedAt, false, recentAccesses);
		}
	}

	public record AccessEvent(
		Instant accessedAt,
		String userAgent,
		String referer
	) {
	}

	public record ShortUrlResponse(
		String code,
		String shortUrl,
		String destinationUrl,
		String title,
		Instant createdAt,
		Instant expiresAt,
		long accessCount,
		boolean active
	) {
	}

	public record UrlAnalyticsResponse(
		String code,
		String destinationUrl,
		String title,
		Instant createdAt,
		Instant expiresAt,
		long accessCount,
		Instant lastAccessedAt,
		boolean active,
		boolean expired,
		List<AccessEvent> recentAccesses
	) {
	}
	
	public record UrlCollectionResponse(List<ShortUrlResponse> urls) {
	}
}