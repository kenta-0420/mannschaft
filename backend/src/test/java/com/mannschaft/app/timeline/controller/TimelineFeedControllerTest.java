package com.mannschaft.app.timeline.controller;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.team.TeamErrorCode;
import com.mannschaft.app.team.service.TeamService;
import com.mannschaft.app.timeline.TimelineErrorCode;
import com.mannschaft.app.timeline.dto.PostResponse;
import com.mannschaft.app.timeline.dto.TimelineFeedResponse;
import com.mannschaft.app.timeline.service.TimelinePostService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link TimelineFeedController} の単体テスト。
 *
 * <p>主に {@code resolveScopeId} の slug / Long 両対応を検証する。
 * slug 解決は Repository 直注入ではなく {@link TeamService} / {@link OrganizationService}
 * 経由で行うことをテストで明示する（ドメイン境界原則）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TimelineFeedController 単体テスト")
class TimelineFeedControllerTest {

    @Mock
    private TimelinePostService postService;

    @Mock
    private TeamService teamService;

    @Mock
    private OrganizationService organizationService;

    @InjectMocks
    private TimelineFeedController controller;

    private static final Long TEAM_INTERNAL_ID = 10L;
    private static final Long ORG_INTERNAL_ID = 20L;
    private static final String TEAM_SLUG = "test-team";
    private static final String ORG_SLUG = "test-org";

    @Nested
    @DisplayName("getFeed - scopeId 解決")
    class GetFeedScopeIdResolution {

        @Test
        @DisplayName("TEAM + Long文字列 scopeId → そのまま内部ID として getFeed を呼ぶ")
        void team_longStringScopeId_resolvesDirectly() {
            given(postService.getFeed(eq("TEAM"), eq(TEAM_INTERNAL_ID), any(), anyInt()))
                    .willReturn(List.of());
            given(postService.getPinnedPosts(eq("TEAM"), eq(TEAM_INTERNAL_ID)))
                    .willReturn(List.of());

            ResponseEntity<TimelineFeedResponse> response =
                    controller.getFeed("TEAM", TEAM_INTERNAL_ID.toString(), null, 20);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(postService).getFeed("TEAM", TEAM_INTERNAL_ID, null, 20);
        }

        @Test
        @DisplayName("TEAM + スラッグ文字列 scopeId → TeamService.resolveTeamId で内部ID を解決して getFeed を呼ぶ")
        void team_slugStringScopeId_resolvesViaTeamService() {
            given(teamService.resolveTeamId(TEAM_SLUG)).willReturn(TEAM_INTERNAL_ID);
            given(postService.getFeed(eq("TEAM"), eq(TEAM_INTERNAL_ID), any(), anyInt()))
                    .willReturn(List.of());
            given(postService.getPinnedPosts(eq("TEAM"), eq(TEAM_INTERNAL_ID)))
                    .willReturn(List.of());

            ResponseEntity<TimelineFeedResponse> response =
                    controller.getFeed("TEAM", TEAM_SLUG, null, 20);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(teamService).resolveTeamId(TEAM_SLUG);
            verify(postService).getFeed("TEAM", TEAM_INTERNAL_ID, null, 20);
        }

        @Test
        @DisplayName("ORGANIZATION + スラッグ文字列 scopeId → OrganizationService.resolveOrgId で内部ID を解決して getFeed を呼ぶ")
        void organization_slugStringScopeId_resolvesViaOrgService() {
            given(organizationService.resolveOrgId(ORG_SLUG)).willReturn(ORG_INTERNAL_ID);
            given(postService.getFeed(eq("ORGANIZATION"), eq(ORG_INTERNAL_ID), any(), anyInt()))
                    .willReturn(List.of());
            given(postService.getPinnedPosts(eq("ORGANIZATION"), eq(ORG_INTERNAL_ID)))
                    .willReturn(List.of());

            ResponseEntity<TimelineFeedResponse> response =
                    controller.getFeed("ORGANIZATION", ORG_SLUG, null, 20);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(organizationService).resolveOrgId(ORG_SLUG);
            verify(postService).getFeed("ORGANIZATION", ORG_INTERNAL_ID, null, 20);
        }

        @Test
        @DisplayName("TEAM + スラッグ文字列 で対象チームが存在しない → BusinessException (POST_NOT_FOUND)")
        void team_slugStringScopeId_teamNotFound_throwsBusinessException() {
            given(teamService.resolveTeamId(anyString()))
                    .willThrow(new BusinessException(TeamErrorCode.TEAM_001));

            assertThatThrownBy(() -> controller.getFeed("TEAM", TEAM_SLUG, null, 20))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.POST_NOT_FOUND));
        }

        @Test
        @DisplayName("TEAM + 不正文字列 scopeId → BusinessException (POST_NOT_FOUND)")
        void team_invalidStringScopeId_throwsBusinessException() {
            given(teamService.resolveTeamId(anyString()))
                    .willThrow(new BusinessException(TeamErrorCode.TEAM_001));

            assertThatThrownBy(() -> controller.getFeed("TEAM", "not-a-long", null, 20))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.POST_NOT_FOUND));
        }

        @Test
        @DisplayName("PUBLIC スコープ + Long文字列 scopeId → そのまま getFeed を呼ぶ")
        void public_longStringScopeId_callsFeedDirectly() {
            given(postService.getFeed(eq("PUBLIC"), eq(0L), any(), anyInt()))
                    .willReturn(List.of());
            given(postService.getPinnedPosts(eq("PUBLIC"), eq(0L)))
                    .willReturn(List.of());

            ResponseEntity<TimelineFeedResponse> response =
                    controller.getFeed("PUBLIC", "0", null, 20);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(postService).getFeed("PUBLIC", 0L, null, 20);
        }

        @Test
        @DisplayName("PUBLIC スコープ + 非数値文字列 scopeId → 0L にフォールバックして getFeed を呼ぶ")
        void public_nonNumericScopeId_fallbacksToZero() {
            given(postService.getFeed(eq("PUBLIC"), eq(0L), any(), anyInt()))
                    .willReturn(List.of());
            given(postService.getPinnedPosts(eq("PUBLIC"), eq(0L)))
                    .willReturn(List.of());

            ResponseEntity<TimelineFeedResponse> response =
                    controller.getFeed("PUBLIC", "invalid", null, 20);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(postService).getFeed("PUBLIC", 0L, null, 20);
        }

        @Test
        @DisplayName("getFeed レスポンスは pinned と posts を含む TimelineFeedResponse を返す")
        void getFeed_returnsTimelineFeedResponseWithPinnedAndPosts() {
            PostResponse pinnedPost = PostResponse.builder().id(1L).build();
            PostResponse normalPost = PostResponse.builder().id(2L).build();
            given(postService.getFeed(eq("PUBLIC"), eq(0L), any(), anyInt()))
                    .willReturn(List.of(normalPost));
            given(postService.getPinnedPosts(eq("PUBLIC"), eq(0L)))
                    .willReturn(List.of(pinnedPost));

            ResponseEntity<TimelineFeedResponse> response =
                    controller.getFeed("PUBLIC", "0", null, 20);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            TimelineFeedResponse body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.getData().getPinned()).containsExactly(pinnedPost);
            assertThat(body.getData().getPosts()).containsExactly(normalPost);
            assertThat(body.getMeta().getLimit()).isEqualTo(20);
        }
    }
}
