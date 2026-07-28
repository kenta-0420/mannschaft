package com.mannschaft.app.circulation.service;

import com.mannschaft.app.circulation.CirculationErrorCode;
import com.mannschaft.app.circulation.CirculationExportStatus;
import com.mannschaft.app.circulation.CirculationStatus;
import com.mannschaft.app.circulation.RecipientStatus;
import com.mannschaft.app.circulation.dto.ExportRequestResponse;
import com.mannschaft.app.circulation.dto.ExportStatusResponse;
import com.mannschaft.app.circulation.entity.CirculationDocumentEntity;
import com.mannschaft.app.circulation.event.CirculationExportRequestedEvent;
import com.mannschaft.app.circulation.repository.CirculationDocumentRepository;
import com.mannschaft.app.circulation.repository.CirculationRecipientRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.common.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * 押印済み証跡 PDF エクスポートサービス（F05.2 Phase 11 第四陣 4-C）。
 *
 * <p>設計書: {@code docs/features/F05.2_circular.md} §4.8 / §残課題マトリクス
 *
 * <p>主な責務:
 * <ul>
 *   <li>COMPLETED 状態の回覧文書のみエクスポート対象とする</li>
 *   <li>非同期ジョブ（{@link CirculationExportAsyncExecutor#generateAsync}）にトリガを渡す</li>
 *   <li>R2 にアップロードされた {@code export_file_key} 経由で 1h Pre-signed URL を返却</li>
 *   <li>生成済の場合は Pre-signed URL を返却（Controller 側で 302 リダイレクト）</li>
 *   <li>監査ログ {@code CIRCULATION_EXPORT_REQUESTED} を発火</li>
 * </ul>
 *
 * <p>認可（認可根治 Wave4 是正）: 旧実装は Controller が JWT の {@code ROLE_ADMIN}
 * （スコープを問わない文字列一致）保持有無を渡し、本サービスが無条件にバイパスしていたため、
 * 「どこか 1 つのチーム/組織で ADMIN であれば他団体の COMPLETED 回覧の押印済み証跡 PDF を
 * 無認可 DL できる」BOLA だった。本サービスは
 * {@link #assertCanAccessExport(CirculationDocumentEntity, Long)} で
 * 「作成者 OR 受信者 OR 当該文書スコープの ADMIN/DEPUTY_ADMIN（SystemAdmin 含む）」の
 * いずれかを満たすかを {@link AccessControlService} で per-scope に確認する
 * （{@code CirculationService#checkScopeAdminAccess} と同型）。</p>
 *
 * <p>非同期ジョブ本体は {@link CirculationExportAsyncExecutor} に分離されている。
 * これは Spring の {@code @Async} プロキシが同一 Bean 内 self-call では効かないため、
 * 別 Bean として DI することで確実に非同期実行されることを保証する。
 * 既存パターン: {@code DigestAsyncExecutor}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CirculationExportService {

    /** R2 Pre-signed URL の有効期間（仕様: 1 時間）。 */
    private static final Duration PRESIGN_TTL = Duration.ofHours(1);

    /** PDF 生成完了見込み時間（秒）。202 応答の参考値。 */
    private static final int ESTIMATED_GENERATION_SECONDS = 10;

    private final CirculationDocumentRepository documentRepository;
    private final CirculationRecipientRepository recipientRepository;
    private final StorageService storageService;
    private final CirculationExportAsyncExecutor asyncExecutor;
    private final DomainEventPublisher eventPublisher;

    /**
     * per-scope 管理者判定に使う（認可根治 Wave4）。テスト構成（Mockito {@code @InjectMocks}）で
     * Bean 不在の場合は {@code null} 注入され、{@link #isScopeAdmin} 内でスキップする
     * （{@code CirculationService#contentVisibilityChecker} と同じ null-safe 防御パターン）。
     */
    private final AccessControlService accessControlService;

    /**
     * 押印済み証跡 PDF のエクスポートを要求する。
     *
     * <p>処理フロー:
     * <ol>
     *   <li>文書取得 + COMPLETED 検証（NG なら CIRCULATION_021）</li>
     *   <li>認可検証（作成者 / 受信者 / 当該文書スコープの ADMIN）</li>
     *   <li>既に COMPLETED であれば Pre-signed URL を返却（Controller が 302 リダイレクト）</li>
     *   <li>PENDING 中なら再生成しない（既存ジョブ完了待ち）</li>
     *   <li>NOT_GENERATED / FAILED ならステータスを PENDING にして非同期ジョブを起動</li>
     * </ol>
     *
     * @param documentId 文書 ID
     * @param actorId    呼び出しユーザー ID
     * @return 既生成済なら {@link ExportStatusResponse}（{@code url} 入り）、それ以外は {@link ExportRequestResponse}
     */
    @Transactional
    public Object requestExport(Long documentId, Long actorId) {
        CirculationDocumentEntity entity = documentRepository.findById(documentId)
                .orElseThrow(() -> new BusinessException(CirculationErrorCode.DOCUMENT_NOT_FOUND));

        if (entity.getStatus() != CirculationStatus.COMPLETED) {
            throw new BusinessException(CirculationErrorCode.EXPORT_NOT_AVAILABLE_NON_COMPLETED);
        }

        assertCanAccessExport(entity, actorId);

        // 既に COMPLETED の場合: Pre-signed URL を返す（Controller が 302 する）
        if (entity.getExportStatus() == CirculationExportStatus.COMPLETED
                && entity.getExportFileKey() != null) {
            String url = storageService.generateDownloadUrl(entity.getExportFileKey(), PRESIGN_TTL);
            return new ExportStatusResponse(
                    entity.getId(),
                    CirculationExportStatus.COMPLETED.name(),
                    entity.getExportRequestedAt(),
                    entity.getExportCompletedAt(),
                    null,
                    url);
        }

        // PENDING 中: 二重起動しない
        if (entity.getExportStatus() == CirculationExportStatus.PENDING) {
            return new ExportRequestResponse(
                    entity.getId(),
                    "GENERATING",
                    "/api/v1/circulations/" + documentId + "/export/status",
                    ESTIMATED_GENERATION_SECONDS);
        }

        // NOT_GENERATED / FAILED: 非同期ジョブを起動
        entity.markExportPending();
        documentRepository.save(entity);

        // 監査ログはイベント経由（circulation ドメインから auth.AuditLogService を直接呼ぶとドメイン境界原則5違反）
        eventPublisher.publish(new CirculationExportRequestedEvent(
                actorId,
                entity.getId(),
                "TEAM".equals(entity.getScopeType()) ? entity.getScopeId() : null,
                "ORGANIZATION".equals(entity.getScopeType()) ? entity.getScopeId() : null));

        log.info("回覧 PDF エクスポート要求: documentId={}, actorId={}", documentId, actorId);
        asyncExecutor.generateAsync(documentId);

        return new ExportRequestResponse(
                entity.getId(),
                "GENERATING",
                "/api/v1/circulations/" + documentId + "/export/status",
                ESTIMATED_GENERATION_SECONDS);
    }

    /**
     * 押印済み証跡 PDF の生成状況を返す。
     *
     * @param documentId 文書 ID
     * @param actorId    呼び出しユーザー ID
     * @return 生成状況レスポンス
     */
    public ExportStatusResponse getExportStatus(Long documentId, Long actorId) {
        CirculationDocumentEntity entity = documentRepository.findById(documentId)
                .orElseThrow(() -> new BusinessException(CirculationErrorCode.DOCUMENT_NOT_FOUND));

        assertCanAccessExport(entity, actorId);

        if (entity.getExportStatus() == CirculationExportStatus.NOT_GENERATED) {
            throw new BusinessException(CirculationErrorCode.EXPORT_NOT_REQUESTED);
        }

        String url = null;
        if (entity.getExportStatus() == CirculationExportStatus.COMPLETED
                && entity.getExportFileKey() != null) {
            url = storageService.generateDownloadUrl(entity.getExportFileKey(), PRESIGN_TTL);
        }

        return new ExportStatusResponse(
                entity.getId(),
                entity.getExportStatus().name(),
                entity.getExportRequestedAt(),
                entity.getExportCompletedAt(),
                entity.getExportErrorMessage(),
                url);
    }

    // ─────────────────────────────────────────────
    // 内部ヘルパー
    // ─────────────────────────────────────────────

    /**
     * 認可判定: 作成者 / 受信者 / 当該文書スコープの ADMIN のいずれかを満たすか。
     *
     * <p>認可根治 Wave4: グローバル {@code ROLE_ADMIN}（スコープを問わない文字列一致）による
     * 無条件バイパスを廃し、{@link #isScopeAdmin} で当該文書の scopeType/scopeId に限定した
     * 管理者判定に差し替えた。</p>
     *
     * @throws BusinessException {@code COMMON_002} 権限不足
     */
    private void assertCanAccessExport(CirculationDocumentEntity entity, Long actorId) {
        if (actorId == null) {
            throw new BusinessException(com.mannschaft.app.common.CommonErrorCode.COMMON_000);
        }
        if (actorId.equals(entity.getCreatedBy())) {
            return;
        }
        // 受信者判定
        boolean isRecipient = recipientRepository.findByDocumentIdAndUserId(entity.getId(), actorId)
                .filter(r -> r.getStatus() != RecipientStatus.REJECTED)
                .isPresent();
        if (isRecipient) {
            return;
        }
        if (isScopeAdmin(entity, actorId)) {
            return;
        }
        throw new BusinessException(com.mannschaft.app.common.CommonErrorCode.COMMON_002);
    }

    /**
     * per-scope 管理者判定（認可根治 Wave4）。
     *
     * <p>{@code CirculationService#checkScopeAdminAccess} と同型のロジック:
     * SystemAdmin は常に許可、TEAM/ORGANIZATION スコープは当該スコープの ADMIN/DEPUTY_ADMIN のみ許可、
     * それ以外のスコープ（PERSONAL 等、team/org 管理者の概念が無い）は SystemAdmin 以外拒否する。</p>
     *
     * <p>{@code accessControlService} が {@code null} のテスト構成（Mockito {@code @InjectMocks}）では
     * false を返しスキップする（既存の null-safe 防御パターン）。</p>
     */
    private boolean isScopeAdmin(CirculationDocumentEntity entity, Long actorId) {
        if (accessControlService == null) {
            return false;
        }
        if (accessControlService.isSystemAdmin(actorId)) {
            return true;
        }
        String scopeType = entity.getScopeType();
        if (!"TEAM".equals(scopeType) && !"ORGANIZATION".equals(scopeType)) {
            return false;
        }
        return accessControlService.isAdminOrAbove(actorId, entity.getScopeId(), scopeType);
    }

}
