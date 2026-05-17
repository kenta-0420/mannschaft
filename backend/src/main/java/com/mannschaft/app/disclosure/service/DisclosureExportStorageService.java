package com.mannschaft.app.disclosure.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.disclosure.DisclosureErrorCode;
import com.mannschaft.app.disclosure.util.DisclosureFileNameBuilder;
import com.mannschaft.app.filesharing.FileScopeType;
import com.mannschaft.app.filesharing.entity.SharedFileEntity;
import com.mannschaft.app.filesharing.entity.SharedFileVersionEntity;
import com.mannschaft.app.filesharing.entity.SharedFolderEntity;
import com.mannschaft.app.filesharing.repository.SharedFileRepository;
import com.mannschaft.app.filesharing.repository.SharedFileVersionRepository;
import com.mannschaft.app.filesharing.repository.SharedFolderRepository;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.resident.entity.DwellingUnitEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 重要事項説明書 出力ファイルの R2 保存 + SharedFile DB 登録 + presigned URL 発行サービス
 * （F09.14 Phase 4-A リファクタリング第 4 弾）。
 *
 * <p>{@link DisclosureExportService} ファサードから委譲され、生成済みバイナリを R2 に直接
 * アップロードして {@code shared_files} / {@code shared_file_versions} に登録する。</p>
 *
 * <p>本クラスはクラスレベルでは読込専用宣言とし、書込メソッドのみ {@link Transactional} を
 * 個別に上書きする。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class DisclosureExportStorageService {

    /** F13 Phase 5-a 命名規則: presigned URL の有効期限（共通 15 分）。 */
    static final Duration PRESIGN_TTL = Duration.ofMinutes(15);

    /** 重説書出力ファイルの専用フォルダ名。 */
    private static final String EXPORT_FOLDER_NAME = "disclosure-exports";

    private final R2StorageService r2StorageService;
    private final SharedFolderRepository folderRepository;
    private final SharedFileRepository sharedFileRepository;
    private final SharedFileVersionRepository sharedFileVersionRepository;

    /**
     * 生成済みバイナリを R2 + shared_files / shared_file_versions に保存する。
     *
     * @return 保存後の {@link SharedFileEntity}（{@code id} 採番済み）と R2 オブジェクトキー
     */
    @Transactional
    public StoredFile storeExportedFile(Long scopeId, Long draftId,
                                        byte[] payload, String contentType, String extension,
                                        OrganizationEntity organization,
                                        DwellingUnitEntity dwellingUnit,
                                        Long userId) {
        String fileName = buildFileName(extension, organization, dwellingUnit);
        String fileKey = buildFileKey(scopeId, extension);
        try {
            r2StorageService.upload(fileKey, payload, contentType);
        } catch (BusinessException e) {
            log.error("重説書 R2 アップロード失敗: scopeId={}, draftId={}", scopeId, draftId, e);
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_010, e);
        }

        SharedFolderEntity folder = ensureExportFolder(scopeId);
        SharedFileEntity sharedFile = SharedFileEntity.builder()
                .folderId(folder.getId())
                .name(fileName)
                .fileKey(fileKey)
                .fileSize((long) payload.length)
                .contentType(contentType)
                .description("F09.14 重説書出力 (draftId=" + draftId + ")")
                .createdBy(userId)
                .build();
        SharedFileEntity savedFile = sharedFileRepository.save(sharedFile);

        SharedFileVersionEntity version = SharedFileVersionEntity.builder()
                .fileId(savedFile.getId())
                .versionNumber(1)
                .fileKey(fileKey)
                .fileSize((long) payload.length)
                .contentType(contentType)
                .uploadedBy(userId)
                .comment("初回アップロード（F09.14 重説書出力）")
                .build();
        sharedFileVersionRepository.save(version);

        return new StoredFile(savedFile, fileKey);
    }

    /**
     * presigned ダウンロード URL を発行する（共通 15 分有効）。
     *
     * @return 発行された URL と有効期限の組
     */
    public PresignedUrl generatePresignedUrl(String fileKey) {
        String url = r2StorageService.generateDownloadUrl(fileKey, PRESIGN_TTL);
        LocalDateTime expiresAt = LocalDateTime.now().plus(PRESIGN_TTL);
        return new PresignedUrl(url, expiresAt);
    }

    /** SharedFile を id で取得する。未発見時は DISCLOSURE_001 を投げる。 */
    public SharedFileEntity findSharedFileOrThrow(Long sharedFileId) {
        return sharedFileRepository.findById(sharedFileId)
                .orElseThrow(() -> new BusinessException(DisclosureErrorCode.DISCLOSURE_001));
    }

    /** R2 から file_key 指定でバイナリをダウンロードする（改ざん検出時の SHA-256 再計算用）。 */
    public byte[] downloadFromR2(String fileKey) {
        return r2StorageService.download(fileKey);
    }

    /**
     * R2 オブジェクトキーを生成する（F13 Phase 5-a 命名規則: files/{scopeType}/{scopeId}/{uuid}.{ext}）。
     */
    private String buildFileKey(Long scopeId, String extension) {
        return "files/" + FileScopeType.ORGANIZATION.name() + "/" + scopeId
                + "/" + UUID.randomUUID() + "." + extension;
    }

    private String buildFileName(String extension, OrganizationEntity organization,
                                 DwellingUnitEntity dwellingUnit) {
        DisclosureFileNameBuilder builder = DisclosureFileNameBuilder.of(extension)
                .date(LocalDate.now());
        if (organization != null) {
            builder.propertyName(organization.getName());
        }
        if (dwellingUnit != null) {
            builder.unitNumber(dwellingUnit.getUnitNumber());
        }
        return builder.build();
    }

    /** disclosure-exports 用フォルダを ensure する（存在しなければ作成）。 */
    @Transactional
    public SharedFolderEntity ensureExportFolder(Long scopeId) {
        // 組織直下のルートフォルダから検索
        List<SharedFolderEntity> roots = folderRepository
                .findByOrganizationIdAndParentIdIsNullOrderByNameAsc(scopeId);
        for (SharedFolderEntity f : roots) {
            if (EXPORT_FOLDER_NAME.equals(f.getName())) {
                return f;
            }
        }
        SharedFolderEntity folder = SharedFolderEntity.builder()
                .scopeType(FileScopeType.ORGANIZATION)
                .organizationId(scopeId)
                .name(EXPORT_FOLDER_NAME)
                .description("F09.14 重説書出力ファイル（自動生成）")
                .createdBy(null)
                .build();
        return folderRepository.save(folder);
    }

    /** {@link #storeExportedFile} の戻り値タプル。 */
    public record StoredFile(SharedFileEntity sharedFile, String fileKey) {
    }

    /** presigned URL 発行結果（URL と有効期限）。 */
    public record PresignedUrl(String url, LocalDateTime expiresAt) {
    }
}
