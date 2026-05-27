package com.shortlink;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Basic application context smoke test.
 * Does not require actual database or Redis connections.
 */
@SpringBootTest
@ActiveProfiles("test")
class ShortLinkApplicationTests {

    @Test
    void contextLoads() {
        // Verifies the Spring context can be loaded
    }
}
