package com.mannschaft.app.onboarding.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.onboarding.OnboardingErrorCode;
import com.mannschaft.app.onboarding.OnboardingMapper;
import com.mannschaft.app.onboarding.OnboardingPresetCategory;
import com.mannschaft.app.onboarding.dto.PresetResponse;
import com.mannschaft.app.onboarding.dto.UpdatePresetRequest;
import com.mannschaft.app.onboarding.entity.SystemOnboardingPresetEntity;
import com.mannschaft.app.onboarding.repository.SystemOnboardingPresetRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link OnboardingPresetService} の単体テスト。
 * 主に更新時の主キー保持（INSERT 化退行防止）を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OnboardingPresetService 単体テスト")
class OnboardingPresetServiceTest {

    @Mock
    private SystemOnboardingPresetRepository presetRepository;

    @Mock
    private OnboardingMapper mapper;

    @InjectMocks
    private OnboardingPresetService onboardingPresetService;

    private static final Long PRESET_ID = 200L;
    private static final Long USER_ID = 10L;

    private SystemOnboardingPresetEntity existingPreset() {
        SystemOnboardingPresetEntity entity = SystemOnboardingPresetEntity.builder()
                .name("旧名").description("旧説明").category(OnboardingPresetCategory.SPORTS)
                .welcomeMessage("ようこそ").isOrderEnforced(false).deadlineDays((short) 7)
                .stepsJson("[]").isActive(true).sortOrder(0).createdBy(USER_ID).build();
        ReflectionTestUtils.setField(entity, "id", PRESET_ID);
        ReflectionTestUtils.setField(entity, "version", 2L);
        return entity;
    }

    @Nested
    @DisplayName("update")
    class Update {

        @Test
        @DisplayName("プリセット更新_findByIdの同一インスタンスをsaveしid・@Version保持（INSERT化退行防止）")
        void プリセット更新_id保持で同一インスタンスをsave() {
            // Given
            SystemOnboardingPresetEntity entity = existingPreset();
            given(presetRepository.findById(PRESET_ID)).willReturn(Optional.of(entity));
            given(presetRepository.save(any(SystemOnboardingPresetEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(mapper.toPresetResponse(any(SystemOnboardingPresetEntity.class)))
                    .willReturn(new PresetResponse(PRESET_ID, "新名", "旧説明",
                            OnboardingPresetCategory.SPORTS, "ようこそ", false, 7, "[]", true, 0, null));

            UpdatePresetRequest request = new UpdatePresetRequest(
                    "新名", null, null, null, null, null, null, null, null);

            // When
            onboardingPresetService.update(PRESET_ID, request);

            // Then: save に渡るのは findById が返した同一インスタンスで、id・@Version が保持される（=UPDATE）
            ArgumentCaptor<SystemOnboardingPresetEntity> captor =
                    ArgumentCaptor.forClass(SystemOnboardingPresetEntity.class);
            verify(presetRepository).save(captor.capture());
            SystemOnboardingPresetEntity savedArg = captor.getValue();
            assertThat(savedArg).isSameAs(entity);
            assertThat(savedArg.getId()).isEqualTo(PRESET_ID);
            assertThat(savedArg.getVersion()).isEqualTo(2L);
            // 非 null のみ更新・null は旧値温存
            assertThat(savedArg.getName()).isEqualTo("新名");
            assertThat(savedArg.getDescription()).isEqualTo("旧説明");
            assertThat(savedArg.getDeadlineDays()).isEqualTo((short) 7);
        }

        @Test
        @DisplayName("プリセット更新_存在しない_BusinessException")
        void プリセット更新_存在しない_BusinessException() {
            // Given
            given(presetRepository.findById(PRESET_ID)).willReturn(Optional.empty());
            UpdatePresetRequest request = new UpdatePresetRequest(
                    "新名", null, null, null, null, null, null, null, null);

            // When & Then
            assertThatThrownBy(() -> onboardingPresetService.update(PRESET_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(OnboardingErrorCode.ONBOARDING_012));
        }
    }
}
