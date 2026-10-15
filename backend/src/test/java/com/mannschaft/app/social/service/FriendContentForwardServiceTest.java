package com.mannschaft.app.social.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.social.SocialErrorCode;
import com.mannschaft.app.social.entity.FriendContentForwardEntity;
import com.mannschaft.app.social.repository.FriendContentForwardRepository;
import com.mannschaft.app.social.repository.TeamFriendRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.PostStatus;
import com.mannschaft.app.timeline.PostedAsType;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * {@link FriendContentForwardService} の単体テスト。
 * 転送取消（revoke）を中心に検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FriendContentForwardService 単体テスト")
class FriendContentForwardServiceTest {

    @Mock
    private FriendContentForwardRepository forwardRepository;

    @Mock
    private TeamFriendRepository teamFriendRepository;

    @Mock
    private TimelinePostRepository timelinePostRepository;

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private FriendContentForwardService friendContentForwardService;

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long SOURCE_TEAM_ID = 20L;
    private static final Long FORWARD_ID = 50L;
    private static final Long FORWARDED_POST_ID = 400L;

    // ========================================
    // revoke
    // ========================================
    @Nested
    @DisplayName("revoke")
    class Revoke {

        @Test
        @DisplayName("正常系: is_revoked = true に更新、転送先 timeline_posts が論理削除される")
        void 正常_取消() {
            // given
            FriendContentForwardEntity forward = buildForward(FORWARD_ID, TEAM_ID, SOURCE_TEAM_ID, false);
            TimelinePostEntity forwardedPost = buildPost(FORWARDED_POST_ID, TEAM_ID);

            given(forwardRepository.findById(FORWARD_ID)).willReturn(Optional.of(forward));
            given(forwardRepository.save(any(FriendContentForwardEntity.class))).willReturn(forward);
            given(timelinePostRepository.findById(FORWARDED_POST_ID)).willReturn(Optional.of(forwardedPost));
            given(timelinePostRepository.save(any(TimelinePostEntity.class))).willReturn(forwardedPost);
            doNothing().when(auditLogService).record(anyString(), anyLong(), isNull(),
                    anyLong(), isNull(), isNull(), isNull(), isNull(), anyString());

            // when
            friendContentForwardService.revoke(TEAM_ID, FORWARD_ID, USER_ID);

            // then
            assertThat(forward.getIsRevoked()).isTrue();
            assertThat(forward.getRevokedBy()).isEqualTo(USER_ID);
            verify(forwardRepository).save(forward);
            verify(timelinePostRepository).save(any(TimelinePostEntity.class));
        }

        @Test
        @DisplayName("異常系: 既に取り消し済み → 冪等に成功（ログのみ・例外なし）")
        void 既に取り消し済み_冪等成功() {
            // given: is_revoked = true の転送履歴
            FriendContentForwardEntity revokedForward = buildForward(FORWARD_ID, TEAM_ID, SOURCE_TEAM_ID, true);
            given(forwardRepository.findById(FORWARD_ID)).willReturn(Optional.of(revokedForward));

            // when: 例外が発生しないことを確認（冪等動作）
            friendContentForwardService.revoke(TEAM_ID, FORWARD_ID, USER_ID);

            // then: save は呼ばれない（冪等：既に取消済みのためスキップ）
            verify(forwardRepository, never()).save(any());
            verify(timelinePostRepository, never()).save(any());
        }

        @Test
        @DisplayName("異常系: 権限不足 → BusinessException")
        void 権限不足_例外() {
            // given
            doThrow(new BusinessException(SocialErrorCode.FRIEND_INSUFFICIENT_PERMISSION))
                    .when(accessControlService).checkPermission(USER_ID, TEAM_ID, "TEAM", "MANAGE_FRIEND_TEAMS");

            // when & then
            assertThatThrownBy(() ->
                    friendContentForwardService.revoke(TEAM_ID, FORWARD_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SocialErrorCode.FRIEND_INSUFFICIENT_PERMISSION));

            verify(forwardRepository, never()).findById(any());
        }

        @Test
        @DisplayName("異常系: 他チームの転送を取り消そうとする → BusinessException(FRIEND_FORWARD_NOT_FOUND)")
        void 他チームの転送_IDOR_例外() {
            // given: TEAM_ID=10 が取り消そうとするが、転送の forwardingTeamId は 99L（別チーム）
            Long otherTeamId = 99L;
            FriendContentForwardEntity otherTeamForward = buildForward(FORWARD_ID, otherTeamId, SOURCE_TEAM_ID, false);

            given(forwardRepository.findById(FORWARD_ID)).willReturn(Optional.of(otherTeamForward));

            // when & then
            assertThatThrownBy(() ->
                    friendContentForwardService.revoke(TEAM_ID, FORWARD_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SocialErrorCode.FRIEND_FORWARD_NOT_FOUND));

            verify(forwardRepository, never()).save(any());
        }
    }

    // ========================================
    // ヘルパー
    // ========================================

    private FriendContentForwardEntity buildForward(
            Long id, Long forwardingTeamId, Long sourceTeamId, boolean isRevoked) {
        FriendContentForwardEntity entity = FriendContentForwardEntity.builder()
                .sourcePostId(300L)
                .sourceTeamId(sourceTeamId)
                .forwardingTeamId(forwardingTeamId)
                .forwardedPostId(FORWARDED_POST_ID)
                .target("MEMBER")
                .isRevoked(isRevoked)
                .forwardedBy(USER_ID)
                .build();
        ReflectionTestUtils.setField(entity, "id", id);
        if (isRevoked) {
            entity.revoke(USER_ID);
        }
        return entity;
    }

    private TimelinePostEntity buildPost(Long id, Long scopeId) {
        TimelinePostEntity entity = TimelinePostEntity.builder()
                .scopeType(PostScopeType.FRIEND_FORWARD)
                .scopeId(scopeId)
                .userId(USER_ID)
                .postedAsType(PostedAsType.TEAM)
                .postedAsId(scopeId)
                .content("転送テスト投稿")
                .status(PostStatus.PUBLISHED)
                .shareWithFriends(false)
                .build();
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }
}
