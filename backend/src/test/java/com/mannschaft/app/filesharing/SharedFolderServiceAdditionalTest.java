package com.mannschaft.app.filesharing;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.filesharing.dto.CreateFolderRequest;
import com.mannschaft.app.filesharing.dto.FolderResponse;
import com.mannschaft.app.filesharing.dto.UpdateFolderRequest;
import com.mannschaft.app.filesharing.entity.SharedFolderEntity;
import com.mannschaft.app.filesharing.repository.SharedFolderRepository;
import com.mannschaft.app.filesharing.service.FolderScopeAccessGuard;
import com.mannschaft.app.filesharing.service.SharedFolderAccessGuard;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
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

    @Mock
    private FolderScopeAccessGuard folderScopeAccessGuard;

    /** 認可根治 Wave7: SharedFolderService に per-scope 認可が入ったため注入対象に追加。 */
    @Mock
    private AccessControlService accessControlService;

    /**
     * 親フォルダの接ぎ木封鎖を担う認可ガード。
     * 判定そのものの網羅検証は {@link SharedFolderAccessGuardTest} が担い、
     * 本テストはサービスがこのガードへ確実に委譲し、その拒否を伝播することを検証する。
     */
    @Mock
    private SharedFolderAccessGuard folderAccessGuard;

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
                null, "テストフォルダ", null, USER_ID, null, null, null, null);
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

            List<FolderResponse> result = service.listTeamRootFolders(TEAM_ID, USER_ID);

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

            List<FolderResponse> result = service.listOrgRootFolders(ORG_ID, USER_ID);

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
            // 認可根治 Wave7: 親フォルダ実体のスコープで閲覧認可を当てるため findById が必要。
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(entity));
            given(folderRepository.findByParentIdOrderByNameAsc(FOLDER_ID))
                    .willReturn(List.of(entity));
            given(fileSharingMapper.toFolderResponseList(any()))
                    .willReturn(List.of(mockFolderResponse("TEAM")));

            List<FolderResponse> result = service.listChildFolders(FOLDER_ID, USER_ID);

            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("認可 Wave7: 他人の個人フォルダ配下は FOLDER_NOT_FOUND（404・存在秘匿）")
        void 他人の個人フォルダ配下は404() {
            SharedFolderEntity personal = createFolder(FileScopeType.PERSONAL);
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(personal));

            assertThatThrownBy(() -> service.listChildFolders(FOLDER_ID, 999L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.FOLDER_NOT_FOUND));
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

            FolderResponse result = service.getFolder(FOLDER_ID, USER_ID);

            assertThat(result.getName()).isEqualTo("テストフォルダ");
        }

        @Test
        @DisplayName("異常系: フォルダ不在でFOLDER_NOT_FOUND例外")
        void フォルダ詳細_不在_例外() {
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.getFolder(FOLDER_ID, USER_ID))
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
            CreateFolderRequest request = new CreateFolderRequest("組織フォルダ", null, null, "ORGANIZATION", null, null, null);
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
            CreateFolderRequest request = new CreateFolderRequest("個人フォルダ", null, null, "PERSONAL", null, null, null);
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
            // 認可根治 Wave7: 移動先の親が同一チームのフォルダであることを検証するため親も stub する。
            SharedFolderEntity parent = createFolder(FileScopeType.TEAM);
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(entity));
            given(folderRepository.findById(50L)).willReturn(Optional.of(parent));
            given(folderRepository.save(entity)).willReturn(entity);
            given(fileSharingMapper.toFolderResponse(entity))
                    .willReturn(mockFolderResponse("TEAM"));
            UpdateFolderRequest request = new UpdateFolderRequest("新名前", "新説明", 50L, null, null);

            FolderResponse result = service.updateFolder(FOLDER_ID, USER_ID, request);

            assertThat(result).isNotNull();
            assertThat(entity.getName()).isEqualTo("新名前");
            assertThat(entity.getDescription()).isEqualTo("新説明");
            assertThat(entity.getParentId()).isEqualTo(50L);
            // 移動先の親は、対象フォルダ実体のスコープ種別・スコープ ID でガードに照合される。
            verify(folderAccessGuard).requireParentWithinScope(parent, FileScopeType.TEAM, TEAM_ID);
        }

        @Test
        @DisplayName("認可 Wave7: 接ぎ木封鎖ガードの拒否は 404 として伝播し、フォルダは保存されない")
        void 接ぎ木拒否は404で保存されない() {
            SharedFolderEntity entity = createFolder(FileScopeType.TEAM);
            SharedFolderEntity foreignParent = SharedFolderEntity.builder()
                    .scopeType(FileScopeType.TEAM).teamId(999L).name("他チームのフォルダ").build();
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(entity));
            given(folderRepository.findById(50L)).willReturn(Optional.of(foreignParent));
            willThrow(new BusinessException(FileSharingErrorCode.FOLDER_NOT_FOUND))
                    .given(folderAccessGuard)
                    .requireParentWithinScope(foreignParent, FileScopeType.TEAM, TEAM_ID);
            UpdateFolderRequest request = new UpdateFolderRequest(null, null, 50L, null, null);

            assertThatThrownBy(() -> service.updateFolder(FOLDER_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.FOLDER_NOT_FOUND));
            verify(folderRepository, never()).save(any());
        }

        @Test
        @DisplayName("正常系: フィールドがnullの場合は更新されない")
        void フォルダ更新_全nullフィールド_変化なし() {
            SharedFolderEntity entity = createFolder(FileScopeType.TEAM);
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(entity));
            given(folderRepository.save(entity)).willReturn(entity);
            given(fileSharingMapper.toFolderResponse(entity))
                    .willReturn(mockFolderResponse("TEAM"));
            UpdateFolderRequest request = new UpdateFolderRequest(null, null, null, null, null);

            service.updateFolder(FOLDER_ID, USER_ID, request);

            assertThat(entity.getName()).isEqualTo("テストフォルダ");
        }
    }

    // ========================================
    // provisionDefaultFolder（F08.7.1 / 04 §4 冪等）
    // ========================================

    @Nested
    @DisplayName("provisionDefaultFolder")
    class ProvisionDefaultFolder {

        @Test
        @DisplayName("冪等: 既存フォルダがある場合は save せず再作成しない")
        void 既存ありは再作成しない() {
            SharedFolderEntity existing = SharedFolderEntity.builder()
                    .scopeType(FileScopeType.TOURNAMENT).scopeRefId(100L).name("大会要項").build();
            given(folderRepository.findByScopeTypeAndScopeRefIdAndParentIdIsNullAndName(
                    FileScopeType.TOURNAMENT, 100L, "大会要項"))
                    .willReturn(Optional.of(existing));

            service.provisionDefaultFolder(FileScopeType.TOURNAMENT, ORG_ID, 100L, USER_ID, "大会要項");

            verify(folderRepository, never()).save(any());
        }

        @Test
        @DisplayName("正常系: 既存なしなら新規にデフォルトフォルダを作成する")
        void 既存なしは新規作成() {
            given(folderRepository.findByScopeTypeAndScopeRefIdAndParentIdIsNullAndName(
                    FileScopeType.TOURNAMENT_DIVISION, 200L, "規約"))
                    .willReturn(Optional.empty());
            given(folderRepository.save(any())).willReturn(
                    SharedFolderEntity.builder().scopeType(FileScopeType.TOURNAMENT_DIVISION)
                            .scopeRefId(200L).name("規約").build());

            service.provisionDefaultFolder(FileScopeType.TOURNAMENT_DIVISION, ORG_ID, 200L, USER_ID, "規約");

            verify(folderRepository).save(any(SharedFolderEntity.class));
        }

        @Test
        @DisplayName("冪等: 同時実行で UNIQUE 競合（DataIntegrityViolation）が起きても再取得で成功する")
        void 競合時は再取得で吸収() {
            given(folderRepository.findByScopeTypeAndScopeRefIdAndParentIdIsNullAndName(
                    FileScopeType.TOURNAMENT, 100L, "大会要項"))
                    .willReturn(Optional.empty())
                    .willReturn(Optional.of(SharedFolderEntity.builder()
                            .scopeType(FileScopeType.TOURNAMENT).scopeRefId(100L).name("大会要項").build()));
            given(folderRepository.save(any()))
                    .willThrow(new org.springframework.dao.DataIntegrityViolationException("dup"));

            // 例外を投げず、再取得で吸収すること（巻き添えで大会作成全体を失敗させない）
            assertThatCode(() -> service.provisionDefaultFolder(
                    FileScopeType.TOURNAMENT, ORG_ID, 100L, USER_ID, "大会要項"))
                    .doesNotThrowAnyException();
        }
    }
}
