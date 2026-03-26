package com.mannschaft.app.activity;

import com.mannschaft.app.activity.dto.CreatePresetRequest;
import com.mannschaft.app.activity.dto.PresetResponse;
import com.mannschaft.app.activity.dto.UpdatePresetRequest;
import com.mannschaft.app.activity.entity.SystemActivityTemplatePresetEntity;
import com.mannschaft.app.activity.repository.SystemActivityTemplatePresetRepository;
import com.mannschaft.app.activity.service.SystemActivityPresetService;
import com.mannschaft.app.common.BusinessException;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("SystemActivityPresetService 単体テスト")
class SystemActivityPresetServiceTest {

    @Mock private SystemActivityTemplatePresetRepository presetRepository;
    @Mock private ActivityMapper activityMapper;

    @InjectMocks
    private SystemActivityPresetService service;

    private static final Long PRESET_ID = 10L;

    @Nested
    @DisplayName("createPreset")
    class CreatePreset {
        @Test
        @DisplayName("正常系: プリセットが作成される")
        void 作成_正常_保存() {
            CreatePresetRequest request = new CreatePresetRequest(
                    "SPORTS", "練習メニュー", null, null, null, null, null, null);
            SystemActivityTemplatePresetEntity saved = SystemActivityTemplatePresetEntity.builder()
                    .category(PresetCategory.SPORTS).name("練習メニュー").build();
            given(presetRepository.save(any())).willReturn(saved);
            given(activityMapper.toPresetResponse(saved))
                    .willReturn(new PresetResponse(1L, "SPORTS", "練習メニュー", null, null, null, null, null, null, true, null, null));

            PresetResponse result = service.createPreset(request);
            assertThat(result).isNotNull();
        }
    }

    @Nested
    @DisplayName("updatePreset")
    class UpdatePreset {
        @Test
        @DisplayName("異常系: プリセット不在でACTIVITY_016例外")
        void 更新_不在_例外() {
            given(presetRepository.findById(PRESET_ID)).willReturn(Optional.empty());
            assertThatThrownBy(() -> service.updatePreset(PRESET_ID,
                    new UpdatePresetRequest("名前", null, null, null, null, null, null)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ACTIVITY_016"));
        }
    }

    @Nested
    @DisplayName("deletePreset")
    class DeletePreset {
        @Test
        @DisplayName("正常系: プリセットが論理削除される")
        void 削除_正常_論理削除() {
            SystemActivityTemplatePresetEntity entity = SystemActivityTemplatePresetEntity.builder()
                    .category(PresetCategory.SPORTS).name("テスト").build();
            given(presetRepository.findById(PRESET_ID)).willReturn(Optional.of(entity));
            service.deletePreset(PRESET_ID);
            verify(presetRepository).save(entity);
        }

        @Test
        @DisplayName("異常系: プリセット不在でACTIVITY_016例外")
        void 削除_不在_例外() {
            given(presetRepository.findById(PRESET_ID)).willReturn(Optional.empty());
            assertThatThrownBy(() -> service.deletePreset(PRESET_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ACTIVITY_016"));
        }
    }
}
