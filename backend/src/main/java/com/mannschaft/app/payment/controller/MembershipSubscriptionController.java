package com.mannschaft.app.payment.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.payment.dto.MembershipSubscriptionResponse;
import com.mannschaft.app.payment.dto.SubscribeRequest;
import com.mannschaft.app.payment.entity.MembershipSubscriptionEntity;
import com.mannschaft.app.payment.service.MembershipSubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * F08.9 P5 第二波: 継続課金コントローラー（subscribe / cancel・設計書 02 §4.1）。
 *
 * <p>払い手は常に {@code SecurityUtils.getCurrentUserId()}（ログインユーザー本人）で解決する。受益者への権原は
 * サービス層 {@code PaymentAuthorizationService} が最終防衛する（二重防御）。</p>
 *
 * <h3>エンドポイント（2本）</h3>
 * <ul>
 *   <li>{@code POST /api/v1/payment-items/{itemId}/subscribe} — 継続課金 加入（案b・初回単発 charge＋次サイクル Subscription）。</li>
 *   <li>{@code DELETE /api/v1/membership-subscriptions/{id}} — 期末解約（cancel_at_period_end=true）。</li>
 * </ul>
 *
 * <h3>@WebMvcTest 非互換の回避</h3>
 * 契約テストは {@code MockMvcBuilders.standaloneSetup} + {@code MockitoExtension} で構成し Spring Security を回避する
 * （#1266 前科・P1 Wave5 と同流儀）。
 */
@RestController
@Tag(name = "継続課金", description = "F08.9 P5 継続課金 加入/期末解約")
@RequiredArgsConstructor
public class MembershipSubscriptionController {

    private final MembershipSubscriptionService membershipSubscriptionService;

    /**
     * 継続課金に加入する（案b・初回単発 charge＋次サイクル Subscription・設計書 02 §4.1）。
     *
     * <p>冪等性：{@code Idempotency-Key} ヘッダ > ボディ {@code idempotencyKey} > 自動生成（UUID）。</p>
     *
     * @param itemId               継続課金項目 ID（{@code is_recurring=true}）
     * @param idempotencyKeyHeader {@code Idempotency-Key} ヘッダ（省略可）
     * @param request              受益者 ID・決済日・冪等キー
     * @return 201 Created + {@link MembershipSubscriptionResponse}（PENDING）
     */
    @PostMapping("/api/v1/payment-items/{itemId}/subscribe")
    @Operation(summary = "継続課金 加入（F08.9 P5・案b）")
    public ResponseEntity<ApiResponse<MembershipSubscriptionResponse>> subscribe(
            @PathVariable Long itemId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKeyHeader,
            @Valid @RequestBody SubscribeRequest request) {

        Long payerUserId = SecurityUtils.getCurrentUserId();
        String idempotencyKey = idempotencyKeyHeader != null ? idempotencyKeyHeader
                : request.getIdempotencyKey() != null ? request.getIdempotencyKey()
                : UUID.randomUUID().toString();

        MembershipSubscriptionEntity subscription = membershipSubscriptionService.subscribe(
                itemId, payerUserId, request.getBeneficiaryUserId(), request.getBillingAnchorDay(), idempotencyKey);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(MembershipSubscriptionResponse.from(subscription)));
    }

    /**
     * 継続課金を期末解約予約する（{@code cancel_at_period_end=true}・設計書 02 §4.1）。
     *
     * <p>認可は払い手本人 or 後見保護者（サービス層・03 §1）。応答に期末日（{@code currentPeriodEnd}）を含め
     * UI に「○月○日まで利用可」を明示する。</p>
     *
     * @param id 継続課金 ID
     * @return 200 OK + {@link MembershipSubscriptionResponse}（cancelAtPeriodEnd=true・currentPeriodEnd に期末日）
     */
    @DeleteMapping("/api/v1/membership-subscriptions/{id}")
    @Operation(summary = "継続課金 期末解約（F08.9 P5）")
    public ResponseEntity<ApiResponse<MembershipSubscriptionResponse>> cancel(@PathVariable UUID id) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        MembershipSubscriptionEntity subscription = membershipSubscriptionService.cancel(id, actorUserId);
        return ResponseEntity.ok(ApiResponse.of(MembershipSubscriptionResponse.from(subscription)));
    }
}
