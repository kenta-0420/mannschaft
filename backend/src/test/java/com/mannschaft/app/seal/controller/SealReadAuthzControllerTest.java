package com.mannschaft.app.seal.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.seal.dto.ScopeDefaultResponse;
import com.mannschaft.app.seal.dto.StampLogResponse;
import com.mannschaft.app.seal.service.SealService;
import com.mannschaft.app.seal.service.SealStampService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * 印鑑（seal）ドメインの不足読み取りAPI2件＋認可一括根治の単体テスト。
 *
 * <p>受け入れ条件（試練）:</p>
 * <ul>
 *   <li>getScopeDefaults: 本人200・scopeName解決済・空配列可 / 他人403 / 未認証401</li>
 *   <li>listStamps: stampedAt降順+カーソルmeta / 初回size未指定20 / 最終hasNext=false,nextCursor=null
 *       / size>50で50丸め / 0件空 / 他人403 / 未認証401 / includeRevoked=false</li>
 *   <li>既存書込ハンドラ（createSeal/stamp/setScopeDefault等）が他人userIdで403（認可一括根治）</li>
 * </ul>
 *
 * <p>SecurityUtils は static のため MockedStatic で固定する。ThreadLocal 漏れ防止に
 * 各テストで close を確実に行う（@BeforeEach/@AfterEach）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("印鑑読み取りAPI＋認可一括根治 単体テスト")
class SealReadAuthzControllerTest {

    @Mock
    private SealService sealService;

    @Mock
    private SealStampService stampService;

    @InjectMocks
    private SealController sealController;

    private SealStampController stampController;

    private static final Long OWNER_ID = 1L;
    private static final Long OTHER_ID = 999L;

    private MockedStatic<SecurityUtils> securityUtilsMock;

    @BeforeEach
    void setUp() {
        // SealStampController は2つの依存（stampService, sealService）を持つため明示構築する
        stampController = new SealStampController(stampService, sealService);
        securityUtilsMock = Mockito.mockStatic(SecurityUtils.class);
    }

    @AfterEach
    void tearDown() {
        securityUtilsMock.close();
    }

    private void loginAs(Long userId) {
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(userId);
    }

    private void unauthenticated() {
        securityUtilsMock.when(SecurityUtils::getCurrentUserId)
                .thenThrow(new BusinessException(com.mannschaft.app.common.CommonErrorCode.COMMON_000));
    }

    // ========================================
    // タスクA: getScopeDefaults
    // ========================================
    @Nested
    @DisplayName("getScopeDefaults (GET /seals/scope-defaults)")
    class GetScopeDefaults {

        private ScopeDefaultResponse buildScopeDefault() {
            return new ScopeDefaultResponse(
                    1L, OWNER_ID, "TEAM", 100L, "チームA", 50L,
                    LocalDateTime.of(2026, 3, 1, 0, 0), null);
        }

        @Test
        @DisplayName("本人: スコープデフォルト一覧が scopeName 付きで返される")
        void 本人取得() {
            loginAs(OWNER_ID);
            given(sealService.listScopeDefaults(OWNER_ID)).willReturn(List.of(buildScopeDefault()));

            ResponseEntity<ApiResponse<List<ScopeDefaultResponse>>> response =
                    sealController.getScopeDefaults(OWNER_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData()).hasSize(1);
            assertThat(response.getBody().getData().get(0).getScopeName()).isEqualTo("チームA");
        }

        @Test
        @DisplayName("本人: 空配列でも200")
        void 空配列() {
            loginAs(OWNER_ID);
            given(sealService.listScopeDefaults(OWNER_ID)).willReturn(List.of());

            ResponseEntity<ApiResponse<List<ScopeDefaultResponse>>> response =
                    sealController.getScopeDefaults(OWNER_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData()).isEmpty();
        }

        @Test
        @DisplayName("他人: 403（COMMON_002）")
        void 他人禁止() {
            loginAs(OTHER_ID);

            assertThatThrownBy(() -> sealController.getScopeDefaults(OWNER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode().getCode())
                            .isEqualTo("COMMON_002"));
        }

        @Test
        @DisplayName("未認証: 401（COMMON_000）")
        void 未認証() {
            unauthenticated();

            assertThatThrownBy(() -> sealController.getScopeDefaults(OWNER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode().getCode())
                            .isEqualTo("COMMON_000"));
        }
    }

    // ========================================
    // タスクB: listStamps（カーソルページング）
    // ========================================
    @Nested
    @DisplayName("listStamps (GET /stamps)")
    class ListStamps {

        private StampLogResponse buildLog(Long id) {
            return new StampLogResponse(id, OWNER_ID, 50L, "hash", "CIRCULATION", 500L,
                    "docHash", false, null, LocalDateTime.of(2026, 3, 1, 12, 0), null);
        }

        private CursorPagedResponse<StampLogResponse> paged(List<StampLogResponse> data,
                                                            String nextCursor, boolean hasNext, int limit) {
            return CursorPagedResponse.of(data,
                    new CursorPagedResponse.CursorMeta(nextCursor, hasNext, limit));
        }

