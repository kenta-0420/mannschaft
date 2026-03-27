package com.mannschaft.app.filesharing;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.filesharing.dto.CreateFolderRequest;
import com.mannschaft.app.filesharing.dto.FolderResponse;
import com.mannschaft.app.filesharing.dto.UpdateFolderRequest;
import com.mannschaft.app.filesharing.entity.SharedFolderEntity;
import com.mannschaft.app.filesharing.repository.SharedFolderRepository;
import com.mannschaft.app.filesharing.service.SharedFolderService;
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
 * {@link SharedFolderService} の追加単体テスト。未テストメソッドをカバーする。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SharedFolderService 追加単体テスト")
class SharedFolderServiceAdditionalTest {

    @Mock
    private SharedFolderRepository folderRepository;

    @Mock
    private FileSharingMapper fileSharingMapper;

    @InjectMocks
    private SharedFolderService service;

    private static final Long FOLDER_ID = 100L;
    private static final Long TEAM_ID = 1L;
    private static final Long ORG_ID = 2L;
    private static final Long USER_ID = 10L;

    private SharedFolderEntity createFolder(FileScopeType scopeType) {
        return SharedFolderEntity.builder()
                .scopeType(scopeType)
                .teamId(scopeType == FileScopeType.TEAM ? TEAM_ID : null)
                .organizationId(scopeType == FileScopeType.ORGANIZATION ? ORG_ID : null)
                .userId(scopeType == FileScopeType.PERSONAL ? USER_ID : null)
                .name("テストフォルダ")
                .createdBy(USER_ID)
                .build();
    }

    private FolderResponse mockFolderResponse(String scopeType) {
        return new FolderResponse(FOLDER_ID, scopeType, TEAM_ID, null, null,
                null, "テストフォルダ", null, USER_ID, null, null);
    }

    // ========================================
    // listTeamRootFolders
    // ========================================

    @Nested
    @DisplayName("listTeamRootFolders")
    class ListTeamRootFolders {

        @Test
        @DisplayName("正常系: チームルートフォルダ一覧が返却される")
        void チームルートフォルダ一覧_正常() {
            SharedFolderEntity entity = createFolder(FileScopeType.TEAM);
            given(folderRepository.findByTeamIdAndParentIdIsNullOrderByNameAsc(TEAM_ID))
                    .willReturn(List.of(entity));
            given(fileSharingMapper.toFolderResponseList(any()))
                    .willReturn(List.of(mockFolderResponse("TEAM")));

            List<FolderResponse> result = service.listTeamRootFolders(TEAM_ID);

            assertThat(result).hasSize(1);
        }
    }

    // ========================================
    // listOrgRootFolders
    // ========================================

    @Nested
    @DisplayName("listOrgRootFolders")
    class ListOrgRootFolders {

        @Test
        @DisplayName("正常系: 組織ルートフォルダ一覧が返却される")
        void 組織ルートフォルダ一覧_正常() {
            SharedFolderEntity entity = createFolder(FileScopeType.ORGANIZATION);
            given(folderRepository.findByOrganizationIdAndParentIdIsNullOrderByNameAsc(ORG_ID))
                    .willReturn(List.of(entity));
            given(fileSharingMapper.toFolderResponseList(any()))
                    .willReturn(List.of(mockFolderResponse("ORGANIZATION")));

            List<FolderResponse> result = service.listOrgRootFolders(ORG_ID);

            assertThat(result).hasSize(1);
        }
    }

    // ========================================
    // listPersonalRootFolders
    // ========================================

    @Nested
    @DisplayName("listPersonalRootFolders")
    class ListPersonalRootFolders {

        @Test
        @DisplayName("正常系: 個人ルートフォルダ一覧が返却される")
        void 個人ルートフォルダ一覧_正常() {
            SharedFolderEntity entity = createFolder(FileScopeType.PERSONAL);
            given(folderRepository.findByUserIdAndScopeTypeAndParentIdIsNullOrderByNameAsc(
                    USER_ID, FileScopeType.PERSONAL))
                    .willReturn(List.of(entity));
            given(fileSharingMapper.toFolderResponseList(any()))
                    .willReturn(List.of(mockFolderResponse("PERSONAL")));

            List<FolderResponse> result = service.listPersonalRootFolders(USER_ID);

            assertThat(result).hasSize(1);
        }
    }

    // ========================================
    // listChildFolders
    // ========================================

    @Nested
    @DisplayName("listChildFolders")
    class ListChildFolders {

