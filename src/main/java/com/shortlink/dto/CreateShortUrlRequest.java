package com.shortlink.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for creating a short URL.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateShortUrlRequest {

    @NotBlank(message = "originUrl must not be blank")
    @Pattern(regexp = "^https?://.*", message = "originUrl must start with http:// or https://")
    @Size(max = 2048, message = "originUrl must not exceed 2048 characters")
    private String originUrl;

    private Integer expireDays;

    private String creator;

    // Optional: custom short code (if not provided, auto-generated)
    @Size(max = 20, message = "customCode must not exceed 20 characters")
    private String customCode;
}
