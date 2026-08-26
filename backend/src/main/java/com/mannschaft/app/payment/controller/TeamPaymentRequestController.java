package com.mannschaft.app.payment.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.payment.dto.PaymentRequestPayResponse;
import com.mannschaft.app.payment.dto.PaymentRequestResponse;
import com.mannschaft.app.payment.entity.PaymentRequestEntity;
import com.mannschaft.app.payment.service.PaymentRequestPayResult;
import com.mannschaft.app.payment.service.PaymentRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * F08.9 P7 第二波: チーム（請求先）視点の協会請求コントローラー（02_api §7）。
 *
 * <p>チーム ADMIN が受信した請求を一覧・詳細（初閲覧で VIEWED 遷移）・支払い（案3 立替課金）する。
 * 認可・IDOR は {@link PaymentRequestService} 内部（{@code requireTeamAdmin}・payer_scope_id 一致）で行う。</p>
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/payment-requests")
@Tag(name = "協会請求（チーム視点）", description = "F08.9 P7 受信した請求の一覧・詳細・支払い")
@RequiredArgsConstructor
public class TeamPaymentRequestController {

    private final PaymentRequestService paymentRequestService;

    /**
     * チームが受信した請求一覧を取得する（新しい順）。
     */
    @GetMapping
    @Operation(summary = "受信した協会請求の一覧")
    public ResponseEntity<ApiResponse<List<PaymentRequestResponse>>> list(@PathVariable Long teamId) {
        List<PaymentRequestEntity> requests = paymentRequestService.findForTeam(
                teamId, SecurityUtils.getCurrentUserId());
        List<PaymentRequestResponse> content = requests.stream()
                .map(PaymentRequestResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.of(content));
    }

    /**
     * 受信した請求の詳細を取得する（初閲覧なら SENT → VIEWED に遷移・冪等）。
     */
    @GetMapping("/{id}")
    @Operation(summary = "受信した協会請求の詳細（初閲覧で VIEWED 遷移）")
    public ResponseEntity<ApiResponse<PaymentRequestResponse>> detail(
            @PathVariable Long teamId,
            @PathVariable UUID id) {
        PaymentRequestEntity request = paymentRequestService.viewByTeam(
                teamId, id, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(PaymentRequestResponse.from(request)));
    }

    /**
     * 請求を支払う（案3 立替課金・SENT/VIEWED/OVERDUE → PAID）。
     *
     * <p>冪等性: {@code Idempotency-Key} ヘッダが付いていればそれを Stripe へ橋渡しする。省略時は UUID を生成。</p>
     */
    @PostMapping("/{id}/pay")
    @Operation(summary = "協会請求の支払い（案3 立替課金）")
    public ResponseEntity<ApiResponse<PaymentRequestPayResponse>> pay(
            @PathVariable Long teamId,
            @PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKeyHeader) {
        String idempotencyKey = idempotencyKeyHeader != null && !idempotencyKeyHeader.isBlank()
                ? idempotencyKeyHeader : UUID.randomUUID().toString();
        PaymentRequestPayResult result = paymentRequestService.pay(
                teamId, id, SecurityUtils.getCurrentUserId(), idempotencyKey);
        return ResponseEntity.ok(ApiResponse.of(PaymentRequestPayResponse.from(result)));
    }
}
