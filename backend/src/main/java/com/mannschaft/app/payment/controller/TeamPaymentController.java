package com.mannschaft.app.payment.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.payment.dto.BulkPaymentRequest;
import com.mannschaft.app.payment.dto.BulkPaymentResponse;
import com.mannschaft.app.payment.dto.CreateManualPaymentRequest;
import com.mannschaft.app.payment.dto.MemberPaymentResponse;
import com.mannschaft.app.payment.dto.RemindResponse;
import com.mannschaft.app.payment.dto.UpdatePaymentRequest;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.payment.service.MemberPaymentService;
import com.mannschaft.app.payment.service.PaymentItemService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.mannschaft.app.common.SecurityUtils;

/**
 * チーム支払い記録コントローラー。チーム単位の支払い記録管理 API を提供する。
 * <p>
 * エンドポイント数: 8（GET payments, POST payments, PATCH payments/{paymentId},
 *                     POST payments/bulk, DELETE payments/{paymentId},
 *                     POST remind, GET payment-summary,
 *                     POST payments/{paymentId}/refund）
 * <p>
 * CSV エクスポート（GET payments/export）は {@link TeamPaymentExportController} に委譲。
 * </p>
 *
 * <p><b>認可根治戦役 Wave3-B1b（2026-07-16）:</b> listPayments/updatePayment/cancelPayment/refundPayment
 * の 4EP に認可を敷設する（双子コントローラー {@link OrganizationPaymentController}（Wave3-B1 済）と同型）。
 * 閲覧系（listPayments）は
 * {@link AccessControlService#checkMembership}（"TEAM" scope）、変更系（updatePayment/cancelPayment/
 * refundPayment・<b>refundPayment は Stripe 実返金の最重要案件</b>）は
 * {@link AccessControlService#checkAdminOrAbove} を要求する。加えて、path 上位スコープの ADMIN であっても
 * {@code itemId} が他チーム/組織の支払い項目である BOLA（越境）を防ぐため、
 * {@link PaymentItemService#findByIdAndTeamIdOrThrow} で {@code itemId} が {@code id}（チーム）配下に
 * 属することを検証してから {@link MemberPaymentService} の {@code itemId} 直渡しメソッドを呼ぶ。
 * 不一致は {@code PAYMENT_ITEM_NOT_FOUND}（404・存在秘匿）。</p>
 *
 * <p>sendRemind は既存の {@code checkAdminOrAbove(id, "TEAM")} に加え、path {@code id} と {@code itemId} の
 * スコープ不一致（team A の ADMIN が team B の itemId を渡す越境）を防ぐため
 * {@code findByIdAndTeamIdOrThrow} を追加した。</p>
 *
 * <p><b>認可根治戦役 Wave6（B3・2026-07-21）:</b> 手動入金記録（createManualPayment /
 * createBulkPayments）を双子の {@link OrganizationPaymentController} と同水準へ揃える。
 * {@code MemberPaymentService} 内部の {@code PaymentAuthorizationService} による払い手権原評価
 * （SELF / GUARDIAN / PROXY_GRANT / ADMIN_MANUAL）に加え、入口で
 * {@link AccessControlService#checkAdminOrAbove}（"TEAM"）と {@code itemId} のチーム帰属検証
 * （{@link PaymentItemService#findByIdAndTeamIdOrThrow}・不一致は 404・存在秘匿）を要求する。
 * これにより「手動での入金記録はスコープ ADMIN の操作である」という不変条件を入口で保証する。
 * 変更前の状態に関する詳細はマージ後に戦役台帳へ記録する。</p>
 */
@RestController
@RequestMapping("/api/v1/teams/{id}/payment-items/{itemId}")
@Tag(name = "チーム支払い記録", description = "F08.2 チーム支払い記録管理")
@RequiredArgsConstructor
public class TeamPaymentController {

    private final MemberPaymentService memberPaymentService;
    private final PaymentItemService paymentItemService;
    private final AccessControlService accessControlService;


