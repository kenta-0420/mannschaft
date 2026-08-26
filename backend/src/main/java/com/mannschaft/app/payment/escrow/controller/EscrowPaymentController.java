package com.mannschaft.app.payment.escrow.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.escrow.ConnectChargeService;
import com.mannschaft.app.payment.escrow.EscrowSourceKind;
import com.mannschaft.app.payment.escrow.EscrowStatus;
import com.mannschaft.app.payment.escrow.dto.ReceivedEscrowResponse;
import com.mannschaft.app.payment.escrow.dto.RecruitmentPaymentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * F22.1 謝礼決済 第二陣: 札主の決済確認 / エスクロー状態照会コントローラー（設計書 02 §1 行#8 / 03 §1）。
 *
 * <p>認証必須（{@code anyRequest().authenticated()} がカバー）。認可（支払者本人 or 受取側 scope ADMIN）と IDOR
 * （無関係者は 404 秘匿）、PCI 出し分け（{@code clientSecret} は支払者本人のみ）はサービス層
 * {@link ConnectChargeService} で担保する（{@link EscrowRefundController} と同方針）。</p>
 *
 * <p><b>二重与信回避（GET で副作用を起こさない）:</b> 成立リスナが成立時に escrow＋PaymentIntent を事前起票するため、
 * 本 GET は<b>新規 authorize を呼ばず</b>既存 escrow を引き当てて {@code clientSecret}（PI から retrieve）を返す。
 * リスナ未起票（@Async 遅延）の競合では 404（準備中）を返し、FE はリトライで起票完了を待つ。</p>
 *
 * <p>エンドポイント数: 3（決済確認 GET / 汎用照会 GET / 受取側一覧 GET）。</p>
 */
@RestController
@RequestMapping("/api/v1/payment/escrow")
@Tag(name = "謝礼決済 確認/照会", description = "F22.1 札主の決済確認（clientSecret）・エスクロー状態照会")
@RequiredArgsConstructor
public class EscrowPaymentController {

    private final ConnectChargeService connectChargeService;

