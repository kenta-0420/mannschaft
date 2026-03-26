package com.mannschaft.app.moderation.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.moderation.ModerationExtErrorCode;
import com.mannschaft.app.moderation.ModerationExtMapper;
import com.mannschaft.app.moderation.ViolationType;
import com.mannschaft.app.moderation.dto.UserViolationHistoryResponse;
import com.mannschaft.app.moderation.dto.ViolationResponse;
import com.mannschaft.app.moderation.entity.ModerationSettingsEntity;
import com.mannschaft.app.moderation.entity.UserViolationEntity;
import com.mannschaft.app.moderation.repository.ModerationSettingsRepository;
import com.mannschaft.app.moderation.repository.UserViolationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link UserViolationService} の単体テスト。
 * 違反履歴・自主修正・ヤバいやつ判定を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserViolationService 単体テスト")
class UserViolationServiceTest {

    @Mock
    private UserViolationRepository violationRepository;

    @Mock
    private ModerationSettingsRepository settingsRepository;

    @Mock
    private ModerationExtMapper mapper;

    @InjectMocks
    private UserViolationService userViolationService;

    private static final Long USER_ID = 100L;
    private static final Long ACTION_ID = 10L;

    private UserViolationEntity createViolation(ViolationType type) {
        return UserViolationEntity.builder()
                .userId(USER_ID)
                .reportId(1L)
                .actionId(ACTION_ID)
                .violationType(type)
                .reason("テスト違反")
                .build();
    }

    // ========================================
    // getViolationHistory
    // ========================================
    @Nested
    @DisplayName("getViolationHistory")
    class GetViolationHistory {

        @Test
        @DisplayName("正常系: 違反履歴を取得できる")
        void 違反履歴を取得できる() {
            // given
            List<UserViolationEntity> violations = List.of(createViolation(ViolationType.WARNING));

            given(violationRepository.findByUserIdOrderByCreatedAtDesc(USER_ID)).willReturn(violations);
            given(violationRepository.countByUserIdAndViolationTypeAndIsActiveTrue(USER_ID, ViolationType.WARNING))
                    .willReturn(1L);
            given(violationRepository.countByUserIdAndViolationTypeAndIsActiveTrue(USER_ID, ViolationType.CONTENT_DELETE))
                    .willReturn(0L);
            given(violationRepository.countByUserIdAndIsActiveTrue(USER_ID)).willReturn(1L);
            given(settingsRepository.findBySettingKey("yabai_violation_threshold")).willReturn(Optional.empty());
            given(mapper.toViolationResponseList(violations)).willReturn(List.of());

            // when
            UserViolationHistoryResponse result = userViolationService.getViolationHistory(USER_ID);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getActiveWarningCount()).isEqualTo(1L);
            assertThat(result.isYabai()).isFalse();
        }

