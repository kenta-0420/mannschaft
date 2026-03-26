package com.mannschaft.app.forms;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.forms.dto.CreateFormPresetRequest;
import com.mannschaft.app.forms.dto.FormPresetResponse;
import com.mannschaft.app.forms.dto.UpdateFormPresetRequest;
import com.mannschaft.app.forms.entity.SystemFormPresetEntity;
import com.mannschaft.app.forms.repository.SystemFormPresetRepository;
import com.mannschaft.app.forms.service.FormPresetService;
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

/**
 * {@link FormPresetService} の単体テスト。
 * プリセットのCRUDを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FormPresetService 単体テスト")
class FormPresetServiceTest {

    @Mock
    private SystemFormPresetRepository presetRepository;

    @Mock
    private FormMapper formMapper;

    @InjectMocks
    private FormPresetService formPresetService;

    private static final Long PRESET_ID = 100L;
    private static final Long USER_ID = 10L;

    @Nested
    @DisplayName("createPreset")
    class CreatePreset {

        @Test
        @DisplayName("プリセット作成_正常_レスポンス返却")
        void プリセット作成_正常_レスポンス返却() {
            // Given
            CreateFormPresetRequest request = new CreateFormPresetRequest(
                    "休暇届", null, "人事", null, null, null);

            SystemFormPresetEntity savedEntity = SystemFormPresetEntity.builder()
                    .name("休暇届").category("人事").createdBy(USER_ID).build();
            FormPresetResponse response = new FormPresetResponse(PRESET_ID, "休暇届", null,
                    "人事", null, null, null, true, USER_ID, null, null);

            given(presetRepository.save(any(SystemFormPresetEntity.class))).willReturn(savedEntity);
            given(formMapper.toPresetResponse(savedEntity)).willReturn(response);

            // When
            FormPresetResponse result = formPresetService.createPreset(USER_ID, request);

            // Then
            assertThat(result.getName()).isEqualTo("休暇届");
        }
    }

    @Nested
    @DisplayName("deletePreset")
    class DeletePreset {

        @Test
        @DisplayName("プリセット削除_正常_論理削除実行")
        void プリセット削除_正常_論理削除実行() {
            // Given
            SystemFormPresetEntity entity = SystemFormPresetEntity.builder()
                    .name("テスト").createdBy(USER_ID).build();
            given(presetRepository.findById(PRESET_ID)).willReturn(Optional.of(entity));

            // When
            formPresetService.deletePreset(PRESET_ID);

            // Then
            verify(presetRepository).save(entity);
        }

        @Test
        @DisplayName("プリセット削除_存在しない_BusinessException")
        void プリセット削除_存在しない_BusinessException() {
            // Given
            given(presetRepository.findById(PRESET_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> formPresetService.deletePreset(PRESET_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FormErrorCode.PRESET_NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("listPresets")
    class ListPresets {

        @Test
        @DisplayName("プリセット一覧_カテゴリ指定_フィルタ結果返却")
        void プリセット一覧_カテゴリ指定_フィルタ結果返却() {
            // Given
            SystemFormPresetEntity entity = SystemFormPresetEntity.builder()
                    .name("テスト").category("人事").createdBy(USER_ID).build();
            given(presetRepository.findByCategoryAndIsActiveTrueOrderByNameAsc("人事"))
                    .willReturn(List.of(entity));
            given(formMapper.toPresetResponseList(List.of(entity)))
                    .willReturn(List.of(new FormPresetResponse(1L, "テスト", null, "人事",
                            null, null, null, true, USER_ID, null, null)));

            // When
            List<FormPresetResponse> result = formPresetService.listPresets("人事");

            // Then
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("プリセット一覧_カテゴリ未指定_全件返却")
        void プリセット一覧_カテゴリ未指定_全件返却() {
            // Given
            given(presetRepository.findByIsActiveTrueOrderByNameAsc()).willReturn(List.of());
            given(formMapper.toPresetResponseList(List.of())).willReturn(List.of());

            // When
            List<FormPresetResponse> result = formPresetService.listPresets(null);

            // Then
            assertThat(result).isEmpty();
        }
    }
}
