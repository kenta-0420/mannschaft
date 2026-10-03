package com.mannschaft.app.filesharing;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.filesharing.controller.SharedFolderController;
import com.mannschaft.app.filesharing.dto.CreateFolderRequest;
import com.mannschaft.app.filesharing.dto.FolderDetailResponse;
import com.mannschaft.app.filesharing.service.SharedFolderQueryService;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

/**
 * {@link SharedFolderController} の単体テスト（401/403/404/200 マトリクス）。
 *
 * <p>認可そのものは {@link SharedFolderQueryService} が担うため、コントローラ層では
 * (1) 未認証で 401（COMMON_000）になること、(2) 正常系の配線、(3) サービスが投げた
 * 認可例外（403/404）がそのまま伝播することを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SharedFolderController 単体テスト")
class SharedFolderControllerTest {

    @Mock
    private SharedFolderQueryService folderQueryService;

    @InjectMocks
    private SharedFolderController controller;

    private static final Long USER_ID = 1L;
    private static final Long FOLDER_ID = 200L;
    private static final Long TEAM_ID = 10L;

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private FolderDetailResponse mockDetail() {
        return new FolderDetailResponse(
                FOLDER_ID, "TEAM", String.valueOf(TEAM_ID), null, "フォルダ", null,
                new FolderDetailResponse.UserRef(USER_ID, "u1"), 0, 0, null, null, null, null,
                List.of(), List.of(), List.of(new FolderDetailResponse.BreadcrumbItem(FOLDER_ID, "フォルダ")));
    }

    private FolderDetailResponse.FolderSummary mockSummary() {
        return new FolderDetailResponse.FolderSummary(
                FOLDER_ID, "TEAM", String.valueOf(TEAM_ID), null, "フォルダ", null,
                new FolderDetailResponse.UserRef(USER_ID, "u1"), 0, null, null, null, null, null);
    }

    @Nested
    @DisplayName("AC-7: 未認証は 401（COMMON_000）")
    class Unauthenticated {

        @BeforeEach
        void noAuth() {
            SecurityContextHolder.clearContext();
        }

        @Test
        @DisplayName("詳細取得は未認証で COMMON_000（401）を投げる")
        void 詳細_未認証_401() {
            assertThatThrownBy(() -> controller.getFolderDetail(FOLDER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_000));
        }

        @Test
        @DisplayName("AC-FD-2: フォルダ削除は未認証で COMMON_000（401）を投げる")
        void 削除_未認証_401() {
            assertThatThrownBy(() -> controller.deleteFolder(FOLDER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_000));
        }
    }

    @Nested
    @DisplayName("正常系（200/201）")
    class Success {

        @BeforeEach
        void auth() {
            authenticate();
        }

        @Test
        @DisplayName("AC-1/200: フォルダ詳細が data 配下で返る")
        void 詳細_200() {
            given(folderQueryService.getFolderDetail(FOLDER_ID, USER_ID)).willReturn(mockDetail());

            ResponseEntity<ApiResponse<FolderDetailResponse>> result =
                    controller.getFolderDetail(FOLDER_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getData().subfolders()).isEmpty();
            assertThat(result.getBody().getData().breadcrumbs()).hasSize(1);
        }

        @Test
        @DisplayName("200: フォルダ一覧が返る")
        void 一覧_200() {
            given(folderQueryService.listFolders(eq("TEAM"), eq(String.valueOf(TEAM_ID)), eq(null), eq(USER_ID)))
                    .willReturn(List.of(mockSummary()));

            ResponseEntity<ApiResponse<List<FolderDetailResponse.FolderSummary>>> result =
                    controller.listFolders("TEAM", String.valueOf(TEAM_ID), null);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getData()).hasSize(1);
        }

        @Test
        @DisplayName("201: フォルダ作成は 201 Created で返る")
        void 作成_201() {
            CreateFolderRequest request =
                    new CreateFolderRequest("新規", null, null, "TEAM", String.valueOf(TEAM_ID), null, null);
            given(folderQueryService.createFolder(any(), eq(String.valueOf(TEAM_ID)), eq(USER_ID)))
                    .willReturn(mockSummary());

            ResponseEntity<ApiResponse<FolderDetailResponse.FolderSummary>> result =
                    controller.createFolder(request);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("AC-FD-1/204: フォルダ削除は 204 No Content で返り service に委譲する")
        void 削除_204() {
            ResponseEntity<Void> result = controller.deleteFolder(FOLDER_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            verify(folderQueryService).deleteFolder(FOLDER_ID, USER_ID);
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
        @DisplayName("AC-8/9/403: サービスの COMMON_002 がそのまま伝播する")
        void 詳細_403伝播() {
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(folderQueryService).getFolderDetail(FOLDER_ID, USER_ID);

            assertThatThrownBy(() -> controller.getFolderDetail(FOLDER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
        }

        @Test
        @DisplayName("AC-10/12/404: サービスの FOLDER_NOT_FOUND がそのまま伝播する")
        void 詳細_404伝播() {
            willThrow(new BusinessException(FileSharingErrorCode.FOLDER_NOT_FOUND))
                    .given(folderQueryService).getFolderDetail(FOLDER_ID, USER_ID);

            assertThatThrownBy(() -> controller.getFolderDetail(FOLDER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.FOLDER_NOT_FOUND));
            verify(folderQueryService).getFolderDetail(FOLDER_ID, USER_ID);
        }

        @Test
        @DisplayName("AC-FD-3/4/5: 削除でサービスが投げた認可/不存在例外がそのまま伝播する")
        void 削除_認可例外伝播() {
            willThrow(new BusinessException(FileSharingErrorCode.FOLDER_NOT_FOUND))
                    .given(folderQueryService).deleteFolder(FOLDER_ID, USER_ID);

            assertThatThrownBy(() -> controller.deleteFolder(FOLDER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.FOLDER_NOT_FOUND));
        }
    }
}
