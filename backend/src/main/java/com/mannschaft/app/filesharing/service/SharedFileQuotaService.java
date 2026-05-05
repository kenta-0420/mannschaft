package com.mannschaft.app.filesharing.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.quota.StorageFeatureType;
import com.mannschaft.app.common.storage.quota.StorageQuotaExceededException;
import com.mannschaft.app.common.storage.quota.StorageQuotaService;
import com.mannschaft.app.common.storage.quota.StorageScopeType;
import com.mannschaft.app.filesharing.FileScopeType;
import com.mannschaft.app.filesharing.FileSharingErrorCode;
import com.mannschaft.app.filesharing.entity.SharedFolderEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * F05.5 ファイル共有の F13 統合ストレージクォータ連携サービス。
 *
 * <p>F13 Phase 4-ε で F05.5 ファイルアップロード・バージョン追加・ファイル削除を
 * 統合クォータに接続する。主な責務:</p>
 * <ul>
 *     <li>フォルダのスコープ種別に応じたスコープ判定（TEAM → TEAM、ORGANIZATION → ORGANIZATION、
 *         PERSONAL → PERSONAL）</li>
 *     <li>{@link StorageQuotaService#checkQuota} 呼び出しと {@link StorageQuotaExceededException} の
 *         {@link FileSharingErrorCode#STORAGE_QUOTA_EXCEEDED} 変換</li>
 *     <li>{@link StorageQuotaService#recordUpload} / {@link StorageQuotaService#recordDeletion} の発火</li>
 * </ul>
 *
 * @see <a href="../../../../../../../../docs/cross-cutting/storage_quota.md">設計書 §11 Phase 4-ε</a>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SharedFileQuotaService {

    /** F13 Phase 4-ε: storage_usage_logs.reference_type に記録するテーブル名（ファイル本体）。 */
    static final String REFERENCE_TYPE_FILE = "shared_files";

    /** F13 Phase 4-ε: storage_usage_logs.reference_type に記録するテーブル名（バージョン）。 */
    static final String REFERENCE_TYPE_VERSION = "shared_file_versions";

    private final StorageQuotaService storageQuotaService;

    /**
     * アップロード前のクォータ事前チェック。
     *
     * <p>ファイル作成・バージョン追加の直前に呼び出す。容量超過時は
     * {@link FileSharingErrorCode#STORAGE_QUOTA_EXCEEDED} をスローする。</p>
     *
     * @param folder   対象フォルダ（スコープ判定の基準）
     * @param fileSize アップロードしようとしているファイルサイズ（バイト）
     */
    public void checkFileQuota(SharedFolderEntity folder, long fileSize) {
        ScopeResolution scope = resolveScope(folder);
        try {
            storageQuotaService.checkQuota(scope.scopeType(), scope.scopeId(), fileSize);
        } catch (StorageQuotaExceededException e) {
            log.info("F05.5 ファイル共有の F13 クォータ超過: folderId={}, scope={}/{}, requested={}, used={}, included={}",
                    folder.getId(), scope.scopeType(), scope.scopeId(),
                    e.getRequestedBytes(), e.getUsedBytes(), e.getIncludedBytes());
            throw new BusinessException(FileSharingErrorCode.STORAGE_QUOTA_EXCEEDED, e);
        }
    }

    /**
     * ファイル登録完了後の使用量加算。
     *
     * @param folder      対象フォルダ
     * @param fileId      INSERT 済みのファイル ID
     * @param fileSize    登録したファイルサイズ（バイト）
     * @param actorId     操作者ユーザー ID
     */
    public void recordFileUpload(SharedFolderEntity folder, Long fileId, long fileSize, Long actorId) {
        if (fileSize <= 0) {
            return;
        }
        ScopeResolution scope = resolveScope(folder);
        storageQuotaService.recordUpload(
                scope.scopeType(), scope.scopeId(), fileSize,
                StorageFeatureType.FILE_SHARING,
                REFERENCE_TYPE_FILE, fileId, actorId);
    }

    /**
     * バージョン登録完了後の使用量加算。
     *
     * @param folder      対象フォルダ
     * @param versionId   INSERT 済みのバージョン ID
     * @param fileSize    登録したファイルサイズ（バイト）
     * @param actorId     操作者ユーザー ID
     */
    public void recordVersionUpload(SharedFolderEntity folder, Long versionId, long fileSize, Long actorId) {
        if (fileSize <= 0) {
            return;
        }
        ScopeResolution scope = resolveScope(folder);
        storageQuotaService.recordUpload(
                scope.scopeType(), scope.scopeId(), fileSize,
                StorageFeatureType.FILE_SHARING,
                REFERENCE_TYPE_VERSION, versionId, actorId);
    }

    /**
     * ファイル削除後の使用量減算。
     *
     * @param folder   対象フォルダ
     * @param fileId   削除対象のファイル ID
     * @param fileSize 削除したファイルサイズ（バイト）
     * @param actorId  操作者ユーザー ID
     */
    public void recordFileDeletion(SharedFolderEntity folder, Long fileId, long fileSize, Long actorId) {
        if (fileSize <= 0) {
            return;
        }
        ScopeResolution scope = resolveScope(folder);
        storageQuotaService.recordDeletion(
                scope.scopeType(), scope.scopeId(), fileSize,
                StorageFeatureType.FILE_SHARING,
                REFERENCE_TYPE_FILE, fileId, actorId);
    }

    /**
     * フォルダのスコープ種別に応じてストレージスコープを解決する。
     *
     * <ul>
     *     <li>TEAM → TEAM (folder.teamId)</li>
     *     <li>ORGANIZATION → ORGANIZATION (folder.organizationId)</li>
     *     <li>PERSONAL → PERSONAL (folder.userId)</li>
     * </ul>
     */
    ScopeResolution resolveScope(SharedFolderEntity folder) {
        FileScopeType scopeType = folder.getScopeType();
        if (scopeType == null) {
            throw new IllegalStateException("FileScopeType is null for folderId=" + folder.getId());
        }
        return switch (scopeType) {
            case TEAM -> {
                if (folder.getTeamId() == null) {
                    throw new IllegalStateException(
                            "TEAM folder has null teamId: folderId=" + folder.getId());
                }
                yield new ScopeResolution(StorageScopeType.TEAM, folder.getTeamId());
            }
            case ORGANIZATION -> {
                if (folder.getOrganizationId() == null) {
                    throw new IllegalStateException(
                            "ORGANIZATION folder has null organizationId: folderId=" + folder.getId());
                }
                yield new ScopeResolution(StorageScopeType.ORGANIZATION, folder.getOrganizationId());
            }
            case PERSONAL -> {
                if (folder.getUserId() == null) {
                    throw new IllegalStateException(
                            "PERSONAL folder has null userId: folderId=" + folder.getId());
                }
                yield new ScopeResolution(StorageScopeType.PERSONAL, folder.getUserId());
            }
        };
    }

    /** 解決されたストレージスコープ。 */
    public record ScopeResolution(StorageScopeType scopeType, Long scopeId) {}
}
