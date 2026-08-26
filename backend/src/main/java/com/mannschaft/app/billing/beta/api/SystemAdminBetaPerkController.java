package com.mannschaft.app.billing.beta.api;

import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.beta.BetaGrantEntity;
import com.mannschaft.app.billing.beta.BetaGrantQueryService;
import com.mannschaft.app.billing.beta.BetaGrantService;
import com.mannschaft.app.billing.beta.BetaPerkCandidateService;
import com.mannschaft.app.billing.beta.BetaPerkCriteriaService;
import com.mannschaft.app.billing.beta.GrantKind;
import com.mannschaft.app.billing.beta.dto.BetaGrantDetailResponse;
import com.mannschaft.app.billing.beta.dto.BetaGrantPageResponse;
import com.mannschaft.app.billing.beta.dto.BetaPerkCandidateResponse;
import com.mannschaft.app.billing.beta.dto.BetaPerkCriteriaResponse;
import com.mannschaft.app.billing.beta.dto.BetaPerkCriteriaUpsertRequest;
import com.mannschaft.app.billing.beta.dto.CreateBetaGrantRequest;
import com.mannschaft.app.billing.beta.dto.ExtendBetaGrantRequest;
import com.mannschaft.app.billing.beta.dto.FlagReviewRequest;
import com.mannschaft.app.billing.beta.dto.RevokeBetaGrantRequest;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.team.service.TeamOrgMembershipQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * F20.3 ベータ特典: シスアド運用 API（付与・取消・延長・審査・候補・条件マスタ・設計書 02 §4）。
 *
 * <p><b>認可（03 §1）</b>: 全 EP {@code @PreAuthorize("hasRole('SYSTEM_ADMIN')")}（メソッドレベル認可シグナル＝
 * Wave4 ArchUnit 番人の要件・memory {@code feedback_new_authz_endpoint_archunit_and_addfilters_it}）。
 * SecurityConfig の {@code /api/v1/system-admin/**}（hasRole SYSTEM_ADMIN）とメソッドガードの二重で担保する
 * （F20.1 {@code SystemAdminBillingController} と同型）。</p>
 *
 * <p><b>テナント境界</b>: プラットフォームシスアドは全 grant を操作対象にできるため {@code tenantOrgId=null} を渡す
 * （{@link BetaGrantService} 側の 404 秘匿は tenantOrgId 非 null 時のみ効く）。{@code WITHDRAWAL} 事由は
 * DTO enum に含めず API から渡さない（システム専用値・03 §4）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/system-admin/beta-perks")
@Tag(name = "システム管理 - ベータ特典", description = "F20.3 付与/取消/延長/審査/候補/条件マスタ（SYSTEM_ADMIN専用）")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
@RequiredArgsConstructor
public class SystemAdminBetaPerkController {

    private final BetaGrantService betaGrantService;
    private final BetaGrantQueryService betaGrantQueryService;
    private final BetaPerkCriteriaService betaPerkCriteriaService;
    private final BetaPerkCandidateService betaPerkCandidateService;
    private final TeamOrgMembershipQueryService teamOrgMembershipQueryService;

    // ============================================================
    // ① 一覧
    // ============================================================

    @GetMapping("/grants")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "付与一覧", description = "grantKind/betaPhase/reviewFlag/scopeKind/scopeId で絞り込み・grantedAt 降順。")
    public ResponseEntity<ApiResponse<BetaGrantPageResponse>> listGrants(
            @RequestParam(value = "grantKind", required = false) GrantKind grantKind,
            @RequestParam(value = "betaPhase", required = false) Integer betaPhase,
            @RequestParam(value = "reviewFlag", required = false) Boolean reviewFlag,
            @RequestParam(value = "scopeKind", required = false) EntitlementScopeKind scopeKind,
            @RequestParam(value = "scopeId", required = false) Long scopeId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.of(betaGrantQueryService.searchGrants(
                reviewFlag, grantKind, betaPhase, scopeKind, scopeId, page, size)));
    }

    // ============================================================
    // ② 手動付与
    // ============================================================

    @PostMapping("/grants")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "手動付与", description = "TEAM_ORG の正規経路（INDIVIDUAL も可）。organizationId は本層で解決して渡す。")
    public ResponseEntity<ApiResponse<BetaGrantDetailResponse>> createGrant(
            @Valid @RequestBody CreateBetaGrantRequest request) {
        Long operatorUserId = SecurityUtils.getCurrentUserId();
        Long organizationId = resolveOrganizationId(request.scopeKind(), request.scopeId());
        // note は監査メモ（設計書 02 §4.1）。骨格の grantBetaPerk は note を受けないため Phase 1 では
        // audit_logs へは載せず操作ログに残す（Phase 2 で grant 発行の audit へ結線予定）。
        if (request.note() != null && !request.note().isBlank()) {
            log.info("ベータ特典 手動付与 note operatorUserId={}, scopeKind={}, scopeId={}, note={}",
                    operatorUserId, request.scopeKind(), request.scopeId(), request.note());
        }
        BetaGrantEntity saved = betaGrantService.grantBetaPerk(
                request.grantKind(), request.betaPhase(), request.scopeKind(), request.scopeId(),
                organizationId, request.skipCriteriaCheckOrDefault(), operatorUserId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(betaGrantQueryService.getDetail(saved.getId())));
    }

    // ============================================================
    // ③ 取消
    // ============================================================

    @PostMapping("/grants/{grantId}/revoke")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "取消", description = "reason=TERMS_VIOLATION|ACCOUNT_TRANSFER|OTHER（WITHDRAWAL 不可）。")
    public ResponseEntity<ApiResponse<BetaGrantDetailResponse>> revokeGrant(
            @PathVariable UUID grantId, @Valid @RequestBody RevokeBetaGrantRequest request) {
        Long operatorUserId = SecurityUtils.getCurrentUserId();
        betaGrantService.revoke(grantId, request.reason().toDomain(), operatorUserId, null);
        return ResponseEntity.ok(ApiResponse.of(betaGrantQueryService.getDetail(grantId)));
    }

    // ============================================================
    // ④ 延長
    // ============================================================

    @PostMapping("/grants/{grantId}/extend")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "延長", description = "TEAM_ORG のみ。extensionMonths 1〜24。")
    public ResponseEntity<ApiResponse<BetaGrantDetailResponse>> extendGrant(
            @PathVariable UUID grantId, @Valid @RequestBody ExtendBetaGrantRequest request) {
        betaGrantService.extend(grantId, request.extensionMonths(), null);
        return ResponseEntity.ok(ApiResponse.of(betaGrantQueryService.getDetail(grantId)));
    }

    // ============================================================
    // ⑤ 審査解決
    // ============================================================

    @PostMapping("/grants/{grantId}/resolve-review")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "審査解決", description = "review_flag=true の grant のみ（問題なし）。")
    public ResponseEntity<ApiResponse<BetaGrantDetailResponse>> resolveReview(@PathVariable UUID grantId) {
        Long resolverUserId = SecurityUtils.getCurrentUserId();
        betaGrantService.resolveReview(grantId, resolverUserId, null);
        return ResponseEntity.ok(ApiResponse.of(betaGrantQueryService.getDetail(grantId)));
    }

    // ============================================================
    // ⑥ 審査フラグ設定
    // ============================================================

    @PostMapping("/grants/{grantId}/flag-review")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "審査フラグ設定", description = "review_reason=MANUAL 固定。取消済みは 409。")
    public ResponseEntity<ApiResponse<BetaGrantDetailResponse>> flagReview(
            @PathVariable UUID grantId, @Valid @RequestBody FlagReviewRequest request) {
        Long operatorUserId = SecurityUtils.getCurrentUserId();
        if (request.note() != null && !request.note().isBlank()) {
            log.info("ベータ特典 審査フラグ note grantId={}, operatorUserId={}, note={}",
                    grantId, operatorUserId, request.note());
        }
        betaGrantService.flagReview(grantId, operatorUserId, null);
        return ResponseEntity.ok(ApiResponse.of(betaGrantQueryService.getDetail(grantId)));
    }

    // ============================================================
    // ⑦ 候補 dry-run
    // ============================================================

    @GetMapping("/candidates")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "付与候補 dry-run", description = "未付与かつ充足のスコープ抽出（付与はしない）。Phase 1 は TEAM_ORG のみ。")
    public ResponseEntity<ApiResponse<List<BetaPerkCandidateResponse>>> listCandidates(
            @RequestParam("grantKind") GrantKind grantKind,
            @RequestParam("betaPhase") int betaPhase,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.of(
                betaPerkCandidateService.findCandidates(grantKind, betaPhase, page, size)));
    }

    // ============================================================
    // ⑧⑨ 条件マスタ CRUD
    // ============================================================

    @GetMapping("/criteria/{betaPhase}/{grantKind}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "条件マスタ取得", description = "未定義は 404。")
    public ResponseEntity<ApiResponse<BetaPerkCriteriaResponse>> getCriteria(
            @PathVariable int betaPhase, @PathVariable GrantKind grantKind) {
        return ResponseEntity.ok(ApiResponse.of(betaPerkCriteriaService.getCriteria(betaPhase, grantKind)));
    }

    @PutMapping("/criteria/{betaPhase}/{grantKind}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "条件マスタ upsert", description = "全指標 NULL は 400（無条件付与の防止）。")
    public ResponseEntity<ApiResponse<BetaPerkCriteriaResponse>> upsertCriteria(
            @PathVariable int betaPhase, @PathVariable GrantKind grantKind,
            @Valid @RequestBody BetaPerkCriteriaUpsertRequest request) {
        return ResponseEntity.ok(ApiResponse.of(
                betaPerkCriteriaService.upsertCriteria(betaPhase, grantKind, request)));
    }

    // ============================================================
    // organizationId 解決（API 層・設計書 01 §1・F20.1 と同一ロジック）
    // ============================================================

    /** USER→null / ORG→scopeId / TEAM→主所属組織（無所属 null）。@Transactional 外で解決してサービスへ渡す。 */
    private Long resolveOrganizationId(EntitlementScopeKind scopeKind, Long scopeId) {
        return switch (scopeKind) {
            case USER -> null;
            case ORG -> scopeId;
            case TEAM -> {
                List<Long> orgIds = teamOrgMembershipQueryService.findActiveOrganizationIds(scopeId);
                yield orgIds.isEmpty() ? null : orgIds.get(0);
            }
        };
    }
}
