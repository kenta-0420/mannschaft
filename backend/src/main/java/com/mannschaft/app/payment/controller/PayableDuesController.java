package com.mannschaft.app.payment.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
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
     */
    @GetMapping("/payable-dues")
    @Operation(summary = "払える未払い会費一覧（後見まとめ払い）")
    public ResponseEntity<ApiResponse<PayableDuesResponse>> getPayableDues() {
        PayableDuesResponse response =
                payableDuesService.getPayableDues(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 選択した複数会費をまとめて決済起票する（部分成功）。
     */
    @PostMapping("/payable-dues/bulk-checkout")
    @Operation(summary = "後見まとめ払い 一括チェックアウト")
    public ResponseEntity<ApiResponse<BulkCheckoutResponse>> bulkCheckout(
            @Valid @RequestBody BulkCheckoutRequest req) {
        BulkCheckoutResponse response =
                payableDuesService.bulkCheckout(SecurityUtils.getCurrentUserId(), req);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
