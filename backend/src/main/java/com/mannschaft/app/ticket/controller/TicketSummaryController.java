package com.mannschaft.app.ticket.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.ticket.dto.TicketSummaryResponse;
import com.mannschaft.app.ticket.service.TicketBookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 顧客チケットサマリコントローラー。カルテ連携ビュー用。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/users/{userId}")
@Tag(name = "顧客チケットサマリ", description = "F08.5 顧客チケット横断サマリ")
@RequiredArgsConstructor
public class TicketSummaryController {

    private final TicketBookService bookService;

    /**
     * 顧客の全チケット残数を横断表示する（ADMIN）。
     */
    @GetMapping("/ticket-summary")
    @Operation(summary = "顧客チケットサマリ")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<TicketSummaryResponse>> getTicketSummary(
            @PathVariable Long teamId,
            @PathVariable Long userId) {
        TicketSummaryResponse response = bookService.getTicketSummary(teamId, userId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
