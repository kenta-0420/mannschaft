package com.mannschaft.app.event.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.event.dto.TicketResponse;
import com.mannschaft.app.event.service.EventTicketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * イベントチケットコントローラー。チケットの照会・キャンセルAPIを提供する。
 */
@RestController
@RequestMapping("/api/v1/events/{eventId}/tickets")
@Tag(name = "イベントチケット", description = "F03.8 チケット照会・キャンセル")
@RequiredArgsConstructor
public class EventTicketController {

    private final EventTicketService ticketService;

    /**
     * チケット一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "チケット一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<TicketResponse>> listTickets(
            @PathVariable Long eventId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<TicketResponse> result = ticketService.listTickets(eventId, PageRequest.of(page, size));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    /**
     * チケット詳細を取得する。
     */
    @GetMapping("/{ticketId}")
    @Operation(summary = "チケット詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<TicketResponse>> getTicket(
            @PathVariable Long eventId,
            @PathVariable Long ticketId) {
        TicketResponse response = ticketService.getTicket(ticketId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * QRトークンでチケットを検索する。
     */
    @GetMapping("/by-qr")
    @Operation(summary = "QRトークンでチケット検索")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<TicketResponse>> getTicketByQrToken(
            @PathVariable Long eventId,
            @RequestParam String qrToken) {
        TicketResponse response = ticketService.getTicketByQrToken(qrToken);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * チケットをキャンセルする。
     */
    @PostMapping("/{ticketId}/cancel")
    @Operation(summary = "チケットキャンセル")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "キャンセル成功")
    public ResponseEntity<ApiResponse<TicketResponse>> cancelTicket(
            @PathVariable Long eventId,
            @PathVariable Long ticketId) {
        TicketResponse response = ticketService.cancelTicket(ticketId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
