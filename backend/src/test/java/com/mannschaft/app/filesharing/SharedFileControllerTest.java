package com.mannschaft.app.filesharing;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.filesharing.controller.SharedFileController;
import com.mannschaft.app.filesharing.dto.FileResponse;
import com.mannschaft.app.filesharing.service.SharedFileService;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

/**
 * {@link SharedFileController} の単体テスト（IDOR 封鎖の 401/403/404/200 マトリクス）。
 *
 * <p>一覧・詳細の認可そのものは {@link SharedFileService} → {@link com.mannschaft.app.filesharing.service.SharedFolderQueryService}
 * が担うため、コントローラ層では (1) 未認証で 401（COMMON_000）になること（AC-A6）、
 * (2) {@code SecurityUtils.getCurrentUserId()} で解決したユーザー ID を Service へ渡すこと、
 * (3) Service が投げた認可例外（403 COMMON_002 / 404 FILE_SHARING_00x）がそのまま伝播することを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SharedFileController 単体テスト（IDOR 封鎖）")
class SharedFileControllerTest {

    @Mock
    private SharedFileService fileService;

    @InjectMocks
    private SharedFileController controller;

    private static final Long USER_ID = 10L;
    private static final Long FOLDER_ID = 1L;
    private static final Long FILE_ID = 100L;

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private FileResponse mockFile() {
        return new FileResponse(FILE_ID, FOLDER_ID, "doc.pdf", "files/TEAM/5/x.pdf",
                2048L, "application/pdf", null, USER_ID, 1, null, null);
    }

    @Nested
    @DisplayName("AC-A6: 未認証は 401（COMMON_000）")
    class Unauthenticated {

        @BeforeEach
        void noAuth() {
            SecurityContextHolder.clearContext();
        }

        @Test
        @DisplayName("一覧は未認証で COMMON_000（401）を投げる")
        void 一覧_未認証_401() {
            assertThatThrownBy(() -> controller.listFiles(FOLDER_ID, 0, 20))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_000));
        }

        @Test
        @DisplayName("詳細は未認証で COMMON_000（401）を投げる")
        void 詳細_未認証_401() {
            assertThatThrownBy(() -> controller.getFile(FILE_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_000));
        }
    }

    @Nested
    @DisplayName("正常系（200）— currentUserId を Service へ渡す")
    class Success {

        @BeforeEach
        void auth() {
            authenticate();
        }

        @Test
        @DisplayName("一覧: 認可済みユーザー ID を listFilesPaged へ渡し 200 で返る")
        void 一覧_200_userId伝播() {
            Page<FileResponse> page = new PageImpl<>(List.of(mockFile()), PageRequest.of(0, 20), 1);
            given(fileService.listFilesPaged(eq(FOLDER_ID), eq(USER_ID), any())).willReturn(page);

            ResponseEntity<PagedResponse<FileResponse>> result = controller.listFiles(FOLDER_ID, 0, 20);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getData()).hasSize(1);
            // IDOR 封鎖の要: folderId だけでなく currentUserId を必ず Service へ渡す
            verify(fileService).listFilesPaged(eq(FOLDER_ID), eq(USER_ID), any());
        }

        @Test
        @DisplayName("詳細: 認可済みユーザー ID を getFile へ渡し 200 で返る")
        void 詳細_200_userId伝播() {
            given(fileService.getFile(FILE_ID, USER_ID)).willReturn(mockFile());

            ResponseEntity<ApiResponse<FileResponse>> result = controller.getFile(FILE_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getData().getId()).isEqualTo(FILE_ID);
            verify(fileService).getFile(FILE_ID, USER_ID);
        }
    }

    @Nested
    @DisplayName("認可例外の伝播（403/404）")
    class AuthzPropagation {

        @BeforeEach
        void auth() {
            authenticate();
        }

        @Test
        @DisplayName("AC-A1: 一覧で TEAM 非会員の COMMON_002（403）が伝播する")
        void 一覧_403伝播() {
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(fileService).listFilesPaged(eq(FOLDER_ID), eq(USER_ID), any());

            assertThatThrownBy(() -> controller.listFiles(FOLDER_ID, 0, 20))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
        }

        @Test
        @DisplayName("AC-A2: 一覧で他人 PERSONAL の FOLDER_NOT_FOUND（404）が伝播する")
        void 一覧_404伝播() {
            willThrow(new BusinessException(FileSharingErrorCode.FOLDER_NOT_FOUND))
                    .given(fileService).listFilesPaged(eq(FOLDER_ID), eq(USER_ID), any());

            assertThatThrownBy(() -> controller.listFiles(FOLDER_ID, 0, 20))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.FOLDER_NOT_FOUND));
        }

        @Test
        @DisplayName("AC-A3: 詳細で他チームの COMMON_002（403）が伝播する")
        void 詳細_403伝播() {
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(fileService).getFile(FILE_ID, USER_ID);

            assertThatThrownBy(() -> controller.getFile(FILE_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
        }

        @Test
        @DisplayName("AC-A3: 詳細で他人 PERSONAL / 不存在の 404 が伝播する")
        void 詳細_404伝播() {
            willThrow(new BusinessException(FileSharingErrorCode.FOLDER_NOT_FOUND))
                    .given(fileService).getFile(FILE_ID, USER_ID);

            assertThatThrownBy(() -> controller.getFile(FILE_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.FOLDER_NOT_FOUND));
        }
    }
}
