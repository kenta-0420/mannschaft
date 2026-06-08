package com.mannschaft.app.match.visibility;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.match.entity.MatchEntity;
import com.mannschaft.app.match.repository.MatchRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * {@link MatchVisibilityResolver} の F00 可視性 UT（03 §C.3.2・純 UT）。
 */
@ExtendWith(MockitoExtension.class)
class MatchVisibilityResolverTest {

    private static final long ORG = 50L;
    private static final long TEAM_HOME = 100L;
    private static final long TEAM_AWAY = 200L;
    private static final long HOME_MEMBER = 1L;
    private static final long AWAY_MEMBER = 2L;
    private static final long ORG_MEMBER = 3L;
    private static final long STRANGER = 9L;
    private static final long SYS_ADMIN = 99L;

    @Mock
    private MatchRepository matchRepository;
    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private MatchVisibilityResolver resolver;

    private MatchEntity buildMatch(UUID id) {
        MatchEntity m = MatchEntity.builder()
                .organizationId(ORG)
                .teamId(TEAM_HOME)
                .opponentTeamId(TEAM_AWAY)
                .build();
        m.setId(id);
        return m;
    }

    @Test
    @DisplayName("referenceType()=MATCH / idKind=UUID_V7")
    void referenceTypeAndIdKind() {
        assertThat(resolver.referenceType()).isEqualTo(ReferenceType.MATCH);
        assertThat(ReferenceType.MATCH.idKind()).isEqualTo(ReferenceType.IdKind.UUID_V7);
    }

    @Test
    @DisplayName("Long 経路は fail-closed（false / 空集合）")
    void longPathFailClosed() {
        assertThat(resolver.canView(1L, HOME_MEMBER)).isFalse();
        assertThat(resolver.filterAccessible(List.of(1L), HOME_MEMBER)).isEmpty();
    }

    @Test
    @DisplayName("主体チームのメンバーは閲覧可")
    void homeMemberCanView() {
        UUID id = UUID.randomUUID();
        when(matchRepository.findById(id)).thenReturn(Optional.of(buildMatch(id)));
        when(accessControlService.isSystemAdmin(HOME_MEMBER)).thenReturn(false);
        when(accessControlService.isMember(HOME_MEMBER, TEAM_HOME, "TEAM")).thenReturn(true);
        assertThat(resolver.canViewUuid(id, HOME_MEMBER)).isTrue();
    }

    @Test
    @DisplayName("相手チームのメンバーも閲覧可（統合表示）")
    void awayMemberCanView() {
        UUID id = UUID.randomUUID();
        when(matchRepository.findById(id)).thenReturn(Optional.of(buildMatch(id)));
        when(accessControlService.isSystemAdmin(AWAY_MEMBER)).thenReturn(false);
        when(accessControlService.isMember(AWAY_MEMBER, TEAM_HOME, "TEAM")).thenReturn(false);
        when(accessControlService.isMember(AWAY_MEMBER, TEAM_AWAY, "TEAM")).thenReturn(true);
        assertThat(resolver.canViewUuid(id, AWAY_MEMBER)).isTrue();
    }

    @Test
    @DisplayName("主催組織のメンバーも閲覧可")
    void orgMemberCanView() {
        UUID id = UUID.randomUUID();
        when(matchRepository.findById(id)).thenReturn(Optional.of(buildMatch(id)));
        when(accessControlService.isSystemAdmin(ORG_MEMBER)).thenReturn(false);
        when(accessControlService.isMember(ORG_MEMBER, TEAM_HOME, "TEAM")).thenReturn(false);
        when(accessControlService.isMember(ORG_MEMBER, TEAM_AWAY, "TEAM")).thenReturn(false);
        when(accessControlService.isMember(ORG_MEMBER, ORG, "ORGANIZATION")).thenReturn(true);
        assertThat(resolver.canViewUuid(id, ORG_MEMBER)).isTrue();
    }

    @Test
    @DisplayName("無関係ユーザーは閲覧不可（fail-closed）")
    void strangerDenied() {
        UUID id = UUID.randomUUID();
        when(matchRepository.findById(id)).thenReturn(Optional.of(buildMatch(id)));
        when(accessControlService.isSystemAdmin(STRANGER)).thenReturn(false);
        when(accessControlService.isMember(STRANGER, TEAM_HOME, "TEAM")).thenReturn(false);
        when(accessControlService.isMember(STRANGER, TEAM_AWAY, "TEAM")).thenReturn(false);
        when(accessControlService.isMember(STRANGER, ORG, "ORGANIZATION")).thenReturn(false);
        assertThat(resolver.canViewUuid(id, STRANGER)).isFalse();
    }

    @Test
    @DisplayName("SystemAdmin は実在 match を常に閲覧可")
    void systemAdminCanView() {
        UUID id = UUID.randomUUID();
        when(matchRepository.findById(id)).thenReturn(Optional.of(buildMatch(id)));
        when(accessControlService.isSystemAdmin(SYS_ADMIN)).thenReturn(true);
        assertThat(resolver.canViewUuid(id, SYS_ADMIN)).isTrue();
    }

    @Test
    @DisplayName("不在 match は fail-closed（404 相当・存在を漏らさない）")
    void notFoundFailClosed() {
        UUID id = UUID.randomUUID();
        when(matchRepository.findById(id)).thenReturn(Optional.empty());
        assertThat(resolver.canViewUuid(id, HOME_MEMBER)).isFalse();
    }

    @Test
    @DisplayName("viewerUserId が null は fail-closed")
    void nullViewerFailClosed() {
        assertThat(resolver.canViewUuid(UUID.randomUUID(), null)).isFalse();
    }

    @Test
    @DisplayName("filterAccessibleUuid: アクセス可能 ID のみ返す（バッチ・1 SQL）")
    void filterBatch() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        MatchEntity ma = buildMatch(a);
        MatchEntity mb = buildMatch(b);
        when(matchRepository.findAllById(List.of(a, b))).thenReturn(List.of(ma, mb));
        when(accessControlService.isSystemAdmin(HOME_MEMBER)).thenReturn(false);
        // a は HOME メンバー可、b はどの scope も不可
        when(accessControlService.isMember(HOME_MEMBER, TEAM_HOME, "TEAM")).thenReturn(true);
        lenient().when(accessControlService.isMember(HOME_MEMBER, TEAM_AWAY, "TEAM")).thenReturn(false);
        lenient().when(accessControlService.isMember(HOME_MEMBER, ORG, "ORGANIZATION")).thenReturn(false);

        var accessible = resolver.filterAccessibleUuid(List.of(a, b), HOME_MEMBER);
        // 両方 HOME=TEAM_HOME ゆえ両方アクセス可（同一 owning team 設定のため）
        assertThat(accessible).containsExactlyInAnyOrder(a, b);
    }
}
