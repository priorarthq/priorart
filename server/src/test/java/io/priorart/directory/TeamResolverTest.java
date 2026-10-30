package io.priorart.directory;

import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TeamResolverTest {
    private final UUID payments = UUID.randomUUID();
    private final UUID platform = UUID.randomUUID();
    private final UUID checkout = UUID.randomUUID();

    @Test
    void onlyMembersWorkLandsInTheTeam() {
        assertEquals(payments, TeamResolver.pick(Set.of(payments), Set.of(payments), true));
        assertNull(TeamResolver.pick(Set.of(), Set.of(payments), true), "a non-member (or someone not approved yet) stays private");
        assertNull(TeamResolver.pick(Set.of(platform), Set.of(payments), true), "membership of another team doesn't count");
    }

    @Test
    void singleUserModeLetsTheRepoDecide() {
        assertEquals(payments, TeamResolver.pick(Set.of(), Set.of(payments), false));
        assertEquals(payments, TeamResolver.pick(Set.of(platform), Set.of(payments), false));
    }

    @Test
    void sharedRepoResolvesThroughMembership() {
        assertEquals(platform, TeamResolver.pick(Set.of(platform), Set.of(payments, platform), true));
    }

    @Test
    void sharedRepoWithNoOrManyMatchingMembershipsIsUnassigned() {
        assertNull(TeamResolver.pick(Set.of(), Set.of(payments, platform), true));
        assertNull(TeamResolver.pick(Set.of(checkout), Set.of(payments, platform), true));
        assertNull(TeamResolver.pick(Set.of(payments, platform), Set.of(payments, platform), true));
    }

    @Test
    void workInAnUnonboardedRepoStaysUnassignedEvenIfTheAuthorHasOneTeam() {
        assertNull(TeamResolver.pick(Set.of(checkout), Set.of(), true), "personal side projects must not land in the author's team");
        assertNull(TeamResolver.pick(Set.of(checkout, payments), Set.of(), true));
        assertNull(TeamResolver.pick(Set.of(), Set.of(), true));
    }
}
