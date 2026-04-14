package com.mannschaft.app.advertising.ranking;

import com.mannschaft.app.advertising.ranking.controller.EquipmentRankingAdminController;
import com.mannschaft.app.advertising.ranking.dto.CreateItemExclusionRequest;
import com.mannschaft.app.advertising.ranking.dto.EquipmentRankingExclusionResponse;
import com.mannschaft.app.advertising.ranking.dto.EquipmentRankingStatsResponse;
import com.mannschaft.app.advertising.ranking.entity.EquipmentRankingExclusionEntity;
import com.mannschaft.app.advertising.ranking.entity.ExclusionType;
import com.mannschaft.app.advertising.ranking.service.EquipmentRankingBatchService;
import com.mannschaft.app.advertising.ranking.service.EquipmentRankingService;
import com.mannschaft.app.advertising.ranking.service.EquipmentRankingService.RankingStatsResult;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.team.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * {@link EquipmentRankingAdminController} の単体テスト。
 * SYSTEM_ADMIN 向けの統計取得・バッチ起動・除外設定管理の HTTP レスポンスを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EquipmentRankingAdminController 単体テスト")
class EquipmentRankingAdminControllerTest {

    @Mock private EquipmentRankingService rankingService;
    @Mock private EquipmentRankingBatchService batchService;
    @Mock private TeamRepository teamRepository;
    @Mock private StringRedisTemplate stringRedisTemplate;

    @InjectMocks
    private EquipmentRankingAdminController controller;

    private static final Long ADMIN_USER_ID = 1L;

    /** テスト用 RankingStatsResult を生成する */
    private RankingStatsResult createStatsResult() {
        return new RankingStatsResult(
                100L,
                LocalDateTime.now(),
                List.of("soccer_youth", "baseball_high"),
                2,
                3,
                10L,
                80L,
                5);
    }

    /** テスト用 EquipmentRankingExclusionEntity (ITEM_EXCLUSION) を生成する */
    private EquipmentRankingExclusionEntity createItemExclusionEntity(Long id) {
        EquipmentRankingExclusionEntity entity = EquipmentRankingExclusionEntity.builder()
                .exclusionType(ExclusionType.ITEM_EXCLUSION)
                .normalizedName("サッカーボール")
                .reason("スパム的な備品名")
                .excludedByUserId(ADMIN_USER_ID)
                .build();
        // @GeneratedValue のため ReflectionTestUtils でIDを設定
        org.springframework.test.util.ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }

    @Nested
    @DisplayName("GET /api/v1/system-admin/equipment-rankings/stats")
    class GetStats {

        @Test
        @DisplayName("正常系: 200 OK と統計データが返る")
        void 正常系_200OKと統計データが返る() {
            // Given
            given(rankingService.getStats()).willReturn(createStatsResult());

            // When
            ResponseEntity<ApiResponse<EquipmentRankingStatsResponse>> result =
                    controller.getStats();

            // Then
            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody()).isNotNull();
            EquipmentRankingStatsResponse stats = result.getBody().getData();
            assertThat(stats.totalRankingItems()).isEqualTo(100L);
            assertThat(stats.templatesCovered()).hasSize(2);
            assertThat(stats.optOutTeamCount()).isEqualTo(2);
            assertThat(stats.excludedItemCount()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("POST /api/v1/system-admin/equipment-rankings/recalculate")
    class Recalculate {

        @Test
        @DisplayName("正常系: 202 Accepted とメッセージが返る")
        void 正常系_202Acceptedが返る() {
            // Given: バッチロックキーが存在しない
            given(stringRedisTemplate.hasKey(anyString())).willReturn(false);

            // When
            ResponseEntity<ApiResponse<Map<String, Object>>> result =
                    controller.recalculate();

            // Then
            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(result.getBody()).isNotNull();
            assertThat(result.getBody().getData()).containsKey("message");
        }

        @Test
        @DisplayName("異常系: バッチ実行中（Redisキー存在）は BATCH_ALREADY_RUNNING 例外が発生する")
        void 異常系_バッチ実行中はBATCH_ALREADY_RUNNING例外() {
            // Given: バッチロックキーが存在する（実行中）
            given(stringRedisTemplate.hasKey(anyString())).willReturn(true);

            // When / Then
            assertThatThrownBy(() -> controller.recalculate())
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ERANK_005"));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/system-admin/equipment-rankings/exclusions")
    class GetExclusions {

        @Test
        @DisplayName("正常系: 200 OK と除外設定一覧が返る")
        void 正常系_200OKと除外設定一覧が返る() {
            // Given
            EquipmentRankingExclusionEntity entity = createItemExclusionEntity(100L);
            given(rankingService.getAllExclusions()).willReturn(List.of(entity));

            // When
            ResponseEntity<ApiResponse<List<EquipmentRankingExclusionResponse>>> result =
                    controller.getExclusions();

            // Then
            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody()).isNotNull();
            assertThat(result.getBody().getData()).hasSize(1);
            assertThat(result.getBody().getData().get(0).exclusionType())
                    .isEqualTo("ITEM_EXCLUSION");
            assertThat(result.getBody().getData().get(0).normalizedName())
                    .isEqualTo("サッカーボール");
        }

        @Test
        @DisplayName("正常系: 除外設定が空の場合は空リストが返る")
        void 正常系_空の場合は空リストが返る() {
            // Given
            given(rankingService.getAllExclusions()).willReturn(List.of());

            // When
            ResponseEntity<ApiResponse<List<EquipmentRankingExclusionResponse>>> result =
                    controller.getExclusions();

            // Then
            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getData()).isEmpty();
        }
    }

