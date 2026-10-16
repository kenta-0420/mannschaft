package com.mannschaft.app.loyalty;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.mannschaft.app.loyalty.dto.AccountDetail;
import com.mannschaft.app.loyalty.dto.AccountSummary;
import com.mannschaft.app.loyalty.dto.ApproveRequest;
import com.mannschaft.app.loyalty.dto.AwardRequestResponse;
import com.mannschaft.app.loyalty.dto.CreateRewardRequest;
import com.mannschaft.app.loyalty.dto.CreateRuleRequest;
import com.mannschaft.app.loyalty.dto.OperationCatalog;
import com.mannschaft.app.loyalty.dto.ProgramSettings;
import com.mannschaft.app.loyalty.dto.RedeemRequest;
import com.mannschaft.app.loyalty.dto.RedemptionPreview;
import com.mannschaft.app.loyalty.dto.RedemptionPreviewRequest;
import com.mannschaft.app.loyalty.dto.RequestActionRequest;
import com.mannschaft.app.loyalty.dto.RewardResponse;
import com.mannschaft.app.loyalty.dto.RuleResponse;
import com.mannschaft.app.loyalty.dto.ScanPreview;
import com.mannschaft.app.loyalty.dto.ScanPreviewRequest;
import com.mannschaft.app.loyalty.dto.SelfAwardBootstrap;
import com.mannschaft.app.loyalty.dto.SelfAwardBootstrapRequest;
import com.mannschaft.app.loyalty.dto.SelfAwardPreview;
import com.mannschaft.app.loyalty.dto.SelfAwardPreviewRequest;
import com.mannschaft.app.loyalty.dto.SelfAwardRequest;
import com.mannschaft.app.loyalty.dto.SelfAwardResult;
import com.mannschaft.app.loyalty.dto.StaffAwardRequest;
import com.mannschaft.app.loyalty.dto.TransactionResponse;
import com.mannschaft.app.loyalty.dto.UpdateProgramRequest;
import com.mannschaft.app.loyalty.dto.UpdateRewardRequest;
import com.mannschaft.app.loyalty.dto.UpdateRuleRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * F18.1チームポイントのHTTP入口。
 * <p>認証主体だけをSecurityUtilsから取得し、認可・receipt再送・業務処理は
 * TXを持たないLoyaltyFacadeへ委譲する。入力QRをログへ出力しない。</p>
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Team loyalty", description = "チームポイントの本人・スタッフ・設定操作")
@ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "入力形式または業務条件の不備"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "本人の認証が必要"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "当該操作の資格なし"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "対象が存在しない、または閲覧不可"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "版または同一キーの内容が競合")
})
public class LoyaltyController {
    private final LoyaltyFacade facade;

    @SelfScopedEndpoint("LoyaltyFacade.listAccountsはSecurityUtils由来の本人IDを検索条件に必須とし他人の口座・申請へ到達させない")
    @Operation(operationId = "loyaltyListAccounts", summary = "本人のチームポイント一覧")
    @GetMapping("/loyalty/accounts/me")
    public CursorPagedResponse<AccountSummary> listAccounts(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return facade.listAccounts(SecurityUtils.getCurrentUserId(), cursor, limit);
    }

    /** LoyaltyFacadeからLoyaltyAccessPolicyでTEAM資格・対象リソースまたは署名拠点を検証する。 */
    @AuthorizedInService
    @Operation(operationId = "loyaltyAccount", summary = "本人のチーム別残高")
    @GetMapping("/teams/{teamId}/loyalty/account/me")
    public ApiResponse<AccountDetail> account(
            @PathVariable Long teamId) {
        return ApiResponse.of(facade.account(SecurityUtils.getCurrentUserId(), teamId));
    }

    /** LoyaltyFacadeからLoyaltyAccessPolicyでTEAM資格・対象リソースまたは署名拠点を検証する。 */
    @AuthorizedInService
    @Operation(operationId = "loyaltyTransactions", summary = "本人のチーム別履歴")
    @GetMapping("/teams/{teamId}/loyalty/transactions/me")
    public CursorPagedResponse<TransactionResponse> transactions(
            @PathVariable Long teamId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return facade.transactions(SecurityUtils.getCurrentUserId(), teamId, cursor, limit);
    }

