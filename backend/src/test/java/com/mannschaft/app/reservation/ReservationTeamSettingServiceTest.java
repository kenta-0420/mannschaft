package com.mannschaft.app.reservation;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.reservation.entity.ReservationTeamSettingEntity;
import com.mannschaft.app.reservation.repository.ReservationTeamSettingRepository;
import com.mannschaft.app.reservation.service.ReservationTeamSettingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
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

    @Nested
    @DisplayName("updateResourceName (upsert・F03.4.5 §5)")
    class UpdateResourceName {

        @Test
        @DisplayName("新規: レコードがなければ既定DEFAULTから作成し指定したプリセットで保存する")
        void 新規作成_プリセット指定() {
            given(settingRepository.findByTeamId(TEAM_ID)).willReturn(Optional.empty());
            given(settingRepository.save(any(ReservationTeamSettingEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            ReservationTeamSettingEntity result =
                    service.updateResourceName(TEAM_ID, ReservationResourceNameType.SEAT, null);

            assertThat(result.getTeamId()).isEqualTo(TEAM_ID);
            assertThat(result.getResourceNameType()).isEqualTo(ReservationResourceNameType.SEAT);
            assertThat(result.getResourceNameCustom()).isNull();
        }

        @Test
        @DisplayName("更新: 既存レコードのプリセットのみ変更しCUSTOM以外なのでcustomはnullへ正規化される")
        void 既存更新_プリセット変更_customはnull正規化() {
            ReservationTeamSettingEntity existing = ReservationTeamSettingEntity.builder()
                    .teamId(TEAM_ID)
                    .resourceNameType(ReservationResourceNameType.CUSTOM)
                    .resourceNameCustom("施術台")
                    .build();
            given(settingRepository.findByTeamId(TEAM_ID)).willReturn(Optional.of(existing));
            given(settingRepository.save(any(ReservationTeamSettingEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            // CUSTOM から SEAT へ切替。customRaw は指定していない。
            service.updateResourceName(TEAM_ID, ReservationResourceNameType.SEAT, null);

            assertThat(existing.getResourceNameType()).isEqualTo(ReservationResourceNameType.SEAT);
            assertThat(existing.getResourceNameCustom())
                    .as("CUSTOM 以外へ切り替えた場合は customRaw 未指定でも旧値を引き継がず null へ正規化される")
                    .isNull();
        }

        @Test
        @DisplayName("CUSTOM選択かつcustom指定: サニタイズ後の値を保存する")
        void CUSTOM選択_custom指定_サニタイズ後保存() {
            given(settingRepository.findByTeamId(TEAM_ID)).willReturn(Optional.empty());
            given(settingRepository.save(any(ReservationTeamSettingEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            ReservationTeamSettingEntity result = service.updateResourceName(
                    TEAM_ID, ReservationResourceNameType.CUSTOM, "<b>施術台</b>");

            assertThat(result.getResourceNameType()).isEqualTo(ReservationResourceNameType.CUSTOM);
            assertThat(result.getResourceNameCustom())
                    .as("HtmlSanitizer.sanitizePlainText によりタグが除去されていること")
                    .isEqualTo("施術台");
        }

        @Test
        @DisplayName("CUSTOM選択かつcustom未指定(新規): 400=COMMON_001で拒否されsaveは呼ばれない")
        void CUSTOM選択_custom未指定_新規_400拒否() {
            given(settingRepository.findByTeamId(TEAM_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() ->
                    service.updateResourceName(TEAM_ID, ReservationResourceNameType.CUSTOM, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_001);
            verify(settingRepository, never()).save(any());
        }

        @Test
        @DisplayName("CUSTOM選択かつcustomがタグのみでサニタイズ後空: 400=COMMON_001で拒否される")
        void CUSTOM選択_customタグのみ_サニタイズ後空_400拒否() {
            given(settingRepository.findByTeamId(TEAM_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() ->
                    service.updateResourceName(TEAM_ID, ReservationResourceNameType.CUSTOM, "<b></b>"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_001);
            verify(settingRepository, never()).save(any());
        }

        @Test
        @DisplayName("CUSTOM選択かつ空白のみ: 400=COMMON_001で拒否される")
        void CUSTOM選択_空白のみ_400拒否() {
            given(settingRepository.findByTeamId(TEAM_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() ->
                    service.updateResourceName(TEAM_ID, ReservationResourceNameType.CUSTOM, "   "))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_001);
        }

        @Test
        @DisplayName("既存CUSTOM設定へtypeを指定せずcustomのみ更新: 既存typeを維持しつつcustomを差し替える")
        void 既存CUSTOM_customのみ更新() {
            ReservationTeamSettingEntity existing = ReservationTeamSettingEntity.builder()
                    .teamId(TEAM_ID)
                    .resourceNameType(ReservationResourceNameType.CUSTOM)
                    .resourceNameCustom("旧呼称")
                    .build();
            given(settingRepository.findByTeamId(TEAM_ID)).willReturn(Optional.of(existing));
            given(settingRepository.save(any(ReservationTeamSettingEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            service.updateResourceName(TEAM_ID, null, "新呼称");

            assertThat(existing.getResourceNameType()).isEqualTo(ReservationResourceNameType.CUSTOM);
            assertThat(existing.getResourceNameCustom()).isEqualTo("新呼称");
        }

        @Test
        @DisplayName("両方null: 既存値を据え置いたまま保存する（呼び出し側の据え置き分岐は上位で制御・本メソッドは単に反映）")
        void 両方null_既存値据え置き() {
            ReservationTeamSettingEntity existing = ReservationTeamSettingEntity.builder()
                    .teamId(TEAM_ID)
                    .resourceNameType(ReservationResourceNameType.LANE)
                    .build();
            given(settingRepository.findByTeamId(TEAM_ID)).willReturn(Optional.of(existing));
            given(settingRepository.save(any(ReservationTeamSettingEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            service.updateResourceName(TEAM_ID, null, null);

            assertThat(existing.getResourceNameType()).isEqualTo(ReservationResourceNameType.LANE);
            assertThat(existing.getResourceNameCustom()).isNull();
        }

        @ParameterizedTest
        @DisplayName("CUSTOM以外の全プリセット: customは常にnullへ正規化される")
        @EnumSource(value = ReservationResourceNameType.class, names = "CUSTOM", mode = EnumSource.Mode.EXCLUDE)
        void CUSTOM以外の全プリセット_customはnullへ正規化(ReservationResourceNameType type) {
            given(settingRepository.findByTeamId(TEAM_ID)).willReturn(Optional.empty());
            given(settingRepository.save(any(ReservationTeamSettingEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            // customRaw を指定しても CUSTOM 以外なら無視されて null になる。
            ReservationTeamSettingEntity result = service.updateResourceName(TEAM_ID, type, "無視されるべき値");

            assertThat(result.getResourceNameType()).isEqualTo(type);
            assertThat(result.getResourceNameCustom()).isNull();
        }
    }
}
