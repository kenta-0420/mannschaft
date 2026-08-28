package com.mannschaft.app.role.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.featuregate.AlwaysReachable;
import com.mannschaft.app.common.featuregate.AlwaysReachableCategory;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.role.dto.TransferOwnershipAcceptResponse;
import com.mannschaft.app.role.dto.TransferOwnershipOfferCreateRequest;
import com.mannschaft.app.role.dto.TransferOwnershipOfferResponse;
import com.mannschaft.app.role.service.OwnershipTransferOfferService;
import com.mannschaft.app.team.service.TeamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * オーナー委譲 承諾型オファーコントローラー（F01.2・2026-07-18 承諾型化）。
 *
 * <p>チーム/組織のオーナー委譲を「打診 → 承諾/辞退/取消」の 2 ステップ承諾型で扱う。
 * 旧即時 API（{@code POST /{scope}/{slug}/transfer-ownership}）を置き換える（設計書 02_api_design）。
 * team/org で同一仕様のため、既存 {@code TeamController}/{@code OrganizationController} の
 * slug 解決の定石（{@code resolveTeamId}/{@code resolveOrgId}）を踏襲し 1 コントローラーに集約する。</p>
 *
 * <p><strong>認可:</strong> 各エンドポイントに認証必須の method-level シグナルを置く。
 * 打診＝ADMIN・承諾/辞退＝指名相手本人（宛先照合）・取消＝発行者/ADMIN の細粒度認可は
 * Service 層で実施する（設計書 03_business_logic。実装は /出陣）。</p>
 */
@RestController
@Tag(name = "オーナー委譲オファー", description = "F01.2 承諾型オーナー委譲")
@RequiredArgsConstructor
public class OwnershipTransferOfferController {

    private static final String SCOPE_TEAM = "TEAM";
    private static final String SCOPE_ORGANIZATION = "ORGANIZATION";

    private final OwnershipTransferOfferService offerService;
    private final TeamService teamService;
    private final OrganizationService organizationService;

