package com.mannschaft.app.match.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.match.entity.MatchEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * {@link MatchAccessService} の認可マトリクス UT（03 §C・純 UT）。
 *
 * <p>自チーム分のみ編集 / 相手分 403 / 記録係 / IDOR 帰属 / 閲覧=F00 委譲を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
class MatchAccessServiceTest {

    private static final long TEAM_HOME = 100L;
    private static final long TEAM_AWAY = 200L;
    private static final long CREATOR = 1L;
    private static final long SCOREKEEPER = 2L;
    private static final long HOME_ADMIN = 3L;
    private static final long AWAY_ADMIN = 4L;
    private static final long STRANGER = 9L;

    @Mock
    private AccessControlService accessControlService;
    @Mock
    private ContentVisibilityChecker visibilityChecker;

    @InjectMocks
    private MatchAccessService service;

    private MatchEntity match(boolean hasScorekeeper) {
        return MatchEntity.builder()
                .organizationId(50L)
                .teamId(TEAM_HOME)
                .opponentTeamId(TEAM_AWAY)
                .createdBy(CREATOR)
                .scorekeeperUserId(hasScorekeeper ? SCOREKEEPER : null)
                .hasScorekeeper(hasScorekeeper)
                .build();
    }

    // ─── canView（F00 委譲） ───────────────────────────────

    @Test
    @DisplayName("canView は F00 ContentVisibilityChecker.canViewUuid(MATCH,...) へ委譲する")
    void canViewDelegatesToF00() {
        UUID matchId = UUID.randomUUID();
        when(visibilityChecker.canViewUuid(eq(ReferenceType.MATCH), eq(matchId), eq(STRANGER)))
                .thenReturn(true);
        assertThat(service.canView(STRANGER, matchId)).isTrue();
    }

