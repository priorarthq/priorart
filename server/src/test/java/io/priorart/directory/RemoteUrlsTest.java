package io.priorart.directory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class RemoteUrlsTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "git@github.com:Acme/Payments-Svc.git",
            "https://github.com/acme/payments-svc",
            "https://github.com/acme/payments-svc.git",
            "https://alice:ghp_token@github.com/acme/payments-svc.git",
            "ssh://git@github.com/acme/payments-svc.git",
            "ssh://git@github.com:22/acme/payments-svc/",
            "  HTTPS://GitHub.com/Acme/Payments-Svc/  ",
    })
    void sameRepoNormalizesToOneKey(String remote) {
        assertEquals("github.com/acme/payments-svc", RemoteUrls.normalize(remote));
    }

    @Test
    void enterpriseHostsAndNestedGroupsKeepTheirPath() {
        assertEquals("gitlab.corp.example/platform/infra/terraform",
                RemoteUrls.normalize("git@gitlab.corp.example:platform/infra/terraform.git"));
        assertEquals("ghe.corp.example/team/repo",
                RemoteUrls.normalize("https://ghe.corp.example:8443/team/repo.git"));
    }

    @Test
    void blankIsNull() {
        assertNull(RemoteUrls.normalize(null));
        assertNull(RemoteUrls.normalize("   "));
    }

    @Test
    void defaultNameIsLastSegment() {
        assertEquals("payments-svc", RemoteUrls.defaultName("github.com/acme/payments-svc"));
    }
}
