package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.recruitment.PenaltyApplyScope;
import com.mannschaft.app.recruitment.RecruitmentErrorCode;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.entity.RecruitmentPenaltySettingEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentUserPenaltyEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentNoShowRecordRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentPenaltySettingRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentUserPenaltyRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * {@link RecruitmentPenaltyService} の単体テスト。
 * §5.8 ペナルティ発動・解除ロジックを中心に検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RecruitmentPenaltyService 単体テスト")
class RecruitmentPenaltyServiceTest {

    @Mock
    private RecruitmentUserPenaltyRepository penaltyRepository;

    @Mock
    private RecruitmentPenaltySettingRepository settingRepository;

    @Mock
    private RecruitmentNoShowRecordRepository noShowRepository;

    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private RecruitmentPenaltyService service;

    private static final Long USER_ID = 1L;
    private static final Long ADMIN_ID = 2L;
    private static final Long SCOPE_ID = 10L;
    private static final Long PENALTY_ID = 100L;
    private static final RecruitmentScopeType SCOPE_TYPE = RecruitmentScopeType.TEAM;

    // ========================================
    // evaluateAndApplyPenalty
    // ========================================

    @Nested
    @DisplayName("evaluateAndApplyPenalty - §5.8 ペナルティ発動判定")
    class EvaluateAndApplyPenalty {

        @Test
        @DisplayName("ペナルティ設定なし → empty を返す")
        void evaluate_noSetting_returnsEmpty() {
            given(settingRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());

            Optional<RecruitmentUserPenaltyEntity> result =
                    service.evaluateAndApplyPenalty(USER_ID, SCOPE_TYPE, SCOPE_ID);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("ペナルティ設定が無効 → empty を返す")
        void evaluate_settingDisabled_returnsEmpty() {
            RecruitmentPenaltySettingEntity setting = RecruitmentPenaltySettingEntity.builder()
                    .scopeType(SCOPE_TYPE)
                    .scopeId(SCOPE_ID)
                    .build();
            // enabled=false がデフォルト値
            given(settingRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(setting));

            Optional<RecruitmentUserPenaltyEntity> result =
                    service.evaluateAndApplyPenalty(USER_ID, SCOPE_TYPE, SCOPE_ID);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("NO_SHOW 件数が閾値未満 → empty を返す")
        void evaluate_belowThreshold_returnsEmpty() {
            RecruitmentPenaltySettingEntity setting = RecruitmentPenaltySettingEntity.builder()
                    .scopeType(SCOPE_TYPE)
                    .scopeId(SCOPE_ID)
                    .build();
            // enabled=true, thresholdCount=3 に更新
            setting.update(true, 3, 180, 30, PenaltyApplyScope.THIS_SCOPE_ONLY, false, 14);
            given(settingRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(setting));
            // 件数 2 < 閾値 3
            given(noShowRepository.countConfirmedNoShows(eq(USER_ID), any(LocalDateTime.class)))
                    .willReturn(2L);

            Optional<RecruitmentUserPenaltyEntity> result =
                    service.evaluateAndApplyPenalty(USER_ID, SCOPE_TYPE, SCOPE_ID);

            assertThat(result).isEmpty();
        }
    }

    // ========================================
    // liftPenalty - §5.8 手動解除
    // ========================================

    @Nested
    @DisplayName("liftPenalty - §5.8 ペナルティ手動解除")
    class LiftPenalty {

        @Test
        @DisplayName("ペナルティが存在しない → PENALTY_NOT_FOUND")
        void liftPenalty_notFound_throws() {
            given(penaltyRepository.findById(PENALTY_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.liftPenalty(PENALTY_ID, ADMIN_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RecruitmentErrorCode.PENALTY_NOT_FOUND);
        }

        @Test
        @DisplayName("既に非アクティブなペナルティ → INVALID_STATE_TRANSITION")
        void liftPenalty_alreadyInactive_throws() {
            // 有効期限切れ（過去）で isActive() = false
            RecruitmentUserPenaltyEntity penalty = RecruitmentUserPenaltyEntity.builder()
                    .userId(USER_ID)
                    .scopeType(SCOPE_TYPE)
                    .scopeId(SCOPE_ID)
                    .startedAt(LocalDateTime.now().minusDays(60))
                    .expiresAt(LocalDateTime.now().minusDays(1)) // 期限切れ
                    .build();
            given(penaltyRepository.findById(PENALTY_ID)).willReturn(Optional.of(penalty));

            assertThatThrownBy(() -> service.liftPenalty(PENALTY_ID, ADMIN_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RecruitmentErrorCode.INVALID_STATE_TRANSITION);
        }
    }
}
