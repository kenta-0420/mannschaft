package com.mannschaft.app.seal.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.seal.dto.CreateSealRequest;
import com.mannschaft.app.seal.dto.SealResponse;
import com.mannschaft.app.seal.dto.UpdateSealRequest;
import com.mannschaft.app.seal.service.SealService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.Mockito.verify;

/**
 * {@link SealController} の単体テスト。
 * 印鑑CRUD APIを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SealController 単体テスト")
class SealControllerTest {

    @Mock
    private SealService sealService;

    @InjectMocks
    private SealController controller;

    private static final Long USER_ID = 1L;
    private static final Long SEAL_ID = 10L;

    private SealResponse buildSealResponse() {
        return new SealResponse(SEAL_ID, USER_ID, "LAST_NAME", "田中",
                "<svg/>", "hash123", 1, null, null);
    }

    // ========================================
    // listSeals
    // ========================================
    @Nested
    @DisplayName("listSeals")
    class ListSeals {

        @Test
        @DisplayName("正常系: 印鑑一覧が返される")
        void 印鑑一覧取得() {
            given(sealService.listSeals(USER_ID)).willReturn(List.of(buildSealResponse()));

            ResponseEntity<ApiResponse<List<SealResponse>>> response =
                    controller.listSeals(USER_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData()).hasSize(1);
        }
    }

    // ========================================
    // getSeal
    // ========================================
    @Nested
    @DisplayName("getSeal")
    class GetSeal {

        @Test
        @DisplayName("正常系: 印鑑詳細が返される")
        void 印鑑詳細取得() {
            given(sealService.getSeal(USER_ID, SEAL_ID)).willReturn(buildSealResponse());

            ResponseEntity<ApiResponse<SealResponse>> response =
                    controller.getSeal(USER_ID, SEAL_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData().getId()).isEqualTo(SEAL_ID);
            assertThat(response.getBody().getData().getVariant()).isEqualTo("LAST_NAME");
        }
    }

    // ========================================
    // createSeal
    // ========================================
    @Nested
    @DisplayName("createSeal")
    class CreateSeal {

        @Test
        @DisplayName("正常系: 印鑑が作成され 201 が返される")
        void 印鑑作成() {
            given(sealService.createSeal(eq(USER_ID), any(CreateSealRequest.class)))
                    .willReturn(buildSealResponse());
            CreateSealRequest request = new CreateSealRequest("LAST_NAME", "田中");

            ResponseEntity<ApiResponse<SealResponse>> response =
                    controller.createSeal(USER_ID, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody().getData().getDisplayText()).isEqualTo("田中");
        }
    }

    // ========================================
    // updateSeal
    // ========================================
    @Nested
    @DisplayName("updateSeal")
    class UpdateSeal {

        @Test
        @DisplayName("正常系: 印鑑が更新される")
        void 印鑑更新() {
            SealResponse updated = new SealResponse(SEAL_ID, USER_ID, "LAST_NAME", "田中二郎",
                    "<svg/>", "hash456", 2, null, null);
            given(sealService.updateSeal(eq(USER_ID), eq(SEAL_ID), any(UpdateSealRequest.class)))
                    .willReturn(updated);
            UpdateSealRequest request = new UpdateSealRequest("田中二郎");

            ResponseEntity<ApiResponse<SealResponse>> response =
                    controller.updateSeal(USER_ID, SEAL_ID, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData().getDisplayText()).isEqualTo("田中二郎");
        }
    }

    // ========================================
    // deleteSeal
    // ========================================
    @Nested
    @DisplayName("deleteSeal")
    class DeleteSeal {

        @Test
        @DisplayName("正常系: 印鑑が削除され 204 が返される")
        void 印鑑削除() {
            willDoNothing().given(sealService).deleteSeal(USER_ID, SEAL_ID);

            ResponseEntity<Void> response = controller.deleteSeal(USER_ID, SEAL_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            verify(sealService).deleteSeal(USER_ID, SEAL_ID);
        }
    }
}
