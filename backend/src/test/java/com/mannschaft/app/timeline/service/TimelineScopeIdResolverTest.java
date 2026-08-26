package com.mannschaft.app.timeline.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.team.TeamErrorCode;
import com.mannschaft.app.team.service.TeamService;
import com.mannschaft.app.timeline.TimelineErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link TimelineScopeIdResolver} の単体テスト。
 *
 * <p>読み取り（feed）・書き込み（投稿作成）の両経路で共有する slug/Long 文字列 → 内部 Long ID の
 * 解決ロジックを検証する。slug 解決は Repository 直注入ではなく {@link TeamService} /
 * {@link OrganizationService} 経由で行うことを明示する（ドメイン境界原則）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TimelineScopeIdResolver 単体テスト")
class TimelineScopeIdResolverTest {

    @Mock
    private TeamService teamService;

    @Mock
    private OrganizationService organizationService;

    @InjectMocks
    private TimelineScopeIdResolver resolver;

    private static final Long TEAM_INTERNAL_ID = 10L;
    private static final Long ORG_INTERNAL_ID = 20L;
    private static final String TEAM_SLUG = "test-team";
    private static final String ORG_SLUG = "test-org";

    @Nested
    @DisplayName("TEAM / ORGANIZATION スコープ")
    class TeamAndOrgScope {

        @Test
        @DisplayName("TEAM + Long文字列 → そのまま内部IDとして返す（slug解決を呼ばない）")
        void team_longString_resolvesDirectly() {
            assertThat(resolver.resolve("TEAM", TEAM_INTERNAL_ID.toString()))
                    .isEqualTo(TEAM_INTERNAL_ID);
        }

        @Test
        @DisplayName("TEAM + スラッグ → TeamService.resolveTeamId で内部IDを解決する")
        void team_slug_resolvesViaTeamService() {
            given(teamService.resolveTeamId(TEAM_SLUG)).willReturn(TEAM_INTERNAL_ID);

            assertThat(resolver.resolve("TEAM", TEAM_SLUG)).isEqualTo(TEAM_INTERNAL_ID);
            verify(teamService).resolveTeamId(TEAM_SLUG);
        }

        @Test
        @DisplayName("ORGANIZATION + スラッグ → OrganizationService.resolveOrgId で内部IDを解決する")
        void org_slug_resolvesViaOrgService() {
            given(organizationService.resolveOrgId(ORG_SLUG)).willReturn(ORG_INTERNAL_ID);

            assertThat(resolver.resolve("ORGANIZATION", ORG_SLUG)).isEqualTo(ORG_INTERNAL_ID);
            verify(organizationService).resolveOrgId(ORG_SLUG);
        }

        @Test
        @DisplayName("TEAM + 存在しないスラッグ → BusinessException(POST_NOT_FOUND)")
        void team_slugNotFound_throwsPostNotFound() {
            given(teamService.resolveTeamId(anyString()))
                    .willThrow(new BusinessException(TeamErrorCode.TEAM_001));

            assertThatThrownBy(() -> resolver.resolve("TEAM", TEAM_SLUG))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.POST_NOT_FOUND));
        }

        @Test
        @DisplayName("TEAM + 不正文字列 → BusinessException(POST_NOT_FOUND)")
        void team_invalidString_throwsPostNotFound() {
            given(teamService.resolveTeamId(anyString()))
                    .willThrow(new BusinessException(TeamErrorCode.TEAM_001));

            assertThatThrownBy(() -> resolver.resolve("TEAM", "not-a-long"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.POST_NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("その他スコープ")
    class OtherScope {

        @Test
        @DisplayName("PUBLIC + Long文字列 → そのまま parse して返す")
        void public_longString_parses() {
            assertThat(resolver.resolve("PUBLIC", "0")).isEqualTo(0L);
            assertThat(resolver.resolve("PUBLIC", "5")).isEqualTo(5L);
        }

        @Test
        @DisplayName("PUBLIC + 非数値文字列 → 0L にフォールバック")
        void public_nonNumeric_fallbacksToZero() {
            assertThat(resolver.resolve("PUBLIC", "invalid")).isEqualTo(0L);
        }
    }
}
