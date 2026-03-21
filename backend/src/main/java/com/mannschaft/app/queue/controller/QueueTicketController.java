package com.mannschaft.app.queue.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.queue.QueueScopeType;
import com.mannschaft.app.queue.dto.AdminTicketRequest;
import com.mannschaft.app.queue.dto.CreateTicketRequest;
import com.mannschaft.app.queue.dto.TicketResponse;
import com.mannschaft.app.queue.service.QueueTicketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

import java.util.List;

/**
 * 順番待ちチケットコントローラー。チケットの発行・操作・一覧APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/queue")
@Tag(name = "順番待ちチケット管理", description = "F03.7 順番待ちチケットの発行・操作")
@RequiredArgsConstructor
public class QueueTicketController {

    private final QueueTicketService ticketService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * チケットを発行する。
     */
    @PostMapping("/counters/{counterId}/tickets")
    @Operation(summary = "チケット発行")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "発行成功")
    public ResponseEntity<ApiResponse<TicketResponse>> issueTicket(
            @PathVariable Long teamId,
            @PathVariable Long counterId,
            @Valid @RequestBody CreateTicketRequest request) {
        TicketResponse ticket = ticketService.issueTicket(
                counterId, request, getCurrentUserId(), QueueScopeType.TEAM, teamId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(ticket));
    }

    /**
     * カウンターの待ちチケット一覧を取得する。
     */
    @GetMapping("/counters/{counterId}/tickets")
    @Operation(summary = "待ちチケット一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<TicketResponse>>> listWaitingTickets(
            @PathVariable Long teamId,
            @PathVariable Long counterId) {
        List<TicketResponse> tickets = ticketService.listWaitingTickets(counterId);
        return ResponseEntity.ok(ApiResponse.of(tickets));
    }

    /**
     * カウンターの当日全チケット一覧を取得する（管理者用）。
     */
    @GetMapping("/counters/{counterId}/tickets/all")
    @Operation(summary = "全チケット一覧（管理者）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<TicketResponse>>> listAllTickets(
            @PathVariable Long teamId,
            @PathVariable Long counterId) {
        List<TicketResponse> tickets = ticketService.listAllTickets(counterId);
        return ResponseEntity.ok(ApiResponse.of(tickets));
    }

    /**
     * チケット詳細を取得する。
     */
    @GetMapping("/tickets/{ticketId}")
    @Operation(summary = "チケット詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<TicketResponse>> getTicket(
            @PathVariable Long teamId,
            @PathVariable Long ticketId) {
        TicketResponse ticket = ticketService.getTicket(ticketId);
        return ResponseEntity.ok(ApiResponse.of(ticket));
    }

    /**
     * 自分のチケット一覧を取得する。
     */
    @GetMapping("/tickets/me")
    @Operation(summary = "自分のチケット一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<TicketResponse>>> listMyTickets(
            @PathVariable Long teamId) {
        List<TicketResponse> tickets = ticketService.listMyTickets(getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(tickets));
    }

    /**
     * 自分のチケットをキャンセルする。
     */
    @DeleteMapping("/tickets/{ticketId}")
    @Operation(summary = "チケットキャンセル")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "キャンセル成功")
    public ResponseEntity<Void> cancelMyTicket(
            @PathVariable Long teamId,
            @PathVariable Long ticketId) {
        ticketService.cancelMyTicket(ticketId, getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    /**
     * 管理者によるチケット操作（呼び出し・対応開始・完了・不在・保留・再呼出）。
     */
    @PatchMapping("/tickets/{ticketId}/action")
    @Operation(summary = "チケット操作（管理者）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "操作成功")
    public ResponseEntity<ApiResponse<TicketResponse>> adminAction(
            @PathVariable Long teamId,
            @PathVariable Long ticketId,
            @Valid @RequestBody AdminTicketRequest request) {
        TicketResponse ticket = ticketService.adminAction(ticketId, request, getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(ticket));
    }

    /**
     * 次の待ちチケットを呼び出す。
     */
    @PostMapping("/counters/{counterId}/tickets/call-next")
    @Operation(summary = "次のチケット呼び出し")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "呼び出し成功")
    public ResponseEntity<ApiResponse<TicketResponse>> callNext(
            @PathVariable Long teamId,
            @PathVariable Long counterId) {
        TicketResponse ticket = ticketService.callNext(counterId, getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(ticket));
    }

    /**
     * カテゴリの待ちチケット一覧を取得する。
     */
    @GetMapping("/categories/{categoryId}/tickets")
    @Operation(summary = "カテゴリチケット一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<TicketResponse>>> listCategoryTickets(
            @PathVariable Long teamId,
            @PathVariable Long categoryId) {
        List<TicketResponse> tickets = ticketService.listCategoryTickets(categoryId);
        return ResponseEntity.ok(ApiResponse.of(tickets));
    }

    /**
     * ゲストチケットを発行する（認証不要）。
     */
    @PostMapping("/counters/{counterId}/tickets/guest")
    @Operation(summary = "ゲストチケット発行")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "発行成功")
    public ResponseEntity<ApiResponse<TicketResponse>> issueGuestTicket(
            @PathVariable Long teamId,
            @PathVariable Long counterId,
            @Valid @RequestBody CreateTicketRequest request) {
        TicketResponse ticket = ticketService.issueTicket(
                counterId, request, null, QueueScopeType.TEAM, teamId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(ticket));
    }

    /**
     * QRコード経由のチケット発行。
     */
    @PostMapping("/counters/{counterId}/tickets/qr")
    @Operation(summary = "QRコード経由チケット発行")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "発行成功")
    public ResponseEntity<ApiResponse<TicketResponse>> issueQrTicket(
            @PathVariable Long teamId,
            @PathVariable Long counterId,
            @Valid @RequestBody CreateTicketRequest request,
            @RequestParam String qrToken) {
        // QRトークン検証はQrCodeServiceで実施済みの前提
        TicketResponse ticket = ticketService.issueTicket(
                counterId, request, getCurrentUserId(), QueueScopeType.TEAM, teamId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(ticket));
    }
}
