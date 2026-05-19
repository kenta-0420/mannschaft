package com.mannschaft.app.circulation.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.circulation.CirculationErrorCode;
import com.mannschaft.app.circulation.CirculationExportStatus;
import com.mannschaft.app.circulation.CirculationStatus;
import com.mannschaft.app.circulation.RecipientStatus;
import com.mannschaft.app.circulation.dto.ExportRequestResponse;
import com.mannschaft.app.circulation.dto.ExportStatusResponse;
import com.mannschaft.app.circulation.entity.CirculationDocumentEntity;
import com.mannschaft.app.circulation.entity.CirculationRecipientEntity;
import com.mannschaft.app.circulation.entity.CirculationStampCorrectionLogEntity;
import com.mannschaft.app.circulation.repository.CirculationDocumentRepository;
import com.mannschaft.app.circulation.repository.CirculationRecipientRepository;
import com.mannschaft.app.circulation.repository.CirculationStampCorrectionLogRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.pdf.PdfGeneratorService;
import com.mannschaft.app.common.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 押印済み証跡 PDF エクスポートサービス（F05.2 Phase 11 第四陣 4-C）。
 *
 * <p>設計書: {@code docs/features/F05.2_circular.md} §4.8 / §残課題マトリクス
 *
 * <p>主な責務:
 * <ul>
 *   <li>COMPLETED 状態の回覧文書のみエクスポート対象とする</li>
 *   <li>非同期ジョブ（{@link Async @Async("job-pool")}）で Thymeleaf + Flying Saucer 経由の PDF を生成</li>
 *   <li>R2 にアップロードし {@code export_file_key} を永続化</li>
 *   <li>生成済の場合は 1h Pre-signed URL を返却（Controller 側で 302 リダイレクト）</li>
 *   <li>監査ログ {@code CIRCULATION_EXPORT_REQUESTED / GENERATED} を発火</li>
 * </ul>
 *
 * <p>認可: Controller 側で作成者 / 受信者 / ADMIN を判定するため、本サービスでは
 * {@link #assertCanAccessExport(CirculationDocumentEntity, Long, boolean)} で
 * 作成者 OR 受信者 OR ADMIN のいずれかを満たすかを確認する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CirculationExportService {

    /** R2 Pre-signed URL の有効期間（仕様: 1 時間）。 */
    private static final Duration PRESIGN_TTL = Duration.ofHours(1);

    /** Thymeleaf テンプレ名。 */
    private static final String EXPORT_TEMPLATE = "pdf/circulation-export";

    /** PDF 生成完了見込み時間（秒）。202 応答の参考値。 */
    private static final int ESTIMATED_GENERATION_SECONDS = 10;

    private final CirculationDocumentRepository documentRepository;
    private final CirculationRecipientRepository recipientRepository;
    private final CirculationStampCorrectionLogRepository correctionLogRepository;
    private final PdfGeneratorService pdfGeneratorService;
    private final StorageService storageService;

    /** 受信者表示名解決用（テスト構成では Optional/null 注入される）。 */
    @Autowired(required = false)
    private UserRepository userRepository;

    /** 監査ログ発火用（テスト構成では null 注入を許容）。 */
    @Autowired(required = false)
    private AuditLogService auditLogService;

    /**
     * 押印済み証跡 PDF のエクスポートを要求する。
     *
     * <p>処理フロー:
     * <ol>
     *   <li>文書取得 + COMPLETED 検証（NG なら CIRCULATION_021）</li>
     *   <li>認可検証（作成者 / 受信者 / ADMIN）</li>
     *   <li>既に COMPLETED であれば Pre-signed URL を返却（Controller が 302 リダイレクト）</li>
     *   <li>PENDING 中なら再生成しない（既存ジョブ完了待ち）</li>
     *   <li>NOT_GENERATED / FAILED ならステータスを PENDING にして非同期ジョブを起動</li>
     * </ol>
     *
     * @param documentId 文書 ID
     * @param actorId    呼び出しユーザー ID
     * @param isAdmin    呼び出しユーザーが ADMIN か（Controller から渡される）
     * @return 既生成済なら {@link ExportStatusResponse}（{@code url} 入り）、それ以外は {@link ExportRequestResponse}
     */
    @Transactional
    public Object requestExport(Long documentId, Long actorId, boolean isAdmin) {
        CirculationDocumentEntity entity = documentRepository.findById(documentId)
                .orElseThrow(() -> new BusinessException(CirculationErrorCode.DOCUMENT_NOT_FOUND));

        if (entity.getStatus() != CirculationStatus.COMPLETED) {
            throw new BusinessException(CirculationErrorCode.EXPORT_NOT_AVAILABLE_NON_COMPLETED);
        }

        assertCanAccessExport(entity, actorId, isAdmin);

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

        recordAudit(AuditEventType.CIRCULATION_EXPORT_REQUESTED.name(), actorId, entity);

        log.info("回覧 PDF エクスポート要求: documentId={}, actorId={}", documentId, actorId);
        triggerAsyncGeneration(documentId);

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
     * @param isAdmin    呼び出しユーザーが ADMIN か
     * @return 生成状況レスポンス
     */
    public ExportStatusResponse getExportStatus(Long documentId, Long actorId, boolean isAdmin) {
        CirculationDocumentEntity entity = documentRepository.findById(documentId)
                .orElseThrow(() -> new BusinessException(CirculationErrorCode.DOCUMENT_NOT_FOUND));

        assertCanAccessExport(entity, actorId, isAdmin);

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

    /**
     * 非同期 PDF 生成ジョブのトリガーポイント。
     *
     * <p>{@code @Async} メソッドは同一 Bean からの呼び出しではプロキシ経由にならないため、
     * 別メソッドとして切り出し、{@code self} 参照ではなく {@code @Async} アノテーション付きの
     * {@link #generateAsync(Long)} に委譲する。
     * 既存パターン: {@code DigestAsyncExecutor}（dispatchAsync → execute）。</p>
     *
     * @param documentId 文書 ID
     */
    public void triggerAsyncGeneration(Long documentId) {
        // テストでは Mock により上書き可能。実装は {@link #generateAsync} を呼ぶだけ。
        generateAsync(documentId);
    }

    /**
     * 非同期 PDF 生成ジョブ本体。
     *
     * <p>{@code job-pool} スレッドプール（{@code AsyncConfig#jobPoolExecutor}）で実行される。
     * 例外が発生した場合は {@code FAILED} ステータスを永続化し、ログを出して終了する
     * （対処療法ではなく根治を意図したエラー要約保存）。</p>
     *
     * @param documentId 文書 ID
     */
    @Async("job-pool")
    @Transactional
    public void generateAsync(Long documentId) {
        log.info("回覧 PDF 非同期生成 開始: documentId={}", documentId);

        CirculationDocumentEntity entity = documentRepository.findById(documentId).orElse(null);
        if (entity == null) {
            log.warn("回覧 PDF 非同期生成: 文書が見つかりません: documentId={}", documentId);
            return;
        }

        try {
            byte[] pdfBytes = buildPdfBytes(entity);
            String fileKey = "circulation/exports/" + documentId + "/" + UUID.randomUUID() + ".pdf";

            storageService.upload(fileKey, pdfBytes, "application/pdf");

            entity.markExportCompleted(fileKey);
            documentRepository.save(entity);

            recordAudit(AuditEventType.CIRCULATION_EXPORT_GENERATED.name(), entity.getCreatedBy(), entity);

            log.info("回覧 PDF 非同期生成 完了: documentId={}, fileKey={}", documentId, fileKey);
        } catch (Exception ex) {
            log.error("回覧 PDF 非同期生成 失敗: documentId={}", documentId, ex);
            entity.markExportFailed(ex.getClass().getSimpleName() + ": " + ex.getMessage());
            documentRepository.save(entity);
        }
    }

    // ─────────────────────────────────────────────
    // 内部ヘルパー
    // ─────────────────────────────────────────────

    /**
     * Thymeleaf テンプレ + Flying Saucer で PDF バイト列を構築する。
     */
    private byte[] buildPdfBytes(CirculationDocumentEntity entity) {
        Long documentId = entity.getId();
        List<CirculationRecipientEntity> recipients =
                recipientRepository.findByDocumentIdOrderBySortOrderAsc(documentId);

        // 受信者表示名を解決
        Map<Long, String> displayNameMap = new HashMap<>();
        if (userRepository != null) {
            for (CirculationRecipientEntity r : recipients) {
                userRepository.findMemberSummaryById(r.getUserId())
                        .ifPresent(ms -> displayNameMap.put(ms.getId(), ms.getDisplayName()));
            }
        }

        // 訂正履歴も時系列で取得（受信者ごとに集約）
        Map<Long, List<CirculationStampCorrectionLogEntity>> correctionsByRecipient = new HashMap<>();
        for (CirculationRecipientEntity r : recipients) {
            List<CirculationStampCorrectionLogEntity> logs =
                    correctionLogRepository.findByRecipientIdOrderByCreatedAtAsc(r.getId());
            if (!logs.isEmpty()) {
                correctionsByRecipient.put(r.getId(), logs);
            }
        }

        List<Map<String, Object>> recipientView = new ArrayList<>();
        for (CirculationRecipientEntity r : recipients) {
            Map<String, Object> row = new HashMap<>();
            row.put("userId", r.getUserId());
            row.put("displayName", displayNameMap.getOrDefault(r.getUserId(), "（ID:" + r.getUserId() + "）"));
            row.put("status", r.getStatus().name());
            row.put("stampedAt", r.getStampedAt());
            row.put("sortOrder", r.getSortOrder());
            row.put("skipReason", r.getSkipReason());
            row.put("tiltAngle", r.getTiltAngle());
            row.put("isFlipped", r.getIsFlipped());
            row.put("corrections", correctionsByRecipient.getOrDefault(r.getId(), List.of()));
            recipientView.add(row);
        }

        Map<String, Object> vars = new HashMap<>();
        vars.put("documentId", entity.getId());
        vars.put("title", entity.getTitle());
        vars.put("body", entity.getBody());
        vars.put("scopeType", entity.getScopeType());
        vars.put("scopeId", entity.getScopeId());
        vars.put("createdBy", entity.getCreatedBy());
        vars.put("createdByName",
                displayNameMap.getOrDefault(entity.getCreatedBy(),
                        "（ID:" + entity.getCreatedBy() + "）"));
        vars.put("completedAt", entity.getCompletedAt());
        vars.put("dueDate", entity.getDueDate());
        vars.put("circulationMode", entity.getCirculationMode().name());
        vars.put("priority", entity.getPriority().name());
        vars.put("totalRecipientCount", entity.getTotalRecipientCount());
        vars.put("stampedCount", entity.getStampedCount());
        vars.put("recipients", recipientView);
        // フッターには SHA-256 の埋め込みは行わない（PDF 自体のハッシュは将来拡張で内部署名 PDF 化）

        return pdfGeneratorService.generateFromTemplate(EXPORT_TEMPLATE, vars);
    }

    /**
     * 認可判定: 作成者 / 受信者 / ADMIN のいずれかを満たすか。
     *
     * @throws BusinessException {@code COMMON_001} 権限不足
     */
    private void assertCanAccessExport(CirculationDocumentEntity entity, Long actorId, boolean isAdmin) {
        if (isAdmin) {
            return;
        }
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
        if (!isRecipient) {
            throw new BusinessException(com.mannschaft.app.common.CommonErrorCode.COMMON_002);
        }
    }

    /**
     * 監査ログ発火（{@link AuditLogService} 未設定時はスキップ）。
     */
    private void recordAudit(String eventType, Long actorId, CirculationDocumentEntity entity) {
        if (auditLogService == null) {
            return;
        }
        auditLogService.record(
                eventType,
                actorId,
                null,
                "TEAM".equals(entity.getScopeType()) ? entity.getScopeId() : null,
                "ORGANIZATION".equals(entity.getScopeType()) ? entity.getScopeId() : null,
                null, null, null,
                "{\"documentId\":" + entity.getId() + "}");
    }
}
