package com.mannschaft.app.ticket.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.ticket.dto.QrCodeResponse;
import com.mannschaft.app.ticket.dto.TicketBookDetailResponse;
import com.mannschaft.app.ticket.dto.TicketBookResponse;
import com.mannschaft.app.ticket.dto.TicketWidgetResponse;
import com.mannschaft.app.ticket.entity.TicketPaymentEntity;
import com.mannschaft.app.ticket.service.TicketBookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 顧客向けチケットコントローラー。自分のチケット一覧・詳細・QR・ウィジェットを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/my-tickets")
@Tag(name = "マイチケット", description = "F08.5 顧客向けチケット閲覧")
@RequiredArgsConstructor
public class MyTicketController {

    private final TicketBookService bookService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * 自分のチケット一覧を取得する（MEMBER / SUPPORTER）。
     */
    @GetMapping
    @Operation(summary = "マイチケット一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<TicketBookResponse>>> getMyTickets(
            @PathVariable Long teamId,
            @RequestParam(required = false) String status) {
        List<TicketBookResponse> tickets = bookService.getMyTickets(teamId, getCurrentUserId(), status);
        return ResponseEntity.ok(ApiResponse.of(tickets));
    }

    /**
     * チケット詳細（消化履歴・決済情報付き）を取得する。
     */
    @GetMapping("/{bookId}")
    @Operation(summary = "マイチケット詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<TicketBookDetailResponse>> getMyTicketDetail(
            @PathVariable Long teamId,
            @PathVariable Long bookId) {
        TicketBookDetailResponse response = bookService.getTicketBookDetail(teamId, bookId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 領収書を取得する。Stripe 決済は receipt_url リダイレクト、現地決済は PDF 生成。
     */
    @GetMapping("/{bookId}/receipt")
    @Operation(summary = "領収書取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<?> getReceipt(
            @PathVariable Long teamId,
            @PathVariable Long bookId) {
        TicketPaymentEntity payment = bookService.getReceiptPayment(teamId, bookId);

        // TODO: Stripe 決済の場合は receipt_url にリダイレクト
        // TODO: 現地決済の場合は PDF を生成してダウンロード
        // 現在はプレースホルダーとして決済情報を返す
        return ResponseEntity.ok(ApiResponse.of("領収書データ（PDF生成は未実装）: paymentId=" + payment.getId()));
    }

    /**
     * チケット消化用 QR コードデータを取得する。
     */
    @GetMapping("/{bookId}/qr")
    @Operation(summary = "QR コード取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<QrCodeResponse>> getQrCode(
            @PathVariable Long teamId,
            @PathVariable Long bookId) {
        QrCodeResponse response = bookService.generateQrCode(teamId, bookId, getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * ダッシュボードウィジェット用の ACTIVE チケット残数サマリを取得する。
     */
    @GetMapping("/widget")
    @Operation(summary = "チケットウィジェット")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<TicketWidgetResponse>> getWidget(
            @PathVariable Long teamId) {
        TicketWidgetResponse response = bookService.getWidget(teamId, getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
