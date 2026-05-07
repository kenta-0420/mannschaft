package com.mannschaft.app.property.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.filesharing.FileScopeType;
import com.mannschaft.app.filesharing.entity.SharedFileEntity;
import com.mannschaft.app.filesharing.entity.SharedFolderEntity;
import com.mannschaft.app.filesharing.repository.SharedFileRepository;
import com.mannschaft.app.filesharing.repository.SharedFolderRepository;
import com.mannschaft.app.property.DocumentKind;
import com.mannschaft.app.property.PropertyHistoryErrorCode;
import com.mannschaft.app.property.entity.PropertyWorkDocumentEntity;
import com.mannschaft.app.property.entity.PropertyWorkPackageEntity;
import com.mannschaft.app.property.repository.PropertyWorkDocumentRepository;
import com.mannschaft.app.property.repository.PropertyWorkPackageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link PropertyWorkDocumentService} 単体テスト（F09.13 Phase 2-α-1）。
 *
 * <p>設計書 §3 / §5.7 / §4 PROPERTY_008・PROPERTY_009 を網羅:</p>
 * <ul>
 *   <li>attach 正常系: TEAM スコープ一致 → save + カウンタ加算</li>
 *   <li>attach 異常系: SharedFile が他スコープのフォルダ → PROPERTY_008</li>
 *   <li>attach 異常系: SharedFile が PERSONAL フォルダ → PROPERTY_008</li>
 *   <li>attach 異常系: 添付件数 50 件超過 → PROPERTY_009</li>
 *   <li>attach 異常系: パッケージ不存在 → PROPERTY_001</li>
 *   <li>attach 異常系: SharedFile 不存在 → PROPERTY_008（fail-closed）</li>
 *   <li>detach 正常系: 中間テーブル削除 + カウンタ減算</li>
 *   <li>detach 異常系: documentId が他パッケージ → PROPERTY_001（IDOR 防止）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PropertyWorkDocumentService 単体テスト（F09.13 Phase 2-α-1）")
class PropertyWorkDocumentServiceTest {

    @Mock
    private PropertyWorkPackageRepository packageRepository;
    @Mock
    private PropertyWorkDocumentRepository documentRepository;
    @Mock
    private SharedFileRepository sharedFileRepository;
    @Mock
    private SharedFolderRepository sharedFolderRepository;

    private PropertyWorkDocumentService service;

    private static final Long PACKAGE_ID = 100L;
    private static final Long SHARED_FILE_ID = 200L;
    private static final Long FOLDER_ID = 300L;
    private static final Long DOCUMENT_ID = 400L;
    private static final Long TEAM_ID = 11L;
    private static final Long OTHER_TEAM_ID = 99L;
    private static final Long ORG_ID = 22L;
    private static final Long USER_ID = 7L;

    @BeforeEach
    void setUp() {
        service = new PropertyWorkDocumentService(
                packageRepository, documentRepository, sharedFileRepository, sharedFolderRepository);
    }

    // =========================================================================
    // attach: 正常系
    // =========================================================================

    @Nested
    @DisplayName("attach 正常系")
    class AttachSuccess {

        @Test
        @DisplayName("TEAM スコープ一致 → save + カウンタ加算で save される")
        void attach_TEAM_スコープ一致() {
            PropertyWorkPackageEntity pkg = teamPackage(TEAM_ID, 5);
            SharedFileEntity file = sharedFile(SHARED_FILE_ID, FOLDER_ID);
            SharedFolderEntity folder = teamFolder(FOLDER_ID, TEAM_ID);

            given(packageRepository.findByIdAndDeletedAtIsNull(PACKAGE_ID))
                    .willReturn(Optional.of(pkg));
            given(sharedFileRepository.findById(SHARED_FILE_ID))
                    .willReturn(Optional.of(file));
            given(sharedFolderRepository.findById(FOLDER_ID))
                    .willReturn(Optional.of(folder));
            given(documentRepository.save(any(PropertyWorkDocumentEntity.class)))
                    .willAnswer(inv -> {
                        PropertyWorkDocumentEntity arg = inv.getArgument(0);
                        ReflectionTestUtils.setField(arg, "id", DOCUMENT_ID);
                        return arg;
                    });

            PropertyWorkDocumentEntity result = service.attach(
                    PACKAGE_ID, SHARED_FILE_ID, DocumentKind.QUOTE, 3, "メモ", USER_ID);

            assertThat(result.getId()).isEqualTo(DOCUMENT_ID);
            assertThat(result.getPackageId()).isEqualTo(PACKAGE_ID);
            assertThat(result.getSharedFileId()).isEqualTo(SHARED_FILE_ID);
            assertThat(result.getDocumentKind()).isEqualTo(DocumentKind.QUOTE);
            assertThat(result.getDisplayOrder()).isEqualTo(3);
            assertThat(result.getNote()).isEqualTo("メモ");
            assertThat(result.getCreatedBy()).isEqualTo(USER_ID);

            // カウンタが 5 → 6 に加算され、パッケージが save される
            assertThat(pkg.getAttachmentCount()).isEqualTo(6);
            verify(packageRepository).save(pkg);
        }

