package com.mannschaft.app.moderation.service;

import com.mannschaft.app.moderation.ModerationExtMapper;
import com.mannschaft.app.moderation.ViolationType;
import com.mannschaft.app.moderation.dto.ViolationResponse;
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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * {@link UserViolationService} の追加単体テスト。
 * countActiveViolations / countYabaiUsers / getActiveViolations を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserViolationService 追加単体テスト")
class UserViolationServiceAdditionalTest {

    @Mock
    private UserViolationRepository violationRepository;

    @Mock
    private ModerationSettingsRepository settingsRepository;

    @Mock
    private ModerationExtMapper mapper;

    @InjectMocks
    private UserViolationService userViolationService;

    private static final Long USER_ID = 100L;

    private UserViolationEntity createViolation(ViolationType type) {
        return UserViolationEntity.builder()
                .userId(USER_ID)
                .reportId(1L)
                .actionId(10L)
                .violationType(type)
                .reason("テスト違反")
                .build();
    }

    // ========================================
    // countActiveViolations
    // ========================================

    @Nested
    @DisplayName("countActiveViolations")
    class CountActiveViolations {

        @Test
        @DisplayName("正常系: 有効な違反総数を取得できる")
        void 有効な違反総数を取得できる() {
            // given
            given(violationRepository.countByIsActiveTrue()).willReturn(15L);

            // when
            long result = userViolationService.countActiveViolations();

            // then
            assertThat(result).isEqualTo(15L);
        }

        @Test
        @DisplayName("正常系: 件数ゼロの場合は0を返す")
        void 違反なしの場合は0を返す() {
            // given
            given(violationRepository.countByIsActiveTrue()).willReturn(0L);

            // when
            long result = userViolationService.countActiveViolations();

            // then
            assertThat(result).isZero();
        }
    }

    // ========================================
    // countYabaiUsers
    // ========================================

    @Nested
    @DisplayName("countYabaiUsers")
    class CountYabaiUsers {

        @Test
        @DisplayName("正常系: ヤバいやつ認定ユーザー数を取得できる")
        void ヤバいやつ認定ユーザー数を取得できる() {
            // given
            given(settingsRepository.findBySettingKey("yabai_violation_threshold")).willReturn(Optional.empty());
            given(violationRepository.findYabaiUserIds(3)).willReturn(List.of(1L, 2L, 5L));

            // when
            long result = userViolationService.countYabaiUsers();

            // then
            assertThat(result).isEqualTo(3L);
        }

        @Test
        @DisplayName("正常系: ヤバいやつが0人の場合は0を返す")
        void ヤバいやつが0人の場合は0を返す() {
            // given
            given(settingsRepository.findBySettingKey("yabai_violation_threshold")).willReturn(Optional.empty());
            given(violationRepository.findYabaiUserIds(3)).willReturn(List.of());

            // when
            long result = userViolationService.countYabaiUsers();

            // then
            assertThat(result).isZero();
        }
    }

    // ========================================
    // getActiveViolations
    // ========================================

    @Nested
    @DisplayName("getActiveViolations")
    class GetActiveViolations {

        @Test
        @DisplayName("正常系: 有効な違反一覧を取得できる")
        void 有効な違反一覧を取得できる() {
            // given
            List<UserViolationEntity> violations = List.of(createViolation(ViolationType.WARNING));
            ViolationResponse response = new ViolationResponse(
                    1L, USER_ID, 1L, 10L, "WARNING", "テスト違反", null, true, null);

            given(violationRepository.findByUserIdAndIsActiveTrueOrderByCreatedAtDesc(USER_ID))
                    .willReturn(violations);
            given(mapper.toViolationResponseList(violations)).willReturn(List.of(response));

            // when
            List<ViolationResponse> result = userViolationService.getActiveViolations(USER_ID);

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getViolationType()).isEqualTo("WARNING");
        }

        @Test
        @DisplayName("正常系: 有効な違反がない場合は空リストが返る")
        void 有効な違反がない場合は空リストが返る() {
            // given
            given(violationRepository.findByUserIdAndIsActiveTrueOrderByCreatedAtDesc(USER_ID))
                    .willReturn(List.of());
            given(mapper.toViolationResponseList(List.of())).willReturn(List.of());

            // when
            List<ViolationResponse> result = userViolationService.getActiveViolations(USER_ID);

            // then
            assertThat(result).isEmpty();
        }
    }
}
