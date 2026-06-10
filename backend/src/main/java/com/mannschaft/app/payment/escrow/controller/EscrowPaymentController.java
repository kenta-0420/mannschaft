package com.mannschaft.app.payment.escrow.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.payment.escrow.ConnectChargeService;
import com.mannschaft.app.payment.escrow.EscrowSourceKind;
import com.mannschaft.app.payment.escrow.dto.RecruitmentPaymentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
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
 * <p>エンドポイント数: 2（決済確認 GET / 汎用照会 GET）。</p>
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
     * @param listingId     札 ID（escrow の source_id）
     * @param participantId 応募 ID（escrow の source_participant_id）
     * @return 決済確認ビュー
     */
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
     * @param id エスクロー取引 ID
     * @return 照会ビュー
     */
    @GetMapping("/{id}")
    @Operation(summary = "エスクロー状態照会（支払者本人=clientSecret 含む / 受取側 ADMIN=状態・金額のみ）")
    public ResponseEntity<ApiResponse<RecruitmentPaymentResponse>> getEscrow(@PathVariable UUID id) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        ConnectChargeService.PaymentView view = connectChargeService.getEscrowView(id, actorUserId);
        return ResponseEntity.ok(ApiResponse.of(toResponse(view)));
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
