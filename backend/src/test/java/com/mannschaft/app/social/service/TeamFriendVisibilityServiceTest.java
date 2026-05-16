package com.mannschaft.app.social.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.social.SocialErrorCode;
import com.mannschaft.app.social.entity.TeamFriendEntity;
import com.mannschaft.app.social.repository.TeamFriendRepository;
import org.junit.jupiter.api.DisplayName;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;

/**
 * {@link TeamFriendVisibilityService} の単体テスト（リファクタリング第4弾 Phase 4-B で分離）。
 *
 * <p>ADMIN 権限チェック・IDOR チェック・isPublic 更新・監査ログ記録を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TeamFriendVisibilityService 単体テスト")
class TeamFriendVisibilityServiceTest {

    @Mock
    private TeamFriendRepository teamFriendRepository;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private TeamFriendVisibilityService teamFriendVisibilityService;

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long TARGET_TEAM_ID = 20L;

    @Test
    @DisplayName("正常系: isPublic が更新される")
    void 公開設定変更_正常() {
        // given
        TeamFriendEntity friend = buildTeamFriend(TEAM_ID, TARGET_TEAM_ID, 1L);
        Long teamFriendId = 1L;

        given(accessControlService.isAdmin(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
        given(teamFriendRepository.findById(teamFriendId)).willReturn(Optional.of(friend));
        given(teamFriendRepository.save(any(TeamFriendEntity.class))).willReturn(friend);
        doNothing().when(auditLogService).record(anyString(), anyLong(), isNull(),
                anyLong(), isNull(), isNull(), isNull(), isNull(), anyString());

        // when
        teamFriendVisibilityService.setVisibility(TEAM_ID, teamFriendId, true, USER_ID);

        // then
        verify(teamFriendRepository).save(any(TeamFriendEntity.class));
        assertThat(friend.getIsPublic()).isTrue();
    }

    @Test
    @DisplayName("異常系: ADMIN 権限なし → BusinessException(FRIEND_VISIBILITY_ADMIN_ONLY)")
    void 権限不足_公開設定変更_例外() {
        // given
        given(accessControlService.isAdmin(USER_ID, TEAM_ID, "TEAM")).willReturn(false);

        // when & then
        assertThatThrownBy(() ->
                teamFriendVisibilityService.setVisibility(TEAM_ID, 1L, true, USER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(SocialErrorCode.FRIEND_VISIBILITY_ADMIN_ONLY));
    }

    @Test
    @DisplayName("異常系: フレンド関係不存在 → BusinessException(FRIEND_RELATION_NOT_FOUND)")
    void フレンド関係不存在_例外() {
        // given
        Long teamFriendId = 9999L;
        given(accessControlService.isAdmin(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
        given(teamFriendRepository.findById(teamFriendId)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() ->
                teamFriendVisibilityService.setVisibility(TEAM_ID, teamFriendId, true, USER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(SocialErrorCode.FRIEND_RELATION_NOT_FOUND));
    }

    @Test
    @DisplayName("異常系: 他チームの teamFriendId → BusinessException(FRIEND_VISIBILITY_ADMIN_ONLY)")
    void IDOR_他チーム関係_例外() {
        // given
        Long teamFriendId = 1L;
        // 自チーム=999 として、teamFriendEntity は他チームのペア
        TeamFriendEntity otherFriend = buildTeamFriend(100L, 200L, teamFriendId);
        given(accessControlService.isAdmin(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
        given(teamFriendRepository.findById(teamFriendId)).willReturn(Optional.of(otherFriend));

        // when & then
        assertThatThrownBy(() ->
                teamFriendVisibilityService.setVisibility(TEAM_ID, teamFriendId, true, USER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(SocialErrorCode.FRIEND_VISIBILITY_ADMIN_ONLY));
    }

    private TeamFriendEntity buildTeamFriend(Long teamAId, Long teamBId, Long id) {
        long aId = Math.min(teamAId, teamBId);
        long bId = Math.max(teamAId, teamBId);
        TeamFriendEntity entity = TeamFriendEntity.builder()
                .teamAId(aId)
                .teamBId(bId)
                .aFollowId(100L)
                .bFollowId(200L)
                .establishedAt(LocalDateTime.now())
                .isPublic(false)
                .build();
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }
}
