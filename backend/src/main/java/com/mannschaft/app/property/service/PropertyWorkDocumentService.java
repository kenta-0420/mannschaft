package com.mannschaft.app.property.service;

import com.mannschaft.app.common.BusinessException;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 物件履歴文書 (F09.13) の attach/detach を専門に扱うサービス。
 *
 * <p>F09.13 設計書 §3 property_work_documents / §5.7 添付制約 / §4 PROPERTY_008・PROPERTY_009
 * に対応。Phase 1-δ では Controller 層が直接 {@link PropertyWorkDocumentRepository} に
 * 書き込み、F05.5 SharedFile の同一スコープ検証は持ち越しになっていた。Phase 2-α-1 で
 * 本サービスに集約し、以下を担う:</p>
 *
 * <ul>
 *   <li>{@code SharedFile.folder.scope} と {@code PropertyWorkPackage.scope} の同一性検証
 *       （PROPERTY_008 — 他スコープ紐付け不可）</li>
 *   <li>添付件数 50 件上限の検証（PROPERTY_009）</li>
 *   <li>{@link PropertyWorkDocumentEntity} の永続化と
 *       {@link PropertyWorkPackageEntity#incrementAttachmentCount()} の整合的な呼出</li>
 *   <li>detach 時のカウンタ減算と中間テーブルの物理削除（同一トランザクション）</li>
 * </ul>
 *
 * <p>SharedFile エンティティには {@code scope_type/scope_id} カラムが直接無く、
 * folder 経由でスコープを判定する。folder.scopeType は {@code FileScopeType}
 * (TEAM/ORGANIZATION/PERSONAL) を持つため、{@code FileScopeType.name()} と
 * パッケージ側の {@code String scopeType} ("TEAM"/"ORGANIZATION") を文字列比較する。</p>
 *
 * <p><strong>PERSONAL フォルダ配下の SharedFile は PROPERTY_008 で拒否する</strong>
 * （パッケージは TEAM/ORGANIZATION スコープしか取らないため、PERSONAL は常に不一致）。</p>
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PropertyWorkDocumentService {

    /** 設計書 §5.7: 添付ファイル数の上限（パッケージあたり 50 件）。 */
    private static final int ATTACHMENT_LIMIT = 50;

    private final PropertyWorkPackageRepository packageRepository;
    private final PropertyWorkDocumentRepository documentRepository;
    private final SharedFileRepository sharedFileRepository;
    private final SharedFolderRepository sharedFolderRepository;

    /**
     * SharedFile をパッケージに紐付ける。
     *
     * <p>処理順:</p>
     * <ol>
     *   <li>パッケージを取得（無ければ {@link PropertyHistoryErrorCode#PROPERTY_001}）</li>
     *   <li>SharedFile を取得（無ければ {@link PropertyHistoryErrorCode#PROPERTY_008} —
     *       「紐付け不可」として扱い、削除済 / IDOR の両方に対応した fail-closed 応答）</li>
     *   <li>SharedFile の folder を取得し、folder の scope（TEAM/ORGANIZATION/PERSONAL）と
     *       パッケージの scope（TEAM/ORGANIZATION）を比較。一致しなければ
     *       {@link PropertyHistoryErrorCode#PROPERTY_008}</li>
     *   <li>パッケージの現在 attachmentCount が上限以上なら
     *       {@link PropertyHistoryErrorCode#PROPERTY_009}</li>
     *   <li>{@link PropertyWorkDocumentEntity} を save、
     *       {@link PropertyWorkPackageEntity#incrementAttachmentCount()} を呼んで save</li>
     * </ol>
     *
     * @param packageId    対象パッケージ
     * @param sharedFileId 紐付ける SharedFile
     * @param documentKind 文書種別
     * @param displayOrder 表示順（{@code null} の場合 0）
     * @param note         補足メモ
     * @param userId       実行ユーザー（{@code created_by} に記録）
     * @return 作成された {@link PropertyWorkDocumentEntity}
     * @throws BusinessException PROPERTY_001 / PROPERTY_008 / PROPERTY_009
     */
    @Transactional
    public PropertyWorkDocumentEntity attach(
            Long packageId,
            Long sharedFileId,
            DocumentKind documentKind,
            Integer displayOrder,
            String note,
            Long userId) {

        // 1. パッケージ取得
        PropertyWorkPackageEntity pkg = packageRepository.findByIdAndDeletedAtIsNull(packageId)
                .orElseThrow(() -> new BusinessException(PropertyHistoryErrorCode.PROPERTY_001));

        // 2. SharedFile 取得（削除済 or 不存在 → PROPERTY_008 として扱う fail-closed）
        SharedFileEntity file = sharedFileRepository.findById(sharedFileId)
                .orElseThrow(() -> new BusinessException(PropertyHistoryErrorCode.PROPERTY_008));

        // 3. folder 経由でスコープ検証
        validateSameScope(pkg, file);

        // 4. 上限チェック（インクリメント前に判定）
        if (pkg.getAttachmentCount() != null && pkg.getAttachmentCount() >= ATTACHMENT_LIMIT) {
            throw new BusinessException(PropertyHistoryErrorCode.PROPERTY_009);
        }

        // 5. 中間テーブルへ INSERT
        PropertyWorkDocumentEntity doc = PropertyWorkDocumentEntity.builder()
                .packageId(packageId)
                .sharedFileId(sharedFileId)
                .documentKind(documentKind)
                .displayOrder(displayOrder != null ? displayOrder : 0)
                .note(note)
                .createdBy(userId)
                .build();
        PropertyWorkDocumentEntity saved = documentRepository.save(doc);

        // 6. パッケージのカウンタ加算
        pkg.incrementAttachmentCount();
        packageRepository.save(pkg);

        log.info("物件履歴文書 attach: packageId={}, documentId={}, sharedFileId={}, kind={}",
                packageId, saved.getId(), sharedFileId, documentKind);
        return saved;
    }

    /**
     * パッケージから文書紐付けを外す。
     *
     * <p>SharedFile 本体は削除せず、中間テーブルの 1 行のみ物理削除する（設計書 §3 注記）。
     * 該当 documentId が指定パッケージに紐付いていない場合は {@link PropertyHistoryErrorCode#PROPERTY_001}
     * 相当（不整合）として扱う。これは IDOR 防止のためで、攻撃者が他パッケージの documentId を
     * 渡しても本パッケージのカウンタが減らないようにする。</p>
     *
     * @param packageId  対象パッケージ
     * @param documentId 解除する PropertyWorkDocumentEntity の ID
     * @param userId     実行ユーザー（ログ用）
     * @throws BusinessException PROPERTY_001（パッケージ不存在 / document 不一致）
     */
    @Transactional
    public void detach(Long packageId, Long documentId, Long userId) {
        PropertyWorkPackageEntity pkg = packageRepository.findByIdAndDeletedAtIsNull(packageId)
                .orElseThrow(() -> new BusinessException(PropertyHistoryErrorCode.PROPERTY_001));

        PropertyWorkDocumentEntity doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new BusinessException(PropertyHistoryErrorCode.PROPERTY_001));

        if (!packageId.equals(doc.getPackageId())) {
            // packageId と doc.packageId の不一致 → IDOR を疑い fail-closed
            throw new BusinessException(PropertyHistoryErrorCode.PROPERTY_001);
        }

        documentRepository.delete(doc);
        pkg.decrementAttachmentCount();
        packageRepository.save(pkg);

        log.info("物件履歴文書 detach: packageId={}, documentId={}, userId={}",
                packageId, documentId, userId);
    }

    // =========================================================================
    // 内部メソッド
    // =========================================================================

    /**
     * SharedFile が紐付くフォルダのスコープと、パッケージのスコープが一致するかを検証する。
     *
     * <p>SharedFile はフォルダ ID のみを持つため、フォルダを取得して
     * {@code FileScopeType} (TEAM/ORGANIZATION/PERSONAL) と teamId/organizationId を確認する。
     * パッケージ側の scopeType は文字列 "TEAM"/"ORGANIZATION"。一致しない / フォルダ不存在の
     * 場合はすべて {@link PropertyHistoryErrorCode#PROPERTY_008} として扱う（fail-closed）。</p>
     *
     * @throws BusinessException PROPERTY_008（スコープ不一致）
     */
    private void validateSameScope(PropertyWorkPackageEntity pkg, SharedFileEntity file) {
        SharedFolderEntity folder = sharedFolderRepository.findById(file.getFolderId())
                .orElseThrow(() -> {
                    log.warn("SharedFile が参照する folder が存在しない: folderId={}, sharedFileId={}",
                            file.getFolderId(), file.getId());
                    return new BusinessException(PropertyHistoryErrorCode.PROPERTY_008);
                });

        if (folder.getScopeType() == null) {
            throw new BusinessException(PropertyHistoryErrorCode.PROPERTY_008);
        }

        // folder.scopeType の文字列名と pkg.scopeType を比較
        // FileScopeType: TEAM / ORGANIZATION / PERSONAL
        // pkg.scopeType: "TEAM" / "ORGANIZATION"
        // PERSONAL フォルダは常にパッケージスコープと不一致 → PROPERTY_008
        String folderScopeName = folder.getScopeType().name();
        if (!folderScopeName.equals(pkg.getScopeType())) {
            throw new BusinessException(PropertyHistoryErrorCode.PROPERTY_008);
        }

        Long folderScopeId = switch (folder.getScopeType()) {
            case TEAM -> folder.getTeamId();
            // F08.7.1: 大会・ディビジョンは主催組織 ID。なお物件パッケージは TEAM/ORGANIZATION のみ扱うため、
            // これらは上の equals(pkg.getScopeType()) チェックで既に弾かれており実質到達しない。
            case ORGANIZATION, TOURNAMENT, TOURNAMENT_DIVISION -> folder.getOrganizationId();
            case PERSONAL -> null; // 上の equals チェックで既に弾かれているはず
        };

        if (folderScopeId == null || !folderScopeId.equals(pkg.getScopeId())) {
            throw new BusinessException(PropertyHistoryErrorCode.PROPERTY_008);
        }
    }
}
