package com.mannschaft.app.circulation.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.circulation.entity.CirculationDocumentEntity;
import com.mannschaft.app.circulation.entity.CirculationRecipientEntity;
import com.mannschaft.app.circulation.entity.CirculationStampCorrectionLogEntity;
import com.mannschaft.app.circulation.repository.CirculationDocumentRepository;
import com.mannschaft.app.circulation.repository.CirculationRecipientRepository;
import com.mannschaft.app.circulation.repository.CirculationStampCorrectionLogRepository;
import com.mannschaft.app.common.pdf.PdfGeneratorService;
import com.mannschaft.app.common.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 押印済み証跡 PDF の非同期生成エグゼキューター（F05.2 Phase 11 第四陣 4-C）。
 *
 * <p>{@link CirculationExportService} から分離し、Spring AOP プロキシ経由の
 * {@code @Async} 呼び出しを保証する。同一 Bean 内の self-call では {@code @Async}
 * プロキシが効かないため、本クラスを別 Bean として DI することでプロダクションでも
 * 確実に非同期実行される。</p>
 *
 * <p>参考: {@code DigestAsyncExecutor} 同等のパターン。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CirculationExportAsyncExecutor {

    /** Thymeleaf テンプレ名。 */
    private static final String EXPORT_TEMPLATE = "pdf/circulation-export";

    private final CirculationDocumentRepository documentRepository;
    private final CirculationRecipientRepository recipientRepository;
    private final CirculationStampCorrectionLogRepository correctionLogRepository;
    private final PdfGeneratorService pdfGeneratorService;
    private final StorageService storageService;

    /** 受信者表示名解決用（テスト構成では null 注入を許容）。 */
    @Autowired(required = false)
    private UserRepository userRepository;

    /** 監査ログ発火用（テスト構成では null 注入を許容）。 */
    @Autowired(required = false)
    private AuditLogService auditLogService;

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