        @Test
        @DisplayName("本人: size未指定は生のnullのままService委譲し、解決結果(limit=20)のmetaを返す")
        void 初回20() {
            loginAs(OWNER_ID);
            // size 未指定時、Controller は生の null を Service へ渡す（デフォルト20の解決は Service の resolvePageSize 責務）。
            given(stampService.listStampLogs(OWNER_ID, null, null, null, true))
                    .willReturn(paged(List.of(buildLog(2L), buildLog(1L)), null, false, 20));

            ResponseEntity<CursorPagedResponse<StampLogResponse>> response =
                    stampController.listStamps(OWNER_ID, null, null, null, true);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData()).hasSize(2);
            assertThat(response.getBody().getMeta().getLimit()).isEqualTo(20);
            assertThat(response.getBody().getMeta().isHasNext()).isFalse();
            assertThat(response.getBody().getMeta().getNextCursor()).isNull();
        }

        @Test
        @DisplayName("本人: size>50 は生値のままService委譲（丸めはService責務）・解決結果limit=50のmetaを返す")
        void サイズ丸め() {
            loginAs(OWNER_ID);
            // Controller は生の 100 を Service へ渡す。上限50への丸めは Service の resolvePageSize が行い、結果 limit=50 が meta に返る。
            given(stampService.listStampLogs(OWNER_ID, null, 100, null, true))
                    .willReturn(paged(List.of(), null, false, 50));

            ResponseEntity<CursorPagedResponse<StampLogResponse>> response =
                    stampController.listStamps(OWNER_ID, null, 100, null, true);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getMeta().getLimit()).isEqualTo(50);
        }

        @Test
        @DisplayName("本人: includeRevoked=false を Service へ伝播")
        void 取消除外() {
            loginAs(OWNER_ID);
            given(stampService.listStampLogs(OWNER_ID, 5L, 20, "CIRCULATION", false))
                    .willReturn(paged(List.of(buildLog(3L)), "3", true, 20));

            ResponseEntity<CursorPagedResponse<StampLogResponse>> response =
                    stampController.listStamps(OWNER_ID, 5L, 20, "CIRCULATION", false);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getMeta().isHasNext()).isTrue();
            assertThat(response.getBody().getMeta().getNextCursor()).isEqualTo("3");
        }

        @Test
        @DisplayName("本人: 0件は空配列")
        void ゼロ件() {
            loginAs(OWNER_ID);
            // size 未指定 → Controller は生の null を Service へ委譲。
            given(stampService.listStampLogs(OWNER_ID, null, null, null, true))
                    .willReturn(paged(List.of(), null, false, 20));

            ResponseEntity<CursorPagedResponse<StampLogResponse>> response =
                    stampController.listStamps(OWNER_ID, null, null, null, true);

            assertThat(response.getBody().getData()).isEmpty();
        }

        @Test
        @DisplayName("他人: 403")
        void 他人禁止() {
            loginAs(OTHER_ID);

            assertThatThrownBy(() -> stampController.listStamps(OWNER_ID, null, null, null, true))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode().getCode())
                            .isEqualTo("COMMON_002"));
        }

        @Test
        @DisplayName("未認証: 401")
        void 未認証() {
            unauthenticated();

            assertThatThrownBy(() -> stampController.listStamps(OWNER_ID, null, null, null, true))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode().getCode())
                            .isEqualTo("COMMON_000"));
        }
    }

    // ========================================
    // タスクC: 認可一括根治（既存書込ハンドラ）
    // ========================================
    @Nested
    @DisplayName("認可一括根治: 他人userIdの操作は403")
    class WriteAuthz {

        @Test
        @DisplayName("listSeals 他人403")
        void listSeals他人() {
            loginAs(OTHER_ID);
            assertThatThrownBy(() -> sealController.listSeals(OWNER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode().getCode())
                            .isEqualTo("COMMON_002"));
        }

        @Test
        @DisplayName("createSeal 他人403")
        void createSeal他人() {
            loginAs(OTHER_ID);
            var request = new com.mannschaft.app.seal.dto.CreateSealRequest("LAST_NAME", "田中");
            assertThatThrownBy(() -> sealController.createSeal(OWNER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode().getCode())
                            .isEqualTo("COMMON_002"));
        }

        @Test
        @DisplayName("stamp 他人403")
        void stamp他人() {
            loginAs(OTHER_ID);
            var request = new com.mannschaft.app.seal.dto.StampRequest(50L, "CIRCULATION", 500L, "docHash");
            assertThatThrownBy(() -> stampController.stamp(OWNER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode().getCode())
                            .isEqualTo("COMMON_002"));
        }

        @Test
        @DisplayName("setScopeDefault 他人403")
        void setScopeDefault他人() {
            loginAs(OTHER_ID);
            var request = new com.mannschaft.app.seal.dto.SetScopeDefaultRequest("DEFAULT", null, 50L);
            assertThatThrownBy(() -> stampController.setScopeDefault(OWNER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode().getCode())
                            .isEqualTo("COMMON_002"));
        }

        @Test
        @DisplayName("本人なら createSeal は通過する（認可で誤遮断しない）")
        void 本人通過() {
            loginAs(OWNER_ID);
            var sealResponse = new com.mannschaft.app.seal.dto.SealResponse(
                    50L, OWNER_ID, "LAST_NAME", "田中", "<svg/>", "hash", 1, null, null);
            given(sealService.createSeal(eq(OWNER_ID), org.mockito.ArgumentMatchers.any()))
                    .willReturn(sealResponse);
            var request = new com.mannschaft.app.seal.dto.CreateSealRequest("LAST_NAME", "田中");

            ResponseEntity<ApiResponse<com.mannschaft.app.seal.dto.SealResponse>> response =
                    sealController.createSeal(OWNER_ID, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
    }
}
