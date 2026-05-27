package com.shortlink.controller;

import com.shortlink.dto.CreateShortUrlRequest;
import com.shortlink.dto.CreateShortUrlResponse;
import com.shortlink.exception.RateLimitException;
import com.shortlink.service.ShortUrlService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller for short URL creation and management.
 */
@RestController
@RequestMapping("/api/v1/urls")
@Validated
public class ShortUrlController {

    private static final Logger log = LoggerFactory.getLogger(ShortUrlController.class);

    private final ShortUrlService shortUrlService;

    public ShortUrlController(ShortUrlService shortUrlService) {
        this.shortUrlService = shortUrlService;
    }

    /**
     * Creates a new short URL mapping.
     * POST /api/v1/urls
     */
    @PostMapping
    public ResponseEntity<?> createShortUrl(@Valid @RequestBody CreateShortUrlRequest request) {
        try {
            CreateShortUrlResponse response = shortUrlService.createShortUrl(request);
            HttpStatus status = response.isExisting() ? HttpStatus.OK : HttpStatus.CREATED;
            return ResponseEntity.status(status).body(response);
        } catch (RateLimitException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("code", 429, "message", e.getMessage()));
        } catch (Exception e) {
            log.error("Error creating short URL for originUrl={}: {}", request.getOriginUrl(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "message", "Internal server error"));
        }
    }

    /**
     * Gets details for a short URL by shortCode.
     * GET /api/v1/urls/{shortCode}
     */
    @GetMapping("/{shortCode}")
    public ResponseEntity<?> getShortUrl(@PathVariable String shortCode) {
        return shortUrlService.resolveShortUrl(shortCode)
                .map(originUrl -> ResponseEntity.ok(Map.of(
                        "shortCode", shortCode,
                        "originUrl", originUrl)))
                .orElse(ResponseEntity.notFound().build());
    }
}
