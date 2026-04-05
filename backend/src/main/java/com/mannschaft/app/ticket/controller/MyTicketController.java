package com.mannschaft.app.ticket.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.pdf.PdfFileNameBuilder;
import com.mannschaft.app.common.pdf.PdfGeneratorService;
import com.mannschaft.app.common.pdf.PdfResponseHelper;
import com.mannschaft.app.ticket.PaymentMethod;
import com.mannschaft.app.ticket.dto.QrCodeResponse;
import com.mannschaft.app.ticket.dto.TicketBookDetailResponse;
import com.mannschaft.app.ticket.dto.TicketBookResponse;
import com.mannschaft.app.ticket.dto.TicketWidgetResponse;
import com.mannschaft.app.ticket.entity.TicketBookEntity;
import com.mannschaft.app.ticket.entity.TicketPaymentEntity;
import com.mannschaft.app.ticket.entity.TicketProductEntity;
import com.mannschaft.app.ticket.repository.TicketBookRepository;
import com.mannschaft.app.ticket.repository.TicketProductRepository;
import com.mannschaft.app.ticket.service.TicketBookService;
import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.model.PaymentIntent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 顧客向けチケットコントローラー。自分のチケット一覧・詳細・QR・ウィジェットを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/my-tickets")
@Tag(name = "マイチケット", description = "F08.5 顧客向けチケット閲覧")
@RequiredArgsConstructor
public class MyTicketController {

    private final TicketBookService bookService;
    private final PdfGeneratorService pdfGeneratorService;
    private final NameResolverService nameResolverService;
    private final TicketProductRepository ticketProductRepository;
    private final TicketBookRepository ticketBookRepository;

    // JwtAuthenticationFilter実装後にSecurityContextHolderから取得に変更予定
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

        // Stripe 決済の場合: Stripe API から receipt_url を取得してリダイレクト
        if (payment.getPaymentMethod() == PaymentMethod.STRIPE
                && payment.getStripePaymentIntentId() != null) {
            try {
                PaymentIntent pi = PaymentIntent.retrieve(payment.getStripePaymentIntentId());
                String receiptUrl = "https://dashboard.stripe.com/payments/" + payment.getStripePaymentIntentId();
                if (pi.getLatestCharge() != null) {
                    Charge charge = Charge.retrieve(pi.getLatestCharge());
                    if (charge.getReceiptUrl() != null) {
                        receiptUrl = charge.getReceiptUrl();
                    }
                }
                return ResponseEntity.status(302)
                        .location(URI.create(receiptUrl))
                        .build();
            } catch (StripeException e) {
                // Stripe API エラー時はダッシュボードURLにフォールバック
                return ResponseEntity.status(302)
                        .location(URI.create("https://dashboard.stripe.com/payments/"
                                + payment.getStripePaymentIntentId()))
                        .build();
            }
        }

        // 現地決済の場合: PDF 領収書を生成してダウンロード
        return generateReceiptPdf(payment, bookId);
    }

    /**
     * 現地決済の領収書PDFを生成する。
     */
    private ResponseEntity<?> generateReceiptPdf(TicketPaymentEntity payment, Long bookId) {
        // TicketProduct・TicketBook から商品名・枚数を解決
        TicketProductEntity product = ticketProductRepository.findById(payment.getProductId()).orElse(null);
        TicketBookEntity book = ticketBookRepository.findById(bookId).orElse(null);

        String productName = product != null ? product.getName() : "回数券";
        int quantity = book != null ? book.getTotalTickets() : 1;

        // テンプレートが期待する変数名にマッピング
        Map<String, Object> paymentMap = new HashMap<>();
        paymentMap.put("buyerName", nameResolverService.resolveUserDisplayName(payment.getUserId()));
        paymentMap.put("purchaseDate", payment.getPaidAt() != null
                ? payment.getPaidAt().toLocalDate().toString() : LocalDate.now().toString());
        paymentMap.put("amount", payment.getAmount());
        paymentMap.put("eventName", productName);
        paymentMap.put("ticketType", productName);
        paymentMap.put("quantity", quantity);
        paymentMap.put("paymentMethod", payment.getPaymentMethod().name());

        Map<String, Object> variables = new HashMap<>();
        variables.put("title", "チケット領収書");
        variables.put("payment", paymentMap);

        byte[] pdfBytes = pdfGeneratorService.generateFromTemplate(
                "pdf/ticket-receipt", variables);

        LocalDate purchaseDate = payment.getPaidAt() != null
                ? payment.getPaidAt().toLocalDate() : LocalDate.now();
        String buyerName = nameResolverService.resolveUserDisplayName(payment.getUserId());

        String fileName = PdfFileNameBuilder.of("チケット領収書")
                .date(purchaseDate)
                .identifier(buyerName + "様")
                .build();

        return PdfResponseHelper.toResponse(pdfBytes, fileName);
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
