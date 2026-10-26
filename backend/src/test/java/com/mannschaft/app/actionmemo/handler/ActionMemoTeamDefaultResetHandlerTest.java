package com.mannschaft.app.actionmemo.handler;

import com.mannschaft.app.actionmemo.entity.UserActionMemoSettingsEntity;
import com.mannschaft.app.actionmemo.repository.UserActionMemoSettingsRepository;
import com.mannschaft.app.team.event.TeamMemberRemovedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ActionMemoTeamDefaultResetHandler} 単体テスト（設計書 §10.1 / §4.3.4）。
 *
 * <p>チームメンバー脱退イベントを受け、行動メモ設定の {@code default_post_team_id} が
 * 脱退対象チームに一致する場合のみ NULL にリセットされること、それ以外のケースでは
 * 副作用を起こさないことを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ActionMemoTeamDefaultResetHandler 単体テスト")
class ActionMemoTeamDefaultResetHandlerTest {

    @Mock
    private UserActionMemoSettingsRepository settingsRepository;

    @InjectMocks
    private ActionMemoTeamDefaultResetHandler handler;

    private static final Long USER_ID = 100L;
    private static final Long TEAM_ID = 200L;

    @Test
    @DisplayName("onTeamMemberRemoved: defaultPostTeamId が脱退チームと一致 → NULL にリセットされる")
    void onTeamMemberRemoved_matchesDefaultTeam_setsNull() {
        UserActionMemoSettingsEntity settings = UserActionMemoSettingsEntity.builder()
                .userId(USER_ID)
                .moodEnabled(false)
                .defaultPostTeamId(TEAM_ID)
                .build();
        given(settingsRepository.findById(USER_ID)).willReturn(Optional.of(settings));

        handler.onTeamMemberRemoved(new TeamMemberRemovedEvent(USER_ID, TEAM_ID));

        ArgumentCaptor<UserActionMemoSettingsEntity> captor =
                ArgumentCaptor.forClass(UserActionMemoSettingsEntity.class);
        verify(settingsRepository).save(captor.capture());
        assertThat(captor.getValue().getDefaultPostTeamId()).isNull();
    }

    @Test
    @DisplayName("onTeamMemberRemoved: 異なるチームから脱退 → 何もしない")
    void onTeamMemberRemoved_differentTeam_doesNothing() {
        Long otherTeamId = 999L;
        UserActionMemoSettingsEntity settings = UserActionMemoSettingsEntity.builder()
                .userId(USER_ID)
                .moodEnabled(false)
                .defaultPostTeamId(TEAM_ID)
                .build();
        given(settingsRepository.findById(USER_ID)).willReturn(Optional.of(settings));

        handler.onTeamMemberRemoved(new TeamMemberRemovedEvent(USER_ID, otherTeamId));

        verify(settingsRepository, never()).save(org.mockito.ArgumentMatchers.any());
        // 元の値が保持されている
        assertThat(settings.getDefaultPostTeamId()).isEqualTo(TEAM_ID);
    }

    @Test
    @DisplayName("onTeamMemberRemoved: 設定レコードなし → no-op（save が呼ばれない）")
    void onTeamMemberRemoved_noSettingsRecord_noOp() {
        given(settingsRepository.findById(USER_ID)).willReturn(Optional.empty());

        handler.onTeamMemberRemoved(new TeamMemberRemovedEvent(USER_ID, TEAM_ID));

        verify(settingsRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
