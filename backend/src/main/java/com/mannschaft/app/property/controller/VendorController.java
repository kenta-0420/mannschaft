package com.mannschaft.app.property.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.property.VendorCategory;
import com.mannschaft.app.property.dto.VendorRequest;
import com.mannschaft.app.property.dto.VendorResponse;
import com.mannschaft.app.property.dto.VendorSuggestionResponse;
import com.mannschaft.app.property.entity.VendorEntity;
import com.mannschaft.app.property.service.VendorService;
import com.mannschaft.app.property.service.VendorService.VendorUpsertRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 業者マスタ コントローラ（F09.13 Phase 1-δ）。
 *
 * <p>設計書 {@code docs/features/F09.13_property_history.md} §4「業者マスタ API」に対応。
 * パス: {@code /api/v1/{scope}/{id}/vendors}（{@code scope} = "teams" or "organizations"）。</p>
 *
 * <p><b>認可根治戦役 Wave3-B5</b>: 閲覧系（一覧/サジェスト/単体取得）は
 * {@link AccessControlService#checkMembership}、作成/更新/削除は
 * {@link AccessControlService#checkAdminOrAbove} で保護する。
 * BOLA（IDOR）防止は従来どおり {@code VendorService} 側の
 * {@code ensureScopeMatches}（scope 不一致は PROPERTY_005 で存在秘匿）に委ねる。</p>
 */
@RestController
@RequestMapping("/api/v1/{scope}/{scopeId}/vendors")
@RequiredArgsConstructor
public class VendorController {

    private final VendorService vendorService;
    private final AccessControlService accessControlService;

    // =========================================================================
    // 一覧 / 検索
    // =========================================================================

    /**
     * 業者一覧をページング取得する。
     *
     * <p>クエリパラメータ {@code q} 指定時は名称・カナ部分一致検索（オートコンプリートでなく
     * ページング表示用）、{@code category} 指定時はカテゴリで絞り込む（{@code q} と排他）。
     * いずれも未指定なら有効業者全件をページングで返す。</p>
     */
    @GetMapping
    public PagedResponse<VendorResponse> listVendors(
            @PathVariable("scope") String scope,
            @PathVariable("scopeId") Long scopeId,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "category", required = false) VendorCategory category,
            @RequestParam(value = "isActive", required = false) Boolean isActive,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        String scopeType = toScopeType(scope);
        accessControlService.checkMembership(SecurityUtils.getCurrentUserId(), scopeId, scopeType);
        Pageable pageable = PageRequest.of(page, size);

        // q または category が指定されていれば絞り込みリストを返す（一覧表示用に Page 化はせず簡易ラップ）
        if (q != null && !q.isBlank()) {
            List<VendorEntity> list = vendorService.suggestByName(scopeType, scopeId, q);
            return PagedResponse.of(
                    list.stream().map(VendorResponse::from).toList(),
                    new PagedResponse.PageMeta(list.size(), 0, list.size(),
                            list.isEmpty() ? 0 : 1));
        }
        if (category != null) {
            List<VendorEntity> list = vendorService.listByCategory(scopeType, scopeId, category);
            return PagedResponse.of(
                    list.stream().map(VendorResponse::from).toList(),
                    new PagedResponse.PageMeta(list.size(), 0, list.size(),
                            list.isEmpty() ? 0 : 1));
        }

        Page<VendorEntity> result = vendorService.listActiveVendors(scopeType, scopeId, pageable);
        // isActive=false が明示指定された場合の絞り込みは Repository に専用クエリが無いため
        // 1-δ では無視する（全件取得 = 有効のみ）。Phase 2 で必要なら専用クエリを追加する。
        return PagedResponse.of(
                result.getContent().stream().map(VendorResponse::from).toList(),
                new PagedResponse.PageMeta(
                        result.getTotalElements(),
                        result.getNumber(),
                        result.getSize(),
                        result.getTotalPages()));
    }

    /**
     * オートコンプリート用 軽量サジェスト（最大 10 件）。
     */
    @GetMapping("/search")
    public ApiResponse<List<VendorSuggestionResponse>> searchVendors(
            @PathVariable("scope") String scope,
            @PathVariable("scopeId") Long scopeId,
            @RequestParam("q") String q) {
        String scopeType = toScopeType(scope);
        accessControlService.checkMembership(SecurityUtils.getCurrentUserId(), scopeId, scopeType);
        List<VendorEntity> list = vendorService.suggestByName(scopeType, scopeId, q);
        return ApiResponse.of(list.stream().map(VendorSuggestionResponse::from).toList());
    }

    // =========================================================================
    // 単体取得 / 作成 / 更新 / 削除
    // =========================================================================

    @GetMapping("/{vendorId}")
    public ApiResponse<VendorResponse> getVendor(
            @PathVariable("scope") String scope,
            @PathVariable("scopeId") Long scopeId,
            @PathVariable("vendorId") Long vendorId) {
        String scopeType = toScopeType(scope);
        accessControlService.checkMembership(SecurityUtils.getCurrentUserId(), scopeId, scopeType);
        // IDOR 防止: VendorService 側で scope 一致を検証する
        VendorEntity vendor = vendorService.getVendor(scopeType, scopeId, vendorId);
        return ApiResponse.of(VendorResponse.from(vendor));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<VendorResponse>> createVendor(
            @PathVariable("scope") String scope,
            @PathVariable("scopeId") Long scopeId,
            @Valid @RequestBody VendorRequest request) {
        String scopeType = toScopeType(scope);
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, scopeId, scopeType);
        VendorEntity created = vendorService.createVendor(scopeType, scopeId, userId, toUpsert(request));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(VendorResponse.from(created)));
    }

    @PutMapping("/{vendorId}")
    public ApiResponse<VendorResponse> updateVendor(
            @PathVariable("scope") String scope,
            @PathVariable("scopeId") Long scopeId,
            @PathVariable("vendorId") Long vendorId,
            @Valid @RequestBody VendorRequest request) {
        String scopeType = toScopeType(scope);
        accessControlService.checkAdminOrAbove(SecurityUtils.getCurrentUserId(), scopeId, scopeType);
        VendorEntity updated = vendorService.updateVendor(scopeType, scopeId, vendorId, toUpsert(request));
        return ApiResponse.of(VendorResponse.from(updated));
    }

    @DeleteMapping("/{vendorId}")
    public ResponseEntity<Void> deleteVendor(
            @PathVariable("scope") String scope,
            @PathVariable("scopeId") Long scopeId,
            @PathVariable("vendorId") Long vendorId) {
        String scopeType = toScopeType(scope);
        accessControlService.checkAdminOrAbove(SecurityUtils.getCurrentUserId(), scopeId, scopeType);
        vendorService.softDelete(scopeType, scopeId, vendorId);
        return ResponseEntity.noContent().build();
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

    private VendorUpsertRequest toUpsert(VendorRequest req) {
        return new VendorUpsertRequest(
                req.name(),
                req.nameKana(),
                req.category(),
                req.phone(),
                req.email(),
                req.website(),
                req.postalCode(),
                req.address(),
                req.representative(),
                req.contactPerson(),
                req.licenseNumber(),
                req.licenseExpiry(),
                req.note());
    }
}
