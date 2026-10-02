package com.mannschaft.app.ticket.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.ticket.dto.TicketSummaryResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.ticket.service.TicketAccessGuard;
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
 *
 * <p>認可（認可根治 Wave5）: 本エンドポイントは URL の {@code teamId}・{@code userId} を検証せず、
 * 任意の顧客の氏名とチケット残数を開示していた。スタッフ向けのカルテ連携ビューであるため、
 * 入口で {@link TicketAccessGuard#requireTeamAdmin} を通し、当該チームの ADMIN/DEPUTY_ADMIN に限定する。
 * サマリの中身は {@code teamId} で束縛済みのため、他チームの顧客を指定しても空サマリに留まる。</p>
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/users/{userId}")
@Tag(name = "顧客チケットサマリ", description = "F08.5 顧客チケット横断サマリ")
@RequiredArgsConstructor
public class TicketSummaryController {

    private final TicketBookService bookService;
    private final TicketAccessGuard ticketAccessGuard;

    /**
     * 顧客の全チケット残数を横断表示する（ADMIN）。
     */
    @GetMapping("/ticket-summary")
    @Operation(summary = "顧客チケットサマリ")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<TicketSummaryResponse>> getTicketSummary(
            @PathVariable Long teamId,
            @PathVariable Long userId) {
        ticketAccessGuard.requireTeamAdmin(teamId, SecurityUtils.getCurrentUserId());
        TicketSummaryResponse response = bookService.getTicketSummary(teamId, userId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
