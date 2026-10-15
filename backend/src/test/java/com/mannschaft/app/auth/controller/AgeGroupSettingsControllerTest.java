package com.mannschaft.app.auth.controller;

import com.mannschaft.app.auth.dto.AgeGroupSettingsResponse;
import com.mannschaft.app.auth.dto.AgeGroupSettingsUpdateRequest;
import com.mannschaft.app.auth.entity.AgeGroupSettingsEntity;
import com.mannschaft.app.auth.service.AgeGroupSettingsService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * F01.9 年齢確認・保護者同意機能: AgeGroupSettingsController 単体テスト。
 *
 * <p>Service を Mockito でモックしてコントローラーを直接呼び出す方式。
 * 管理者専用エンドポイントのため SecurityContext 設定は不要（SecurityConfig 側で制御）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgeGroupSettingsController 単体テスト")
class AgeGroupSettingsControllerTest {

    @Mock
    private AgeGroupSettingsService ageGroupSettingsService;

    @InjectMocks
    private AgeGroupSettingsController controller;

    /**
     * テスト用 AgeGroupSettingsEntity を生成するヘルパー。
     */
    private AgeGroupSettingsEntity buildEntity(String ageGroup) {
        return AgeGroupSettingsEntity.builder()
                .ageGroup(ageGroup)
                .displayName("テスト年齢グループ")
                .minAge(0)
                .maxAge(12)
                .featuresEnabled("{\"chat\":false}")
                .themeConfig("{\"primaryColor\":\"#FF0000\"}")
                .build();
    }

    // ========================================
    // GET / — 年齢区分設定一覧取得
    // ========================================

    @Nested
    @DisplayName("GET /api/v1/admin/age-group-settings — 一覧取得")
    class GetAllTests {

        @Test
        @DisplayName("正常系: 年齢区分設定一覧が200で返る")
        void getAll_正常_200() {
            // Given
            AgeGroupSettingsEntity child = buildEntity("CHILD");
            AgeGroupSettingsEntity teen = buildEntity("TEEN");
            given(ageGroupSettingsService.getAll()).willReturn(List.of(child, teen));

            // When
            ResponseEntity<ApiResponse<List<AgeGroupSettingsResponse>>> response = controller.getAll();

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData()).hasSize(2);
            assertThat(response.getBody().getData().get(0).getAgeGroup()).isEqualTo("CHILD");
            assertThat(response.getBody().getData().get(1).getAgeGroup()).isEqualTo("TEEN");
            verify(ageGroupSettingsService).getAll();
        }

        @Test
        @DisplayName("正常系: 設定が0件の場合は空リストで200で返る")
        void getAll_空の場合_200() {
            // Given
            given(ageGroupSettingsService.getAll()).willReturn(List.of());

            // When
            ResponseEntity<ApiResponse<List<AgeGroupSettingsResponse>>> response = controller.getAll();

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData()).isEmpty();
        }
    }

    // ========================================
    // PUT /{ageGroup} — 年齢区分設定更新
    // ========================================

    @Nested
    @DisplayName("PUT /api/v1/admin/age-group-settings/{ageGroup} — 設定更新")
    class UpdateTests {

        @Test
        @DisplayName("正常系: 設定更新が200で更新後のレスポンスを返す")
        void update_正常_200() {
            // Given
            String ageGroup = "CHILD";
            Map<String, Object> featuresEnabled = Map.of("chat", false, "advertising", false);
            Map<String, Object> themeConfig = Map.of("primaryColor", "#4CAF50");
            AgeGroupSettingsUpdateRequest req = new AgeGroupSettingsUpdateRequest(featuresEnabled, themeConfig);

            AgeGroupSettingsEntity updatedEntity = AgeGroupSettingsEntity.builder()
                    .ageGroup(ageGroup)
                    .displayName("子ども（12歳以下）")
                    .minAge(0)
                    .maxAge(12)
                    .featuresEnabled("{\"chat\":false,\"advertising\":false}")
                    .themeConfig("{\"primaryColor\":\"#4CAF50\"}")
                    .build();

            given(ageGroupSettingsService.update(eq(ageGroup), eq(featuresEnabled), eq(themeConfig)))
                    .willReturn(updatedEntity);

            // When
            ResponseEntity<ApiResponse<AgeGroupSettingsResponse>> response = controller.update(ageGroup, req);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData().getAgeGroup()).isEqualTo(ageGroup);
            assertThat(response.getBody().getData().getDisplayName()).isEqualTo("子ども（12歳以下）");
            assertThat(response.getBody().getData().getMinAge()).isEqualTo(0);
            assertThat(response.getBody().getData().getMaxAge()).isEqualTo(12);
            verify(ageGroupSettingsService).update(ageGroup, featuresEnabled, themeConfig);
        }

        @Test
        @DisplayName("異常系: 存在しない ageGroup を指定した場合は BusinessException がスローされる")
        void update_存在しないAgeGroup_例外スロー() {
            // Given
            String nonExistentAgeGroup = "UNKNOWN_GROUP";
            AgeGroupSettingsUpdateRequest req = new AgeGroupSettingsUpdateRequest(
                    Map.of("chat", true), Map.of());

            given(ageGroupSettingsService.update(eq(nonExistentAgeGroup), any(), any()))
                    .willThrow(new BusinessException(CommonErrorCode.COMMON_999));

            // When / Then
            assertThatThrownBy(() -> controller.update(nonExistentAgeGroup, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> {
                        BusinessException be = (BusinessException) e;
                        assertThat(be.getErrorCode().getCode()).isEqualTo("COMMON_999");
                    });
        }
    }
}
