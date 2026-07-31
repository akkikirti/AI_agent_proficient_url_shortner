package com.akki.agentic.urlshortener.url;

import com.akki.agentic.urlshortener.config.ApplicationProperties;
import com.akki.agentic.urlshortener.url.UrlModels.AccessEvent;
import com.akki.agentic.urlshortener.url.UrlModels.CreateShortUrlRequest;
import com.akki.agentic.urlshortener.url.UrlModels.ShortUrl;
import com.akki.agentic.urlshortener.url.UrlModels.ShortUrlResponse;
import com.akki.agentic.urlshortener.url.UrlModels.UrlAnalyticsResponse;
import com.akki.agentic.urlshortener.url.UrlModels.UrlCollectionResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UrlShortenerService {

	private static final Pattern VALID_HTTP_URL = Pattern.compile("^https?://.+", Pattern.CASE_INSENSITIVE);
	private static final String CODE_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
	private static final int GENERATED_CODE_LENGTH = 7;
	private static final int MAX_RECENT_EVENTS = 10;

	private final ApplicationProperties properties;
	private final ObjectMapper objectMapper;
	private final Clock clock;
	private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
	private final Map<String, ShortUrl> urlsByCode = new LinkedHashMap<>();

	private Path storagePath;

	public UrlShortenerService(ApplicationProperties properties, ObjectMapper objectMapper) {
		this(properties, objectMapper, Clock.systemUTC());
	}

	UrlShortenerService(ApplicationProperties properties, ObjectMapper objectMapper, Clock clock) {
		this.properties = properties;
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	@PostConstruct
	void initialize() {
		storagePath = Path.of(properties.getUrlStoragePath());
		createStorageDirectory();
		loadState();
	}

	public ShortUrlResponse createShortUrl(CreateShortUrlRequest request) {
		validateDestinationUrl(request.destinationUrl());
		Instant now = Instant.now(clock);
		String requestedAlias = normalizeAlias(request.alias());

		lock.writeLock().lock();
		try {
			String code = requestedAlias != null ? requestedAlias : generateUniqueCode();
			if (urlsByCode.containsKey(code)) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "Short code already exists");
			}

			ShortUrl shortUrl = new ShortUrl(
				code,
				request.destinationUrl().trim(),
				normalizeTitle(request.title()),
				now,
				request.expiresAt(),
				0,
				null,
				true,
				List.of()
			);

			urlsByCode.put(code, shortUrl);
			persistState();
			return toResponse(shortUrl);
		} finally {
			lock.writeLock().unlock();
		}
	}

	public URI resolveAndTrack(String code, String userAgent, String referer) {
		lock.writeLock().lock();
		try {
			ShortUrl shortUrl = requireAvailable(code);
			AccessEvent accessEvent = new AccessEvent(Instant.now(clock), userAgent, referer);
			ShortUrl updated = shortUrl.recordAccess(accessEvent, MAX_RECENT_EVENTS);
			urlsByCode.put(code, updated);
			persistState();
			return URI.create(updated.destinationUrl());
		} finally {
			lock.writeLock().unlock();
		}
	}

	public UrlAnalyticsResponse getAnalytics(String code) {
		lock.readLock().lock();
		try {
			ShortUrl shortUrl = requireExisting(code);
			return toAnalytics(shortUrl);
		} finally {
			lock.readLock().unlock();
		}
	}

	public UrlCollectionResponse listUrls() {
		lock.readLock().lock();
		try {
			List<ShortUrlResponse> urls = urlsByCode.values().stream()
				.sorted(Comparator.comparing(ShortUrl::createdAt).reversed())
				.map(this::toResponse)
				.toList();
			return new UrlCollectionResponse(urls);
		} finally {
			lock.readLock().unlock();
		}
	}

	public ShortUrlResponse deactivate(String code) {
		lock.writeLock().lock();
		try {
			ShortUrl shortUrl = requireExisting(code);
			ShortUrl updated = shortUrl.deactivate();
			urlsByCode.put(code, updated);
			persistState();
			return toResponse(updated);
		} finally {
			lock.writeLock().unlock();
		}
	}

	private ShortUrl requireAvailable(String code) {
		ShortUrl shortUrl = requireExisting(code);
		Instant now = Instant.now(clock);
		if (!shortUrl.active()) {
			throw new ResponseStatusException(HttpStatus.GONE, "Short URL is inactive");
		}
		if (shortUrl.isExpired(now)) {
			throw new ResponseStatusException(HttpStatus.GONE, "Short URL has expired");
		}
		return shortUrl;
	}

	private ShortUrl requireExisting(String code) {
		ShortUrl shortUrl = urlsByCode.get(normalizeCode(code));
		if (shortUrl == null) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Short URL not found");
		}
		return shortUrl;
	}

	private String generateUniqueCode() {
		for (int attempt = 0; attempt < 20; attempt++) {
			String candidate = randomCode();
			if (!urlsByCode.containsKey(candidate)) {
				return candidate;
			}
		}
		throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Unable to allocate a short code");
	}

	private String randomCode() {
		StringBuilder builder = new StringBuilder(GENERATED_CODE_LENGTH);
		for (int index = 0; index < GENERATED_CODE_LENGTH; index++) {
			int alphabetIndex = (int) Math.floor(Math.random() * CODE_ALPHABET.length());
			builder.append(CODE_ALPHABET.charAt(alphabetIndex));
		}
		return builder.toString();
	}

	private void validateDestinationUrl(String destinationUrl) {
		if (destinationUrl == null || !VALID_HTTP_URL.matcher(destinationUrl.trim()).matches()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "destinationUrl must be an absolute http or https URL");
		}
	}

	private String normalizeAlias(String alias) {
		return Optional.ofNullable(alias)
			.map(String::trim)
			.filter(value -> !value.isEmpty())
			.orElse(null);
	}

	private String normalizeTitle(String title) {
		return Optional.ofNullable(title)
			.map(String::trim)
			.filter(value -> !value.isEmpty())
			.orElse(null);
	}

	private String normalizeCode(String code) {
		return Optional.ofNullable(code)
			.map(String::trim)
			.filter(value -> !value.isEmpty())
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Short code is required"));
	}

	private void createStorageDirectory() {
		try {
			Path parent = Objects.requireNonNullElse(storagePath.getParent(), Path.of("."));
			Files.createDirectories(parent);
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to create storage directory", exception);
		}
	}

	private void loadState() {
		if (!Files.exists(storagePath)) {
			return;
		}

		lock.writeLock().lock();
		try {
			List<ShortUrl> storedUrls = objectMapper.readValue(storagePath.toFile(), new TypeReference<List<ShortUrl>>() {
			});
			urlsByCode.clear();
			for (ShortUrl shortUrl : storedUrls) {
				urlsByCode.put(shortUrl.code(), shortUrl);
			}
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to load URL state", exception);
		} finally {
			lock.writeLock().unlock();
		}
	}

	private void persistState() {
		try {
			objectMapper.writerWithDefaultPrettyPrinter().writeValue(storagePath.toFile(), new ArrayList<>(urlsByCode.values()));
		} catch (IOException exception) {
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to persist URL state", exception);
		}
	}

	private ShortUrlResponse toResponse(ShortUrl shortUrl) {
		return new ShortUrlResponse(
			shortUrl.code(),
			properties.getBaseUrl() + "/r/" + shortUrl.code(),
			shortUrl.destinationUrl(),
			shortUrl.title(),
			shortUrl.createdAt(),
			shortUrl.expiresAt(),
			shortUrl.accessCount(),
			shortUrl.active()
		);
	}

	private UrlAnalyticsResponse toAnalytics(ShortUrl shortUrl) {
		return new UrlAnalyticsResponse(
			shortUrl.code(),
			shortUrl.destinationUrl(),
			shortUrl.title(),
			shortUrl.createdAt(),
			shortUrl.expiresAt(),
			shortUrl.accessCount(),
			shortUrl.lastAccessedAt(),
			shortUrl.active(),
			shortUrl.isExpired(Instant.now(clock)),
			shortUrl.recentAccesses()
		);
	}
}