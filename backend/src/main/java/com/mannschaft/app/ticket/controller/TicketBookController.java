package com.mannschaft.app.ticket.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.ticket.dto.BulkConsumeRequest;
import com.mannschaft.app.ticket.dto.BulkConsumeResponse;
import com.mannschaft.app.ticket.dto.ConsumeByQrRequest;
import com.mannschaft.app.ticket.dto.ConsumeResultResponse;
import com.mannschaft.app.ticket.dto.ConsumeTicketRequest;
import com.mannschaft.app.ticket.dto.ExtendRequest;
import com.mannschaft.app.ticket.dto.IssueResultResponse;
import com.mannschaft.app.ticket.dto.IssueTicketBookRequest;
import com.mannschaft.app.ticket.dto.RefundRequest;
import com.mannschaft.app.ticket.dto.TicketBookDetailResponse;
import com.mannschaft.app.ticket.dto.TicketBookResponse;
import com.mannschaft.app.ticket.dto.TicketStatsResponse;
import com.mannschaft.app.ticket.dto.TicketSummaryResponse;
import com.mannschaft.app.ticket.dto.VoidResultResponse;
import com.mannschaft.app.ticket.service.TicketBookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 回数券管理コントローラー（スタッフ向け）。
 * 手動発行・消化・取消・返金・延長・統計・エクスポート APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/ticket-books")
@Tag(name = "回数券管理", description = "F08.5 回数券管理（スタッフ向け）")
@RequiredArgsConstructor
public class TicketBookController {

    private final TicketBookService bookService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * 手動発行（現地決済。ADMIN / DEPUTY_ADMIN）。
     */
    @PostMapping("/issue")
    @Operation(summary = "回数券手動発行")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "発行成功")
    public ResponseEntity<ApiResponse<IssueResultResponse>> issueTicketBook(
            @PathVariable Long teamId,
            @Valid @RequestBody IssueTicketBookRequest request) {
        IssueResultResponse response = bookService.issueTicketBook(teamId, getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * チケット発行一覧（全顧客。ADMIN）。
     */
    @GetMapping
    @Operation(summary = "チケット発行一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<TicketBookResponse>> listTicketBooks(
            @PathVariable Long teamId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<TicketBookResponse> result = bookService.listTicketBooks(teamId, status, page, size);
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    /**
     * チケット詳細 + 消化履歴 + 決済情報（ADMIN）。
     */
    @GetMapping("/{id}")
    @Operation(summary = "チケット詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<TicketBookDetailResponse>> getTicketBookDetail(
            @PathVariable Long teamId,
            @PathVariable Long id) {
        TicketBookDetailResponse response = bookService.getTicketBookDetail(teamId, id);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * チケット消化（ADMIN / DEPUTY_ADMIN）。
     */
    @PostMapping("/{id}/consume")
    @Operation(summary = "チケット消化")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "消化成功")
    public ResponseEntity<ApiResponse<ConsumeResultResponse>> consumeTicket(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @Valid @RequestBody ConsumeTicketRequest request) {
        ConsumeResultResponse response = bookService.consumeTicket(teamId, id, getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 消化取消（ADMIN）。
     */
    @PostMapping("/{id}/void/{consumptionId}")
    @Operation(summary = "消化取消")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取消成功")
    public ResponseEntity<ApiResponse<VoidResultResponse>> voidConsumption(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @PathVariable Long consumptionId) {
        VoidResultResponse response = bookService.voidConsumption(teamId, id, consumptionId, getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 返金（ADMIN）。
     */
    @PostMapping("/{id}/refund")
    @Operation(summary = "返金")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "返金成功")
    public ResponseEntity<ApiResponse<TicketBookDetailResponse>> refund(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @Valid @RequestBody RefundRequest request) {
        TicketBookDetailResponse response = bookService.refund(teamId, id, getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 有効期限延長（ADMIN）。
     */
    @PatchMapping("/{id}/extend")
    @Operation(summary = "有効期限延長")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "延長成功")
    public ResponseEntity<ApiResponse<TicketBookDetailResponse>> extendExpiry(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @Valid @RequestBody ExtendRequest request) {
        TicketBookDetailResponse response = bookService.extendExpiry(teamId, id, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * QR スキャンによるチケット消化（ADMIN / DEPUTY_ADMIN）。
     */
    @PostMapping("/consume-by-qr")
    @Operation(summary = "QR スキャン消化")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "消化成功")
    public ResponseEntity<ApiResponse<ConsumeResultResponse>> consumeByQr(
            @PathVariable Long teamId,
            @Valid @RequestBody ConsumeByQrRequest request) {
        ConsumeResultResponse response = bookService.consumeByQr(
                teamId, getCurrentUserId(), request.getQrPayload(), request.getNote());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 複数チケット同時消化（ADMIN / DEPUTY_ADMIN）。
     */
    @PostMapping("/bulk-consume")
    @Operation(summary = "一括消化")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "一括消化成功")
    public ResponseEntity<ApiResponse<BulkConsumeResponse>> bulkConsume(
            @PathVariable Long teamId,
            @Valid @RequestBody BulkConsumeRequest request) {
        BulkConsumeResponse response = bookService.bulkConsume(teamId, getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * チケット統計（ADMIN）。
     */
    @GetMapping("/stats")
    @Operation(summary = "チケット統計")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<TicketStatsResponse>> getStats(
            @PathVariable Long teamId,
            @RequestParam(defaultValue = "30d") String period) {
        TicketStatsResponse response = bookService.getStats(teamId, period);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 売上レポート CSV/PDF エクスポート（ADMIN）。
     */
    @GetMapping("/stats/export")
    @Operation(summary = "売上レポートエクスポート")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "エクスポート成功")
    public ResponseEntity<?> exportStats(
            @PathVariable Long teamId,
            @RequestParam String format,
            @RequestParam String period) {
        // TODO: CSV / PDF エクスポート実装
        return ResponseEntity.ok(ApiResponse.of("エクスポート機能は未実装です: format=" + format + ", period=" + period));
    }
}
