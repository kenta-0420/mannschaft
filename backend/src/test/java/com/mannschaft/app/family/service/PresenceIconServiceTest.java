package com.mannschaft.app.family.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.family.EventType;
import com.mannschaft.app.family.dto.PresenceIconRequest;
import com.mannschaft.app.family.dto.PresenceIconResponse;
import com.mannschaft.app.family.entity.TeamPresenceIconEntity;
import com.mannschaft.app.family.repository.TeamPresenceIconRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * {@link PresenceIconService} の単体テスト。
 * プレゼンスカスタムアイコンの取得・更新ロジックを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PresenceIconService 単体テスト")
class PresenceIconServiceTest {

    @Mock
    private TeamPresenceIconRepository teamPresenceIconRepository;

    @InjectMocks
    private PresenceIconService presenceIconService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long TEAM_ID = 1L;
    private static final Long USER_ID = 10L;

    private TeamPresenceIconEntity createIconEntity(EventType eventType, String icon) {
        return TeamPresenceIconEntity.builder()
                .teamId(TEAM_ID)
                .eventType(eventType)
                .icon(icon)
                .updatedBy(USER_ID)
                .build();
    }

    // ========================================
    // getIcons
    // ========================================

    @Nested
    @DisplayName("getIcons")
    class GetIcons {

        @Test
        @DisplayName("正常系: チームのアイコン一覧が返る")
        void getIcons_正常_アイコン一覧が返る() {
            // Given
            List<TeamPresenceIconEntity> icons = List.of(
                    createIconEntity(EventType.HOME, "🏠"),
                    createIconEntity(EventType.GOING_OUT, "🚗")
            );
            given(teamPresenceIconRepository.findByTeamId(TEAM_ID)).willReturn(icons);

            // When
            ApiResponse<List<PresenceIconResponse>> response = presenceIconService.getIcons(TEAM_ID);

            // Then
            assertThat(response.getData()).hasSize(2);
            assertThat(response.getData().get(0).getEventType()).isEqualTo("HOME");
            assertThat(response.getData().get(0).getIcon()).isEqualTo("🏠");
            assertThat(response.getData().get(1).getEventType()).isEqualTo("GOING_OUT");
            assertThat(response.getData().get(1).getIcon()).isEqualTo("🚗");
        }

        @Test
        @DisplayName("正常系: アイコン未設定で空リストが返る")
        void getIcons_未設定_空リストが返る() {
            // Given
            given(teamPresenceIconRepository.findByTeamId(TEAM_ID)).willReturn(List.of());

            // When
            ApiResponse<List<PresenceIconResponse>> response = presenceIconService.getIcons(TEAM_ID);

            // Then
            assertThat(response.getData()).isEmpty();
        }
    }

    // ========================================
    // updateIcons
    // ========================================

    @Nested
    @DisplayName("updateIcons")
    class UpdateIcons {

        @Test
        @DisplayName("正常系: 既存アイコンが更新される")
        void updateIcons_既存アイコン_更新される() {
            // Given
            TeamPresenceIconEntity existingIcon = createIconEntity(EventType.HOME, "🏠");
            given(teamPresenceIconRepository.findByTeamIdAndEventType(TEAM_ID, EventType.HOME))
                    .willReturn(Optional.of(existingIcon));

            PresenceIconRequest request = new PresenceIconRequest(
                    List.of(new PresenceIconRequest.IconEntry("HOME", "🏡"))
            );

            // getIcons の結果を返す
            given(teamPresenceIconRepository.findByTeamId(TEAM_ID))
                    .willReturn(List.of(existingIcon));

            // When
            ApiResponse<List<PresenceIconResponse>> response =
                    presenceIconService.updateIcons(TEAM_ID, USER_ID, request);

            // Then
            verify(teamPresenceIconRepository, never()).save(any());
            assertThat(response.getData()).isNotNull();
        }

        @Test
        @DisplayName("正常系: 新規アイコンが作成される")
        void updateIcons_新規アイコン_作成される() {
            // Given
            given(teamPresenceIconRepository.findByTeamIdAndEventType(TEAM_ID, EventType.GOING_OUT))
                    .willReturn(Optional.empty());
            given(teamPresenceIconRepository.save(any(TeamPresenceIconEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            PresenceIconRequest request = new PresenceIconRequest(
                    List.of(new PresenceIconRequest.IconEntry("GOING_OUT", "🚗"))
            );

            given(teamPresenceIconRepository.findByTeamId(TEAM_ID)).willReturn(List.of());

            // When
            ApiResponse<List<PresenceIconResponse>> response =
                    presenceIconService.updateIcons(TEAM_ID, USER_ID, request);

            // Then
            verify(teamPresenceIconRepository).save(any(TeamPresenceIconEntity.class));
            assertThat(response.getData()).isNotNull();
        }

        @Test
        @DisplayName("正常系: 複数アイコンを一括更新できる")
        void updateIcons_複数アイコン_一括更新() {
            // Given
            TeamPresenceIconEntity existingHome = createIconEntity(EventType.HOME, "🏠");
            given(teamPresenceIconRepository.findByTeamIdAndEventType(TEAM_ID, EventType.HOME))
                    .willReturn(Optional.of(existingHome));
            given(teamPresenceIconRepository.findByTeamIdAndEventType(TEAM_ID, EventType.GOING_OUT))
                    .willReturn(Optional.empty());
            given(teamPresenceIconRepository.save(any(TeamPresenceIconEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            PresenceIconRequest request = new PresenceIconRequest(List.of(
                    new PresenceIconRequest.IconEntry("HOME", "🏡"),
                    new PresenceIconRequest.IconEntry("GOING_OUT", "🚗")
            ));

            given(teamPresenceIconRepository.findByTeamId(TEAM_ID)).willReturn(List.of());

            // When
            presenceIconService.updateIcons(TEAM_ID, USER_ID, request);

            // Then
            verify(teamPresenceIconRepository, times(1)).save(any(TeamPresenceIconEntity.class));
        }

        @Test
        @DisplayName("異常系: 不正なイベントタイプでIllegalArgumentException")
        void updateIcons_不正なイベントタイプ_例外() {
            // Given
            PresenceIconRequest request = new PresenceIconRequest(
                    List.of(new PresenceIconRequest.IconEntry("INVALID_TYPE", "🏠"))
            );

            // When / Then
            assertThatThrownBy(() -> presenceIconService.updateIcons(TEAM_ID, USER_ID, request))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