        @Test
        @DisplayName("ORGANIZATION スコープ一致 → 正常に保存される")
        void attach_ORG_スコープ一致() {
            PropertyWorkPackageEntity pkg = orgPackage(ORG_ID, 0);
            SharedFileEntity file = sharedFile(SHARED_FILE_ID, FOLDER_ID);
            SharedFolderEntity folder = orgFolder(FOLDER_ID, ORG_ID);

            given(packageRepository.findByIdAndDeletedAtIsNull(PACKAGE_ID))
                    .willReturn(Optional.of(pkg));
            given(sharedFileRepository.findById(SHARED_FILE_ID))
                    .willReturn(Optional.of(file));
            given(sharedFolderRepository.findById(FOLDER_ID))
                    .willReturn(Optional.of(folder));
            given(documentRepository.save(any(PropertyWorkDocumentEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            service.attach(PACKAGE_ID, SHARED_FILE_ID, DocumentKind.OTHER, null, null, USER_ID);

            ArgumentCaptor<PropertyWorkDocumentEntity> cap =
                    ArgumentCaptor.forClass(PropertyWorkDocumentEntity.class);
            verify(documentRepository).save(cap.capture());
            // displayOrder = null 指定 → 0 にデフォルト
            assertThat(cap.getValue().getDisplayOrder()).isEqualTo(0);
            assertThat(pkg.getAttachmentCount()).isEqualTo(1);
        }
    }

    // =========================================================================
    // attach: 異常系
    // =========================================================================

    @Nested
    @DisplayName("attach 異常系")
    class AttachFailure {

        @Test
        @DisplayName("パッケージ不存在 → PROPERTY_001")
        void attach_パッケージ不存在() {
            given(packageRepository.findByIdAndDeletedAtIsNull(PACKAGE_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.attach(
                    PACKAGE_ID, SHARED_FILE_ID, DocumentKind.QUOTE, 0, null, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(PropertyHistoryErrorCode.PROPERTY_001);

            verify(documentRepository, never()).save(any());
            verify(packageRepository, never()).save(any());
        }

        @Test
        @DisplayName("SharedFile 不存在 → PROPERTY_008（fail-closed）")
        void attach_SharedFile不存在() {
            PropertyWorkPackageEntity pkg = teamPackage(TEAM_ID, 0);
            given(packageRepository.findByIdAndDeletedAtIsNull(PACKAGE_ID))
                    .willReturn(Optional.of(pkg));
            given(sharedFileRepository.findById(SHARED_FILE_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.attach(
                    PACKAGE_ID, SHARED_FILE_ID, DocumentKind.QUOTE, 0, null, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(PropertyHistoryErrorCode.PROPERTY_008);

            verify(documentRepository, never()).save(any());
        }

        @Test
        @DisplayName("SharedFile が他チームのフォルダ → PROPERTY_008")
        void attach_他チーム() {
            PropertyWorkPackageEntity pkg = teamPackage(TEAM_ID, 0);
            SharedFileEntity file = sharedFile(SHARED_FILE_ID, FOLDER_ID);
            SharedFolderEntity folder = teamFolder(FOLDER_ID, OTHER_TEAM_ID);

            given(packageRepository.findByIdAndDeletedAtIsNull(PACKAGE_ID))
                    .willReturn(Optional.of(pkg));
            given(sharedFileRepository.findById(SHARED_FILE_ID))
                    .willReturn(Optional.of(file));
            given(sharedFolderRepository.findById(FOLDER_ID))
                    .willReturn(Optional.of(folder));

            assertThatThrownBy(() -> service.attach(
                    PACKAGE_ID, SHARED_FILE_ID, DocumentKind.QUOTE, 0, null, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(PropertyHistoryErrorCode.PROPERTY_008);

            verify(documentRepository, never()).save(any());
            verify(packageRepository, never()).save(any());
        }

        @Test
        @DisplayName("SharedFile が ORGANIZATION フォルダだがパッケージは TEAM → PROPERTY_008")
        void attach_スコープ種別不一致() {
            PropertyWorkPackageEntity pkg = teamPackage(TEAM_ID, 0);
            SharedFileEntity file = sharedFile(SHARED_FILE_ID, FOLDER_ID);
            SharedFolderEntity folder = orgFolder(FOLDER_ID, ORG_ID);

            given(packageRepository.findByIdAndDeletedAtIsNull(PACKAGE_ID))
                    .willReturn(Optional.of(pkg));
            given(sharedFileRepository.findById(SHARED_FILE_ID))
                    .willReturn(Optional.of(file));
            given(sharedFolderRepository.findById(FOLDER_ID))
                    .willReturn(Optional.of(folder));

            assertThatThrownBy(() -> service.attach(
                    PACKAGE_ID, SHARED_FILE_ID, DocumentKind.QUOTE, 0, null, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(PropertyHistoryErrorCode.PROPERTY_008);
        }

        @Test
        @DisplayName("SharedFile が PERSONAL フォルダ → PROPERTY_008")
        void attach_PERSONALフォルダ() {
            PropertyWorkPackageEntity pkg = teamPackage(TEAM_ID, 0);
            SharedFileEntity file = sharedFile(SHARED_FILE_ID, FOLDER_ID);
            SharedFolderEntity folder = personalFolder(FOLDER_ID, USER_ID);

            given(packageRepository.findByIdAndDeletedAtIsNull(PACKAGE_ID))
                    .willReturn(Optional.of(pkg));
            given(sharedFileRepository.findById(SHARED_FILE_ID))
                    .willReturn(Optional.of(file));
            given(sharedFolderRepository.findById(FOLDER_ID))
                    .willReturn(Optional.of(folder));

            assertThatThrownBy(() -> service.attach(
                    PACKAGE_ID, SHARED_FILE_ID, DocumentKind.QUOTE, 0, null, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(PropertyHistoryErrorCode.PROPERTY_008);
        }

        @Test
        @DisplayName("添付件数 50 件超過 → PROPERTY_009")
        void attach_上限超過() {
            // attachmentCount = 50（上限と等しい）の段階で次の attach は拒否
            PropertyWorkPackageEntity pkg = teamPackage(TEAM_ID, 50);
            SharedFileEntity file = sharedFile(SHARED_FILE_ID, FOLDER_ID);
            SharedFolderEntity folder = teamFolder(FOLDER_ID, TEAM_ID);

            given(packageRepository.findByIdAndDeletedAtIsNull(PACKAGE_ID))
                    .willReturn(Optional.of(pkg));
            given(sharedFileRepository.findById(SHARED_FILE_ID))
                    .willReturn(Optional.of(file));
            given(sharedFolderRepository.findById(FOLDER_ID))
                    .willReturn(Optional.of(folder));

            assertThatThrownBy(() -> service.attach(
                    PACKAGE_ID, SHARED_FILE_ID, DocumentKind.QUOTE, 0, null, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(PropertyHistoryErrorCode.PROPERTY_009);

            verify(documentRepository, never()).save(any());
            verify(packageRepository, never()).save(any());
        }

        @Test
        @DisplayName("SharedFile.folder が存在しない → PROPERTY_008")
        void attach_folder不存在() {
            PropertyWorkPackageEntity pkg = teamPackage(TEAM_ID, 0);
            SharedFileEntity file = sharedFile(SHARED_FILE_ID, FOLDER_ID);

            given(packageRepository.findByIdAndDeletedAtIsNull(PACKAGE_ID))
                    .willReturn(Optional.of(pkg));
            given(sharedFileRepository.findById(SHARED_FILE_ID))
                    .willReturn(Optional.of(file));
            given(sharedFolderRepository.findById(FOLDER_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.attach(
                    PACKAGE_ID, SHARED_FILE_ID, DocumentKind.QUOTE, 0, null, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(PropertyHistoryErrorCode.PROPERTY_008);
        }
    }

    // =========================================================================
    // detach
    // =========================================================================

    @Nested
    @DisplayName("detach")
    class Detach {

        @Test
        @DisplayName("正常系: 中間テーブル削除 + カウンタ減算")
        void detach_正常系() {
            PropertyWorkPackageEntity pkg = teamPackage(TEAM_ID, 3);
            PropertyWorkDocumentEntity doc = PropertyWorkDocumentEntity.builder()
                    .packageId(PACKAGE_ID)
                    .sharedFileId(SHARED_FILE_ID)
                    .documentKind(DocumentKind.QUOTE)
                    .displayOrder(0)
                    .createdBy(USER_ID)
                    .build();
            ReflectionTestUtils.setField(doc, "id", DOCUMENT_ID);

            given(packageRepository.findByIdAndDeletedAtIsNull(PACKAGE_ID))
                    .willReturn(Optional.of(pkg));
            given(documentRepository.findById(DOCUMENT_ID))
                    .willReturn(Optional.of(doc));

            service.detach(PACKAGE_ID, DOCUMENT_ID, USER_ID);

            verify(documentRepository, times(1)).delete(doc);
            assertThat(pkg.getAttachmentCount()).isEqualTo(2);
            verify(packageRepository).save(pkg);
        }

        @Test
        @DisplayName("異常系: パッケージ不存在 → PROPERTY_001")
        void detach_パッケージ不存在() {
            given(packageRepository.findByIdAndDeletedAtIsNull(PACKAGE_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.detach(PACKAGE_ID, DOCUMENT_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(PropertyHistoryErrorCode.PROPERTY_001);
        }

        @Test
        @DisplayName("異常系: documentId が他パッケージに属する → PROPERTY_001（IDOR 防止）")
        void detach_他パッケージのdocument() {
            PropertyWorkPackageEntity pkg = teamPackage(TEAM_ID, 3);
            PropertyWorkDocumentEntity doc = PropertyWorkDocumentEntity.builder()
                    .packageId(999L) // 他パッケージ
                    .sharedFileId(SHARED_FILE_ID)
                    .documentKind(DocumentKind.QUOTE)
                    .displayOrder(0)
                    .createdBy(USER_ID)
                    .build();
            ReflectionTestUtils.setField(doc, "id", DOCUMENT_ID);

            given(packageRepository.findByIdAndDeletedAtIsNull(PACKAGE_ID))
                    .willReturn(Optional.of(pkg));
            given(documentRepository.findById(DOCUMENT_ID))
                    .willReturn(Optional.of(doc));

            assertThatThrownBy(() -> service.detach(PACKAGE_ID, DOCUMENT_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(PropertyHistoryErrorCode.PROPERTY_001);

            verify(documentRepository, never()).delete(any());
            verify(packageRepository, never()).save(any());
        }
    }

    // =========================================================================
    // テスト用ヘルパー
    // =========================================================================

    private static PropertyWorkPackageEntity teamPackage(Long teamId, int attachmentCount) {
        PropertyWorkPackageEntity pkg = PropertyWorkPackageEntity.builder()
                .scopeType("TEAM")
                .scopeId(teamId)
                .attachmentCount(attachmentCount)
                .build();
        ReflectionTestUtils.setField(pkg, "id", PACKAGE_ID);
        return pkg;
    }

    private static PropertyWorkPackageEntity orgPackage(Long orgId, int attachmentCount) {
        PropertyWorkPackageEntity pkg = PropertyWorkPackageEntity.builder()
                .scopeType("ORGANIZATION")
                .scopeId(orgId)
                .attachmentCount(attachmentCount)
                .build();
        ReflectionTestUtils.setField(pkg, "id", PACKAGE_ID);
        return pkg;
    }

    private static SharedFileEntity sharedFile(Long id, Long folderId) {
        SharedFileEntity file = SharedFileEntity.builder()
                .folderId(folderId)
                .name("dummy.pdf")
                .fileKey("k")
                .fileSize(1L)
                .contentType("application/pdf")
                .build();
        ReflectionTestUtils.setField(file, "id", id);
        return file;
    }

    private static SharedFolderEntity teamFolder(Long id, Long teamId) {
        SharedFolderEntity folder = SharedFolderEntity.builder()
                .scopeType(FileScopeType.TEAM)
                .teamId(teamId)
                .name("folder")
                .build();
        ReflectionTestUtils.setField(folder, "id", id);
        return folder;
    }

    private static SharedFolderEntity orgFolder(Long id, Long orgId) {
        SharedFolderEntity folder = SharedFolderEntity.builder()
                .scopeType(FileScopeType.ORGANIZATION)
                .organizationId(orgId)
                .name("folder")
                .build();
        ReflectionTestUtils.setField(folder, "id", id);
        return folder;
    }

    private static SharedFolderEntity personalFolder(Long id, Long userId) {
        SharedFolderEntity folder = SharedFolderEntity.builder()
                .scopeType(FileScopeType.PERSONAL)
                .userId(userId)
                .name("personal")
                .build();
        ReflectionTestUtils.setField(folder, "id", id);
        return folder;
    }
}
