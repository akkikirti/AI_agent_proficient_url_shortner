package com.akki.agentic.urlshortener.url;

import com.akki.agentic.urlshortener.url.UrlModels.CreateShortUrlRequest;
import com.akki.agentic.urlshortener.url.UrlModels.ShortUrlResponse;
import com.akki.agentic.urlshortener.url.UrlModels.UrlAnalyticsResponse;
import com.akki.agentic.urlshortener.url.UrlModels.UrlCollectionResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping
public class UrlShortenerController {

	private final UrlShortenerService urlShortenerService;

	public UrlShortenerController(UrlShortenerService urlShortenerService) {
		this.urlShortenerService = urlShortenerService;
	}

	@PostMapping("/api/urls")
	public ResponseEntity<ShortUrlResponse> createShortUrl(@Valid @RequestBody CreateShortUrlRequest request) {
		ShortUrlResponse response = urlShortenerService.createShortUrl(request);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	@GetMapping("/api/urls")
	public UrlCollectionResponse listUrls() {
		return urlShortenerService.listUrls();
	}

	@GetMapping("/api/urls/{code}/analytics")
	public UrlAnalyticsResponse getAnalytics(@PathVariable String code) {
		return urlShortenerService.getAnalytics(code);
	}

	@DeleteMapping("/api/urls/{code}")
	public ShortUrlResponse deactivate(@PathVariable String code) {
		return urlShortenerService.deactivate(code);
	}

	@GetMapping("/r/{code}")
	public ResponseEntity<Void> redirect(@PathVariable String code, HttpServletRequest request) {
		URI destination = urlShortenerService.resolveAndTrack(code, request.getHeader("User-Agent"), request.getHeader("Referer"));
		return ResponseEntity.status(HttpStatus.FOUND).location(destination).build();
	}
}