    /**
     * 札主の決済確認ビューを取得する（謝礼・設計書 02 §1 行#8）。
     *
     * <p>札主（支払者本人）が、該当応募の謝礼エスクローの {@code clientSecret}＋手数料内訳＋状態を取得する。
     * {@code clientSecret} は支払者本人 × {@code PENDING_CONFIRMATION} 時のみ非 null。受取側 ADMIN は状態/金額のみ、
     * 無関係者は 404 秘匿。</p>
     *
     * <p><b>認可の所在</b>: {@code ConnectChargeService.buildPaymentView}
     * （{@code payment/escrow/ConnectChargeService.java:406}）が escrow の {@code payer_scope} と
     * 照会者を照合し、支払者本人でなければ {@code authorizePayeeAdminForView}（同 {@code :443}）で
     * 受取側スコープの権限を {@code AccessControlService} 経由で検証する。いずれにも該当しない照会者は
     * {@code PAYMENT_RESOURCE_NOT_FOUND}（404）で存在ごと秘匿する。</p>
     *
     * @param listingId     札 ID（escrow の source_id）
     * @param participantId 応募 ID（escrow の source_participant_id）
     * @return 決済確認ビュー
     */
    @AuthorizedInService
    @GetMapping("/recruitment/{listingId}/{participantId}/payment-intent")
    @Operation(summary = "札主の決済確認（謝礼エスクローの clientSecret＋手数料内訳・支払者本人のみ clientSecret）")
    public ResponseEntity<ApiResponse<RecruitmentPaymentResponse>> getRecruitmentPaymentIntent(
            @PathVariable Long listingId,
            @PathVariable Long participantId) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        ConnectChargeService.PaymentView view = connectChargeService.getRecruitmentPaymentView(
                EscrowSourceKind.RECRUITMENT, listingId, participantId, actorUserId);
        return ResponseEntity.ok(ApiResponse.of(toResponse(view)));
    }

    /**
     * エスクロー取引の状態を照会する（汎用・設計書 02 §1 行#8 / §8）。
     *
     * <p>認可で出し分け: 支払者本人→{@code clientSecret} 含む（PENDING_CONFIRMATION 時）、受取側 ADMIN→状態/金額のみ、
     * 無関係者→404 秘匿。</p>
     *
     * <p><b>認可の所在</b>: {@code ConnectChargeService.buildPaymentView}
     * （{@code payment/escrow/ConnectChargeService.java:406}）／
     * {@code authorizePayeeAdminForView}（同 {@code :443}）。支払者本人でも受取側スコープの
     * 権限保有者でもない照会者は {@code PAYMENT_RESOURCE_NOT_FOUND}（404）で秘匿する。</p>
     *
     * @param id エスクロー取引 ID
     * @return 照会ビュー
     */
    @AuthorizedInService
    @GetMapping("/{id}")
    @Operation(summary = "エスクロー状態照会（支払者本人=clientSecret 含む / 受取側 ADMIN=状態・金額のみ）")
    public ResponseEntity<ApiResponse<RecruitmentPaymentResponse>> getEscrow(@PathVariable UUID id) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        ConnectChargeService.PaymentView view = connectChargeService.getEscrowView(id, actorUserId);
        return ResponseEntity.ok(ApiResponse.of(toResponse(view)));
    }

    /**
     * 受取側（payee）が受け取ったエスクロー一覧を取得する（フォロー Wave A・設計書 02 §1 / 03 §1）。
     *
     * <p>受取側（応じ手＝payee 本人 or そのチーム/組織 ADMIN）が、自分が受け取った謝礼/会費のエスクローを一覧し、
     * 返金管理の対象を選ぶための EP。{@code scopeKind}/{@code scopeId} で受取 scope を指定し、サービス層
     * {@link ConnectChargeService#listReceivedEscrows} が認可（USER=本人のみ / TEAM=ADMIN / ORG=ADMIN）・IDOR
     * （無関係 scope は 403）を担保する。レスポンスに {@code clientSecret}/{@code pi_}/{@code acct_} 等の PCI 機密は
     * 含めない（受取側向け・03 §10）。</p>
     *
     * @param scopeKind 受取 scope 種別（USER/TEAM/ORG）
     * @param scopeId   受取 scope ID（USER は users.id・TEAM は teams.id・ORG は organizations.id）
     * @param status    状態フィルタ（任意・未指定は全状態）
     * @param page      ページ番号（既定 0）
     * @param size      ページサイズ（既定 20）
     * @return 受取エスクローの 1 ページ（camelCase・clientSecret 非含有）
     */
    @GetMapping("/received")
    @Operation(summary = "受取側のエスクロー一覧（payee 本人 or scope ADMIN・返金管理用・clientSecret 非含有）")
    public ResponseEntity<PagedResponse<ReceivedEscrowResponse>> listReceivedEscrows(
            @RequestParam ScopeKind scopeKind,
            @RequestParam Long scopeId,
            @RequestParam(required = false) EscrowStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        Page<ConnectChargeService.ReceivedEscrow> result = connectChargeService.listReceivedEscrows(
                scopeKind, scopeId, status, actorUserId, PageRequest.of(page, size));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(
                result.getContent().stream().map(this::toReceivedResponse).toList(), meta));
    }

    private ReceivedEscrowResponse toReceivedResponse(ConnectChargeService.ReceivedEscrow r) {
        return new ReceivedEscrowResponse(
                r.escrowId(), r.sourceKind(), r.sourceId(), r.sourceParticipantId(),
                r.captureMode(), r.status(), r.faceAmount(), r.chargeAmount(),
                r.applicationFeeAmount(), r.refundedAmount(), r.createdAt());
    }

    private RecruitmentPaymentResponse toResponse(ConnectChargeService.PaymentView view) {
        return new RecruitmentPaymentResponse(
                view.clientSecret(),
                view.escrowId(),
                view.status(),
                view.faceAmount(),
                view.chargeAmount(),
                view.applicationFeeAmount());
    }
}
