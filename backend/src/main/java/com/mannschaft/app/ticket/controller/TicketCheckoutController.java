package com.mannschaft.app.ticket.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.ticket.dto.CheckoutResponse;
import com.mannschaft.app.ticket.service.TicketAccessGuard;
import com.mannschaft.app.ticket.service.TicketBookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.mannschaft.app.common.SecurityUtils;

/**
 * Stripe Checkout コントローラー。チケット購入の決済フローを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/ticket-products")
@Tag(name = "回数券購入", description = "F08.5 回数券 Stripe Checkout")
@RequiredArgsConstructor
public class TicketCheckoutController {

    private final TicketBookService bookService;
    private final TicketAccessGuard ticketAccessGuard;


    /**
     * Stripe Checkout Session を作成する（MEMBER / SUPPORTER）。
     *
     * <p>認可根治 Wave5 追込: 本 EP は JavaDoc で「MEMBER / SUPPORTER」を宣言しながら
     * 認可の強制実装を持たず、ログイン済みであれば誰でも任意チームの商品に対して
     * Stripe Checkout Session と PENDING の購入行を作成できた。
     * 同ドメインの {@code TicketProductController.listProducts} は既に
     * {@link TicketAccessGuard#requireTeamMember} で MEMBER/SUPPORTER に限定されており、
     * <b>カタログ閲覧のほうが購入より厳しい</b>という粒度逆転が生じていたため、
     * 購入側を閲覧側と同水準に揃える。</p>
     *
     * <p>{@code requireTeamMember} は {@code memberships} 由来の判定
     * （{@code AccessControlService.java:73-76} → {@code MembershipRepository.java:40-46}）で、
     * {@code role_kind} を絞らないため <b>SUPPORTER も通る</b>
     * （{@code RoleKind.java:18-25} のとおり SUPPORTER は memberships の role_kind 値であり、
     * 別テーブルではない）。よって JavaDoc の宣言どおり MEMBER / SUPPORTER の双方が購入できる。</p>
     */
    @PostMapping("/{id}/checkout")
    @Operation(summary = "Stripe Checkout Session 作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Checkout Session 作成成功")
    public ResponseEntity<ApiResponse<CheckoutResponse>> createCheckout(
            @PathVariable Long teamId,
            @PathVariable Long id) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        ticketAccessGuard.requireTeamMember(teamId, currentUserId);
        CheckoutResponse response = bookService.createCheckout(teamId, id, currentUserId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
