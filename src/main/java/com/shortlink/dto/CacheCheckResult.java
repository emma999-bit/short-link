package com.shortlink.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of a smart cache check during short URL creation.
 * Indicates whether an existing mapping was found and the source of the hit.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CacheCheckResult {

    public enum Source {
        LOCAL_CACHE, REDIS, DB, NONE
    }

    /** Whether a cached mapping was found. */
    private boolean hit;

    /** The source where the hit occurred. */
    private Source source;

    /** The short code found (non-null when hit=true). */
    private String shortCode;

    /** The origin URL associated with the short code. */
    private String originUrl;

    public static CacheCheckResult miss() {
        return CacheCheckResult.builder().hit(false).source(Source.NONE).build();
    }

    public static CacheCheckResult hit(Source source, String shortCode, String originUrl) {
        return CacheCheckResult.builder()
                .hit(true)
                .source(source)
                .shortCode(shortCode)
                .originUrl(originUrl)
                .build();
    }
}
