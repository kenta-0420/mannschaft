package com.mannschaft.app.timeline.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.timeline.TimelineErrorCode;
import com.mannschaft.app.timeline.dto.PostResponse;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@link TimelineFeedController} の単体テスト。
 *
 * <p>主に {@code resolveScopeId} の UUID / Long 両対応を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TimelineFeedController 単体テスト")
class TimelineFeedControllerTest {

    @Mock
    private TimelinePostService postService;

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private OrganizationRepository organizationRepository;

    @InjectMocks
    private TimelineFeedController controller;

    private static final Long TEAM_INTERNAL_ID = 10L;
    private static final Long ORG_INTERNAL_ID = 20L;
    private static final UUID TEAM_PUBLIC_ID = UUID.randomUUID();
    private static final UUID ORG_PUBLIC_ID = UUID.randomUUID();

    @Nested
    @DisplayName("getFeed - scopeId 解決")
    class GetFeedScopeIdResolution {

        @Test
        @DisplayName("TEAM + Long文字列 scopeId → そのまま内部ID として getFeed を呼ぶ")
        void team_longStringScopeId_resolvesDirectly() {
            given(postService.getFeed(eq("TEAM"), eq(TEAM_INTERNAL_ID), any(), anyInt()))
                    .willReturn(List.of());

            ResponseEntity<ApiResponse<List<PostResponse>>> response =
                    controller.getFeed("TEAM", TEAM_INTERNAL_ID.toString(), null, 20);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(postService).getFeed("TEAM", TEAM_INTERNAL_ID, null, 20);
        }

        @Test
        @DisplayName("TEAM + UUID文字列 scopeId → publicId から内部ID を解決して getFeed を呼ぶ")
        void team_uuidStringScopeId_resolvesViaPublicId() {
            // @NoArgsConstructor(PROTECTED) のため mock() を使用
            TeamEntity teamEntity = mock(TeamEntity.class);
            given(teamEntity.getId()).willReturn(TEAM_INTERNAL_ID);
            given(teamRepository.findByPublicId(TEAM_PUBLIC_ID)).willReturn(Optional.of(teamEntity));
            given(postService.getFeed(eq("TEAM"), eq(TEAM_INTERNAL_ID), any(), anyInt()))
                    .willReturn(List.of());

            ResponseEntity<ApiResponse<List<PostResponse>>> response =
                    controller.getFeed("TEAM", TEAM_PUBLIC_ID.toString(), null, 20);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(teamRepository).findByPublicId(TEAM_PUBLIC_ID);
            verify(postService).getFeed("TEAM", TEAM_INTERNAL_ID, null, 20);
        }

        @Test
        @DisplayName("ORGANIZATION + UUID文字列 scopeId → publicId から内部ID を解決して getFeed を呼ぶ")
        void organization_uuidStringScopeId_resolvesViaPublicId() {
            // @NoArgsConstructor(PROTECTED) のため mock() を使用
            OrganizationEntity orgEntity = mock(OrganizationEntity.class);
            given(orgEntity.getId()).willReturn(ORG_INTERNAL_ID);
            given(organizationRepository.findByPublicId(ORG_PUBLIC_ID)).willReturn(Optional.of(orgEntity));
            given(postService.getFeed(eq("ORGANIZATION"), eq(ORG_INTERNAL_ID), any(), anyInt()))
                    .willReturn(List.of());

            ResponseEntity<ApiResponse<List<PostResponse>>> response =
                    controller.getFeed("ORGANIZATION", ORG_PUBLIC_ID.toString(), null, 20);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(organizationRepository).findByPublicId(ORG_PUBLIC_ID);
            verify(postService).getFeed("ORGANIZATION", ORG_INTERNAL_ID, null, 20);
        }

        @Test
        @DisplayName("TEAM + UUID文字列 で対象チームが存在しない → BusinessException (POST_NOT_FOUND)")
        void team_uuidStringScopeId_teamNotFound_throwsBusinessException() {
            given(teamRepository.findByPublicId(TEAM_PUBLIC_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> controller.getFeed("TEAM", TEAM_PUBLIC_ID.toString(), null, 20))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.POST_NOT_FOUND));
        }

        @Test
        @DisplayName("TEAM + 不正文字列 scopeId → BusinessException (POST_NOT_FOUND)")
        void team_invalidStringScopeId_throwsBusinessException() {
            assertThatThrownBy(() -> controller.getFeed("TEAM", "not-a-uuid-or-long", null, 20))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.POST_NOT_FOUND));
        }

        @Test
        @DisplayName("PUBLIC スコープ + Long文字列 scopeId → そのまま getFeed を呼ぶ")
        void public_longStringScopeId_callsFeedDirectly() {
            given(postService.getFeed(eq("PUBLIC"), eq(0L), any(), anyInt()))
                    .willReturn(List.of());

            ResponseEntity<ApiResponse<List<PostResponse>>> response =
                    controller.getFeed("PUBLIC", "0", null, 20);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(postService).getFeed("PUBLIC", 0L, null, 20);
        }

        @Test
        @DisplayName("PUBLIC スコープ + 非数値文字列 scopeId → 0L にフォールバックして getFeed を呼ぶ")
        void public_nonNumericScopeId_fallbacksToZero() {
            given(postService.getFeed(eq("PUBLIC"), eq(0L), any(), anyInt()))
                    .willReturn(List.of());

            ResponseEntity<ApiResponse<List<PostResponse>>> response =
                    controller.getFeed("PUBLIC", "invalid", null, 20);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(postService).getFeed("PUBLIC", 0L, null, 20);
        }
    }

}
