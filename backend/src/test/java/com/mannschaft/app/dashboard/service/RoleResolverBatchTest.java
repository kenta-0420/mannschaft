package com.mannschaft.app.dashboard.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.ScopeKey;
import com.mannschaft.app.common.visibility.UserScopeRoleSnapshot;
import com.mannschaft.app.dashboard.ViewerRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Inbox用の最新batch解決は、direct所属だけで既存のViewerRole正規化を適用する。 */
@ExtendWith(MockitoExtension.class)
class RoleResolverBatchTest {
    @Mock private AccessControlService accessControlService;
    @Mock private MembershipBatchQueryService membershipBatchQueryService;
    @InjectMocks private RoleResolver resolver;
    private static final ScopeKey TEAM = new ScopeKey("TEAM", 2L);

    @ParameterizedTest
    @CsvSource({"ADMIN,ADMIN", "DEPUTY_ADMIN,DEPUTY_ADMIN", "MEMBER,MEMBER",
            "SUPPORTER,SUPPORTER", "GUEST,PUBLIC", "UNKNOWN_ROLE,PUBLIC", "member,MEMBER"})
    void batchUsesSameRoleNormalization(String role, ViewerRole expected) {
        givenSnapshot(Map.of(TEAM, role));
        given(accessControlService.isActiveUser(1L)).willReturn(true);
        assertThat(resolver.resolveViewerRolesFresh(1L, Set.of(TEAM)))
                .containsExactly(Map.entry(TEAM, expected));
        verify(membershipBatchQueryService).snapshotForUser(1L, Set.of(TEAM), Set.of());
        verify(accessControlService).isActiveUser(1L);
        verify(accessControlService, never()).getRoleName(1L, 2L, "TEAM");
    }

    @Test
    void missingRoleIsPublic() {
        givenSnapshot(Map.of());
        given(accessControlService.isActiveUser(1L)).willReturn(true);
        assertThat(resolver.resolveViewerRolesFresh(1L, Set.of(TEAM)).get(TEAM)).isEqualTo(ViewerRole.PUBLIC);
    }

    @Test
    void inactiveUserIsPublicDespiteSnapshotAssignment() {
        givenSnapshot(Map.of(TEAM, "ADMIN"));
        given(accessControlService.isActiveUser(1L)).willReturn(false);
        assertThat(resolver.resolveViewerRolesFresh(1L, Set.of(TEAM)).get(TEAM)).isEqualTo(ViewerRole.PUBLIC);
    }

    @Test
    void systemAdminWinsWithoutActiveUserQuery() {
        given(membershipBatchQueryService.snapshotForUser(1L, Set.of(TEAM), Set.of()))
                .willReturn(UserScopeRoleSnapshot.forSystemAdmin());
        assertThat(resolver.resolveViewerRolesFresh(1L, Set.of(TEAM)).get(TEAM))
                .isEqualTo(ViewerRole.SYSTEM_ADMIN);
        verifyNoInteractions(accessControlService);
    }

    @Test
    void emptyAndNullScopesDoNotQuery() {
        assertThat(resolver.resolveViewerRolesFresh(1L, Set.of())).isEmpty();
        assertThat(resolver.resolveViewerRolesFresh(1L, null)).isEmpty();
        verifyNoInteractions(accessControlService, membershipBatchQueryService);
    }

    @Test
    void nullUserIsRejectedBeforeQuery() {
        assertThatThrownBy(() -> resolver.resolveViewerRolesFresh(null, Set.of(TEAM)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(accessControlService, membershipBatchQueryService);
    }

    private void givenSnapshot(Map<ScopeKey, String> roles) {
        given(membershipBatchQueryService.snapshotForUser(1L, Set.of(TEAM), Set.of()))
                .willReturn(new UserScopeRoleSnapshot(false, roles, Map.of(), Set.of(), Set.of()));
    }
}