    /** LoyaltyFacadeからLoyaltyAccessPolicyでTEAM資格・対象リソースまたは署名拠点を検証する。 */
    @AuthorizedInService
    @Operation(operationId = "loyaltyOperationCatalog", summary = "スタッフ操作対象の取得")
    @GetMapping("/teams/{teamId}/loyalty/operation-catalog")
    public ApiResponse<OperationCatalog> operationCatalog(
            @PathVariable Long teamId) {
        return ApiResponse.of(facade.operationCatalog(SecurityUtils.getCurrentUserId(), teamId));
    }

    /** LoyaltyFacadeからLoyaltyAccessPolicyでTEAM資格・対象リソースまたは署名拠点を検証する。 */
    @AuthorizedInService
    @Operation(operationId = "loyaltySettings", summary = "ポイント設定の取得")
    @GetMapping("/teams/{teamId}/loyalty/settings")
    public ApiResponse<ProgramSettings> settings(
            @PathVariable Long teamId) {
        return ApiResponse.of(facade.settings(SecurityUtils.getCurrentUserId(), teamId));
    }

    /** LoyaltyFacadeからLoyaltyAccessPolicyでTEAM資格・対象リソースまたは署名拠点を検証する。 */
    @AuthorizedInService
    @Operation(operationId = "loyaltyUpdateSettings", summary = "ポイント設定の更新")
    @PutMapping("/teams/{teamId}/loyalty/settings")
    public ApiResponse<ProgramSettings> updateSettings(
            @PathVariable Long teamId,
            @Valid @RequestBody UpdateProgramRequest body) {
        return ApiResponse.of(facade.updateSettings(SecurityUtils.getCurrentUserId(), teamId, body));
    }

