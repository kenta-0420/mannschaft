package com.mannschaft.app.payment.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.payment.dto.ReceiptResponse;
import com.mannschaft.app.payment.service.ReceiptService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会費領収書 API コントローラー（F08.9 P8）。
 *
 * <p>GET /api/v1/member-payments/{memberPaymentId}/receipt</p>
 * <p>払い手または受益者本人のみアクセス可能（IDOR 防止は ReceiptService で実施）。</p>
 */
@RestController
@RequestMapping("/api/v1/member-payments")
public class ReceiptController {

    private final ReceiptService receiptService;

    public ReceiptController(@Qualifier("memberPaymentReceiptService") ReceiptService receiptService) {
        this.receiptService = receiptService;
    }

    /**
     * 会費領収書を取得する。
     *
     * <p><b>認可の所在</b>: {@code ReceiptService.getReceipt}
     * （{@code payment/service/ReceiptService.java:50}）が対象の支払い記録を取得したうえで
     * 「払い手本人（{@code payer_user_id}）または受益者本人（{@code user_id}）」であることを照合し、
     * どちらでもなければ {@code PAYMENT_ACCESS_DENIED}（404）で拒否する。
     * 記録が存在しない場合は {@code MEMBER_PAYMENT_NOT_FOUND}（404）。
     * 両者を同一ステータスに揃えているのは、応答の差から支払い記録 ID の実在を
     * 判別されること（存在オラクル）を防ぐためである。
     * 金額・領収 URL は照合を通過した場合にのみ組み立てられる。</p>
     *
     * @param memberPaymentId 会費支払い記録ID
     * @return 領収書レスポンス
     */
    @AuthorizedInService
    @GetMapping("/{memberPaymentId}/receipt")
    public ResponseEntity<ApiResponse<ReceiptResponse>> getReceipt(
            @PathVariable Long memberPaymentId) {
        return ResponseEntity.ok(ApiResponse.of(
                receiptService.getReceipt(memberPaymentId, SecurityUtils.getCurrentUserId())));
    }
}
