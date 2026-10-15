package com.mannschaft.app.tournament.fee;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.payment.dto.CheckoutResponse;
import com.mannschaft.app.tournament.fee.dto.CreateTournamentFeeRequest;
import com.mannschaft.app.tournament.fee.dto.TournamentFeeResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 大会参加費コントローラー（F08.7.1/07）。
 *
 * <p>大会／ディビジョンと F08.2 の payment_item を結ぶ薄い連結（{@code tournament_fee}）の管理と、
 * 参加チーム代表による支払い導線を提供する。支払いの実処理（Stripe Checkout・webhook・返金）は
 * F08.2 の既存 API に委譲する（本コントローラーは「大会スコープのファサード」に留まる・設計書 §6）。</p>
 *
 * <p>エンドポイント:</p>
 * <ul>
 *   <li>POST   /fees                              参加費作成（主催組織 ADMIN）</li>
 *   <li>GET    /fees                              参加費一覧</li>
 *   <li>DELETE /fees/{feeId}                      参加費削除（主催組織 ADMIN）</li>
 *   <li>POST   /fees/{feeId}/teams/{teamId}/checkout  自チーム分の支払い（自チーム ADMIN/DEPUTY）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/tournaments/{tournamentId}/fees")
@Tag(name = "大会参加費", description = "F08.7.1/07 大会費用支払い（F08.2 決済基盤 再利用）")
@RequiredArgsConstructor
public class TournamentFeeController {

    private final TournamentFeeService feeService;

    @PostMapping
    @Operation(summary = "大会参加費作成", description = "主催組織 ADMIN のみ。payment_item は F08.2 で作成済みのものを連結する")
    public ResponseEntity<ApiResponse<TournamentFeeResponse>> createFee(
            @PathVariable Long orgId,
            @PathVariable Long tournamentId,
            @Valid @RequestBody CreateTournamentFeeRequest request) {
        TournamentFeeResponse response =
                feeService.createFee(orgId, tournamentId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @GetMapping
    @Operation(summary = "大会参加費一覧（全件）",
            description = "主催組織 ADMIN / SYSTEM_ADMIN のみ。全チーム分の参加費額・対象チームを含む全件閲覧（設計書 §6）")
    public ResponseEntity<ApiResponse<List<TournamentFeeResponse>>> listFees(
            @PathVariable Long orgId,
            @PathVariable Long tournamentId) {
        return ResponseEntity.ok(ApiResponse.of(
                feeService.listFees(orgId, tournamentId, SecurityUtils.getCurrentUserId())));
    }

    @DeleteMapping("/{feeId}")
    @Operation(summary = "大会参加費削除", description = "主催組織 ADMIN のみ。論理削除")
    public ResponseEntity<Void> deleteFee(
            @PathVariable Long orgId,
            @PathVariable Long tournamentId,
            @PathVariable UUID feeId) {
        feeService.deleteFee(orgId, tournamentId, feeId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{feeId}/teams/{teamId}/checkout")
    @Operation(summary = "自チーム分の参加費支払い（Stripe Checkout）",
            description = "自チーム ADMIN/DEPUTY_ADMIN のみ。実処理は F08.2 の checkout フローに委譲")
    public ResponseEntity<ApiResponse<CheckoutResponse>> checkout(
            @PathVariable Long orgId,
            @PathVariable Long tournamentId,
            @PathVariable UUID feeId,
            @PathVariable Long teamId) {
        CheckoutResponse response =
                feeService.checkout(orgId, tournamentId, feeId, teamId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
