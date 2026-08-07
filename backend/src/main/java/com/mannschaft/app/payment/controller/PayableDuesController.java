package com.mannschaft.app.payment.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.mannschaft.app.payment.dto.BulkCheckoutRequest;
import com.mannschaft.app.payment.dto.BulkCheckoutResponse;
import com.mannschaft.app.payment.dto.PayableDuesResponse;
import com.mannschaft.app.payment.service.PayableDuesService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F08.9 P2: 後見まとめ払いコントローラー（払い手分離・複数受益者の一括決済・設計書 02_api_design §1.2）。
 *
 * <p>払い手（保護者・本人＝認証ユーザー）が「本人＋後見下の子」の未払い会費を一覧し、選択分をまとめて決済する。
 * 払い手は常に {@link SecurityUtils#getCurrentUserId()} で確定し、受益者への権原はサービス層
 * {@code PaymentAuthorizationService.authorizePayment} が毎回実行時評価する（二重防御・IDOR 防止）。</p>
 *
 * <p>認可: {@code /api/v1/me/**} は SecurityConfig の {@code anyRequest().authenticated()}（deny-by-default）で
 * 認証必須。未認証は {@link SecurityUtils#getCurrentUserId()} が 401 を投げる。</p>
 *
 * <p>エンドポイント数: 2（GET payable-dues / POST payable-dues/bulk-checkout）</p>
 */
@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "後見まとめ払い", description = "F08.9 P2 払い手分離・複数受益者の一括決済")
@RequiredArgsConstructor
public class PayableDuesController {

    private final PayableDuesService payableDuesService;

    /**
     * 払える未払い会費一覧（本人＋後見下の子）を取得する。
     *
     * <p><b>認可根拠（{@link SelfScopedEndpoint}）</b>: {@code payableDuesService.getPayableDues}
     * は払い手を {@code SecurityUtils.getCurrentUserId()} 固定で解決し、後見下の子は当該払い手の
     * 後見関係から導出するため、リクエストで他人を払い手に据える経路が構造的に無い
     * （PayableDuesController#getPayableDues）。認可根治戦役 Wave6 監査済。</p>
     */
    @SelfScopedEndpoint(
            "payableDuesService.getPayableDues(userId) は SecurityUtils.getCurrentUserId() 固定の払い手から"
                    + "後見関係を導出する（PayableDuesController#getPayableDues）")
    @GetMapping("/payable-dues")
    @Operation(summary = "払える未払い会費一覧（後見まとめ払い）")
    public ResponseEntity<ApiResponse<PayableDuesResponse>> getPayableDues() {
        PayableDuesResponse response =
                payableDuesService.getPayableDues(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 選択した複数会費をまとめて決済起票する（部分成功）。
     *
     * <p><b>認可の所在</b>: 払い手は {@link SecurityUtils#getCurrentUserId()} 固定。ボディの
     * {@code beneficiaryUserId} に対する権原は、明細ごとに
     * {@code MemberPaymentService.createConnectCheckout}
     * （{@code payment/service/MemberPaymentService.java:392}）が
     * {@code PaymentAuthorizationService.authorizePayment}
     * （{@code payment/service/PaymentAuthorizationService.java:97}）で毎回実行時評価する
     * （{@code PayableDuesService.processOneItem}・{@code payment/service/PayableDuesService.java:242} 経由）。
     * 権原が成立しない明細は {@code skipReason="NOT_AUTHORIZED"} として結果に記録するだけで、
     * <b>Connect への課金起票も member_payments の INSERT も一切行わない</b>
     * （権原検証は起票・外部課金より前に位置する）。</p>
     */
    @AuthorizedInService
    @PostMapping("/payable-dues/bulk-checkout")
    @Operation(summary = "後見まとめ払い 一括チェックアウト")
    public ResponseEntity<ApiResponse<BulkCheckoutResponse>> bulkCheckout(
            @Valid @RequestBody BulkCheckoutRequest req) {
        BulkCheckoutResponse response =
                payableDuesService.bulkCheckout(SecurityUtils.getCurrentUserId(), req);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
