package com.mannschaft.app.circulation;

import com.mannschaft.app.circulation.controller.OrgCirculationDocumentController;
import com.mannschaft.app.circulation.controller.TeamCirculationDocumentController;
import com.mannschaft.app.circulation.dto.CreateDocumentRequest;
import com.mannschaft.app.circulation.dto.DocumentResponse;
import com.mannschaft.app.circulation.dto.DocumentStatsResponse;
import com.mannschaft.app.circulation.dto.RecipientEntry;
import com.mannschaft.app.circulation.dto.UpdateDocumentRequest;
import com.mannschaft.app.circulation.service.CirculationService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
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
 * TeamCirculationDocumentController / OrgCirculationDocumentController の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CirculationController 単体テスト")
class CirculationControllerTest {

    @Mock
    private CirculationService circulationService;

    @InjectMocks
    private TeamCirculationDocumentController teamController;

    private OrgCirculationDocumentController orgController;

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 10L;
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

    private DocumentResponse mockDocumentResponse() {
        return new DocumentResponse(DOC_ID, "TEAM", TEAM_ID, USER_ID,
                "回覧文書", "本文", "SIMULTANEOUS", 0, "DRAFT", "NORMAL",
                null, false, (short) 24, "STANDARD", 3, 0, null, 0, 0,
                null, null);
    }

    // ========================================
    // TeamCirculationDocumentController
    // ========================================

    @Nested
    @DisplayName("TeamCirculationDocumentController")
    class TeamController {

        @Test
        @DisplayName("正常系: チーム回覧文書一覧が返却される")
        void チーム回覧文書一覧_正常() {
            Page<DocumentResponse> page = new PageImpl<>(List.of(mockDocumentResponse()));
            given(circulationService.listDocuments(eq("TEAM"), eq(TEAM_ID), eq(null), any()))
                    .willReturn(page);

            ResponseEntity<PagedResponse<DocumentResponse>> result =
                    teamController.listDocuments(TEAM_ID, null, 0, 20);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getData()).hasSize(1);
        }

