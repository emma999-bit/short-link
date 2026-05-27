package com.shortlink.controller;

import com.shortlink.exception.RateLimitException;
import com.shortlink.service.ShortUrlService;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Map;

/**
 * Redirect controller: handles short URL resolution and HTTP 301 redirect.
 * Applies Cache-Control headers for browser-level caching.
 */
@RestController
public class RedirectController {

    private static final Logger log = LoggerFactory.getLogger(RedirectController.class);

    // Cache-Control header value: cache redirect for 1 hour
    private static final String CACHE_CONTROL = "public, max-age=3600, immutable";

    private final ShortUrlService shortUrlService;

    public RedirectController(ShortUrlService shortUrlService) {
        this.shortUrlService = shortUrlService;
    }

    /**
     * Resolves a short code and redirects to the original URL.
     * Uses 301 (Moved Permanently) with Cache-Control for aggressive browser caching.
     * GET /{shortCode}
     */
    @GetMapping("/{shortCode:[A-Za-z0-9]{4,20}}")
    public ResponseEntity<?> redirect(@PathVariable String shortCode) {
        try {
            return shortUrlService.resolveShortUrl(shortCode)
                    .map(originUrl -> ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
                            .header(HttpHeaders.LOCATION, originUrl)
                            .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                            .build())
                    .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body(Map.of("code", 404, "message", "Short URL not found: " + shortCode)));
        } catch (RateLimitException e) {
            log.warn("Rate limited redirect for shortCode={}", shortCode);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("code", 429, "message", "Too many requests"));
        } catch (Exception e) {
            log.error("Error resolving shortCode={}: {}", shortCode, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "message", "Internal server error"));
        }
    }
}
