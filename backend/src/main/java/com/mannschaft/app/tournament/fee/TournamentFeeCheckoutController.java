package com.mannschaft.app.tournament.fee;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.mannschaft.app.tournament.fee.dto.MyTournamentFeesResponse;
import com.mannschaft.app.tournament.fee.dto.TournamentFeeCheckoutRequest;
import com.mannschaft.app.tournament.fee.dto.TournamentFeeCheckoutResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 大会参加費 Connect 決済コントローラー（F08.7.1 Connect 決済連携）。
 *
 * <p>認証ユーザー個人の未払い参加費一覧と、Connect 決済チェックアウトの2エンドポイントを提供する。</p>
 *
 * <p>エンドポイント:</p>
 * <ul>
 *   <li>GET  /api/v1/tournament-fees/my           認証ユーザーの参加費一覧</li>
 *   <li>POST /api/v1/tournament-fees/{feeId}/checkout  Connect 決済チェックアウト</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/tournament-fees")
@Tag(name = "大会参加費（個人）", description = "F08.7.1 Connect 決済 — 認証ユーザー個人の参加費支払い導線")
@RequiredArgsConstructor
public class TournamentFeeCheckoutController {

    private final TournamentFeePaymentService tournamentFeePaymentService;

    /**
     * 認証ユーザーが対象となっている大会参加費一覧を取得する。
     *
     * <p><b>認可根拠（{@link SelfScopedEndpoint}）</b>: {@code tournamentFeePaymentService.getMyTournamentFees}
     * は {@code SecurityUtils.getCurrentUserId()} から解決した自身の所属組織・所属チーム集合のみを対象に
     * 参加費を導出するため、他人の参加費へ到達する経路が構造的に無い
     * （TournamentFeeCheckoutController#getMyTournamentFees）。認可根治戦役 Wave6 監査済。</p>
     */
    @SelfScopedEndpoint(
            "tournamentFeePaymentService.getMyTournamentFees(userId) は SecurityUtils.getCurrentUserId() から"
                    + "解決した自身の所属組織・所属チームのみを対象とする"
                    + "（TournamentFeeCheckoutController#getMyTournamentFees）")
    @GetMapping("/my")
    @Operation(summary = "自分の大会参加費一覧",
            description = "認証ユーザーが属する組織／チームを対象とした参加費を返す。支払い済みフラグ付き")
    public ResponseEntity<ApiResponse<MyTournamentFeesResponse>> getMyTournamentFees() {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(
                tournamentFeePaymentService.getMyTournamentFees(userId)));
    }

    /**
     * 大会参加費の Connect 決済チェックアウトを実行する。
     *
     * <p><b>認可根拠（{@link AuthorizedInService}）</b>: 払い手は {@code SecurityUtils.getCurrentUserId()}
     * 固定で、{@code tournamentFeePaymentService.checkoutFee} が委譲する
     * {@code MemberPaymentService.createConnectCheckout} 内の
     * {@code PaymentAuthorizationService.authorizePayment}（payer=beneficiary=SELF）が
     * 権原を実行時評価する（{@code checkoutFee} は payer/beneficiary をともに認証ユーザー固定で渡す）。
     * 認可根治戦役 Wave6 監査済。</p>
     */
    @AuthorizedInService
    @PostMapping("/{feeId}/checkout")
    @Operation(summary = "大会参加費 Connect 決済チェックアウト",
            description = "指定した参加費を Stripe Connect 経由で支払う。clientSecret を返すので Stripe.js で confirm する")
    public ResponseEntity<ApiResponse<TournamentFeeCheckoutResponse>> checkout(
            @PathVariable UUID feeId,
            @RequestBody(required = false) TournamentFeeCheckoutRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        String idempotencyKey = (request != null) ? request.idempotencyKey() : null;
        return ResponseEntity.ok(ApiResponse.of(
                tournamentFeePaymentService.checkoutFee(feeId, userId, idempotencyKey)));
    }
}
