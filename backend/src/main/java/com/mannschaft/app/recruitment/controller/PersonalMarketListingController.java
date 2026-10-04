package com.mannschaft.app.recruitment.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.mannschaft.app.recruitment.dto.CancelRecruitmentListingRequest;
import com.mannschaft.app.recruitment.dto.CreateRecruitmentListingRequest;
import com.mannschaft.app.recruitment.dto.PersonalMarketMatchResponse;
import com.mannschaft.app.recruitment.dto.RecruitmentListingResponse;
import com.mannschaft.app.recruitment.dto.RecruitmentListingSummaryResponse;
import com.mannschaft.app.recruitment.dto.PersonalMarketListingSummaryResponse;
import com.mannschaft.app.recruitment.dto.UpdateRecruitmentListingRequest;
import com.mannschaft.app.recruitment.service.PersonalMarketListingService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/market/listings")
@RequiredArgsConstructor
public class PersonalMarketListingController {

    private final PersonalMarketListingService personalMarketListingService;

    /**
     * 札の所有者（scopeId・createdBy）は認証主体に固定するが、本文の公開先 {@code audienceScopes[].scopeId} は
     * スコープIDとして受け取るため自己スコープ（到達不能）の主張はできない。認可の実体は Service にある:
     * {@code RecruitmentListingService#validatePersonalAudienceScopes} が公開先を本人の有効な所属
     * （{@code MembershipScopeQueryService} の user_roles ∪ memberships）と照合し、所属外・重複・不正値は
     * {@code PERSONAL_VISIBILITY_NOT_ALLOWED} で保存前に拒否する。{@code friendTargets} は PERSONAL では
     * 使われない（{@code MarketFriendTargetService#validate} は FRIEND_TEAMS_ONLY のみを扱い、個人札の可視性では受け付けない）。
     * 固定する契約テスト: {@code PersonalMarketAudienceScopeContractIT}。
     */
    @AuthorizedInService
    @PostMapping
    @Operation(summary = "個人市の札を下書きで作成")
    public ResponseEntity<ApiResponse<RecruitmentListingResponse>> create(
            @Valid @RequestBody CreateRecruitmentListingRequest request) {
        RecruitmentListingResponse response =
                personalMarketListingService.create(SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @SelfScopedEndpoint("認証済みユーザーIDを履歴の検索スコープへ固定する")
    @GetMapping
    @Operation(summary = "個人市で立てた札の履歴を取得")
    public ResponseEntity<PagedResponse<PersonalMarketListingSummaryResponse>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String prefectureCode,
            @RequestParam(required = false) String cityCode,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<PersonalMarketListingSummaryResponse> result = personalMarketListingService.list(
                SecurityUtils.getCurrentUserId(), status, prefectureCode, cityCode, categoryId,
                PageRequest.of(page, size));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    /** PersonalMarketListingController#listMatches の自己スコープ契約は PersonalMarketListingControllerTest が固定する。 */
    @SelfScopedEndpoint("認証済みユーザーIDをPERSONALのscopeIdとcreatedByへ複合固定する")
    @GetMapping("/{id}/matches")
    @Operation(summary = "個人市の札のマッチング状況を取得")
    public ResponseEntity<PagedResponse<PersonalMarketMatchResponse>> listMatches(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<PersonalMarketMatchResponse> result = personalMarketListingService.listMatches(
                SecurityUtils.getCurrentUserId(), id, PageRequest.of(page, size));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    /**
     * 対象札は (id, PERSONAL, scopeId=認証主体) で取得するが、本文の公開先 {@code audienceScopes[].scopeId} を
     * スコープIDとして受け取るため自己スコープの主張はできない。認可の実体は Service にある:
     * {@code RecruitmentListingService#validatePersonalUpdate} → {@code validatePersonalAudienceScopes} が
     * 公開先を本人の有効な所属と照合し、所属外は {@code PERSONAL_VISIBILITY_NOT_ALLOWED} で保存前に拒否する。
     * 固定する契約テスト: {@code PersonalMarketAudienceScopeContractIT}。
     */
    @AuthorizedInService
    @PatchMapping("/{id}")
    @Operation(summary = "個人札のDRAFT編集")
    public ResponseEntity<ApiResponse<RecruitmentListingResponse>> update(
            @PathVariable Long id, @Valid @RequestBody UpdateRecruitmentListingRequest request) {
        return ResponseEntity.ok(ApiResponse.of(personalMarketListingService.update(
                SecurityUtils.getCurrentUserId(), id, request)));
    }

    @SelfScopedEndpoint("個人札の scopeId と createdBy をログインユーザーへ固定して公開する")
    @PostMapping("/{id}/publish")
    @Operation(summary = "個人札の公開")
    public ResponseEntity<ApiResponse<RecruitmentListingResponse>> publish(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.of(personalMarketListingService.publish(
                SecurityUtils.getCurrentUserId(), id)));
    }

    @SelfScopedEndpoint("個人札の所有者を認証済みユーザーに固定する")
    @PostMapping("/{id}/cancel")
    @Operation(summary = "個人札の取消")
    public ResponseEntity<ApiResponse<RecruitmentListingResponse>> cancel(
            @PathVariable Long id, @RequestBody(required = false) CancelRecruitmentListingRequest request) {
        return ResponseEntity.ok(ApiResponse.of(personalMarketListingService.cancel(
                SecurityUtils.getCurrentUserId(), id, request)));
    }
}
