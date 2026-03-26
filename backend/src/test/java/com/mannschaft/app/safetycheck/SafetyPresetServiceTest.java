package com.mannschaft.app.safetycheck;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.safetycheck.dto.CreatePresetRequest;
import com.mannschaft.app.safetycheck.dto.SafetyPresetResponse;
import com.mannschaft.app.safetycheck.dto.UpdatePresetRequest;
import com.mannschaft.app.safetycheck.entity.SafetyCheckMessagePresetEntity;
import com.mannschaft.app.safetycheck.repository.SafetyCheckMessagePresetRepository;
import com.mannschaft.app.safetycheck.service.SafetyPresetService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link SafetyPresetService} の単体テスト。
 * メッセージプリセットのCRUDを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SafetyPresetService 単体テスト")
class SafetyPresetServiceTest {

    @Mock
    private SafetyCheckMessagePresetRepository presetRepository;

    @Mock
    private SafetyCheckMapper mapper;

    @InjectMocks
    private SafetyPresetService safetyPresetService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long PRESET_ID = 60L;

    private SafetyCheckMessagePresetEntity createPresetEntity() {
        SafetyCheckMessagePresetEntity entity = SafetyCheckMessagePresetEntity.builder()
                .body("無事です。特に問題ありません。")
                .sortOrder(1)
                .isActive(true)
                .build();
        callOnCreate(entity);
        return entity;
    }

    private SafetyPresetResponse createPresetResponse() {
        return new SafetyPresetResponse(
                PRESET_ID, "無事です。特に問題ありません。", 1, true, LocalDateTime.now());
    }

    private void callOnCreate(Object entity) {
        try {
            Method method = entity.getClass().getSuperclass().getDeclaredMethod("onCreate");
            method.setAccessible(true);
            method.invoke(entity);
        } catch (Exception ignored) {
        }
    }

    // ========================================
    // listActivePresets
    // ========================================

    @Nested
    @DisplayName("listActivePresets")
    class ListActivePresets {