    /** LoyaltyFacadeからLoyaltyAccessPolicyでTEAM資格・対象リソースまたは署名拠点を検証する。 */
    @AuthorizedInService
    @Operation(operationId = "loyaltyCreateRule", summary = "付与ルールの作成", responses = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成済み", useReturnTypeSchema = true)
    })
    @PostMapping("/teams/{teamId}/loyalty/rules")
    public ResponseEntity<ApiResponse<RuleResponse>> createRule(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateRuleRequest body) {
        RuleResponse created = facade.createRule(SecurityUtils.getCurrentUserId(), teamId, body);
        URI location = URI.create("/api/v1/teams/" + teamId + "/loyalty/rules/" + created.id());
        return ResponseEntity.created(location).body(ApiResponse.of(created));
    }

    /** LoyaltyFacadeからLoyaltyAccessPolicyでTEAM資格・対象リソースまたは署名拠点を検証する。 */
    @AuthorizedInService
    @Operation(operationId = "loyaltyUpdateRule", summary = "付与ルールの更新")
    @PutMapping("/teams/{teamId}/loyalty/rules/{ruleId}")
    public ApiResponse<RuleResponse> updateRule(
            @PathVariable Long teamId,
            @PathVariable UUID ruleId,
            @Valid @RequestBody UpdateRuleRequest body) {
        return ApiResponse.of(facade.updateRule(SecurityUtils.getCurrentUserId(), teamId, ruleId, body));
    }

    /** LoyaltyFacadeからLoyaltyAccessPolicyでTEAM資格・対象リソースまたは署名拠点を検証する。 */
    @AuthorizedInService
    @Operation(operationId = "loyaltyCreateReward", summary = "特典の作成", responses = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成済み", useReturnTypeSchema = true)
    })
    @PostMapping("/teams/{teamId}/loyalty/rewards")
    public ResponseEntity<ApiResponse<RewardResponse>> createReward(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateRewardRequest body) {
        RewardResponse created = facade.createReward(SecurityUtils.getCurrentUserId(), teamId, body);
        URI location = URI.create("/api/v1/teams/" + teamId + "/loyalty/rewards/" + created.id());
        return ResponseEntity.created(location).body(ApiResponse.of(created));
    }

    /** LoyaltyFacadeからLoyaltyAccessPolicyでTEAM資格・対象リソースまたは署名拠点を検証する。 */
    @AuthorizedInService
    @Operation(operationId = "loyaltyUpdateReward", summary = "特典の更新")
    @PutMapping("/teams/{teamId}/loyalty/rewards/{rewardId}")
    public ApiResponse<RewardResponse> updateReward(
            @PathVariable Long teamId,
            @PathVariable UUID rewardId,
            @Valid @RequestBody UpdateRewardRequest body) {
        return ApiResponse.of(facade.updateReward(SecurityUtils.getCurrentUserId(), teamId, rewardId, body));
    }

    /** LoyaltyFacadeからLoyaltyAccessPolicyでTEAM資格・対象リソースまたは署名拠点を検証する。 */
    @AuthorizedInService
    @Operation(operationId = "loyaltyScanPreview", summary = "会員QRの付与前確認")
    @PostMapping("/teams/{teamId}/loyalty/scan-previews")
    public ApiResponse<ScanPreview> scanPreview(
            @PathVariable Long teamId,
            @Valid @RequestBody ScanPreviewRequest body) {
        return ApiResponse.of(facade.scanPreview(SecurityUtils.getCurrentUserId(), teamId, body));
    }

    /** LoyaltyFacadeからLoyaltyAccessPolicyでTEAM資格・対象リソースまたは署名拠点を検証する。 */
    @AuthorizedInService
    @Operation(operationId = "loyaltyRedemptionPreview", summary = "会員QRの交換前確認")
    @PostMapping("/teams/{teamId}/loyalty/redemption-previews")
    public ApiResponse<RedemptionPreview> redemptionPreview(
            @PathVariable Long teamId,
            @Valid @RequestBody RedemptionPreviewRequest body) {
        return ApiResponse.of(facade.redemptionPreview(SecurityUtils.getCurrentUserId(), teamId, body));
    }

    /** LoyaltyFacadeからLoyaltyAccessPolicyでTEAM資格・対象リソースまたは署名拠点を検証する。 */
    @AuthorizedInService
    @Operation(operationId = "loyaltyAward", summary = "スタッフによる付与確定")
    @PostMapping("/teams/{teamId}/loyalty/awards")
    public ApiResponse<TransactionResponse> award(
            @PathVariable Long teamId,
            @Valid @RequestBody StaffAwardRequest body,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return ApiResponse.of(facade.award(SecurityUtils.getCurrentUserId(), teamId, body, idempotencyKey));
    }

    /** LoyaltyFacadeからLoyaltyAccessPolicyでTEAM資格・対象リソースまたは署名拠点を検証する。 */
    @AuthorizedInService
    @Operation(operationId = "loyaltyRedeem", summary = "特典交換の確定")
    @PostMapping("/teams/{teamId}/loyalty/redemptions")
    public ApiResponse<TransactionResponse> redeem(
            @PathVariable Long teamId,
            @Valid @RequestBody RedeemRequest body,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return ApiResponse.of(facade.redeem(SecurityUtils.getCurrentUserId(), teamId, body, idempotencyKey));
    }

    /** LoyaltyFacadeからLoyaltyAccessPolicyでTEAM資格・対象リソースまたは署名拠点を検証する。 */
    @AuthorizedInService
    @Operation(operationId = "loyaltyBootstrap", summary = "掲示QRから本人の受付情報を取得")
    @PostMapping("/loyalty/self-award-bootstrap/me")
    public ApiResponse<SelfAwardBootstrap> bootstrap(
            @Valid @RequestBody SelfAwardBootstrapRequest body) {
        return ApiResponse.of(facade.bootstrap(SecurityUtils.getCurrentUserId(), body));
    }

    /** LoyaltyFacadeからLoyaltyAccessPolicyでTEAM資格・対象リソースまたは署名拠点を検証する。 */
    @AuthorizedInService
    @Operation(operationId = "loyaltySelfPreview", summary = "本人の受付確認")
    @PostMapping("/teams/{teamId}/loyalty/self-award-previews")
    public ApiResponse<SelfAwardPreview> selfPreview(
            @PathVariable Long teamId,
            @Valid @RequestBody SelfAwardPreviewRequest body) {
        return ApiResponse.of(facade.selfPreview(SecurityUtils.getCurrentUserId(), teamId, body));
    }

    /** LoyaltyFacadeからLoyaltyAccessPolicyでTEAM資格・対象リソースまたは署名拠点を検証する。 */
    @AuthorizedInService
    @Operation(operationId = "loyaltySelfAward", summary = "本人の受付確定")
    @PostMapping("/teams/{teamId}/loyalty/self-awards")
    public ApiResponse<SelfAwardResult> selfAward(
            @PathVariable Long teamId,
            @Valid @RequestBody SelfAwardRequest body,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return ApiResponse.of(facade.selfAward(SecurityUtils.getCurrentUserId(), teamId, body, idempotencyKey));
    }

    @SelfScopedEndpoint("LoyaltyFacade.myRequestsはSecurityUtils由来の本人IDを検索条件に必須とし他人の口座・申請へ到達させない")
    @Operation(operationId = "loyaltyMyRequests", summary = "本人の申請一覧")
    @GetMapping("/loyalty/award-requests/me")
    public CursorPagedResponse<AwardRequestResponse> myRequests(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return facade.myRequests(SecurityUtils.getCurrentUserId(), cursor, limit);
    }

    @SelfScopedEndpoint("LoyaltyFacade.myRequestはSecurityUtils由来の本人IDを検索条件に必須とし他人の口座・申請へ到達させない")
    @Operation(operationId = "loyaltyMyRequest", summary = "本人の申請詳細")
    @GetMapping("/loyalty/award-requests/{requestId}/me")
    public ApiResponse<AwardRequestResponse> myRequest(
            @PathVariable UUID requestId) {
        return ApiResponse.of(facade.myRequest(SecurityUtils.getCurrentUserId(), requestId));
    }

    @SelfScopedEndpoint("LoyaltyFacade.cancelはSecurityUtils由来の本人IDを検索条件に必須とし他人の口座・申請へ到達させない")
    @Operation(operationId = "loyaltyCancel", summary = "本人による申請取消")
    @PostMapping("/loyalty/award-requests/{requestId}/cancel/me")
    public ApiResponse<AwardRequestResponse> cancel(
            @PathVariable UUID requestId,
            @Valid @RequestBody RequestActionRequest body,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return ApiResponse.of(facade.cancel(SecurityUtils.getCurrentUserId(), requestId, body, idempotencyKey));
    }

    /** LoyaltyFacadeからLoyaltyAccessPolicyでTEAM資格・対象リソースまたは署名拠点を検証する。 */
    @AuthorizedInService
    @Operation(operationId = "loyaltyStaffRequests", summary = "スタッフの承認申請一覧")
    @GetMapping("/teams/{teamId}/loyalty/award-requests")
    public CursorPagedResponse<AwardRequestResponse> staffRequests(
            @PathVariable Long teamId,
            @RequestParam(defaultValue = "PENDING") RequestFilter status,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return facade.staffRequests(SecurityUtils.getCurrentUserId(), teamId, status, cursor, limit);
    }

    /** LoyaltyFacadeからLoyaltyAccessPolicyでTEAM資格・対象リソースまたは署名拠点を検証する。 */
    @AuthorizedInService
    @Operation(operationId = "loyaltyStaffRequest", summary = "スタッフの承認申請詳細")
    @GetMapping("/teams/{teamId}/loyalty/award-requests/{requestId}")
    public ApiResponse<AwardRequestResponse> staffRequest(
            @PathVariable Long teamId,
            @PathVariable UUID requestId) {
        return ApiResponse.of(facade.staffRequest(SecurityUtils.getCurrentUserId(), teamId, requestId));
    }

    /** LoyaltyFacadeからLoyaltyAccessPolicyでTEAM資格・対象リソースまたは署名拠点を検証する。 */
    @AuthorizedInService
    @Operation(operationId = "loyaltyApprove", summary = "照合コードによる申請承認")
    @PostMapping("/teams/{teamId}/loyalty/award-requests/{requestId}/approve")
    public ApiResponse<TransactionResponse> approve(
            @PathVariable Long teamId,
            @PathVariable UUID requestId,
            @Valid @RequestBody ApproveRequest body,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return ApiResponse.of(facade.approve(SecurityUtils.getCurrentUserId(), teamId, requestId, body, idempotencyKey));
    }

    /** LoyaltyFacadeからLoyaltyAccessPolicyでTEAM資格・対象リソースまたは署名拠点を検証する。 */
    @AuthorizedInService
    @Operation(operationId = "loyaltyReject", summary = "申請の却下")
    @PostMapping("/teams/{teamId}/loyalty/award-requests/{requestId}/reject")
    public ApiResponse<AwardRequestResponse> reject(
            @PathVariable Long teamId,
            @PathVariable UUID requestId,
            @Valid @RequestBody RequestActionRequest body,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return ApiResponse.of(facade.reject(SecurityUtils.getCurrentUserId(), teamId, requestId, body, idempotencyKey));
    }
}
