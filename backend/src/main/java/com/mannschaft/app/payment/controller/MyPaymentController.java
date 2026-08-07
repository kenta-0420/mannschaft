package com.mannschaft.app.payment.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.payment.dto.MemberPaymentResponse;
import com.mannschaft.app.payment.dto.PaymentRequirementResponse;
import com.mannschaft.app.payment.service.MemberPaymentService;
import com.mannschaft.app.payment.service.PaymentRequirementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedByPathConfig;
import com.mannschaft.app.common.security.SelfScopedEndpoint;

/**
 * 自分の支払いコントローラー。ログインユーザー自身の支払い状況・未払い要件を提供する。
 * <p>
 * エンドポイント数: 3（GET me/payments, GET me/payment-requirements, GET me/subscriptions [Phase4]）
 */
@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "自分の支払い", description = "F08.2 自分の支払い状況・未払い要件")
@RequiredArgsConstructor
public class MyPaymentController {

    private final MemberPaymentService memberPaymentService;
    private final PaymentRequirementService paymentRequirementService;


    /**
     * 自分の支払い状況一覧を取得する。
     *
     * <p><b>認可根拠（{@link SelfScopedEndpoint}）</b>: {@code memberPaymentService.listMyPayments}
     * は {@code SecurityUtils.getCurrentUserId()} のみを検索条件に渡すため、他人の支払い状況へ
     * 到達する経路が構造的に無い（MyPaymentController#listMyPayments）。認可根治戦役 Wave6 監査済。</p>
     */
    @SelfScopedEndpoint(
            "memberPaymentService.listMyPayments(userId, pageable) は SecurityUtils.getCurrentUserId() のみを"
                    + "検索条件に渡す（MyPaymentController#listMyPayments）")
    @GetMapping("/payments")
    @Operation(summary = "自分の支払い状況一覧")
    public ResponseEntity<PagedResponse<MemberPaymentResponse>> listMyPayments(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<MemberPaymentResponse> result = memberPaymentService.listMyPayments(
                SecurityUtils.getCurrentUserId(), PageRequest.of(page, Math.min(size, 100)));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    /**
     * 自分に課されている未払い項目一覧を取得する。
     *
     * <p><b>認可根拠（{@link SelfScopedEndpoint}）</b>: {@code paymentRequirementService.getPaymentRequirements}
     * は {@code SecurityUtils.getCurrentUserId()} のみを検索条件に渡すため、他人の未払い項目へ
     * 到達する経路が構造的に無い（MyPaymentController#getPaymentRequirements）。認可根治戦役 Wave6 監査済。</p>
     */
    @SelfScopedEndpoint(
            "paymentRequirementService.getPaymentRequirements(userId) は SecurityUtils.getCurrentUserId() のみを"
                    + "検索条件に渡す（MyPaymentController#getPaymentRequirements）")
    @GetMapping("/payment-requirements")
    @Operation(summary = "未払い項目一覧")
    public ResponseEntity<ApiResponse<List<PaymentRequirementResponse>>> getPaymentRequirements() {
        List<PaymentRequirementResponse> requirements =
                paymentRequirementService.getPaymentRequirements(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(requirements));
    }

    /**
     * 自分の有効サブスクリプション一覧を取得する（Phase 4）。
     *
     * <p><b>認可方式（{@link AuthorizedByPathConfig} メソッド付与）</b>: {@code SecurityConfig.java:457
     * — .anyRequest().authenticated()}。Phase 4 未実装のため実体照会を行わず、認証済みユーザー全員に
     * 共通の固定応答（空リスト）を返す（MyPaymentController#listMySubscriptions）。
     * 実データ照会を実装する際は本注釈を外し、{@code SelfScopedEndpoint} 等へ差し替えること。
     * 認可根治戦役 Wave6 監査済。</p>
     */
    @AuthorizedByPathConfig
    @GetMapping("/subscriptions")
    @Operation(summary = "自分のサブスクリプション一覧（Phase 4）")
    public ResponseEntity<ApiResponse<List<Object>>> listMySubscriptions() {
        // Phase 4 実装予定
        return ResponseEntity.ok(ApiResponse.of(List.of()));
    }
}