    @Nested
    @DisplayName("POST /api/v1/system-admin/equipment-rankings/exclusions")
    class AddExclusion {

        @Test
        @DisplayName("正常系: 201 Created と作成された除外設定が返る")
        void 正常系_201Createdと作成された除外設定が返る() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                // Given
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(ADMIN_USER_ID);

                CreateItemExclusionRequest request = mock(CreateItemExclusionRequest.class);
                given(request.getNormalizedName()).willReturn("サッカーボール");
                given(request.getReason()).willReturn("スパム的な備品名");

                EquipmentRankingExclusionEntity saved = createItemExclusionEntity(200L);
                given(rankingService.addItemExclusion("サッカーボール", "スパム的な備品名", ADMIN_USER_ID))
                        .willReturn(saved);

                // When
                ResponseEntity<ApiResponse<EquipmentRankingExclusionResponse>> result =
                        controller.addExclusion(request);

                // Then
                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
                assertThat(result.getBody()).isNotNull();
                assertThat(result.getBody().getData().normalizedName()).isEqualTo("サッカーボール");
                assertThat(result.getBody().getData().exclusionType()).isEqualTo("ITEM_EXCLUSION");
            }
        }

        @Test
        @DisplayName("異常系: 同一 normalizedName が既に存在する場合は DUPLICATE_EXCLUSION 例外が発生する")
        void 異常系_重複exclusionはDUPLICATE_EXCLUSION例外() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                // Given
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(ADMIN_USER_ID);

                CreateItemExclusionRequest request = mock(CreateItemExclusionRequest.class);
                given(request.getNormalizedName()).willReturn("サッカーボール");
                given(request.getReason()).willReturn("理由");

                given(rankingService.addItemExclusion("サッカーボール", "理由", ADMIN_USER_ID))
                        .willThrow(new BusinessException(EquipmentRankingErrorCode.DUPLICATE_EXCLUSION));

                // When / Then
                assertThatThrownBy(() -> controller.addExclusion(request))
                        .isInstanceOf(BusinessException.class)
                        .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                                .isEqualTo("ERANK_006"));
            }
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/system-admin/equipment-rankings/exclusions/{id}")
    class RemoveExclusion {

        @Test
        @DisplayName("正常系: 204 No Content が返る")
        void 正常系_204NoContentが返る() {
            // Given
            Long exclusionId = 100L;
            willDoNothing().given(rankingService).removeItemExclusion(exclusionId);

            // When
            ResponseEntity<Void> result = controller.removeExclusion(exclusionId);

            // Then
            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        }

        @Test
        @DisplayName("異常系: 存在しないIDの場合は EXCLUSION_NOT_FOUND 例外が発生する")
        void 異常系_存在しないIDはEXCLUSION_NOT_FOUND例外() {
            // Given
            Long exclusionId = 999L;
            doThrow(new BusinessException(EquipmentRankingErrorCode.EXCLUSION_NOT_FOUND))
                    .when(rankingService).removeItemExclusion(exclusionId);

            // When / Then
            assertThatThrownBy(() -> controller.removeExclusion(exclusionId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ERANK_004"));
        }
    }
}