    /**
     * メンバー支払い状況一覧を取得する。
     */
    @GetMapping("/payments")
    @Operation(summary = "メンバー支払い状況一覧")
    public ResponseEntity<PagedResponse<MemberPaymentResponse>> listPayments(
            @PathVariable Long id,
            @PathVariable Long itemId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, id, "TEAM");
        paymentItemService.findByIdAndTeamIdOrThrow(itemId, id);
        Page<MemberPaymentResponse> result = memberPaymentService.listPayments(
                itemId, status, PageRequest.of(page, Math.min(size, 100)));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    /**
     * 手動支払い記録を作成する。
     */
    @PostMapping("/payments")
    @Operation(summary = "手動支払い記録")
    public ResponseEntity<ApiResponse<MemberPaymentResponse>> createManualPayment(
            @PathVariable Long id,
            @PathVariable Long itemId,
            @Valid @RequestBody CreateManualPaymentRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, id, "TEAM");
        paymentItemService.findByIdAndTeamIdOrThrow(itemId, id);
        MemberPaymentResponse response = memberPaymentService.createManualPayment(
                itemId, userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 支払い記録を修正する。
     */
    @PatchMapping("/payments/{paymentId}")
    @Operation(summary = "支払い記録修正")
    public ResponseEntity<ApiResponse<MemberPaymentResponse>> updatePayment(
            @PathVariable Long id,
            @PathVariable Long itemId,
            @PathVariable Long paymentId,
            @Valid @RequestBody UpdatePaymentRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, id, "TEAM");
        paymentItemService.findByIdAndTeamIdOrThrow(itemId, id);
        MemberPaymentResponse response = memberPaymentService.updatePayment(itemId, paymentId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 手動支払い一括記録を作成する。
     */
    @PostMapping("/payments/bulk")
    @Operation(summary = "手動支払い一括記録")
    public ResponseEntity<ApiResponse<BulkPaymentResponse>> createBulkPayments(
            @PathVariable Long id,
            @PathVariable Long itemId,
            @Valid @RequestBody BulkPaymentRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, id, "TEAM");
        paymentItemService.findByIdAndTeamIdOrThrow(itemId, id);
        BulkPaymentResponse response = memberPaymentService.createBulkPayments(
                itemId, userId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 支払い記録を取り消す。
     */
    @DeleteMapping("/payments/{paymentId}")
    @Operation(summary = "支払い記録取り消し")
    public ResponseEntity<Void> cancelPayment(
            @PathVariable Long id,
            @PathVariable Long itemId,
            @PathVariable Long paymentId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, id, "TEAM");
        paymentItemService.findByIdAndTeamIdOrThrow(itemId, id);
        memberPaymentService.cancelPayment(itemId, paymentId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 未払いメンバーへリマインドを送信する。
     */
    @PostMapping("/remind")
    @Operation(summary = "未払いリマインド送信")
    public ResponseEntity<ApiResponse<RemindResponse>> sendRemind(
            @PathVariable Long id,
            @PathVariable Long itemId) {
        accessControlService.checkAdminOrAbove(SecurityUtils.getCurrentUserId(), id, "TEAM");
        // 認可根治戦役 Wave3-B1b: path {id}（TEAM）の ADMIN 判定に加え、その itemId が
        // team {id} 配下であることを検証する（他チームの itemId を渡す越境を防ぐ）。
        // itemId → scope が path {id} と一致するかをここで検証する（不一致は 404・存在秘匿）。
        paymentItemService.findByIdAndTeamIdOrThrow(itemId, id);
        RemindResponse response = memberPaymentService.sendRemind(itemId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 全額返金を実行する。
     */
    @PostMapping("/payments/{paymentId}/refund")
    @Operation(summary = "全額返金実行")
    public ResponseEntity<ApiResponse<MemberPaymentResponse>> refundPayment(
            @PathVariable Long id,
            @PathVariable Long itemId,
            @PathVariable Long paymentId) {
        Long userId = SecurityUtils.getCurrentUserId();
        // 最重要案件: Stripe 実返金。TEAM ADMIN 以上のみ＋itemId のチーム帰属検証（BOLA 是正）を通す。
        accessControlService.checkAdminOrAbove(userId, id, "TEAM");
        paymentItemService.findByIdAndTeamIdOrThrow(itemId, id);
        MemberPaymentResponse response = memberPaymentService.refundPayment(
                itemId, paymentId, userId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