        @Test
        @DisplayName("正常系: 有効違反がしきい値以上の場合はヤバいやつ判定される")
        void 有効違反がしきい値以上の場合はヤバいやつ判定される() {
            // given
            given(violationRepository.findByUserIdOrderByCreatedAtDesc(USER_ID)).willReturn(List.of());
            given(violationRepository.countByUserIdAndViolationTypeAndIsActiveTrue(USER_ID, ViolationType.WARNING))
                    .willReturn(2L);
            given(violationRepository.countByUserIdAndViolationTypeAndIsActiveTrue(USER_ID, ViolationType.CONTENT_DELETE))
                    .willReturn(1L);
            given(violationRepository.countByUserIdAndIsActiveTrue(USER_ID)).willReturn(3L);
            given(settingsRepository.findBySettingKey("yabai_violation_threshold")).willReturn(Optional.empty());
            given(mapper.toViolationResponseList(any())).willReturn(List.of());

            // when
            UserViolationHistoryResponse result = userViolationService.getViolationHistory(USER_ID);

            // then
            assertThat(result.isYabai()).isTrue();
        }
    }

    // ========================================
    // selfCorrect
    // ========================================
    @Nested
    @DisplayName("selfCorrect")
    class SelfCorrect {

        @Test
        @DisplayName("正常系: WARNING自主修正を完了できる")
        void WARNING自主修正を完了できる() {
            // given
            UserViolationEntity violation = createViolation(ViolationType.WARNING);
            // BaseEntity の createdAt を設定
            try {
                java.lang.reflect.Method m = com.mannschaft.app.common.BaseEntity.class.getDeclaredMethod("onCreate");
                m.setAccessible(true);
                m.invoke(violation);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            ViolationResponse expected = new ViolationResponse(1L, USER_ID, 1L, ACTION_ID,
                    "WARNING", "テスト違反", null, false, null);

            given(violationRepository.findByActionId(ACTION_ID)).willReturn(violation);
            given(violationRepository.save(any(UserViolationEntity.class))).willReturn(violation);
            given(settingsRepository.findBySettingKey("self_correct_window_days")).willReturn(Optional.empty());
            given(mapper.toViolationResponse(any(UserViolationEntity.class))).willReturn(expected);

            // when
            ViolationResponse result = userViolationService.selfCorrect(ACTION_ID, USER_ID, "修正しました");

            // then
            assertThat(result).isNotNull();
            verify(violationRepository).save(any(UserViolationEntity.class));
        }

        @Test
        @DisplayName("異常系: 違反が見つからない場合はエラー")
        void 違反が見つからない場合はエラー() {
            // given
            given(violationRepository.findByActionId(ACTION_ID)).willReturn(null);

            // when & then
            assertThatThrownBy(() -> userViolationService.selfCorrect(ACTION_ID, USER_ID, "修正"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ModerationExtErrorCode.VIOLATION_NOT_FOUND));
        }

        @Test
        @DisplayName("異常系: 他人の違反を修正しようとするとエラー")
        void 他人の違反を修正しようとするとエラー() {
            // given
            UserViolationEntity violation = createViolation(ViolationType.WARNING);
            given(violationRepository.findByActionId(ACTION_ID)).willReturn(violation);

            // when & then
            assertThatThrownBy(() -> userViolationService.selfCorrect(ACTION_ID, 999L, "修正"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ModerationExtErrorCode.VIOLATION_NOT_FOUND));
        }

        @Test
        @DisplayName("異常系: WARNING以外の違反は自主修正不可")
        void WARNING以外の違反は自主修正不可() {
            // given
            UserViolationEntity violation = createViolation(ViolationType.CONTENT_DELETE);
            given(violationRepository.findByActionId(ACTION_ID)).willReturn(violation);

            // when & then
            assertThatThrownBy(() -> userViolationService.selfCorrect(ACTION_ID, USER_ID, "修正"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ModerationExtErrorCode.SELF_CORRECT_EXPIRED));
        }
    }

    // ========================================
    // isYabaiUser
    // ========================================
    @Nested
    @DisplayName("isYabaiUser")
    class IsYabaiUser {

        @Test
        @DisplayName("正常系: しきい値以上でtrue")
        void しきい値以上でtrue() {
            // given
            given(violationRepository.countByUserIdAndIsActiveTrue(USER_ID)).willReturn(3L);
            given(settingsRepository.findBySettingKey("yabai_violation_threshold")).willReturn(Optional.empty());

            // when
            boolean result = userViolationService.isYabaiUser(USER_ID);

            // then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("正常系: しきい値未満でfalse")
        void しきい値未満でfalse() {
            // given
            given(violationRepository.countByUserIdAndIsActiveTrue(USER_ID)).willReturn(2L);
            given(settingsRepository.findBySettingKey("yabai_violation_threshold")).willReturn(Optional.empty());

            // when
            boolean result = userViolationService.isYabaiUser(USER_ID);

            // then
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("正常系: カスタムしきい値を使用する")
        void カスタムしきい値を使用する() {
            // given
            ModerationSettingsEntity setting = ModerationSettingsEntity.builder()
                    .settingKey("yabai_violation_threshold").settingValue("5").build();
            given(violationRepository.countByUserIdAndIsActiveTrue(USER_ID)).willReturn(4L);
            given(settingsRepository.findBySettingKey("yabai_violation_threshold"))
                    .willReturn(Optional.of(setting));

            // when
            boolean result = userViolationService.isYabaiUser(USER_ID);

            // then
            assertThat(result).isFalse();
        }
    }

    // ========================================
    // unflagYabaiUser
    // ========================================
    @Nested
    @DisplayName("unflagYabaiUser")
    class UnflagYabaiUser {

        @Test
        @DisplayName("正常系: ヤバいやつ手動解除ができる")
        void ヤバいやつ手動解除ができる() {
            // given
            given(violationRepository.deactivateAllByUserId(USER_ID)).willReturn(3);

            // when
            int result = userViolationService.unflagYabaiUser(USER_ID);

            // then
            assertThat(result).isEqualTo(3);
        }
    }
}