        @Test
        @DisplayName("正常系: 子フォルダ一覧が返却される")
        void 子フォルダ一覧_正常() {
            SharedFolderEntity entity = createFolder(FileScopeType.TEAM);
            given(folderRepository.findByParentIdOrderByNameAsc(FOLDER_ID))
                    .willReturn(List.of(entity));
            given(fileSharingMapper.toFolderResponseList(any()))
                    .willReturn(List.of(mockFolderResponse("TEAM")));

            List<FolderResponse> result = service.listChildFolders(FOLDER_ID);

            assertThat(result).hasSize(1);
        }
    }

    // ========================================
    // getFolder
    // ========================================

    @Nested
    @DisplayName("getFolder")
    class GetFolder {

        @Test
        @DisplayName("正常系: フォルダ詳細が返却される")
        void フォルダ詳細_正常() {
            SharedFolderEntity entity = createFolder(FileScopeType.TEAM);
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(entity));
            given(fileSharingMapper.toFolderResponse(entity))
                    .willReturn(mockFolderResponse("TEAM"));

            FolderResponse result = service.getFolder(FOLDER_ID);

            assertThat(result.getName()).isEqualTo("テストフォルダ");
        }

        @Test
        @DisplayName("異常系: フォルダ不在でFOLDER_NOT_FOUND例外")
        void フォルダ詳細_不在_例外() {
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.getFolder(FOLDER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.FOLDER_NOT_FOUND));
        }
    }

    // ========================================
    // createOrgFolder
    // ========================================

    @Nested
    @DisplayName("createOrgFolder")
    class CreateOrgFolder {

        @Test
        @DisplayName("正常系: 組織フォルダが作成される")
        void 組織フォルダ作成_正常() {
            CreateFolderRequest request = new CreateFolderRequest("組織フォルダ", null, null, "ORGANIZATION");
            SharedFolderEntity savedEntity = createFolder(FileScopeType.ORGANIZATION);
            given(folderRepository.existsByParentIdAndName(null, "組織フォルダ")).willReturn(false);
            given(folderRepository.save(any())).willReturn(savedEntity);
            given(fileSharingMapper.toFolderResponse(savedEntity))
                    .willReturn(mockFolderResponse("ORGANIZATION"));

            FolderResponse result = service.createOrgFolder(ORG_ID, USER_ID, request);

            assertThat(result).isNotNull();
        }
    }

    // ========================================
    // createPersonalFolder
    // ========================================

    @Nested
    @DisplayName("createPersonalFolder")
    class CreatePersonalFolder {

        @Test
        @DisplayName("正常系: 個人フォルダが作成される")
        void 個人フォルダ作成_正常() {
            CreateFolderRequest request = new CreateFolderRequest("個人フォルダ", null, null, "PERSONAL");
            SharedFolderEntity savedEntity = createFolder(FileScopeType.PERSONAL);
            given(folderRepository.existsByParentIdAndName(null, "個人フォルダ")).willReturn(false);
            given(folderRepository.save(any())).willReturn(savedEntity);
            given(fileSharingMapper.toFolderResponse(savedEntity))
                    .willReturn(mockFolderResponse("PERSONAL"));

            FolderResponse result = service.createPersonalFolder(USER_ID, request);

            assertThat(result).isNotNull();
        }
    }

    // ========================================
    // updateFolder
    // ========================================

    @Nested
    @DisplayName("updateFolder")
    class UpdateFolder {

        @Test
        @DisplayName("正常系: フォルダ名・説明・親フォルダが更新される")
        void フォルダ更新_全フィールド_正常() {
            SharedFolderEntity entity = createFolder(FileScopeType.TEAM);
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(entity));
            given(folderRepository.save(entity)).willReturn(entity);
            given(fileSharingMapper.toFolderResponse(entity))
                    .willReturn(mockFolderResponse("TEAM"));
            UpdateFolderRequest request = new UpdateFolderRequest("新名前", "新説明", 50L);

            FolderResponse result = service.updateFolder(FOLDER_ID, request);

            assertThat(result).isNotNull();
            assertThat(entity.getName()).isEqualTo("新名前");
            assertThat(entity.getDescription()).isEqualTo("新説明");
            assertThat(entity.getParentId()).isEqualTo(50L);
        }

        @Test
        @DisplayName("正常系: フィールドがnullの場合は更新されない")
        void フォルダ更新_全nullフィールド_変化なし() {
            SharedFolderEntity entity = createFolder(FileScopeType.TEAM);
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(entity));
            given(folderRepository.save(entity)).willReturn(entity);
            given(fileSharingMapper.toFolderResponse(entity))
                    .willReturn(mockFolderResponse("TEAM"));
            UpdateFolderRequest request = new UpdateFolderRequest(null, null, null);

            service.updateFolder(FOLDER_ID, request);

            assertThat(entity.getName()).isEqualTo("テストフォルダ");
        }
    }
}
