package com.mannschaft.app.repairplan.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.pdf.PdfGeneratorService;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.repairplan.RepairPlanErrorCode;
import com.mannschaft.app.repairplan.dto.GenerateHandoverPackRequest;
import com.mannschaft.app.repairplan.dto.HandoverPackDownloadResponse;
import com.mannschaft.app.repairplan.dto.HandoverPackDto;
import com.mannschaft.app.repairplan.entity.BoardHandoverPack;
import com.mannschaft.app.repairplan.entity.RepairPlanItem;
import com.mannschaft.app.repairplan.entity.RepairSimulationScenario;
import com.mannschaft.app.repairplan.entity.TeamMemberTerm;
import com.mannschaft.app.repairplan.repository.BoardHandoverPackRepository;
import com.mannschaft.app.repairplan.repository.RepairPlanItemRepository;
import com.mannschaft.app.repairplan.repository.RepairSimulationScenarioRepository;
import com.mannschaft.app.repairplan.repository.TeamMemberTermRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 申し送りパックサービス（F08.8 Phase 5）。
 *
 * <p>PDF 生成・R2 アップロード・署名付き URL の発行を担う。
 * PDF 生成には {@link PdfGeneratorService}（Thymeleaf + Flying Saucer）を使用する。
 * ウォーターマークは PDF テンプレートの変数として渡す（viewer_name / timestamp）。</p>
 *
 * <h2>ドメイン境界</h2>
 * <p>user ドメインの表示名取得はクロスドメイン依存を避けるため、
 * {@code viewer_watermark_template} フィールドに生成時の情報を保存する設計とした。</p>
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class BoardHandoverPackService {

    /** 署名付きダウンロード URL の有効期限。 */
    private static final Duration DOWNLOAD_URL_TTL = Duration.ofMinutes(15);

    /** R2 キーのプレフィックス形式。 */
    private static final String R2_KEY_FORMAT = "repair_plan/%s/%d/packs/%s/%s.pdf";

    private static final Set<String> ALLOWED_SCOPE_TYPES = Set.of("TEAM", "ORGANIZATION");

    private final BoardHandoverPackRepository packRepository;
    private final TeamMemberTermRepository termRepository;
    private final RepairPlanItemRepository itemRepository;
    private final RepairSimulationScenarioRepository scenarioRepository;
    private final PdfGeneratorService pdfGeneratorService;
    private final R2StorageService r2StorageService;
    private final AccessControlService accessControlService;
    private final AuditLogService auditLogService;

    // =========================================================================
    // パック生成
    // =========================================================================

    /**
     * 申し送りパックを生成する。
     *
     * <p>処理フロー:</p>
     * <ol>
     *   <li>認可チェック（ADMIN/DEPUTY_ADMIN 以上）</li>
     *   <li>任期の取得・検証</li>
     *   <li>PDF 生成（Thymeleaf テンプレート + Flying Saucer）</li>
     *   <li>R2 アップロード</li>
     *   <li>メタデータ保存</li>
     *   <li>監査ログ: PACK_GENERATED</li>
     * </ol>
     *
     * @param scopeType      スコープ種別（TEAM / ORGANIZATION）
     * @param scopeId        スコープ ID
     * @param organizationId テナント組織 ID
     * @param req            生成リクエスト
     * @param requesterId    リクエスト者 ID
     * @return 生成したパックの DTO
     */
    @Transactional
    public HandoverPackDto generatePack(String scopeType, Long scopeId, Long organizationId,
                                         GenerateHandoverPackRequest req, Long requesterId) {
        validateScopeType(scopeType);
        accessControlService.checkAdminOrAbove(requesterId, scopeId, scopeType);

        // 任期を取得
        TeamMemberTerm term = findTermOrThrow(req.termId(), organizationId);

        String piiLevel = req.normalizedPiiLevel();

        // 修繕計画項目を取得（COMPLETED と未完了に分けて PDF に表示する）
        List<RepairPlanItem> completedItems = itemRepository
                .findByScopeTypeAndScopeIdAndStatusAndDeletedAtIsNull(scopeType, scopeId, "COMPLETED");
        List<RepairPlanItem> pendingItems = itemRepository
                .findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByPlannedYearAsc(scopeType, scopeId)
                .stream()
                .filter(i -> !"COMPLETED".equals(i.getStatus()))
                .toList();

        // 最新シナリオ（最大3件）
        List<RepairSimulationScenario> recentScenarios = scenarioRepository
                .findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByCreatedAtDesc(scopeType, scopeId)
                .stream()
                .limit(3)
                .toList();

        // PDF テンプレート変数を構築
        Map<String, Object> vars = buildPdfVariables(scopeType, scopeId, term, piiLevel,
                completedItems, pendingItems, recentScenarios, req.memo());

        UUID packId = UUID.randomUUID();
        String watermark = buildWatermarkLabel(requesterId);

        vars.put("watermarkFor", watermark);
        vars.put("packId", packId.toString());

        // PDF 生成
        byte[] pdfBytes;
        try {
            pdfBytes = pdfGeneratorService.generateFromTemplate("repair-handover-pack", vars);
        } catch (Exception e) {
            log.error("申し送りパック PDF 生成失敗: scope={}:{}, termId={}", scopeType, scopeId, req.termId(), e);
            throw new BusinessException(RepairPlanErrorCode.PACK_GENERATION_FAILED, e);
        }

        String sha256 = pdfGeneratorService.sha256Hex(pdfBytes);
        String r2Key = String.format(R2_KEY_FORMAT, scopeType, scopeId, packId, sha256);

        // R2 にアップロード
        try {
            r2StorageService.upload(r2Key, pdfBytes, "application/pdf");
        } catch (Exception e) {
            log.error("申し送りパック R2 アップロード失敗: key={}", r2Key, e);
            throw new BusinessException(RepairPlanErrorCode.PACK_GENERATION_FAILED, e);
        }

        LocalDateTime now = LocalDateTime.now();
        // パックメタデータを保存
        BoardHandoverPack pack = BoardHandoverPack.builder()
                .organizationId(organizationId)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .termYear(term.getTermEnd().getYear())
                .periodStart(term.getTermStart())
                .periodEnd(term.getTermEnd())
                .pdfR2Key(r2Key)
                .pdfSize((long) pdfBytes.length)
                .pdfSha256(sha256)
                .piiLevel(piiLevel)
                .viewerWatermarkTemplate(watermark)
                .status("READY")
                .passwordSeparatelySent(false)
                .generatedBy(requesterId)
                .generatedAt(now)
                .build();

        pack = packRepository.save(pack);

        log.info("申し送りパック生成完了: id={}, scope={}:{}, termYear={}",
                pack.getId(), scopeType, scopeId, pack.getTermYear());

        recordAudit(AuditEventType.PACK_GENERATED.name(), requesterId, scopeType, scopeId,
                organizationId, pack.getId(),
                "{\"piiLevel\":\"" + piiLevel + "\",\"termId\":\"" + req.termId() + "\"}");

        return toDto(pack);
    }

    // =========================================================================
    // パック一覧取得
    // =========================================================================

    /**
     * スコープ単位の申し送りパック一覧を返す（年度降順）。
     */
    public List<HandoverPackDto> listPacks(String scopeType, Long scopeId, Long organizationId) {
        validateScopeType(scopeType);
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, scopeId, scopeType);

        return packRepository
                .findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByTermYearDesc(scopeType, scopeId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    // =========================================================================
    // ダウンロード URL 発行
    // =========================================================================

    /**
     * 申し送りパックの署名付きダウンロード URL を返す（TTL 15分）。
     *
     * @param packId         パック ID
     * @param organizationId テナント組織 ID
     * @param requesterId    リクエスト者 ID（監査ログ用）
     * @return ダウンロードレスポンス
     */
    public HandoverPackDownloadResponse getDownloadUrl(UUID packId, Long organizationId,
                                                        Long requesterId) {
        BoardHandoverPack pack = findPackOrThrow(packId, organizationId);
        validateScopeType(pack.getScopeType());
        accessControlService.checkMembership(requesterId, pack.getScopeId(), pack.getScopeType());

        if (!"READY".equals(pack.getStatus())) {
            throw new BusinessException(RepairPlanErrorCode.PACK_NOT_READY);
        }

        String downloadUrl = r2StorageService.generateDownloadUrl(pack.getPdfR2Key(), DOWNLOAD_URL_TTL);
        LocalDateTime expiresAt = LocalDateTime.now().plus(DOWNLOAD_URL_TTL);

        String watermarkFor = buildWatermarkLabel(requesterId);

        log.info("申し送りパックダウンロード URL 発行: packId={}, requesterId={}", packId, requesterId);

        recordAudit(AuditEventType.PACK_DOWNLOADED.name(), requesterId,
                pack.getScopeType(), pack.getScopeId(), organizationId, packId,
                "{\"piiLevel\":\"" + pack.getPiiLevel() + "\"}");

        return new HandoverPackDownloadResponse(downloadUrl, expiresAt, watermarkFor);
    }

    // =========================================================================
    // パック削除（論理削除）
    // =========================================================================

    /**
     * 申し送りパックを論理削除する（ADMIN 以上）。
     */
    @Transactional
    public void deletePack(UUID packId, Long organizationId, Long requesterId) {
        BoardHandoverPack pack = findPackOrThrow(packId, organizationId);
        accessControlService.checkAdminOrAbove(requesterId, pack.getScopeId(), pack.getScopeType());

        pack.softDelete();
        packRepository.save(pack);

        log.info("申し送りパック削除: id={}, requesterId={}", packId, requesterId);
    }

    // =========================================================================
    // 内部ヘルパー
    // =========================================================================

    private TeamMemberTerm findTermOrThrow(UUID termId, Long organizationId) {
        return termRepository.findById(termId)
                .filter(t -> organizationId.equals(t.getOrganizationId()))
                .orElseThrow(() -> new BusinessException(RepairPlanErrorCode.TERM_NOT_FOUND));
    }

    private BoardHandoverPack findPackOrThrow(UUID packId, Long organizationId) {
        return packRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(packId, organizationId)
                .orElseThrow(() -> new BusinessException(RepairPlanErrorCode.PACK_NOT_FOUND));
    }

    private void validateScopeType(String scopeType) {
        if (scopeType == null || !ALLOWED_SCOPE_TYPES.contains(scopeType)) {
            throw new BusinessException(RepairPlanErrorCode.INVALID_SCOPE);
        }
    }

    /**
     * 「氏名(ID略) / 日時」形式のウォーターマークラベルを生成する。
     * ユーザー表示名はクロスドメイン依存を避けるため requesterId のみを使う。
     */
    private String buildWatermarkLabel(Long requesterId) {
        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        return "requesterId=" + requesterId + " / " + timestamp;
    }

    private Map<String, Object> buildPdfVariables(String scopeType, Long scopeId,
                                                    TeamMemberTerm term, String piiLevel,
                                                    List<RepairPlanItem> completedItems,
                                                    List<RepairPlanItem> pendingItems,
                                                    List<RepairSimulationScenario> recentScenarios,
                                                    String memo) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("scopeType", scopeType);
        vars.put("scopeId", scopeId);
        vars.put("termStart", term.getTermStart());
        vars.put("termEnd", term.getTermEnd());
        vars.put("roleLabel", term.getRoleLabel());
        vars.put("generatedAt", LocalDateTime.now());
        vars.put("piiLevel", piiLevel);
        vars.put("memo", memo);

        // ANONYMIZED の場合は個人情報（担当者名など）を除外
        if ("ANONYMIZED".equals(piiLevel)) {
            vars.put("completedItems", completedItems.stream()
                    .map(i -> Map.of("title", i.getTitle(), "plannedYear", i.getPlannedYear(),
                            "estimatedAmount", i.getEstimatedAmount(), "category", i.getCategory()))
                    .toList());
            vars.put("pendingItems", pendingItems.stream()
                    .map(i -> Map.of("title", i.getTitle(), "plannedYear", i.getPlannedYear(),
                            "estimatedAmount", i.getEstimatedAmount(), "category", i.getCategory(),
                            "status", i.getStatus()))
                    .toList());
        } else {
            vars.put("completedItems", completedItems);
            vars.put("pendingItems", pendingItems);
        }

        vars.put("recentScenarios", recentScenarios.stream()
                .map(s -> Map.of("name", s.getName() != null ? s.getName() : "",
                        "baselineAt", s.getBaselineAt() != null ? s.getBaselineAt().toString() : "",
                        "engineVersion", s.getEngineVersion() != null ? s.getEngineVersion() : ""))
                .toList());

        return vars;
    }

    private HandoverPackDto toDto(BoardHandoverPack pack) {
        return new HandoverPackDto(
                pack.getId(),
                pack.getScopeId(),
                pack.getScopeType(),
                pack.getOrganizationId(),
                pack.getStatus(),
                pack.getPiiLevel(),
                pack.getPdfSha256(),
                pack.getPdfSize(),
                pack.getTermYear(),
                pack.getPeriodStart(),
                pack.getPeriodEnd(),
                null, // memo は Entity に保存していないため null
                pack.getGeneratedAt(),
                null  // expiresAt はダウンロード時に算出するため null
        );
    }

    private void recordAudit(String eventType, Long userId, String scopeType, Long scopeId,
                              Long organizationId, UUID packId, String extraJson) {
        Long teamId = "TEAM".equals(scopeType) ? scopeId : null;
        Long orgId = "ORGANIZATION".equals(scopeType) ? scopeId : organizationId;
        String metadata = String.format(
                "{\"packId\":\"%s\",\"scopeType\":\"%s\",\"scopeId\":%d%s}",
                packId, scopeType, scopeId,
                extraJson != null ? "," + extraJson.substring(1, extraJson.length() - 1) : "");
        auditLogService.record(eventType, userId, null, teamId, orgId, null, null,
                SecurityUtils.getCurrentSessionHash(), metadata);
    }
}
