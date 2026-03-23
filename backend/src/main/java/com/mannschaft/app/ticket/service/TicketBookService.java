package com.mannschaft.app.ticket.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.ticket.PaymentMethod;
import com.mannschaft.app.ticket.PaymentStatus;
import com.mannschaft.app.ticket.TicketBookStatus;
import com.mannschaft.app.ticket.TicketErrorCode;
import com.mannschaft.app.ticket.TicketMapper;
import com.mannschaft.app.ticket.dto.BulkConsumeRequest;
import com.mannschaft.app.ticket.dto.BulkConsumeResponse;
import com.mannschaft.app.ticket.dto.CheckoutResponse;
import com.mannschaft.app.ticket.dto.ConsumeResultResponse;
import com.mannschaft.app.ticket.dto.ConsumeTicketRequest;
import com.mannschaft.app.ticket.dto.ConsumptionResponse;
import com.mannschaft.app.ticket.dto.ExtendRequest;
import com.mannschaft.app.ticket.dto.IssueResultResponse;
import com.mannschaft.app.ticket.dto.IssueTicketBookRequest;
import com.mannschaft.app.ticket.dto.QrCodeResponse;
import com.mannschaft.app.ticket.dto.RefundRequest;
import com.mannschaft.app.ticket.dto.TicketBookDetailResponse;
import com.mannschaft.app.ticket.dto.TicketBookResponse;
import com.mannschaft.app.ticket.dto.TicketStatsResponse;
import com.mannschaft.app.ticket.dto.TicketSummaryResponse;
import com.mannschaft.app.ticket.dto.TicketWidgetResponse;
import com.mannschaft.app.ticket.dto.VoidResultResponse;
import com.mannschaft.app.ticket.entity.TicketBookEntity;
import com.mannschaft.app.ticket.entity.TicketConsumptionEntity;
import com.mannschaft.app.ticket.entity.TicketPaymentEntity;
import com.mannschaft.app.ticket.entity.TicketProductEntity;
import com.mannschaft.app.ticket.repository.TicketBookRepository;
import com.mannschaft.app.ticket.repository.TicketConsumptionRepository;
import com.mannschaft.app.ticket.repository.TicketPaymentRepository;
import com.mannschaft.app.ticket.repository.TicketProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 回数券チケットサービス。チケットの発行・消化・返金・統計を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TicketBookService {

    private static final int VOID_TIME_LIMIT_HOURS = 72;
    private static final int CONSUMED_AT_LIMIT_HOURS = 72;
    private static final int BULK_CONSUME_MAX = 5;

    private final TicketBookRepository bookRepository;
    private final TicketConsumptionRepository consumptionRepository;
    private final TicketPaymentRepository paymentRepository;
    private final TicketProductRepository productRepository;
    private final StripeTicketService stripeTicketService;
    private final TicketQrService ticketQrService;
    private final TicketMapper ticketMapper;
    private final NameResolverService nameResolverService;

    // ==================== チケット購入 ====================

    /**
     * Stripe Checkout Session を作成する。
     *
     * @param teamId    チームID
     * @param productId 商品ID
     * @param userId    購入者ID
     * @return Checkout レスポンス
     */
    @Transactional
    public CheckoutResponse createCheckout(Long teamId, Long productId, Long userId) {
        TicketProductEntity product = findProductOrThrow(teamId, productId);

        if (!product.getIsActive()) {
            throw new BusinessException(TicketErrorCode.PRODUCT_NOT_ACTIVE);
        }
        if (!product.getIsOnlinePurchasable()) {
            throw new BusinessException(TicketErrorCode.PRODUCT_NOT_ONLINE_PURCHASABLE);
        }

        // 重複 Checkout 防止: 同一ユーザー × 同一商品で PENDING がある場合
        bookRepository.findByUserIdAndProductIdAndStatus(userId, productId, TicketBookStatus.PENDING)
                .ifPresent(existing -> {
                    // 既存がある場合はキャンセルして再作成
                    existing.cancel();
                    bookRepository.save(existing);
                    if (existing.getPaymentId() != null) {
                        paymentRepository.findById(existing.getPaymentId()).ifPresent(payment -> {
                            payment.markAsCancelled();
                            paymentRepository.save(payment);
                        });
                    }
                });

        // 決済レコード作成（PENDING）
        TicketPaymentEntity payment = TicketPaymentEntity.builder()
                .teamId(teamId)
                .userId(userId)
                .productId(productId)
                .paymentMethod(PaymentMethod.STRIPE)
                .amount(product.getPrice())
                .status(PaymentStatus.PENDING)
                .build();
        TicketPaymentEntity savedPayment = paymentRepository.save(payment);

        // チケットレコード作成（PENDING）
        TicketBookEntity book = TicketBookEntity.builder()
                .teamId(teamId)
                .productId(productId)
                .userId(userId)
                .totalTickets(product.getTotalTickets())
                .status(TicketBookStatus.PENDING)
                .paymentId(savedPayment.getId())
                .build();
        TicketBookEntity savedBook = bookRepository.save(book);

        // Stripe Checkout Session 作成
        try {
            String successUrl = "/teams/" + teamId + "/my-tickets";
            String cancelUrl = "/teams/" + teamId + "/ticket-products";
            StripeTicketService.CheckoutSessionResult result =
                    stripeTicketService.createCheckoutSession(product, userId,
                            savedPayment.getId(), savedBook.getId(), successUrl, cancelUrl);

            log.info("Checkout Session 作成: teamId={}, productId={}, userId={}, sessionId={}",
                    teamId, productId, userId, result.sessionId());
            return new CheckoutResponse(result.checkoutUrl(), result.sessionId(), result.expiresAt());
        } catch (Exception e) {
            log.error("Stripe Checkout Session 作成失敗: teamId={}, productId={}", teamId, productId, e);
            throw new BusinessException(TicketErrorCode.STRIPE_API_ERROR, e);
        }
    }

    /**
     * 手動発行（現地決済）。
     *
     * @param teamId  チームID
     * @param staffId スタッフID
     * @param request 発行リクエスト
     * @return 発行結果レスポンス
     */
    @Transactional
    public IssueResultResponse issueTicketBook(Long teamId, Long staffId, IssueTicketBookRequest request) {
        TicketProductEntity product = findProductOrThrow(teamId, request.getProductId());

        PaymentMethod paymentMethod = PaymentMethod.valueOf(request.getPaymentMethod());

        // 決済レコード作成（即座に PAID）
        TicketPaymentEntity payment = TicketPaymentEntity.builder()
                .teamId(teamId)
                .userId(request.getUserId())
                .productId(request.getProductId())
                .paymentMethod(paymentMethod)
                .amount(request.getAmount())
                .recordedBy(staffId)
                .note(request.getNote())
                .build();
        payment.markAsPaidOnSite();
        TicketPaymentEntity savedPayment = paymentRepository.save(payment);

        // チケットレコード作成（即座に ACTIVE）
        TicketBookEntity book = TicketBookEntity.builder()
                .teamId(teamId)
                .productId(request.getProductId())
                .userId(request.getUserId())
                .totalTickets(product.getTotalTickets())
                .paymentId(savedPayment.getId())
                .issuedBy(staffId)
                .note(request.getNote())
                .build();
        book.activate(product.getValidityDays());
        TicketBookEntity savedBook = bookRepository.save(book);

        log.info("回数券手動発行: teamId={}, bookId={}, userId={}, productId={}",
                teamId, savedBook.getId(), request.getUserId(), request.getProductId());

        TicketBookResponse bookResponse = toBookResponseWithProductName(savedBook, product.getName());
        IssueResultResponse.PaymentResponse paymentResponse = new IssueResultResponse.PaymentResponse(
                savedPayment.getId(), savedPayment.getPaymentMethod().name(),
                savedPayment.getAmount(), savedPayment.getStatus().name());

        return new IssueResultResponse(bookResponse, paymentResponse);
    }

    // ==================== チケット閲覧 ====================

    /**
     * 自分のチケット一覧を取得する。
     *
     * @param teamId チームID
     * @param userId ユーザーID
     * @param status ステータスフィルタ（null = ACTIVE のみ）
     * @return チケットレスポンスリスト
     */
    public List<TicketBookResponse> getMyTickets(Long teamId, Long userId, String status) {
        List<TicketBookEntity> books;
        if (status != null && "ALL".equalsIgnoreCase(status)) {
            books = bookRepository.findByUserIdAndTeamIdOrderByCreatedAtDesc(userId, teamId);
        } else {
            books = bookRepository.findByUserIdAndTeamIdAndStatus(userId, teamId, TicketBookStatus.ACTIVE);
        }
        return books.stream().map(book -> {
            String productName = productRepository.findById(book.getProductId())
                    .map(TicketProductEntity::getName).orElse("不明な商品");
            return toBookResponseWithProductName(book, productName);
        }).toList();
    }

    /**
     * チケット詳細（消化履歴・決済情報付き）を取得する。
     *
     * @param teamId チームID
     * @param bookId チケットID
     * @return チケット詳細レスポンス
     */
    public TicketBookDetailResponse getTicketBookDetail(Long teamId, Long bookId) {
        TicketBookEntity book = findBookOrThrow(teamId, bookId);
        String productName = productRepository.findById(book.getProductId())
                .map(TicketProductEntity::getName).orElse("不明な商品");
        List<TicketConsumptionEntity> consumptions = consumptionRepository.findByBookIdOrderByConsumedAtAsc(bookId);
        List<ConsumptionResponse> consumptionResponses = ticketMapper.toConsumptionResponseList(consumptions);

        TicketBookDetailResponse.PaymentSummary paymentSummary = null;
        if (book.getPaymentId() != null) {
            paymentRepository.findById(book.getPaymentId()).ifPresent(payment -> {});
            TicketPaymentEntity payment = paymentRepository.findById(book.getPaymentId()).orElse(null);
            if (payment != null) {
                paymentSummary = new TicketBookDetailResponse.PaymentSummary(
                        payment.getPaymentMethod().name(), payment.getAmount(),
                        payment.getStatus().name(), payment.getPaidAt());
            }
        }

        Long daysUntilExpiry = ticketMapper.calculateDaysUntilExpiry(book.getExpiresAt());

        return new TicketBookDetailResponse(
                book.getId(), productName, book.getTotalTickets(), book.getUsedTickets(),
                book.getRemainingTickets(), book.getStatus().name(), book.getPurchasedAt(),
                book.getExpiresAt(), daysUntilExpiry, book.getNote(),
                paymentSummary, consumptionResponses, book.getCreatedAt(), book.getUpdatedAt());
    }

    /**
     * チケット発行一覧（ADMIN用、全顧客）をページング取得する。
     *
     * @param teamId チームID
     * @param status ステータスフィルタ（null = 全件）
     * @param page   ページ番号
     * @param size   ページサイズ
     * @return チケットレスポンスのページ
     */
    public Page<TicketBookResponse> listTicketBooks(Long teamId, String status, int page, int size) {
        Page<TicketBookEntity> bookPage;
        if (status != null && !status.isEmpty()) {
            TicketBookStatus bookStatus = TicketBookStatus.valueOf(status);
            bookPage = bookRepository.findByTeamIdAndStatusOrderByCreatedAtDesc(teamId, bookStatus, PageRequest.of(page, size));
        } else {
            bookPage = bookRepository.findByTeamIdOrderByCreatedAtDesc(teamId, PageRequest.of(page, size));
        }
        return bookPage.map(book -> {
            String productName = productRepository.findById(book.getProductId())
                    .map(TicketProductEntity::getName).orElse("不明な商品");
            return toBookResponseWithProductName(book, productName);
        });
    }

    // ==================== チケット消化 ====================

    /**
     * チケットを1回消化する。
     *
     * @param teamId  チームID
     * @param bookId  チケットID
     * @param staffId スタッフID
     * @param request 消化リクエスト
     * @return 消化結果レスポンス
     */
    @Transactional
    public ConsumeResultResponse consumeTicket(Long teamId, Long bookId, Long staffId, ConsumeTicketRequest request) {
        TicketBookEntity book = bookRepository.findByIdAndTeamIdForUpdate(bookId, teamId)
                .orElseThrow(() -> new BusinessException(TicketErrorCode.BOOK_NOT_FOUND));

        validateBookForConsumption(book);
        LocalDateTime consumedAt = validateAndGetConsumedAt(request.getConsumedAt());

        book.consume();
        bookRepository.save(book);

        TicketConsumptionEntity consumption = TicketConsumptionEntity.builder()
                .bookId(bookId)
                .consumedBy(staffId)
                .reservationId(request.getReservationId())
                .serviceRecordId(request.getServiceRecordId())
                .consumedAt(consumedAt)
                .note(request.getNote())
                .build();
        TicketConsumptionEntity savedConsumption = consumptionRepository.save(consumption);

        log.info("チケット消化: bookId={}, remaining={}, status={}", bookId, book.getRemainingTickets(), book.getStatus());

        return new ConsumeResultResponse(
                savedConsumption.getId(), bookId,
                book.getTotalTickets() - book.getUsedTickets(),
                book.getStatus().name(), consumedAt);
    }

    /**
     * QR スキャンによるチケット消化。
     *
     * @param teamId  チームID
     * @param staffId スタッフID
     * @param qrPayload QR ペイロード
     * @param note    消化メモ
     * @return 消化結果レスポンス
     */
    @Transactional
    public ConsumeResultResponse consumeByQr(Long teamId, Long staffId, String qrPayload, String note) {
        Long bookId;
        try {
            bookId = ticketQrService.validateAndConsumeToken(qrPayload);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(TicketErrorCode.QR_PAYLOAD_INVALID);
        } catch (IllegalStateException e) {
            throw new BusinessException(TicketErrorCode.QR_TOKEN_INVALID);
        }

        ConsumeTicketRequest request = new ConsumeTicketRequest(null, null, null, note);
        return consumeTicket(teamId, bookId, staffId, request);
    }

    /**
     * 複数チケット同時消化。
     *
     * @param teamId  チームID
     * @param staffId スタッフID
     * @param request 一括消化リクエスト
     * @return 一括消化結果レスポンス
     */
    @Transactional
    public BulkConsumeResponse bulkConsume(Long teamId, Long staffId, BulkConsumeRequest request) {
        if (request.getConsumptions().size() > BULK_CONSUME_MAX) {
            throw new BusinessException(TicketErrorCode.BULK_CONSUME_LIMIT_EXCEEDED);
        }

        LocalDateTime consumedAt = validateAndGetConsumedAt(request.getConsumedAt());
        List<BulkConsumeResponse.BulkConsumeResultItem> consumed = new ArrayList<>();

        for (BulkConsumeRequest.BulkConsumeItem item : request.getConsumptions()) {
            ConsumeTicketRequest consumeRequest = new ConsumeTicketRequest(
                    request.getReservationId(), request.getServiceRecordId(),
                    consumedAt, item.getNote());
            ConsumeResultResponse result = consumeTicket(teamId, item.getBookId(), staffId, consumeRequest);
            consumed.add(new BulkConsumeResponse.BulkConsumeResultItem(
                    item.getBookId(), result.getRemainingTickets(), result.getStatus()));
        }

        return new BulkConsumeResponse(consumed, List.of());
    }

    // ==================== 消化取消 ====================

    /**
     * 消化を取り消す。
     *
     * @param teamId        チームID
     * @param bookId        チケットID
     * @param consumptionId 消化レコードID
     * @param staffId       スタッフID
     * @return 取消結果レスポンス
     */
    @Transactional
    public VoidResultResponse voidConsumption(Long teamId, Long bookId, Long consumptionId, Long staffId) {
        TicketBookEntity book = bookRepository.findByIdAndTeamIdForUpdate(bookId, teamId)
                .orElseThrow(() -> new BusinessException(TicketErrorCode.BOOK_NOT_FOUND));

        TicketConsumptionEntity consumption = consumptionRepository.findByIdAndBookId(consumptionId, bookId)
                .orElseThrow(() -> new BusinessException(TicketErrorCode.CONSUMPTION_NOT_FOUND));

        if (consumption.getIsVoided()) {
            throw new BusinessException(TicketErrorCode.ALREADY_VOIDED);
        }

        // 72時間制限チェック
        long hoursSinceConsumption = ChronoUnit.HOURS.between(consumption.getConsumedAt(), LocalDateTime.now());
        if (hoursSinceConsumption > VOID_TIME_LIMIT_HOURS) {
            throw new BusinessException(TicketErrorCode.VOID_TIME_EXCEEDED);
        }

        consumption.voidConsumption(staffId);
        consumptionRepository.save(consumption);

        book.voidConsumption();
        bookRepository.save(book);

        log.info("消化取消: bookId={}, consumptionId={}, remaining={}", bookId, consumptionId, book.getTotalTickets() - book.getUsedTickets());

        return new VoidResultResponse(consumptionId, true, consumption.getVoidedAt(),
                book.getTotalTickets() - book.getUsedTickets());
    }

    // ==================== 返金 ====================

    /**
     * 返金処理を実行する。
     *
     * @param teamId  チームID
     * @param bookId  チケットID
     * @param staffId スタッフID
     * @param request 返金リクエスト
     * @return チケット詳細レスポンス
     */
    @Transactional
    public TicketBookDetailResponse refund(Long teamId, Long bookId, Long staffId, RefundRequest request) {
        TicketBookEntity book = findBookOrThrow(teamId, bookId);

        if (book.getStatus() == TicketBookStatus.CANCELLED) {
            throw new BusinessException(TicketErrorCode.ALREADY_REFUNDED);
        }

        TicketPaymentEntity payment = paymentRepository.findById(book.getPaymentId())
                .orElseThrow(() -> new BusinessException(TicketErrorCode.PAYMENT_NOT_FOUND));

        if (payment.getStatus() == PaymentStatus.REFUNDED) {
            throw new BusinessException(TicketErrorCode.ALREADY_REFUNDED);
        }

        boolean isFull = "FULL".equalsIgnoreCase(request.getRefundType());
        int refundAmount;

        if (isFull) {
            refundAmount = payment.getRefundableAmount();
        } else {
            if (request.getRefundAmount() == null) {
                throw new BusinessException(TicketErrorCode.PARTIAL_REFUND_AMOUNT_REQUIRED);
            }
            refundAmount = request.getRefundAmount();
            if (refundAmount > payment.getRefundableAmount()) {
                throw new BusinessException(TicketErrorCode.REFUND_AMOUNT_EXCEEDED);
            }
        }

        // adjusted_remaining のバリデーション
        if (request.getAdjustedRemaining() != null) {
            if (request.getAdjustedRemaining() < 0 || request.getAdjustedRemaining() > book.getTotalTickets()) {
                throw new BusinessException(TicketErrorCode.INVALID_ADJUSTED_REMAINING);
            }
        }

        // Stripe 返金実行
        String stripeRefundId = null;
        if (payment.getPaymentMethod() == PaymentMethod.STRIPE && payment.getStripePaymentIntentId() != null) {
            try {
                stripeRefundId = stripeTicketService.createRefund(payment.getStripePaymentIntentId(), refundAmount);
            } catch (Exception e) {
                log.error("Stripe Refund 作成失敗: bookId={}", bookId, e);
                throw new BusinessException(TicketErrorCode.STRIPE_API_ERROR, e);
            }
        }

        // 決済ステータス更新
        if (isFull) {
            payment.markAsRefunded(stripeRefundId);
            book.refundFull();
        } else {
            payment.markAsPartiallyRefunded(refundAmount, stripeRefundId);
            if (request.getAdjustedRemaining() != null) {
                book.adjustRemaining(request.getAdjustedRemaining());
            }
        }

        paymentRepository.save(payment);
        bookRepository.save(book);

        log.info("返金処理: bookId={}, type={}, amount={}", bookId, request.getRefundType(), refundAmount);

        return getTicketBookDetail(teamId, bookId);
    }

    // ==================== 有効期限延長 ====================

    /**
     * 有効期限を延長する。
     *
     * @param teamId  チームID
     * @param bookId  チケットID
     * @param request 延長リクエスト
     * @return チケット詳細レスポンス
     */
    @Transactional
    public TicketBookDetailResponse extendExpiry(Long teamId, Long bookId, ExtendRequest request) {
        TicketBookEntity book = findBookOrThrow(teamId, bookId);

        if (book.getExpiresAt() == null) {
            throw new BusinessException(TicketErrorCode.CANNOT_EXTEND_NO_EXPIRY);
        }
        if (book.getStatus() == TicketBookStatus.EXHAUSTED) {
            throw new BusinessException(TicketErrorCode.CANNOT_EXTEND_EXHAUSTED);
        }
        if (book.getStatus() == TicketBookStatus.CANCELLED) {
            throw new BusinessException(TicketErrorCode.CANNOT_EXTEND_CANCELLED);
        }
        if (!request.getNewExpiresAt().isAfter(book.getExpiresAt())) {
            throw new BusinessException(TicketErrorCode.EXTEND_DATE_NOT_FUTURE);
        }

        LocalDateTime oldExpiresAt = book.getExpiresAt();
        book.extendExpiry(request.getNewExpiresAt());
        bookRepository.save(book);

        log.info("有効期限延長: bookId={}, old={}, new={}", bookId, oldExpiresAt, request.getNewExpiresAt());

        return getTicketBookDetail(teamId, bookId);
    }

    // ==================== QR コード ====================

    /**
     * QR コードデータを生成する。
     *
     * @param teamId チームID
     * @param bookId チケットID
     * @param userId ユーザーID（所有者検証用）
     * @return QR コードレスポンス
     */
    public QrCodeResponse generateQrCode(Long teamId, Long bookId, Long userId) {
        TicketBookEntity book = findBookOrThrow(teamId, bookId);
        // 所有者チェックは Controller 層で実施
        TicketQrService.QrGenerateResult result = ticketQrService.generateToken(bookId);
        return new QrCodeResponse(result.qrPayload(), result.expiresAt());
    }

    // ==================== ウィジェット ====================

    /**
     * ダッシュボードウィジェット用の ACTIVE チケット残数サマリを取得する。
     *
     * @param teamId チームID
     * @param userId ユーザーID
     * @return ウィジェットレスポンス
     */
    public TicketWidgetResponse getWidget(Long teamId, Long userId) {
        List<TicketBookEntity> activeBooks = bookRepository.findByUserIdAndTeamIdAndStatus(userId, teamId, TicketBookStatus.ACTIVE);
        List<TicketWidgetResponse.TicketWidgetItem> items = activeBooks.stream().map(book -> {
            String productName = productRepository.findById(book.getProductId())
                    .map(TicketProductEntity::getName).orElse("不明な商品");
            Long daysUntilExpiry = ticketMapper.calculateDaysUntilExpiry(book.getExpiresAt());
            String urgency = calculateUrgency(daysUntilExpiry);
            return new TicketWidgetResponse.TicketWidgetItem(
                    book.getId(), productName,
                    book.getTotalTickets() - book.getUsedTickets(),
                    book.getExpiresAt(), daysUntilExpiry, urgency);
        }).toList();
        return new TicketWidgetResponse(items.size(), items);
    }

    // ==================== 顧客サマリ ====================

    /**
     * 顧客のチケット横断サマリを取得する。
     *
     * @param teamId チームID
     * @param userId ユーザーID
     * @return サマリレスポンス
     */
    public TicketSummaryResponse getTicketSummary(Long teamId, Long userId) {
        List<TicketBookEntity> activeBooks = bookRepository.findByUserIdAndTeamIdAndStatusOrderByExpiresAtAsc(
                userId, teamId, TicketBookStatus.ACTIVE);
        List<TicketSummaryResponse.ActiveTicketItem> items = activeBooks.stream().map(book -> {
            String productName = productRepository.findById(book.getProductId())
                    .map(TicketProductEntity::getName).orElse("不明な商品");
            return new TicketSummaryResponse.ActiveTicketItem(
                    book.getId(), productName,
                    book.getTotalTickets() - book.getUsedTickets(),
                    book.getExpiresAt());
        }).toList();
        int totalRemaining = items.stream().mapToInt(TicketSummaryResponse.ActiveTicketItem::getRemaining).sum();

        String userName = nameResolverService.resolveUserDisplayName(userId);
        return new TicketSummaryResponse(userId, userName, items, totalRemaining);
    }

    // ==================== 統計 ====================

    /**
     * チケット統計を取得する。
     *
     * @param teamId チームID
     * @param period 集計期間
     * @return 統計レスポンス
     */
    public TicketStatsResponse getStats(Long teamId, String period) {
        long activeBooks = bookRepository.countByTeamIdAndStatus(teamId, TicketBookStatus.ACTIVE);
        long exhaustedBooks = bookRepository.countByTeamIdAndStatus(teamId, TicketBookStatus.EXHAUSTED);
        long expiredBooks = bookRepository.countByTeamIdAndStatus(teamId, TicketBookStatus.EXPIRED);
        long cancelledBooks = bookRepository.countByTeamIdAndStatus(teamId, TicketBookStatus.CANCELLED);

        // 将来実装: period パラメータに基づく期間別集計・消化数推移の計算
        return new TicketStatsResponse(
                (int) activeBooks, exhaustedBooks + expiredBooks, (int) cancelledBooks, 0, List.of());
    }

    // ==================== 領収書 ====================

    /**
     * 領収書情報を取得する。Stripe 決済の場合は receipt_url、現地決済の場合は PDF 生成用データ。
     *
     * @param teamId チームID
     * @param bookId チケットID
     * @return 決済情報（呼び出し元で Stripe redirect / PDF 生成を判断）
     */
    public TicketPaymentEntity getReceiptPayment(Long teamId, Long bookId) {
        TicketBookEntity book = findBookOrThrow(teamId, bookId);
        if (book.getPaymentId() == null) {
            throw new BusinessException(TicketErrorCode.PAYMENT_NOT_FOUND);
        }
        TicketPaymentEntity payment = paymentRepository.findById(book.getPaymentId())
                .orElseThrow(() -> new BusinessException(TicketErrorCode.PAYMENT_NOT_FOUND));
        if (payment.getStatus() == PaymentStatus.PENDING) {
            throw new BusinessException(TicketErrorCode.RECEIPT_NOT_AVAILABLE);
        }
        return payment;
    }

    // ==================== ヘルパーメソッド ====================

    private TicketProductEntity findProductOrThrow(Long teamId, Long productId) {
        return productRepository.findByIdAndTeamId(productId, teamId)
                .orElseThrow(() -> new BusinessException(TicketErrorCode.PRODUCT_NOT_FOUND));
    }

    private TicketBookEntity findBookOrThrow(Long teamId, Long bookId) {
        return bookRepository.findByIdAndTeamId(bookId, teamId)
                .orElseThrow(() -> new BusinessException(TicketErrorCode.BOOK_NOT_FOUND));
    }

    private void validateBookForConsumption(TicketBookEntity book) {
        if (book.getStatus() != TicketBookStatus.ACTIVE) {
            throw new BusinessException(TicketErrorCode.TICKET_NOT_ACTIVE);
        }
        if (book.getUsedTickets() >= book.getTotalTickets()) {
            throw new BusinessException(TicketErrorCode.TICKET_EXHAUSTED);
        }
    }

    private LocalDateTime validateAndGetConsumedAt(LocalDateTime consumedAt) {
        if (consumedAt == null) {
            return LocalDateTime.now();
        }
        if (consumedAt.isAfter(LocalDateTime.now())) {
            throw new BusinessException(TicketErrorCode.CONSUMED_AT_FUTURE);
        }
        long hours = ChronoUnit.HOURS.between(consumedAt, LocalDateTime.now());
        if (hours > CONSUMED_AT_LIMIT_HOURS) {
            throw new BusinessException(TicketErrorCode.CONSUMED_AT_TOO_OLD);
        }
        return consumedAt;
    }

    private TicketBookResponse toBookResponseWithProductName(TicketBookEntity book, String productName) {
        Long daysUntilExpiry = ticketMapper.calculateDaysUntilExpiry(book.getExpiresAt());
        return new TicketBookResponse(
                book.getId(), productName, book.getTotalTickets(), book.getUsedTickets(),
                book.getTotalTickets() - book.getUsedTickets(),
                book.getStatus().name(), book.getPurchasedAt(), book.getExpiresAt(),
                daysUntilExpiry, book.getNote(), book.getCreatedAt(), book.getUpdatedAt());
    }

    private String calculateUrgency(Long daysUntilExpiry) {
        if (daysUntilExpiry == null) {
            return "NONE";
        }
        if (daysUntilExpiry <= 7) {
            return "CRITICAL";
        }
        if (daysUntilExpiry <= 30) {
            return "WARNING";
        }
        return "NORMAL";
    }
}