    // ========================================
    // 受信インボックス（自分宛オファー）
    // ========================================

    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "本人宛てオーナー委譲打診の確認は中核の所有権管理機能として常時提供する")
    @GetMapping("/api/v1/me/ownership-transfer-offers")
    @Operation(summary = "自分宛の有効な（PENDING）オーナー委譲オファー一覧",
            description = "指名相手（本人）宛の PENDING オファーのみ返す。第三者が他人宛を取得する経路は"
                    + "構造的に存在しない（本人限定・IDOR 防止）。通知の actionUrl から到達した受信側 UI が"
                    + "オファーの存在確認・一覧表示に用いる。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<TransferOwnershipOfferResponse>>> listMyOffers() {
        List<TransferOwnershipOfferResponse> offers =
                offerService.listMyPendingOffers(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(offers));
    }

    // ========================================
    // チーム
    // ========================================

    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "チームのオーナー委譲打診は中核の所有権管理機能として常時提供する")
    @PostMapping("/api/v1/teams/{slug}/transfer-ownership-offers")
    @Operation(summary = "チーム オーナー委譲を打診")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "打診成功")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<TransferOwnershipOfferResponse>> createTeamOffer(
            @PathVariable String slug,
            @Valid @RequestBody TransferOwnershipOfferCreateRequest request) {
        Long id = teamService.resolveTeamId(slug);
        TransferOwnershipOfferResponse response =
                offerService.createOffer(id, SCOPE_TEAM, slug, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "チームの保留中オーナー委譲打診の確認は中核の所有権管理機能として常時提供する")
    @GetMapping("/api/v1/teams/{slug}/transfer-ownership-offers/pending")
    @Operation(summary = "チームの有効なオーナー委譲打診を取得")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<TransferOwnershipOfferResponse>>> listPendingTeamOffers(
            @PathVariable String slug) {
        Long id = teamService.resolveTeamId(slug);
        return ResponseEntity.ok(ApiResponse.of(offerService.listPendingOffersInScope(
                id, SCOPE_TEAM, SecurityUtils.getCurrentUserId())));
    }

    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "チームのオーナー委譲承諾は中核の所有権管理機能として常時提供する")
    @PostMapping("/api/v1/teams/{slug}/transfer-ownership-offers/{offerId}/accept")
    @Operation(summary = "チーム委譲を承諾（＝実行）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "承諾成功")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<TransferOwnershipAcceptResponse>> acceptTeamOffer(
            @PathVariable String slug,
            @PathVariable UUID offerId) {
        Long id = teamService.resolveTeamId(slug);
        TransferOwnershipAcceptResponse response =
                offerService.acceptOffer(id, SCOPE_TEAM, offerId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "チームのオーナー委譲辞退は中核の所有権管理機能として常時提供する")
    @PostMapping("/api/v1/teams/{slug}/transfer-ownership-offers/{offerId}/decline")
    @Operation(summary = "チーム委譲を辞退")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "辞退成功")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> declineTeamOffer(
            @PathVariable String slug,
            @PathVariable UUID offerId) {
        Long id = teamService.resolveTeamId(slug);
        offerService.declineOffer(id, SCOPE_TEAM, slug, offerId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok().build();
    }

    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "チームのオーナー委譲打診取消は中核の所有権管理機能として常時提供する")
    @DeleteMapping("/api/v1/teams/{slug}/transfer-ownership-offers/{offerId}")
    @Operation(summary = "チーム委譲オファーを取消")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "取消成功")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> cancelTeamOffer(
            @PathVariable String slug,
            @PathVariable UUID offerId) {
        Long id = teamService.resolveTeamId(slug);
        offerService.cancelOffer(id, SCOPE_TEAM, offerId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    // ========================================
    // 組織
    // ========================================

    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "組織のオーナー委譲打診は中核の所有権管理機能として常時提供する")
    @PostMapping("/api/v1/organizations/{slug}/transfer-ownership-offers")
    @Operation(summary = "組織 オーナー委譲を打診")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "打診成功")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<TransferOwnershipOfferResponse>> createOrgOffer(
            @PathVariable String slug,
            @Valid @RequestBody TransferOwnershipOfferCreateRequest request) {
        Long id = organizationService.resolveOrgId(slug);
        TransferOwnershipOfferResponse response =
                offerService.createOffer(id, SCOPE_ORGANIZATION, slug, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "組織の保留中オーナー委譲打診の確認は中核の所有権管理機能として常時提供する")
    @GetMapping("/api/v1/organizations/{slug}/transfer-ownership-offers/pending")
    @Operation(summary = "組織の有効なオーナー委譲打診を取得")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<TransferOwnershipOfferResponse>>> listPendingOrgOffers(
            @PathVariable String slug) {
        Long id = organizationService.resolveOrgId(slug);
        return ResponseEntity.ok(ApiResponse.of(offerService.listPendingOffersInScope(
                id, SCOPE_ORGANIZATION, SecurityUtils.getCurrentUserId())));
    }

    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "組織のオーナー委譲承諾は中核の所有権管理機能として常時提供する")
    @PostMapping("/api/v1/organizations/{slug}/transfer-ownership-offers/{offerId}/accept")
    @Operation(summary = "組織委譲を承諾（＝実行）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "承諾成功")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<TransferOwnershipAcceptResponse>> acceptOrgOffer(
            @PathVariable String slug,
            @PathVariable UUID offerId) {
        Long id = organizationService.resolveOrgId(slug);
        TransferOwnershipAcceptResponse response =
                offerService.acceptOffer(id, SCOPE_ORGANIZATION, offerId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "組織のオーナー委譲辞退は中核の所有権管理機能として常時提供する")
    @PostMapping("/api/v1/organizations/{slug}/transfer-ownership-offers/{offerId}/decline")
    @Operation(summary = "組織委譲を辞退")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "辞退成功")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> declineOrgOffer(
            @PathVariable String slug,
            @PathVariable UUID offerId) {
        Long id = organizationService.resolveOrgId(slug);
        offerService.declineOffer(id, SCOPE_ORGANIZATION, slug, offerId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok().build();
    }

    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "組織のオーナー委譲打診取消は中核の所有権管理機能として常時提供する")
    @DeleteMapping("/api/v1/organizations/{slug}/transfer-ownership-offers/{offerId}")
    @Operation(summary = "組織委譲オファーを取消")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "取消成功")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> cancelOrgOffer(
            @PathVariable String slug,
            @PathVariable UUID offerId) {
        Long id = organizationService.resolveOrgId(slug);
        offerService.cancelOffer(id, SCOPE_ORGANIZATION, offerId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }
}