    @Test
    @DisplayName("assertCanView は閲覧不可で 404（MATCH_001）")
    void assertCanViewThrows404() {
        UUID matchId = UUID.randomUUID();
        when(visibilityChecker.canViewUuid(eq(ReferenceType.MATCH), eq(matchId), eq(STRANGER)))
                .thenReturn(false);
        assertThatThrownBy(() -> service.assertCanView(STRANGER, matchId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("試合が見つかりません");
    }

    // ─── canListTeamMatches（一覧・メンバー以上・Phase2C） ──

    @Test
    @DisplayName("canListTeamMatches: 当該チームのメンバーは可")
    void canListTeamMatchesMember() {
        when(accessControlService.isMember(HOME_ADMIN, TEAM_HOME, "TEAM")).thenReturn(true);
        assertThat(service.canListTeamMatches(HOME_ADMIN, TEAM_HOME)).isTrue();
    }

    @Test
    @DisplayName("canListTeamMatches: 非メンバーは不可・assertCanListTeamMatches は 403（MATCH_010）")
    void canListTeamMatchesNonMember() {
        when(accessControlService.isMember(STRANGER, TEAM_HOME, "TEAM")).thenReturn(false);
        assertThat(service.canListTeamMatches(STRANGER, TEAM_HOME)).isFalse();
        assertThatThrownBy(() -> service.assertCanListTeamMatches(STRANGER, TEAM_HOME))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("canListTeamMatches: userId / teamId が null なら不可（NPE にならず false）")
    void canListTeamMatchesNullArgs() {
        assertThat(service.canListTeamMatches(null, TEAM_HOME)).isFalse();
        assertThat(service.canListTeamMatches(HOME_ADMIN, null)).isFalse();
    }

    // ─── canEditMeta ───────────────────────────────────────

    @Test
    @DisplayName("canEditMeta: 作成者本人は可")
    void canEditMetaCreator() {
        assertThat(service.canEditMeta(CREATOR, match(false))).isTrue();
    }

    @Test
    @DisplayName("canEditMeta: 記録係本人は可")
    void canEditMetaScorekeeper() {
        assertThat(service.canEditMeta(SCOREKEEPER, match(true))).isTrue();
    }

    @Test
    @DisplayName("canEditMeta: 主体チーム ADMIN は可")
    void canEditMetaHomeAdmin() {
        when(accessControlService.isAdminOrAbove(HOME_ADMIN, TEAM_HOME, "TEAM")).thenReturn(true);
        assertThat(service.canEditMeta(HOME_ADMIN, match(false))).isTrue();
    }

    @Test
    @DisplayName("canEditMeta: 無関係ユーザーは不可")
    void canEditMetaStranger() {
        when(accessControlService.isAdminOrAbove(STRANGER, TEAM_HOME, "TEAM")).thenReturn(false);
        assertThat(service.canEditMeta(STRANGER, match(false))).isFalse();
    }

    // ─── canRecordTimeline ─────────────────────────────────

    @Test
    @DisplayName("canRecordTimeline 公式戦: 記録係のみ可・他チーム ADMIN は不可")
    void recordTimelineOfficialOnlyScorekeeper() {
        MatchEntity m = match(true);
        assertThat(service.canRecordTimeline(SCOREKEEPER, m)).isTrue();
        // 公式戦ではチーム ADMIN でも記録不可（記録係専属）
        assertThat(service.canRecordTimeline(HOME_ADMIN, m)).isFalse();
    }

    @Test
    @DisplayName("canRecordTimeline 共同記録: 両チーム ADMIN が可")
    void recordTimelineCoopBothAdmins() {
        MatchEntity m = match(false);
        when(accessControlService.isAdminOrAbove(HOME_ADMIN, TEAM_HOME, "TEAM")).thenReturn(true);
        lenient().when(accessControlService.isAdminOrAbove(AWAY_ADMIN, TEAM_HOME, "TEAM")).thenReturn(false);
        when(accessControlService.isAdminOrAbove(AWAY_ADMIN, TEAM_AWAY, "TEAM")).thenReturn(true);

        assertThat(service.canRecordTimeline(HOME_ADMIN, m)).isTrue();
        assertThat(service.canRecordTimeline(AWAY_ADMIN, m)).isTrue();
    }

    @Test
    @DisplayName("canRecordTimeline 共同記録: どちらのチーム ADMIN でもない者は不可（403）")
    void recordTimelineCoopStranger() {
        MatchEntity m = match(false);
        when(accessControlService.isAdminOrAbove(STRANGER, TEAM_HOME, "TEAM")).thenReturn(false);
        when(accessControlService.isAdminOrAbove(STRANGER, TEAM_AWAY, "TEAM")).thenReturn(false);
        assertThat(service.canRecordTimeline(STRANGER, m)).isFalse();
        assertThatThrownBy(() -> service.assertCanRecordTimeline(STRANGER, m))
                .isInstanceOf(BusinessException.class);
    }

    // ─── canEditTeamData（自チーム分のみ・相手分 403） ──────

    @Test
    @DisplayName("canEditTeamData: 自チーム ADMIN は自チーム分を編集可")
    void editTeamDataOwnTeam() {
        MatchEntity m = match(false);
        when(accessControlService.isAdminOrAbove(HOME_ADMIN, TEAM_HOME, "TEAM")).thenReturn(true);
        assertThat(service.canEditTeamData(HOME_ADMIN, m, TEAM_HOME)).isTrue();
    }

    @Test
    @DisplayName("canEditTeamData: 自チーム ADMIN でも相手チーム分は不可（403・自チーム分のみ）")
    void editTeamDataOpponentForbidden() {
        MatchEntity m = match(false);
        // HOME_ADMIN は TEAM_AWAY の ADMIN ではない
        when(accessControlService.isAdminOrAbove(HOME_ADMIN, TEAM_AWAY, "TEAM")).thenReturn(false);
        assertThat(service.canEditTeamData(HOME_ADMIN, m, TEAM_AWAY)).isFalse();
        assertThatThrownBy(() -> service.assertCanEditTeamData(HOME_ADMIN, m, TEAM_AWAY))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("canEditTeamData: 当該試合に無関係なチーム ID は不可（IDOR 帰属・false）")
    void editTeamDataUnrelatedTeam() {
        MatchEntity m = match(false);
        // 999L はこの試合の HOME でも AWAY でもない → ADMIN 判定に到達せず false
        assertThat(service.canEditTeamData(HOME_ADMIN, m, 999L)).isFalse();
    }
}
