package com.mannschaft.app.payment;

import com.mannschaft.app.payment.entity.PaymentBeneficiarySettingEntity;
import com.mannschaft.app.payment.repository.PaymentBeneficiarySettingRepository;
import com.mannschaft.app.payment.service.PaymentBeneficiarySettingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link PaymentBeneficiarySettingService} 単体テスト（会費受益者制限設定・既定 ON）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentBeneficiarySettingService 単体テスト")
class PaymentBeneficiarySettingServiceTest {

    @Mock private PaymentBeneficiarySettingRepository settingRepository;

    @InjectMocks private PaymentBeneficiarySettingService service;

    private static final Long TEAM_ID = 500L;
    private static final Long ORG_ID = 900L;

    @Test
    @DisplayName("[AC-S1] 設定行が無いチーム → isMemberOnly=true（既定 ON・会員のみ）")
    void AC_S1_行無しは既定true_team() {
        given(settingRepository.findByTeamId(TEAM_ID)).willReturn(Optional.empty());

        assertThat(service.isMemberOnly(TEAM_ID, null)).isTrue();
    }

    @Test
    @DisplayName("[AC-S1] 設定行が無い組織 → isMemberOnly=true（既定 ON・会員のみ）")
    void AC_S1_行無しは既定true_org() {
        given(settingRepository.findByOrganizationId(ORG_ID)).willReturn(Optional.empty());

        assertThat(service.isMemberOnly(null, ORG_ID)).isTrue();
    }

    @Test
    @DisplayName("[AC-S1] 設定行が beneficiaryMemberOnly=false → isMemberOnly=false（応援者も可）")
    void AC_S1_行ありfalseはfalse() {
        PaymentBeneficiarySettingEntity entity = PaymentBeneficiarySettingEntity.builder()
                .teamId(TEAM_ID).beneficiaryMemberOnly(false).build();
        given(settingRepository.findByTeamId(TEAM_ID)).willReturn(Optional.of(entity));

        assertThat(service.isMemberOnly(TEAM_ID, null)).isFalse();
    }

    @Test
    @DisplayName("[AC-S3] updateSetting: 行が無ければ新規作成（upsert insert）")
    void AC_S3_upsert新規作成() {
        given(settingRepository.findByTeamId(TEAM_ID)).willReturn(Optional.empty());
        ArgumentCaptor<PaymentBeneficiarySettingEntity> captor =
                ArgumentCaptor.forClass(PaymentBeneficiarySettingEntity.class);
        given(settingRepository.save(captor.capture())).willAnswer(inv -> inv.getArgument(0));

        service.updateSetting(TEAM_ID, null, false);

        PaymentBeneficiarySettingEntity saved = captor.getValue();
        assertThat(saved.getTeamId()).isEqualTo(TEAM_ID);
        assertThat(saved.getOrganizationId()).isNull();
        assertThat(saved.getBeneficiaryMemberOnly()).isFalse();
    }

    @Test
    @DisplayName("[AC-S3] updateSetting: 行があれば値を更新（upsert update・false↔true）")
    void AC_S3_upsert更新() {
        PaymentBeneficiarySettingEntity existing = PaymentBeneficiarySettingEntity.builder()
                .teamId(TEAM_ID).beneficiaryMemberOnly(false).build();
        given(settingRepository.findByTeamId(TEAM_ID)).willReturn(Optional.of(existing));
        given(settingRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.updateSetting(TEAM_ID, null, true);

        assertThat(existing.getBeneficiaryMemberOnly()).isTrue();
        verify(settingRepository).save(existing);
    }

    @Test
    @DisplayName("[AC-S3] updateSetting: team と org の両方指定は IllegalArgumentException")
    void AC_S3_両方指定は例外() {
        assertThatThrownBy(() -> service.updateSetting(TEAM_ID, ORG_ID, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("[AC-S3] updateSetting: team も org も null は IllegalArgumentException")
    void AC_S3_両方nullは例外() {
        assertThatThrownBy(() -> service.updateSetting(null, null, true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
