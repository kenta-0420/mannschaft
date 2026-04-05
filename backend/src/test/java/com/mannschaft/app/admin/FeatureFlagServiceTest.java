package com.mannschaft.app.admin;

import com.mannschaft.app.admin.dto.FeatureFlagResponse;
import com.mannschaft.app.admin.dto.UpdateFeatureFlagRequest;
import com.mannschaft.app.admin.entity.FeatureFlagEntity;
import com.mannschaft.app.admin.repository.FeatureFlagRepository;
import com.mannschaft.app.admin.service.FeatureFlagService;
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

/**
 * {@link FeatureFlagService} の単体テスト。
 * フィーチャーフラグの取得・更新・有効判定を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FeatureFlagService 単体テスト")
class FeatureFlagServiceTest {

    @Mock
    private FeatureFlagRepository featureFlagRepository;

    @Mock
    private AdminMapper adminMapper;

    @InjectMocks
    private FeatureFlagService service;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final String FLAG_KEY = "FEATURE_NEW_UI";
    private static final Long USER_ID = 100L;

    private FeatureFlagEntity createFlagEntity(boolean enabled) {
        return FeatureFlagEntity.builder()
                .flagKey(FLAG_KEY)
                .isEnabled(enabled)
                .description("新UIの有効化フラグ")
                .build();
    }

    private FeatureFlagResponse createFlagResponse(boolean enabled) {
        return new FeatureFlagResponse(1L, FLAG_KEY, enabled, "新UIの有効化フラグ", null, null, null);
    }

    // ========================================
    // getAllFlags
    // ========================================

    @Nested
    @DisplayName("getAllFlags")
    class GetAllFlags {

        @Test
        @DisplayName("正常系: 全フラグ一覧が返却される")
        void 取得_全件_一覧返却() {
            // Given
            List<FeatureFlagEntity> entities = List.of(createFlagEntity(true));
            List<FeatureFlagResponse> responses = List.of(createFlagResponse(true));
            given(featureFlagRepository.findAll()).willReturn(entities);
            given(adminMapper.toFeatureFlagResponseList(entities)).willReturn(responses);

            // When
            List<FeatureFlagResponse> result = service.getAllFlags();

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getFlagKey()).isEqualTo(FLAG_KEY);
        }
    }

    // ========================================
    // getByKey
    // ========================================

    @Nested
    @DisplayName("getByKey")
    class GetByKey {

        @Test
        @DisplayName("正常系: フラグキーで取得できる")
        void 取得_キー指定_フラグ返却() {
            // Given
            FeatureFlagEntity entity = createFlagEntity(true);
            FeatureFlagResponse response = createFlagResponse(true);
            given(featureFlagRepository.findByFlagKey(FLAG_KEY)).willReturn(Optional.of(entity));
            given(adminMapper.toFeatureFlagResponse(entity)).willReturn(response);

            // When
            FeatureFlagResponse result = service.getByKey(FLAG_KEY);

            // Then
            assertThat(result.getFlagKey()).isEqualTo(FLAG_KEY);
            assertThat(result.getIsEnabled()).isTrue();
        }

        @Test
        @DisplayName("異常系: フラグ不在でADMIN_001例外")
        void 取得_フラグ不在_例外() {
            // Given
            given(featureFlagRepository.findByFlagKey(FLAG_KEY)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.getByKey(FLAG_KEY))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ADMIN_001"));
        }
    }

    // ========================================
    // updateFlag
    // ========================================

    @Nested
    @DisplayName("updateFlag")
    class UpdateFlag {

        @Test
        @DisplayName("正常系: フラグが更新される")
        void 更新_正常_フラグ保存() {
            // Given
            UpdateFeatureFlagRequest req = new UpdateFeatureFlagRequest(true, "更新後説明");
            FeatureFlagEntity entity = createFlagEntity(false);
            FeatureFlagResponse response = createFlagResponse(true);

            given(featureFlagRepository.findByFlagKey(FLAG_KEY)).willReturn(Optional.of(entity));
            given(featureFlagRepository.save(any(FeatureFlagEntity.class))).willAnswer(inv -> inv.getArgument(0));
            given(adminMapper.toFeatureFlagResponse(any(FeatureFlagEntity.class))).willReturn(response);

            // When
            FeatureFlagResponse result = service.updateFlag(FLAG_KEY, req, USER_ID);

            // Then
            assertThat(result.getIsEnabled()).isTrue();
            verify(featureFlagRepository).save(any(FeatureFlagEntity.class));
        }

        @Test
        @DisplayName("正常系: descriptionがnullの場合descriptionは変更されない")
        void 更新_descriptionNull_変更なし() {
            // Given
            UpdateFeatureFlagRequest req = new UpdateFeatureFlagRequest(false, null);
            FeatureFlagEntity entity = createFlagEntity(true);
            FeatureFlagResponse response = createFlagResponse(false);

            given(featureFlagRepository.findByFlagKey(FLAG_KEY)).willReturn(Optional.of(entity));
            given(featureFlagRepository.save(any(FeatureFlagEntity.class))).willAnswer(inv -> inv.getArgument(0));
            given(adminMapper.toFeatureFlagResponse(any(FeatureFlagEntity.class))).willReturn(response);

            // When
            FeatureFlagResponse result = service.updateFlag(FLAG_KEY, req, USER_ID);

            // Then
            assertThat(result).isNotNull();
            verify(featureFlagRepository).save(entity);
        }

        @Test
        @DisplayName("異常系: フラグ不在でADMIN_001例外")
        void 更新_フラグ不在_例外() {
            // Given
            UpdateFeatureFlagRequest req = new UpdateFeatureFlagRequest(true, null);
            given(featureFlagRepository.findByFlagKey(FLAG_KEY)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.updateFlag(FLAG_KEY, req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ADMIN_001"));
        }
    }

    // ========================================
    // isEnabled
    // ========================================

    @Nested
    @DisplayName("isEnabled")
    class IsEnabled {

        @Test
        @DisplayName("正常系: フラグが有効の場合trueが返却される")
        void 判定_有効フラグ_true返却() {
            // Given
            FeatureFlagEntity entity = createFlagEntity(true);
            given(featureFlagRepository.findByFlagKey(FLAG_KEY)).willReturn(Optional.of(entity));

            // When
            boolean result = service.isEnabled(FLAG_KEY);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("正常系: フラグが無効の場合falseが返却される")
        void 判定_無効フラグ_false返却() {
            // Given
            FeatureFlagEntity entity = createFlagEntity(false);
            given(featureFlagRepository.findByFlagKey(FLAG_KEY)).willReturn(Optional.of(entity));

            // When
            boolean result = service.isEnabled(FLAG_KEY);

            // Then
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("正常系: フラグ不在の場合falseが返却される")
        void 判定_フラグ不在_false返却() {
            // Given
            given(featureFlagRepository.findByFlagKey(FLAG_KEY)).willReturn(Optional.empty());

            // When
            boolean result = service.isEnabled(FLAG_KEY);

            // Then
            assertThat(result).isFalse();
        }
    }
}
