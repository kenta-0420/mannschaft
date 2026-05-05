package com.mannschaft.app.filesharing;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.filesharing.controller.SharedFileController;
import com.mannschaft.app.filesharing.controller.TeamFolderController;
import com.mannschaft.app.filesharing.dto.CreateFileRequest;
import com.mannschaft.app.filesharing.dto.CreateFolderRequest;
import com.mannschaft.app.filesharing.dto.FileResponse;
import com.mannschaft.app.filesharing.dto.FolderResponse;
import com.mannschaft.app.filesharing.dto.UpdateFileRequest;
import com.mannschaft.app.filesharing.dto.UpdateFolderRequest;
import com.mannschaft.app.filesharing.service.SharedFileService;
import com.mannschaft.app.filesharing.service.SharedFolderService;
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
 * SharedFileController / TeamFolderController の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FileSharingController 単体テスト")
class FileSharingControllerTest {

    @Mock
    private SharedFileService fileService;

    @Mock
    private SharedFolderService folderService;

    @InjectMocks
    private SharedFileController fileController;

    private TeamFolderController teamFolderController;

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long FILE_ID = 100L;
    private static final Long FOLDER_ID = 200L;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
        teamFolderController = new TeamFolderController(folderService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private FileResponse mockFileResponse() {
        return new FileResponse(FILE_ID, FOLDER_ID, "test.pdf", "uploads/test.pdf",
                1024L, "application/pdf", "説明", USER_ID, 1, null, null);
    }

    private FolderResponse mockFolderResponse() {
        return new FolderResponse(FOLDER_ID, "TEAM", TEAM_ID, null, null, null,
                "テストフォルダ", "説明", USER_ID, null, null);
    }

    // ========================================
    // SharedFileController
    // ========================================

    @Nested
    @DisplayName("SharedFileController")
    class SharedFileControllerTests {

        @Test
        @DisplayName("正常系: フォルダ内のファイル一覧が返却される")
        void ファイル一覧_正常() {
            Page<FileResponse> page = new PageImpl<>(List.of(mockFileResponse()));
            given(fileService.listFilesPaged(eq(FOLDER_ID), any())).willReturn(page);

            ResponseEntity<PagedResponse<FileResponse>> result =
                    fileController.listFiles(FOLDER_ID, 0, 20);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getData()).hasSize(1);
        }

        @Test
        @DisplayName("正常系: ファイル詳細が返却される")
        void ファイル詳細_正常() {
            given(fileService.getFile(FILE_ID)).willReturn(mockFileResponse());

            ResponseEntity<ApiResponse<FileResponse>> result = fileController.getFile(FILE_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getData().getName()).isEqualTo("test.pdf");
        }

        @Test
        @DisplayName("正常系: ファイルが作成される (201)")
        void ファイル作成_正常_201() {
            CreateFileRequest request = new CreateFileRequest(
                    FOLDER_ID, "test.pdf", "uploads/test.pdf", 1024L, "application/pdf", null);
            given(fileService.createFile(eq(USER_ID), any())).willReturn(mockFileResponse());

            ResponseEntity<ApiResponse<FileResponse>> result = fileController.createFile(request);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("正常系: ファイルが更新される")
        void ファイル更新_正常() {
            UpdateFileRequest request = new UpdateFileRequest("renamed.pdf", null, null);
            given(fileService.updateFile(eq(FILE_ID), any())).willReturn(mockFileResponse());

            ResponseEntity<ApiResponse<FileResponse>> result =
                    fileController.updateFile(FILE_ID, request);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("正常系: ファイルが削除される (204)")
        void ファイル削除_正常_204() {
            ResponseEntity<Void> result = fileController.deleteFile(FILE_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            // F13 Phase 4-ε: actorId（USER_ID）を渡す呼び出しに変更済み
            verify(fileService).deleteFile(FILE_ID, USER_ID);
        }
    }

    // ========================================
    // TeamFolderController
    // ========================================

    @Nested
    @DisplayName("TeamFolderController")
    class TeamFolderControllerTests {

        @Test
        @DisplayName("正常系: チームルートフォルダ一覧が返却される")
        void チームルートフォルダ一覧_正常() {
            given(folderService.listTeamRootFolders(TEAM_ID))
                    .willReturn(List.of(mockFolderResponse()));

            ResponseEntity<ApiResponse<List<FolderResponse>>> result =
                    teamFolderController.listRootFolders(TEAM_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getData()).hasSize(1);
        }

        @Test
        @DisplayName("正常系: 子フォルダ一覧が返却される")
        void 子フォルダ一覧_正常() {
            given(folderService.listChildFolders(FOLDER_ID))
                    .willReturn(List.of(mockFolderResponse()));

            ResponseEntity<ApiResponse<List<FolderResponse>>> result =
                    teamFolderController.listChildFolders(TEAM_ID, FOLDER_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getData()).hasSize(1);
        }

        @Test
        @DisplayName("正常系: フォルダ詳細が返却される")
        void フォルダ詳細_正常() {
            given(folderService.getFolder(FOLDER_ID)).willReturn(mockFolderResponse());

            ResponseEntity<ApiResponse<FolderResponse>> result =
                    teamFolderController.getFolder(TEAM_ID, FOLDER_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getData().getName()).isEqualTo("テストフォルダ");
        }

        @Test
        @DisplayName("正常系: チームフォルダが作成される (201)")
        void チームフォルダ作成_正常_201() {
            CreateFolderRequest request = new CreateFolderRequest("新フォルダ", null, null, "TEAM");
            given(folderService.createTeamFolder(eq(TEAM_ID), eq(USER_ID), any()))
                    .willReturn(mockFolderResponse());

            ResponseEntity<ApiResponse<FolderResponse>> result =
                    teamFolderController.createFolder(TEAM_ID, request);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("正常系: フォルダが更新される")
        void フォルダ更新_正常() {
            UpdateFolderRequest request = new UpdateFolderRequest("更新フォルダ", null, null);
            given(folderService.updateFolder(eq(FOLDER_ID), any())).willReturn(mockFolderResponse());

            ResponseEntity<ApiResponse<FolderResponse>> result =
                    teamFolderController.updateFolder(TEAM_ID, FOLDER_ID, request);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("正常系: フォルダが削除される (204)")
        void フォルダ削除_正常_204() {
            ResponseEntity<Void> result = teamFolderController.deleteFolder(TEAM_ID, FOLDER_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            verify(folderService).deleteFolder(FOLDER_ID);
        }
    }
}