        @Test
        @DisplayName("有効プリセット一覧取得_正常_リスト返却")
        void 有効プリセット一覧取得_正常_リスト返却() {
            // Given
            SafetyCheckMessagePresetEntity entity = createPresetEntity();
            SafetyPresetResponse response = createPresetResponse();
            given(presetRepository.findByIsActiveTrueOrderBySortOrderAsc())
                    .willReturn(List.of(entity));
            given(mapper.toPresetResponseList(List.of(entity)))
                    .willReturn(List.of(response));

            // When
            List<SafetyPresetResponse> result = safetyPresetService.listActivePresets();

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getBody()).isEqualTo("無事です。特に問題ありません。");
        }
    }

    // ========================================
    // listAllPresets
    // ========================================

    @Nested
    @DisplayName("listAllPresets")
    class ListAllPresets {

        @Test
        @DisplayName("全プリセット一覧取得_正常_リスト返却")
        void 全プリセット一覧取得_正常_リスト返却() {
            // Given
            SafetyCheckMessagePresetEntity entity = createPresetEntity();
            SafetyPresetResponse response = createPresetResponse();
            given(presetRepository.findAllByOrderBySortOrderAsc())
                    .willReturn(List.of(entity));
            given(mapper.toPresetResponseList(List.of(entity)))
                    .willReturn(List.of(response));

            // When
            List<SafetyPresetResponse> result = safetyPresetService.listAllPresets();

            // Then
            assertThat(result).hasSize(1);
        }
    }

    // ========================================
    // createPreset
    // ========================================

    @Nested
    @DisplayName("createPreset")
    class CreatePreset {

        @Test
        @DisplayName("プリセット作成_正常_レスポンス返却")
        void プリセット作成_正常_レスポンス返却() {
            // Given
            CreatePresetRequest req = new CreatePresetRequest("怪我はありません", 2);
            SafetyCheckMessagePresetEntity savedEntity = createPresetEntity();
            SafetyPresetResponse response = createPresetResponse();
            given(presetRepository.save(any(SafetyCheckMessagePresetEntity.class))).willReturn(savedEntity);
            given(mapper.toPresetResponse(savedEntity)).willReturn(response);

            // When
            SafetyPresetResponse result = safetyPresetService.createPreset(req);

            // Then
            assertThat(result).isNotNull();
            verify(presetRepository).save(any(SafetyCheckMessagePresetEntity.class));
        }

        @Test
        @DisplayName("プリセット作成_sortOrder未指定_デフォルト0")
        void プリセット作成_sortOrder未指定_デフォルト0() {
            // Given
            CreatePresetRequest req = new CreatePresetRequest("デフォルト順メッセージ", null);
            SafetyCheckMessagePresetEntity savedEntity = createPresetEntity();
            SafetyPresetResponse response = createPresetResponse();
            given(presetRepository.save(any(SafetyCheckMessagePresetEntity.class))).willReturn(savedEntity);
            given(mapper.toPresetResponse(savedEntity)).willReturn(response);

            // When
            SafetyPresetResponse result = safetyPresetService.createPreset(req);

            // Then
            assertThat(result).isNotNull();
        }
    }

    // ========================================
    // updatePreset
    // ========================================

    @Nested
    @DisplayName("updatePreset")
    class UpdatePreset {

        @Test
        @DisplayName("プリセット更新_body変更_正常")
        void プリセット更新_body変更_正常() {
            // Given
            SafetyCheckMessagePresetEntity entity = createPresetEntity();
            UpdatePresetRequest req = new UpdatePresetRequest("更新メッセージ", 3, null);
            SafetyPresetResponse response = createPresetResponse();
            given(presetRepository.findById(PRESET_ID)).willReturn(Optional.of(entity));
            given(presetRepository.save(entity)).willReturn(entity);
            given(mapper.toPresetResponse(entity)).willReturn(response);

            // When
            SafetyPresetResponse result = safetyPresetService.updatePreset(PRESET_ID, req);

            // Then
            assertThat(entity.getBody()).isEqualTo("更新メッセージ");
            verify(presetRepository).save(entity);
        }

        @Test
        @DisplayName("プリセット更新_無効化_正常")
        void プリセット更新_無効化_正常() {
            // Given
            SafetyCheckMessagePresetEntity entity = createPresetEntity();
            UpdatePresetRequest req = new UpdatePresetRequest(null, null, false);
            SafetyPresetResponse response = createPresetResponse();
            given(presetRepository.findById(PRESET_ID)).willReturn(Optional.of(entity));
            given(presetRepository.save(entity)).willReturn(entity);
            given(mapper.toPresetResponse(entity)).willReturn(response);

            // When
            safetyPresetService.updatePreset(PRESET_ID, req);

            // Then
            assertThat(entity.getIsActive()).isFalse();
        }

        @Test
        @DisplayName("プリセット更新_有効化_正常")
        void プリセット更新_有効化_正常() {
            // Given
            SafetyCheckMessagePresetEntity entity = createPresetEntity();
            entity.deactivate();
            UpdatePresetRequest req = new UpdatePresetRequest(null, null, true);
            SafetyPresetResponse response = createPresetResponse();
            given(presetRepository.findById(PRESET_ID)).willReturn(Optional.of(entity));
            given(presetRepository.save(entity)).willReturn(entity);
            given(mapper.toPresetResponse(entity)).willReturn(response);

            // When
            safetyPresetService.updatePreset(PRESET_ID, req);

            // Then
            assertThat(entity.getIsActive()).isTrue();
        }

        @Test
        @DisplayName("プリセット更新_存在しない_BusinessException")
        void プリセット更新_存在しない_BusinessException() {
            // Given
            UpdatePresetRequest req = new UpdatePresetRequest("更新", null, null);
            given(presetRepository.findById(PRESET_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> safetyPresetService.updatePreset(PRESET_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SafetyCheckErrorCode.PRESET_NOT_FOUND));
        }
    }

    // ========================================
    // deletePreset
    // ========================================

    @Nested
    @DisplayName("deletePreset")
    class DeletePreset {

        @Test
        @DisplayName("プリセット削除_正常_deleteが呼ばれる")
        void プリセット削除_正常_deleteが呼ばれる() {
            // Given
            SafetyCheckMessagePresetEntity entity = createPresetEntity();
            given(presetRepository.findById(PRESET_ID)).willReturn(Optional.of(entity));

            // When
            safetyPresetService.deletePreset(PRESET_ID);

            // Then
            verify(presetRepository).delete(entity);
        }

        @Test
        @DisplayName("プリセット削除_存在しない_BusinessException")
        void プリセット削除_存在しない_BusinessException() {
            // Given
            given(presetRepository.findById(PRESET_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> safetyPresetService.deletePreset(PRESET_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SafetyCheckErrorCode.PRESET_NOT_FOUND));
        }
    }
}
