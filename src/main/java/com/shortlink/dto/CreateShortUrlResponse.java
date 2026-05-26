package com.shortlink.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Response DTO for short URL creation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateShortUrlResponse {

    private String shortCode;
    private String shortUrl;
    private String originUrl;
    private LocalDateTime createTime;
    private Integer expireDays;

    /**
     * Whether the short code was reused from an existing mapping.
     */
    private boolean existing;
}