        @Test
        @DisplayName("正常系: ステータスフィルタで文書一覧が返却される")
        void チーム回覧文書一覧_ステータスフィルタ_正常() {
            Page<DocumentResponse> page = new PageImpl<>(List.of(mockDocumentResponse()));
            given(circulationService.listDocuments(eq("TEAM"), eq(TEAM_ID), eq("ACTIVE"), any()))
                    .willReturn(page);

            ResponseEntity<PagedResponse<DocumentResponse>> result =
                    teamController.listDocuments(TEAM_ID, "ACTIVE", 0, 20);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("正常系: チーム回覧文書詳細が返却される")
        void チーム回覧文書詳細_正常() {
            given(circulationService.getDocument("TEAM", TEAM_ID, DOC_ID))
                    .willReturn(mockDocumentResponse());

            ResponseEntity<ApiResponse<DocumentResponse>> result =
                    teamController.getDocument(TEAM_ID, DOC_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getData().getTitle()).isEqualTo("回覧文書");
        }

        @Test
        @DisplayName("正常系: チーム回覧文書が作成される (201)")
        void チーム回覧文書作成_正常_201() {
            CreateDocumentRequest request = new CreateDocumentRequest(
                    "新文書", "本文", null, null, null, null, null, null,
                    List.of(new RecipientEntry(50L, null)));
            given(circulationService.createDocument(eq("TEAM"), eq(TEAM_ID), eq(USER_ID), any()))
                    .willReturn(mockDocumentResponse());

            ResponseEntity<ApiResponse<DocumentResponse>> result =
                    teamController.createDocument(TEAM_ID, request);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("正常系: チーム回覧文書が更新される")
        void チーム回覧文書更新_正常() {
            UpdateDocumentRequest request = new UpdateDocumentRequest(
                    "更新タイトル", "更新本文", null, null, null, null, null);
            given(circulationService.updateDocument(eq("TEAM"), eq(TEAM_ID), eq(DOC_ID), any()))
                    .willReturn(mockDocumentResponse());

            ResponseEntity<ApiResponse<DocumentResponse>> result =
                    teamController.updateDocument(TEAM_ID, DOC_ID, request);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("正常系: チーム回覧文書が公開される")
        void チーム回覧文書公開_正常() {
            given(circulationService.activateDocument("TEAM", TEAM_ID, DOC_ID))
                    .willReturn(mockDocumentResponse());

            ResponseEntity<ApiResponse<DocumentResponse>> result =
                    teamController.activateDocument(TEAM_ID, DOC_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("正常系: チーム回覧文書がキャンセルされる")
        void チーム回覧文書キャンセル_正常() {
            given(circulationService.cancelDocument("TEAM", TEAM_ID, DOC_ID))
                    .willReturn(mockDocumentResponse());

            ResponseEntity<ApiResponse<DocumentResponse>> result =
                    teamController.cancelDocument(TEAM_ID, DOC_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("正常系: チーム回覧文書が削除される (204)")
        void チーム回覧文書削除_正常_204() {
            ResponseEntity<Void> result = teamController.deleteDocument(TEAM_ID, DOC_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            verify(circulationService).deleteDocument("TEAM", TEAM_ID, DOC_ID);
        }

        @Test
        @DisplayName("正常系: チーム回覧統計が返却される")
        void チーム回覧統計_正常() {
            DocumentStatsResponse stats = new DocumentStatsResponse(10L, 2L, 5L, 2L, 1L);
            given(circulationService.getStats("TEAM", TEAM_ID)).willReturn(stats);

            ResponseEntity<ApiResponse<DocumentStatsResponse>> result = teamController.getStats(TEAM_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getData().getTotal()).isEqualTo(10L);
        }
    }

    // ========================================
    // OrgCirculationDocumentController
    // ========================================

    @Nested
    @DisplayName("OrgCirculationDocumentController")
    class OrgControllerTests {

        @Test
        @DisplayName("正常系: 組織回覧文書一覧が返却される")
        void 組織回覧文書一覧_正常() {
            DocumentResponse orgDoc = new DocumentResponse(DOC_ID, "ORGANIZATION", ORG_ID, USER_ID,
                    "組織文書", "本文", "SIMULTANEOUS", 0, "DRAFT", "NORMAL",
                    null, false, (short) 24, "STANDARD", 2, 0, null, 0, 0, null, null);
            Page<DocumentResponse> page = new PageImpl<>(List.of(orgDoc));
            given(circulationService.listDocuments(eq("ORGANIZATION"), eq(ORG_ID), eq(null), any()))
                    .willReturn(page);

            ResponseEntity<PagedResponse<DocumentResponse>> result =
                    orgController.listDocuments(ORG_ID, null, 0, 20);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getData()).hasSize(1);
        }

        @Test
        @DisplayName("正常系: 組織回覧文書詳細が返却される")
        void 組織回覧文書詳細_正常() {
            DocumentResponse orgDoc = new DocumentResponse(DOC_ID, "ORGANIZATION", ORG_ID, USER_ID,
                    "組織文書", "本文", "SIMULTANEOUS", 0, "DRAFT", "NORMAL",
                    null, false, (short) 24, "STANDARD", 2, 0, null, 0, 0, null, null);
            given(circulationService.getDocument("ORGANIZATION", ORG_ID, DOC_ID)).willReturn(orgDoc);

            ResponseEntity<ApiResponse<DocumentResponse>> result = orgController.getDocument(ORG_ID, DOC_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("正常系: 組織回覧文書が作成される (201)")
        void 組織回覧文書作成_正常_201() {
            CreateDocumentRequest request = new CreateDocumentRequest(
                    "組織文書", "本文", null, null, null, null, null, null,
                    List.of(new RecipientEntry(50L, null)));
            DocumentResponse orgDoc = new DocumentResponse(DOC_ID, "ORGANIZATION", ORG_ID, USER_ID,
                    "組織文書", "本文", "SIMULTANEOUS", 0, "DRAFT", "NORMAL",
                    null, false, (short) 24, "STANDARD", 1, 0, null, 0, 0, null, null);
            given(circulationService.createDocument(eq("ORGANIZATION"), eq(ORG_ID), eq(USER_ID), any()))
                    .willReturn(orgDoc);

            ResponseEntity<ApiResponse<DocumentResponse>> result =
                    orgController.createDocument(ORG_ID, request);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("正常系: 組織回覧文書が公開される")
        void 組織回覧文書公開_正常() {
            DocumentResponse orgDoc = new DocumentResponse(DOC_ID, "ORGANIZATION", ORG_ID, USER_ID,
                    "組織文書", "本文", "SIMULTANEOUS", 0, "ACTIVE", "NORMAL",
                    null, false, (short) 24, "STANDARD", 1, 0, null, 0, 0, null, null);
            given(circulationService.activateDocument("ORGANIZATION", ORG_ID, DOC_ID))
                    .willReturn(orgDoc);

            ResponseEntity<ApiResponse<DocumentResponse>> result =
                    orgController.activateDocument(ORG_ID, DOC_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }
}
