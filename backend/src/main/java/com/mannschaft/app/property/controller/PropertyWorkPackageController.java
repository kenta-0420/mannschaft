package com.mannschaft.app.property.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.ScopeKey;
import com.mannschaft.app.common.visibility.UserScopeRoleSnapshot;
import com.mannschaft.app.property.WorkPackageStatus;
import com.mannschaft.app.property.WorkType;
import com.mannschaft.app.property.dto.CategorySuggestionResponse;
import com.mannschaft.app.property.dto.ChangeStatusRequest;
import com.mannschaft.app.property.dto.PropertyWorkDocumentRequest;
import com.mannschaft.app.property.dto.PropertyWorkDocumentResponse;
import com.mannschaft.app.property.dto.PropertyWorkPackageRequest;
import com.mannschaft.app.property.dto.PropertyWorkPackageResponse;
import com.mannschaft.app.property.dto.PropertyWorkPackageSummaryResponse;
import com.mannschaft.app.property.entity.PropertyWorkPackageEntity;
import com.mannschaft.app.property.entity.VendorEntity;
import com.mannschaft.app.property.repository.PropertyWorkDocumentRepository;
import com.mannschaft.app.property.repository.PropertyWorkPackageRepository;
import com.mannschaft.app.property.service.PropertyWorkDocumentService;
import com.mannschaft.app.property.service.PropertyWorkExportService;
import com.mannschaft.app.property.service.PropertyWorkPackageMaskingService;
import com.mannschaft.app.property.service.PropertyWorkPackageMaskingService.MaskedView;
import com.mannschaft.app.property.service.PropertyWorkPackageService;
import com.mannschaft.app.property.service.PropertyWorkPackageService.WorkPackageRequest;
import com.mannschaft.app.property.service.VendorService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 物件履歴パッケージ コントローラ（F09.13 Phase 1-δ）。
 *
 * <p>設計書 {@code docs/features/F09.13_property_history.md} §4「履歴パッケージ API」に対応。
 * パス: {@code /api/v1/{scope}/{id}/property-history}（{@code scope} = "teams" or "organizations"）。</p>
 *
 * <p>マスキング判定はリクエストごとに 1 回 {@link MembershipBatchQueryService#snapshotForUser} を
 * 呼んで {@link UserScopeRoleSnapshot} を取得し、{@link PropertyWorkPackageMaskingService}
 * 経由で {@link MaskedView} を生成する。設計書 §5.5 マトリクス準拠。</p>
 */
@RestController
@RequestMapping("/api/v1/{scope}/{scopeId}/property-history")
@RequiredArgsConstructor
public class PropertyWorkPackageController {

    private final PropertyWorkPackageService packageService;
    private final PropertyWorkDocumentService documentService;
    private final PropertyWorkPackageMaskingService maskingService;
    private final PropertyWorkExportService exportService;
    private final PropertyWorkPackageRepository packageRepository;
    private final PropertyWorkDocumentRepository documentRepository;
    private final VendorService vendorService;
    private final MembershipBatchQueryService membershipBatchQueryService;
    /** 認可根治戦役 Wave3-B5: scope 認可（checkMembership/checkAdminOrAbove）用。 */
    private final AccessControlService accessControlService;

    // =========================================================================
    // 一覧 / タイムライン / ガント
    // =========================================================================

    /**
     * パッケージ一覧をページング取得する（フィルタ: from/to/workType/vendorId/status）。
     *
     * <p>本フェーズでは Repository に専用 Specification を組まず、{@code workType} or
     * {@code status} のいずれかで絞り込めるシンプルなパスのみ用意する。複合条件 / 期間指定は
     * Phase 2 で {@link org.springframework.data.jpa.domain.Specification} 構築に切替予定。</p>
     */
    @GetMapping
    public PagedResponse<PropertyWorkPackageSummaryResponse> listPackages(
            @PathVariable("scope") String scope,
            @PathVariable("scopeId") Long scopeId,
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "workType", required = false) WorkType workType,
            @RequestParam(value = "vendorId", required = false) Long vendorId,
            @RequestParam(value = "status", required = false) WorkPackageStatus status,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        String scopeType = toScopeType(scope);
        accessControlService.checkMembership(SecurityUtils.getCurrentUserId(), scopeId, scopeType);
        UserScopeRoleSnapshot snapshot = loadSnapshot(scopeType, scopeId);
        Pageable pageable = PageRequest.of(page, size);

        Page<PropertyWorkPackageEntity> result;
        if (status != null) {
            result = packageRepository
                    .findByScopeTypeAndScopeIdAndStatusAndDeletedAtIsNull(
                            scopeType, scopeId, status, pageable);
        } else if (workType != null) {
            result = packageRepository
                    .findByScopeTypeAndScopeIdAndWorkTypeAndDeletedAtIsNull(
                            scopeType, scopeId, workType, pageable);
        } else {
            result = packageService.list(scopeType, scopeId, pageable);
        }

        // from/to/vendorId は本フェーズでは Service にフィルタ機能を実装しないため、
        // ここでメモリ上で簡易絞り込みを掛ける（負荷の本格対策は Phase 2 で Specification 化）。
        List<PropertyWorkPackageEntity> filtered = result.getContent().stream()
                .filter(e -> from == null || (e.getActualEndDate() != null
                        && !e.getActualEndDate().isBefore(from)))
                .filter(e -> to == null || (e.getActualEndDate() != null
                        && !e.getActualEndDate().isAfter(to)))
                .filter(e -> vendorId == null || vendorId.equals(e.getVendorId()))
                .toList();

        List<PropertyWorkPackageSummaryResponse> body = filtered.stream()
                .map(e -> toSummary(e, snapshot))
                .filter(s -> s != null)
                .toList();

        return PagedResponse.of(body, new PagedResponse.PageMeta(
                result.getTotalElements(),
                result.getNumber(),
                result.getSize(),
                result.getTotalPages()));
    }

    /** タイムラインビュー: actualEndDate / plannedEndDate を時系列に返す簡易実装。 */
    @GetMapping("/timeline")
    public ApiResponse<List<PropertyWorkPackageSummaryResponse>> timeline(
            @PathVariable("scope") String scope,
            @PathVariable("scopeId") Long scopeId,
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        String scopeType = toScopeType(scope);
        accessControlService.checkMembership(SecurityUtils.getCurrentUserId(), scopeId, scopeType);
        UserScopeRoleSnapshot snapshot = loadSnapshot(scopeType, scopeId);
        // タイムライン用の専用クエリは Repository に未実装のため、ガント用クエリで代替して時系列に整列。
        LocalDate effectiveFrom = from != null ? from : LocalDate.of(1970, 1, 1);
        LocalDate effectiveTo = to != null ? to : LocalDate.of(2999, 12, 31);
        List<PropertyWorkPackageEntity> entities =
                packageRepository.findForGantt(scopeType, scopeId, effectiveFrom, effectiveTo);
        return ApiResponse.of(entities.stream()
                .map(e -> toSummary(e, snapshot))
                .filter(s -> s != null)
                .toList());
    }

    /** ガントビュー: 計画日が範囲に重なる未削除パッケージを返す。 */
    @GetMapping("/gantt")
    public ApiResponse<List<PropertyWorkPackageSummaryResponse>> gantt(
            @PathVariable("scope") String scope,
            @PathVariable("scopeId") Long scopeId,
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        String scopeType = toScopeType(scope);
        accessControlService.checkMembership(SecurityUtils.getCurrentUserId(), scopeId, scopeType);
        UserScopeRoleSnapshot snapshot = loadSnapshot(scopeType, scopeId);
        List<PropertyWorkPackageEntity> entities =
                packageRepository.findForGantt(scopeType, scopeId, from, to);
        return ApiResponse.of(entities.stream()
                .map(e -> toSummary(e, snapshot))
                .filter(s -> s != null)
                .toList());
    }

    // =========================================================================
    // 詳細 / 作成 / 更新 / ステータス変更 / 削除
    // =========================================================================

    @GetMapping("/{packageId}")
    public ApiResponse<PropertyWorkPackageResponse> getPackage(
            @PathVariable("scope") String scope,
            @PathVariable("scopeId") Long scopeId,
            @PathVariable("packageId") Long packageId) {
        String scopeType = toScopeType(scope);
        // BOLA対策: entity由来scopeを path scope と照合（不一致は不在扱い＝存在秘匿）してから認可判定
        PropertyWorkPackageEntity entity = packageService.getByIdInScope(scopeType, scopeId, packageId);
        accessControlService.checkMembership(SecurityUtils.getCurrentUserId(), scopeId, scopeType);
        UserScopeRoleSnapshot snapshot = loadSnapshot(scopeType, scopeId);
        return ApiResponse.of(toDetail(entity, snapshot));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<PropertyWorkPackageResponse>> createPackage(
            @PathVariable("scope") String scope,
            @PathVariable("scopeId") Long scopeId,
            @Valid @RequestBody PropertyWorkPackageRequest request) {
        String scopeType = toScopeType(scope);
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, scopeId, scopeType);
        PropertyWorkPackageEntity created =
                packageService.create(scopeType, scopeId, userId, toServiceRequest(request));
        UserScopeRoleSnapshot snapshot = loadSnapshot(scopeType, scopeId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(toDetail(created, snapshot)));
    }

    @PutMapping("/{packageId}")
    public ApiResponse<PropertyWorkPackageResponse> updatePackage(
            @PathVariable("scope") String scope,
            @PathVariable("scopeId") Long scopeId,
            @PathVariable("packageId") Long packageId,
            @Valid @RequestBody PropertyWorkPackageRequest request) {
        String scopeType = toScopeType(scope);
        Long userId = SecurityUtils.getCurrentUserId();
        // BOLA対策: entity由来scopeを path scope と照合してから認可判定
        packageService.getByIdInScope(scopeType, scopeId, packageId);
        accessControlService.checkAdminOrAbove(userId, scopeId, scopeType);
        PropertyWorkPackageEntity updated =
                packageService.update(packageId, userId, toServiceRequest(request));
        UserScopeRoleSnapshot snapshot = loadSnapshot(scopeType, scopeId);
        return ApiResponse.of(toDetail(updated, snapshot));
    }

    @PatchMapping("/{packageId}/status")
    public ApiResponse<PropertyWorkPackageResponse> changeStatus(
            @PathVariable("scope") String scope,
            @PathVariable("scopeId") Long scopeId,
            @PathVariable("packageId") Long packageId,
            @Valid @RequestBody ChangeStatusRequest request) {
        String scopeType = toScopeType(scope);
        Long userId = SecurityUtils.getCurrentUserId();
        // BOLA対策: entity由来scopeを path scope と照合してから認可判定
        packageService.getByIdInScope(scopeType, scopeId, packageId);
        accessControlService.checkAdminOrAbove(userId, scopeId, scopeType);
        PropertyWorkPackageEntity updated =
                packageService.changeStatus(packageId, userId, request.status());
        UserScopeRoleSnapshot snapshot = loadSnapshot(scopeType, scopeId);
        return ApiResponse.of(toDetail(updated, snapshot));
    }

    @DeleteMapping("/{packageId}")
    public ResponseEntity<Void> deletePackage(
            @PathVariable("scope") String scope,
            @PathVariable("scopeId") Long scopeId,
            @PathVariable("packageId") Long packageId) {
        String scopeType = toScopeType(scope);
        // BOLA対策: entity由来scopeを path scope と照合してから認可判定
        packageService.getByIdInScope(scopeType, scopeId, packageId);
        accessControlService.checkAdminOrAbove(SecurityUtils.getCurrentUserId(), scopeId, scopeType);
        packageService.softDelete(packageId);
        return ResponseEntity.noContent().build();
    }

    // =========================================================================
    // 文書添付
    // =========================================================================

    @PostMapping("/{packageId}/documents")
    public ResponseEntity<ApiResponse<PropertyWorkDocumentResponse>> attachDocument(
            @PathVariable("scope") String scope,
            @PathVariable("scopeId") Long scopeId,
            @PathVariable("packageId") Long packageId,
            @Valid @RequestBody PropertyWorkDocumentRequest request) {
        // F09.13 Phase 2-α-1: PropertyWorkDocumentService に集約。
        // SharedFile の同スコープ検証（PROPERTY_008）と添付上限（PROPERTY_009）は
        // Service 内で実施し、Controller はリクエスト → Service 呼出 → DTO 変換のみ担う。
        String scopeType = toScopeType(scope);
        Long userId = SecurityUtils.getCurrentUserId();
        // 認可根治戦役 Wave3-B5: BOLA対策（entity由来scopeをpath scopeと照合）＋ checkAdminOrAbove
        packageService.getByIdInScope(scopeType, scopeId, packageId);
        accessControlService.checkAdminOrAbove(userId, scopeId, scopeType);
        com.mannschaft.app.property.entity.PropertyWorkDocumentEntity saved =
                documentService.attach(
                        packageId,
                        request.sharedFileId(),
                        request.documentKind(),
                        request.displayOrder(),
                        request.note(),
                        userId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(PropertyWorkDocumentResponse.from(saved)));
    }

    @DeleteMapping("/{packageId}/documents/{documentId}")
    public ResponseEntity<Void> detachDocument(
            @PathVariable("scope") String scope,
            @PathVariable("scopeId") Long scopeId,
            @PathVariable("packageId") Long packageId,
            @PathVariable("documentId") Long documentId) {
        // F09.13 Phase 2-α-1: PropertyWorkDocumentService.detach に集約。
        String scopeType = toScopeType(scope);
        Long userId = SecurityUtils.getCurrentUserId();
        // 認可根治戦役 Wave3-B5: BOLA対策（entity由来scopeをpath scopeと照合）＋ checkAdminOrAbove
        packageService.getByIdInScope(scopeType, scopeId, packageId);
        accessControlService.checkAdminOrAbove(userId, scopeId, scopeType);
        documentService.detach(packageId, documentId, userId);
        return ResponseEntity.noContent().build();
    }

    // =========================================================================
    // エクスポート (PDF / Excel)
    // =========================================================================

    @PostMapping("/{packageId}/export")
    public ResponseEntity<byte[]> exportSingle(
            @PathVariable("scope") String scope,
            @PathVariable("scopeId") Long scopeId,
            @PathVariable("packageId") Long packageId,
            @RequestParam(value = "format", defaultValue = "pdf") String format) {
        String scopeType = toScopeType(scope);
        accessControlService.checkMembership(SecurityUtils.getCurrentUserId(), scopeId, scopeType);
        UserScopeRoleSnapshot snapshot = loadSnapshot(scopeType, scopeId);
        Long viewerUserId = SecurityUtils.getCurrentUserIdOrNull();
        return exportService.exportSinglePackage(scopeType, scopeId, packageId, format,
                viewerUserId, snapshot);
    }

    @PostMapping("/export")
    public ResponseEntity<byte[]> exportList(
            @PathVariable("scope") String scope,
            @PathVariable("scopeId") Long scopeId,
            @RequestParam(value = "format", defaultValue = "pdf") String format,
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "workType", required = false) WorkType workType,
            @RequestParam(value = "vendorId", required = false) Long vendorId,
            @RequestParam(value = "status", required = false) WorkPackageStatus status) {
        String scopeType = toScopeType(scope);
        accessControlService.checkMembership(SecurityUtils.getCurrentUserId(), scopeId, scopeType);
        UserScopeRoleSnapshot snapshot = loadSnapshot(scopeType, scopeId);
        Long viewerUserId = SecurityUtils.getCurrentUserIdOrNull();
        return exportService.exportList(scopeType, scopeId, from, to, workType, vendorId, status,
                format, viewerUserId, snapshot);
    }

    // =========================================================================
    // カテゴリサジェスト
    // =========================================================================

    /**
     * 既存 category の頻度集計を返す（補完候補表示用）。
     *
     * @param sinceMonths {@code since} を「過去 N ヶ月」で指定する簡易パラメータ（デフォルト 12）。
     *                    本格的な日付指定は Phase 2 で {@code since=YYYY-MM-DD} を受け付ける。
     */
    @GetMapping("/categories/suggestions")
    public ApiResponse<List<CategorySuggestionResponse>> categorySuggestions(
            @PathVariable("scope") String scope,
            @PathVariable("scopeId") Long scopeId,
            @RequestParam(value = "since", defaultValue = "12") int sinceMonths) {
        String scopeType = toScopeType(scope);
        accessControlService.checkMembership(SecurityUtils.getCurrentUserId(), scopeId, scopeType);
        LocalDateTime since = LocalDateTime.now().minusMonths(Math.max(1, sinceMonths));
        List<Object[]> rows = packageRepository.aggregateCategoriesSince(scopeType, scopeId, since);
        List<CategorySuggestionResponse> body = rows.stream()
                .map(r -> new CategorySuggestionResponse(
                        (String) r[0],
                        ((Number) r[1]).longValue()))
                .toList();
        return ApiResponse.of(body);
    }

    // =========================================================================
    // 内部ヘルパー
    // =========================================================================

    private String toScopeType(String scope) {
        return switch (scope) {
            case "teams" -> "TEAM";
            case "organizations" -> "ORGANIZATION";
            default -> throw new IllegalArgumentException("Unsupported scope: " + scope);
        };
    }

    /** 当該 scope を direct/orgWide 両方の判定対象として snapshot を 1 SQL 経路で取得。 */
    private UserScopeRoleSnapshot loadSnapshot(String scopeType, Long scopeId) {
        Long userId = SecurityUtils.getCurrentUserIdOrNull();
        ScopeKey key = new ScopeKey(scopeType, scopeId);
        return membershipBatchQueryService.snapshotForUser(userId, Set.of(key), Set.of(key));
    }

    /** Entity → 詳細レスポンス（マスキング + tags + documents 同梱）。 */
    private PropertyWorkPackageResponse toDetail(PropertyWorkPackageEntity entity,
                                                 UserScopeRoleSnapshot snapshot) {
        VendorEntity vendor = entity.getVendorId() != null
                ? safeLoadVendor(entity.getScopeType(), entity.getScopeId(), entity.getVendorId())
                : null;
        Long viewerUserId = SecurityUtils.getCurrentUserIdOrNull();
        MaskedView masked = maskingService.applyMasking(entity, vendor, viewerUserId, snapshot);
        List<PropertyWorkDocumentResponse> docs =
                documentRepository.findByPackageIdOrderByDisplayOrderAscIdAsc(entity.getId())
                        .stream()
                        .map(PropertyWorkDocumentResponse::from)
                        .toList();
        boolean canEdit = canEdit(entity, snapshot);
        boolean canDelete = canDelete(entity, snapshot);
        List<String> tags = packageService.deserializeTags(entity);
        return PropertyWorkPackageResponse.from(entity, masked, tags, docs, canEdit, canDelete);
    }

    private PropertyWorkPackageSummaryResponse toSummary(PropertyWorkPackageEntity entity,
                                                         UserScopeRoleSnapshot snapshot) {
        VendorEntity vendor = entity.getVendorId() != null
                ? safeLoadVendor(entity.getScopeType(), entity.getScopeId(), entity.getVendorId())
                : null;
        Long viewerUserId = SecurityUtils.getCurrentUserIdOrNull();
        MaskedView masked = maskingService.applyMasking(entity, vendor, viewerUserId, snapshot);
        if (!masked.visible()) {
            return null;
        }
        return PropertyWorkPackageSummaryResponse.from(entity, masked);
    }

    /** 業者取得失敗（削除済 vendor / scope 不一致の場合等）でも一覧表示が壊れないよう null フォールバック。
     *  IDOR 防止のため、パッケージ自身の scope を渡して vendor が同一スコープか検証する。 */
    private VendorEntity safeLoadVendor(String scopeType, Long scopeId, Long vendorId) {
        try {
            return vendorService.getVendor(scopeType, scopeId, vendorId);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 編集可否判定。設計書 §6.1 アクセス制御に基づき、ADMIN / DEPUTY_ADMIN / SystemAdmin
     * のみ true。MEMBER 以下は false。
     *
     * <p>DEPUTY_ADMIN(MANAGE)/(VIEW) の区別は {@link PropertyWorkPackageMaskingService}
     * 同様、本フェーズでは MANAGE 相当として扱う暫定実装。</p>
     */
    private boolean canEdit(PropertyWorkPackageEntity entity, UserScopeRoleSnapshot snapshot) {
        if (snapshot.isSystemAdmin()) {
            return true;
        }
        ScopeKey scope = new ScopeKey(entity.getScopeType(), entity.getScopeId());
        String role = snapshot.roleByScope().get(scope);
        return "ADMIN".equals(role) || "DEPUTY_ADMIN".equals(role);
    }

    /** 削除可否判定。本フェーズでは canEdit と同基準（ADMIN 系のみ）。 */
    private boolean canDelete(PropertyWorkPackageEntity entity, UserScopeRoleSnapshot snapshot) {
        return canEdit(entity, snapshot);
    }

    private WorkPackageRequest toServiceRequest(PropertyWorkPackageRequest req) {
        return new WorkPackageRequest(
                req.workType(),
                req.category(),
                req.title(),
                req.description(),
                req.dwellingUnitId(),
                req.incidentId(),
                req.incidentDate(),
                req.incidentNarrative(),
                req.plannedStartDate(),
                req.plannedEndDate(),
                req.actualStartDate(),
                req.actualEndDate(),
                req.vendorId(),
                req.estimatedAmount(),
                req.contractAmount(),
                req.actualAmount(),
                req.currency(),
                req.budgetTransactionId(),
                req.warrantyUntil(),
                req.isDisclosable(),
                req.visibility(),
                req.tags());
    }
}
