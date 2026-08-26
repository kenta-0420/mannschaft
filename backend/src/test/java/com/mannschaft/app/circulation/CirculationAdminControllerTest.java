package com.mannschaft.app.circulation;

import com.mannschaft.app.circulation.controller.CirculationAdminController;
import com.mannschaft.app.circulation.controller.OrgCirculationDocumentController;
import com.mannschaft.app.circulation.dto.DocumentResponse;
import com.mannschaft.app.circulation.dto.DocumentStatsResponse;
import com.mannschaft.app.circulation.dto.DocumentStatusResponse;
import com.mannschaft.app.circulation.dto.ForceCompleteBatchRequest;
import com.mannschaft.app.circulation.dto.ForceCompleteBatchResponse;
import com.mannschaft.app.circulation.dto.RecipientStatusEntry;
import com.mannschaft.app.circulation.dto.RemindResponse;
import com.mannschaft.app.circulation.dto.UpdateDocumentRequest;
import com.mannschaft.app.circulation.service.CirculationService;
import com.mannschaft.app.common.ApiResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * Phase 11 第三陣 3-A で追加した Controller のテスト。
 *
 * <ul>
 *   <li>{@link CirculationAdminController} - 5 エンドポイント</li>
 *   <li>{@link OrgCirculationDocumentController} 追加 4 エンドポイント（PATCH/cancel/DELETE/stats）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Circulation Phase 11 第三陣 3-A Controller テスト")
class CirculationAdminControllerTest {

    @Mock
    private CirculationService circulationService;

    @InjectMocks
    private CirculationAdminController adminController;

    private OrgCirculationDocumentController orgController;

    private static final Long USER_ID = 1L;
    private static final Long ORG_ID = 20L;
    private static final Long DOC_ID = 100L;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
        orgController = new OrgCirculationDocumentController(circulationService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private DocumentResponse mockResponse() {
        return DocumentResponse.builder()
                .id(DOC_ID).scopeType("ORGANIZATION").scopeId(ORG_ID).createdBy(USER_ID)
                .title("回覧").body("本文").circulationMode("SIMULTANEOUS").sequentialCount(0)
                .status("COMPLETED").priority("NORMAL").stampDisplayStyle("STANDARD")
                .totalRecipientCount(3).stampedCount(0).attachmentCount(0).commentCount(0)
                .build();
    }

    // ─────────────────────────────────────────────
    // CirculationAdminController
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("CirculationAdminController")
    class Admin {

        @Test
        @DisplayName("強制完了_200_DocumentResponse返却")
        void 強制完了_正常() {
            given(circulationService.forceCompleteDocument(DOC_ID, USER_ID))
                    .willReturn(mockResponse());

            ResponseEntity<ApiResponse<DocumentResponse>> result = adminController.forceComplete(DOC_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getData().getStatus()).isEqualTo("COMPLETED");
        }

        @Test
        @DisplayName("一括強制完了_200_部分成功レスポンス返却")
        void 一括強制完了_正常() {
            ForceCompleteBatchRequest req = new ForceCompleteBatchRequest();
            req.setDocumentIds(List.of(101L, 102L));
            ForceCompleteBatchResponse svc = new ForceCompleteBatchResponse(
                    List.of(101L), List.of(new ForceCompleteBatchResponse.FailureEntry(
                            102L, "CIRCULATION_005", "状態不正")));
            given(circulationService.forceCompleteBatch(eq(List.of(101L, 102L)), eq(USER_ID)))
                    .willReturn(svc);

            ResponseEntity<ApiResponse<ForceCompleteBatchResponse>> result =
                    adminController.forceCompleteBatch(req);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getData().getSucceeded()).hasSize(1);
            assertThat(result.getBody().getData().getFailed()).hasSize(1);
        }

        @Test
        @DisplayName("手動リマインド_200_remindedCount返却")
        void 手動リマインド_正常() {
            given(circulationService.remindDocument(DOC_ID, USER_ID))
                    .willReturn(new RemindResponse(DOC_ID, 5));

            ResponseEntity<ApiResponse<RemindResponse>> result = adminController.remind(DOC_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getData().getRemindedCount()).isEqualTo(5);
        }

        @Test
        @DisplayName("複製_201_新規DocumentResponse返却")
        void 複製_正常_201() {
            given(circulationService.duplicateDocument(DOC_ID, USER_ID))
                    .willReturn(mockResponse());

            ResponseEntity<ApiResponse<DocumentResponse>> result = adminController.duplicate(DOC_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("押印状況一覧_200_受信者リスト返却")
        void 押印状況一覧_正常() {
            DocumentStatusResponse svc = new DocumentStatusResponse(
                    DOC_ID, "ACTIVE",
                    List.of(new RecipientStatusEntry(50L, "佐藤", "STAMPED", null, 0),
                            new RecipientStatusEntry(51L, "鈴木", "PENDING", null, 1)));
            given(circulationService.getDocumentStatus(DOC_ID, USER_ID)).willReturn(svc);

            ResponseEntity<ApiResponse<DocumentStatusResponse>> result = adminController.getStatus(DOC_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getData().getRecipients()).hasSize(2);
        }
    }

    // ─────────────────────────────────────────────
    // OrgCirculationDocumentController 追加分
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("OrgCirculationDocumentController 追加 4 件")
    class OrgAdditions {

        @Test
        @DisplayName("組織回覧文書更新_200")
        void 組織回覧更新_正常() {
            UpdateDocumentRequest req = new UpdateDocumentRequest(
                    "新タイトル", null, null, null, null, null, null);
            given(circulationService.updateDocument(eq("ORGANIZATION"), eq(ORG_ID), eq(DOC_ID), any()))
                    .willReturn(mockResponse());

            ResponseEntity<ApiResponse<DocumentResponse>> result =
                    orgController.updateDocument(ORG_ID, DOC_ID, req);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("組織回覧文書キャンセル_200")
        void 組織回覧キャンセル_正常() {
            given(circulationService.cancelDocument("ORGANIZATION", ORG_ID, DOC_ID))
                    .willReturn(mockResponse());

            ResponseEntity<ApiResponse<DocumentResponse>> result =
                    orgController.cancelDocument(ORG_ID, DOC_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("組織回覧文書削除_204_Serviceに委譲")
        void 組織回覧削除_正常_204() {
            ResponseEntity<Void> result = orgController.deleteDocument(ORG_ID, DOC_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            verify(circulationService).deleteDocument("ORGANIZATION", ORG_ID, DOC_ID);
        }

        @Test
        @DisplayName("組織回覧統計_200_集計値返却")
        void 組織回覧統計_正常() {
            DocumentStatsResponse stats = new DocumentStatsResponse(20L, 5L, 10L, 4L, 1L);
            given(circulationService.getStats("ORGANIZATION", ORG_ID)).willReturn(stats);

            ResponseEntity<ApiResponse<DocumentStatsResponse>> result = orgController.getStats(ORG_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getData().getTotal()).isEqualTo(20L);
        }
    }
}
