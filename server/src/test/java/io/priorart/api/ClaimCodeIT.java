package io.priorart.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** With PRIORART_CLAIM_CODE set, signing up needs the code. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "priorart.claim-code=let-me-in", "priorart.extract.sweep-ms=3600000", "priorart.extract.sweep-initial-ms=3600000"})
class ClaimCodeIT {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;

    @Test
    void claimingNeedsTheCode() {
        assertThat(http.postForEntity("/api/users/claim", Map.of("handle", "nina"), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(http.postForEntity("/api/users/claim", Map.of("handle", "nina", "code", "guess"), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(http.postForEntity("/api/users/claim", Map.of("handle", "nina", "code", "let-me-in"), String.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
}
