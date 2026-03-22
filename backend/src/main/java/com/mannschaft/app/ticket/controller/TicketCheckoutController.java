package com.mannschaft.app.ticket.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.ticket.dto.CheckoutResponse;
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


    /**
     * Stripe Checkout Session を作成する（MEMBER / SUPPORTER）。
     */
    @PostMapping("/{id}/checkout")
    @Operation(summary = "Stripe Checkout Session 作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Checkout Session 作成成功")
    public ResponseEntity<ApiResponse<CheckoutResponse>> createCheckout(
            @PathVariable Long teamId,
            @PathVariable Long id) {
        CheckoutResponse response = bookService.createCheckout(teamId, id, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
