package com.mannschaft.app.reservation;

import com.mannschaft.app.reservation.entity.ReservationTeamSettingEntity;
import com.mannschaft.app.reservation.repository.ReservationTeamSettingRepository;
import com.mannschaft.app.reservation.service.ReservationTeamSettingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link ReservationTeamSettingService} の単体テスト。
 * 予約公開設定の取得・既定値・upsert を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationTeamSettingService 単体テスト")
class ReservationTeamSettingServiceTest {

    @Mock
    private ReservationTeamSettingRepository settingRepository;

    @InjectMocks
    private ReservationTeamSettingService service;

    private static final Long TEAM_ID = 42L;

    @Nested
    @DisplayName("getOrDefault / isAllowPublic")
    class GetOrDefault {

        @Test
        @DisplayName("設定なし: 既定で allowPublicReservation=false を返す（DB書き込みなし）")
        void 設定なし_既定false() {
            given(settingRepository.findByTeamId(TEAM_ID)).willReturn(Optional.empty());

            ReservationTeamSettingEntity result = service.getOrDefault(TEAM_ID);

            assertThat(result.getTeamId()).isEqualTo(TEAM_ID);
            assertThat(result.isAllowPublicReservation()).isFalse();
            assertThat(service.isAllowPublic(TEAM_ID)).isFalse();
        }

        @Test
        @DisplayName("設定あり(公開): isAllowPublic は true を返す")
        void 設定あり_公開() {
            ReservationTeamSettingEntity entity = ReservationTeamSettingEntity.builder()
                    .teamId(TEAM_ID)
                    .allowPublicReservation(true)
                    .build();
            given(settingRepository.findByTeamId(TEAM_ID)).willReturn(Optional.of(entity));

            assertThat(service.isAllowPublic(TEAM_ID)).isTrue();
        }
    }

    @Nested
    @DisplayName("updateAllowPublic (upsert)")
    class UpdateAllowPublic {

        @Test
        @DisplayName("新規: レコードがなければ作成する")
        void 新規作成() {
            given(settingRepository.findByTeamId(TEAM_ID)).willReturn(Optional.empty());
            given(settingRepository.save(any(ReservationTeamSettingEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            service.updateAllowPublic(TEAM_ID, true);

            ArgumentCaptor<ReservationTeamSettingEntity> captor =
                    ArgumentCaptor.forClass(ReservationTeamSettingEntity.class);
            verify(settingRepository).save(captor.capture());
            assertThat(captor.getValue().getTeamId()).isEqualTo(TEAM_ID);
            assertThat(captor.getValue().isAllowPublicReservation()).isTrue();
        }

        @Test
        @DisplayName("更新: 既存レコードがあれば値を更新する")
        void 既存更新() {
            ReservationTeamSettingEntity existing = ReservationTeamSettingEntity.builder()
                    .teamId(TEAM_ID)
                    .allowPublicReservation(false)
                    .build();
            given(settingRepository.findByTeamId(TEAM_ID)).willReturn(Optional.of(existing));
            given(settingRepository.save(any(ReservationTeamSettingEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            service.updateAllowPublic(TEAM_ID, true);

            assertThat(existing.isAllowPublicReservation()).isTrue();
            verify(settingRepository).save(existing);
        }
    }
}
