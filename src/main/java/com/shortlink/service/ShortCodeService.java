package com.shortlink.service;

import com.shortlink.generator.ShortCodeGenerator;
import com.shortlink.util.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Short code computation service.
 * <p>
 * Provides multi-hash strategy for generating candidate short codes:
 * 1. MD5 primary hash → Base62 prefix
 * 2. SHA-256 backup hash → Base62 prefix
 * 3. Salted MD5 hash → Base62 prefix
 * 4. Snowflake-based generation (collision-free, used as final fallback)
 */
@Service
public class ShortCodeService {

    private static final Logger log = LoggerFactory.getLogger(ShortCodeService.class);

    @Value("${short-link.snowflake.code-length:8}")
    private int codeLength;

    private final ShortCodeGenerator generator;

    public ShortCodeService(ShortCodeGenerator generator) {
        this.generator = generator;
    }

    /**
     * Generates a sequence of candidate short codes for the given URL.
     * Returns an array of candidates in priority order:
     * [0] MD5-based, [1] SHA256-based, [2] salted-MD5, [3] snowflake
     */
    public String[] generateCandidates(String originUrl) {
        String md5Hash = DigestUtils.md5Hex(originUrl);
        String sha256Hash = DigestUtils.sha256Hex(originUrl);
        String salt = String.valueOf(System.currentTimeMillis());
        String saltedHash = DigestUtils.saltedMd5Hex(originUrl, salt);

        return new String[]{
            hashToCode(md5Hash),
            hashToCode(sha256Hash),
            hashToCode(saltedHash),
            generator.generate()  // Snowflake: guaranteed unique
        };
    }

    /**
     * Generates a single snowflake-based short code.
     * Used for custom code generation when hash-based codes all collide.
     */
    public String generateSnowflakeCode() {
        return generator.generate();
    }

    /**
     * Computes the canonical URL hash for deduplication (SHA-256 of the URL).
     */
    public String computeUrlHash(String originUrl) {
        return DigestUtils.sha256Hex(originUrl);
    }

    /**
     * Converts a hex hash string to a Base62 short code of codeLength characters.
     */
    private String hashToCode(String hexHash) {
        // Use first 16 hex chars (64-bit value) to compute Base62 code
        String prefix = hexHash.length() >= 16 ? hexHash.substring(0, 16) : hexHash;
        long value = Long.parseUnsignedLong(prefix, 16) & Long.MAX_VALUE;

        // Encode to Base62
        String encoded = com.shortlink.util.Base62Util.encode(value, codeLength);
        // Trim to codeLength
        return encoded.length() > codeLength ? encoded.substring(0, codeLength) : encoded;
    }
}
