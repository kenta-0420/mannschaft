package com.mannschaft.app.receipt.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.receipt.ReceiptErrorCode;
import com.mannschaft.app.receipt.ReceiptMapper;
import com.mannschaft.app.receipt.ReceiptPdfGenerator;
import com.mannschaft.app.receipt.ReceiptScopeType;
import com.mannschaft.app.receipt.ReceiptStatus;
import com.mannschaft.app.receipt.dto.BulkCreateReceiptRequest;
import com.mannschaft.app.receipt.dto.BulkResultResponse;
import com.mannschaft.app.receipt.dto.BulkVoidReceiptRequest;
import com.mannschaft.app.receipt.dto.BulkVoidResultResponse;
import com.mannschaft.app.receipt.dto.CreateReceiptRequest;
import com.mannschaft.app.receipt.dto.ReceiptPreviewResponse;
import com.mannschaft.app.receipt.dto.ReceiptResponse;
import com.mannschaft.app.receipt.dto.ReceiptSummaryResponse;
import com.mannschaft.app.receipt.dto.ReissueReceiptRequest;
import com.mannschaft.app.receipt.dto.SendEmailRequest;
import com.mannschaft.app.receipt.dto.SendEmailResponse;
import com.mannschaft.app.receipt.dto.VoidReceiptRequest;
import com.mannschaft.app.receipt.entity.ReceiptEntity;
import com.mannschaft.app.receipt.entity.ReceiptIssuerSettingsEntity;
import com.mannschaft.app.receipt.entity.ReceiptLineItemEntity;
import com.mannschaft.app.receipt.repository.ReceiptIssuerSettingsRepository;
import com.mannschaft.app.receipt.repository.ReceiptLineItemRepository;
import com.mannschaft.app.receipt.repository.ReceiptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 領収書サービス。領収書の発行・無効化・検索を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReceiptService {

    private final ReceiptRepository receiptRepository;
    private final ReceiptLineItemRepository lineItemRepository;
    private final ReceiptIssuerSettingsRepository issuerSettingsRepository;
    private final ReceiptMapper receiptMapper;
    private final ReceiptPdfGenerator pdfGenerator;
    private final NameResolverService nameResolverService;

    /**
     * 領収書を発行する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param userId    発行者ユーザーID
     * @param request   発行リクエスト
     * @return 発行された領収書レスポンス
     */
    @Transactional
    public ReceiptResponse createReceipt(ReceiptScopeType scopeType, Long scopeId,
                                         Long userId, CreateReceiptRequest request) {
        ReceiptIssuerSettingsEntity settings = issuerSettingsRepository
                .findByScopeTypeAndScopeIdForUpdate(scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(ReceiptErrorCode.ISSUER_SETTINGS_NOT_CONFIGURED));

        ReceiptStatus status = request.getStatus() != null
                ? ReceiptStatus.valueOf(request.getStatus())
                : ReceiptStatus.ISSUED;

        // 税額計算
        BigDecimal amount = request.getAmount();
        BigDecimal taxRate = request.getTaxRate() != null ? request.getTaxRate() : new BigDecimal("10.00");
        BigDecimal taxAmount = calculateTaxAmount(amount, taxRate);
        BigDecimal amountExclTax = amount.subtract(taxAmount);

        // 領収書番号の採番（ISSUED の場合のみ）
        String receiptNumber = null;
        if (status == ReceiptStatus.ISSUED) {
            int num = settings.incrementReceiptNumber(1);
            receiptNumber = formatReceiptNumber(settings.getReceiptNumberPrefix(), num);
            issuerSettingsRepository.save(settings);
        }

        // 受領者名の解決
        String recipientName = request.getRecipientName();
        if (recipientName == null || recipientName.isBlank()) {
            if (request.getRecipientUserId() == null) {
                throw new BusinessException(ReceiptErrorCode.RECIPIENT_NAME_REQUIRED);
            }
            recipientName = nameResolverService.resolveUserDisplayName(request.getRecipientUserId());
        }

        ReceiptEntity receipt = ReceiptEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .status(status)
                .receiptNumber(receiptNumber)
                .memberPaymentId(request.getMemberPaymentId())
                .recipientUserId(request.getRecipientUserId())
                .recipientName(recipientName)
                .recipientPostalCode(request.getRecipientPostalCode())
                .recipientAddress(request.getRecipientAddress())
                .issuerName(settings.getIssuerName())
                .issuerPostalCode(settings.getPostalCode())
                .issuerAddress(settings.getAddress())
                .issuerPhone(settings.getPhone())
                .isQualifiedInvoice(settings.getIsQualifiedInvoicer())
                .invoiceRegistrationNumber(
                        Boolean.TRUE.equals(settings.getIsQualifiedInvoicer())
                                ? settings.getInvoiceRegistrationNumber() : null)
                .description(request.getDescription() != null ? request.getDescription() : "")
                .amount(amount)
                .taxRate(taxRate)
                .taxAmount(taxAmount)
                .amountExclTax(amountExclTax)
                .paymentMethodLabel(request.getPaymentMethodLabel())
                .paymentDate(request.getPaymentDate() != null ? request.getPaymentDate() : LocalDate.now())
                .issuedAt(LocalDateTime.now())
                .issuedBy(userId)
                .scheduleId(request.getScheduleId())
                .build();

        ReceiptEntity saved = receiptRepository.save(receipt);

        // 明細行の保存
        List<ReceiptLineItemEntity> lineItems = new ArrayList<>();
        if (request.getLineItems() != null && !request.getLineItems().isEmpty()) {
            validateLineItems(request.getLineItems(), amount);
            for (int i = 0; i < request.getLineItems().size(); i++) {
                CreateReceiptRequest.LineItemRequest item = request.getLineItems().get(i);
                BigDecimal itemTaxAmount = calculateTaxAmount(item.getAmount(), item.getTaxRate());
                ReceiptLineItemEntity lineItem = ReceiptLineItemEntity.builder()
                        .receiptId(saved.getId())
                        .description(item.getDescription())
                        .amount(item.getAmount())
                        .taxRate(item.getTaxRate())
                        .taxAmount(itemTaxAmount)
                        .amountExclTax(item.getAmount().subtract(itemTaxAmount))
                        .sortOrder(i)
                        .build();
                lineItems.add(lineItem);
            }
            lineItemRepository.saveAll(lineItems);
        }

        // 重複チェック警告
        List<ReceiptResponse.WarningResponse> warnings = checkDuplicateWarnings(request.getMemberPaymentId());

        log.info("領収書発行: receiptId={}, receiptNumber={}, amount={}", saved.getId(), receiptNumber, amount);

        return buildReceiptResponse(saved, lineItems, warnings);
    }

    /**
     * 領収書を一括発行する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param userId    発行者ユーザーID
     * @param request   一括発行リクエスト
     * @return 一括発行結果レスポンス
     */
    @Transactional
    public BulkResultResponse bulkCreateReceipts(ReceiptScopeType scopeType, Long scopeId,
                                                  Long userId, BulkCreateReceiptRequest request) {
        if (request.getMemberPaymentIds().size() > 50) {
            throw new BusinessException(ReceiptErrorCode.BULK_LIMIT_EXCEEDED);
        }

        ReceiptIssuerSettingsEntity settings = issuerSettingsRepository
                .findByScopeTypeAndScopeIdForUpdate(scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(ReceiptErrorCode.ISSUER_SETTINGS_NOT_CONFIGURED));

        int startNum = settings.incrementReceiptNumber(request.getMemberPaymentIds().size());
        issuerSettingsRepository.save(settings);

        List<BulkResultResponse.IssuedReceipt> issuedReceipts = new ArrayList<>();
        int currentNum = startNum;

        for (Long paymentId : request.getMemberPaymentIds()) {
            String receiptNumber = formatReceiptNumber(settings.getReceiptNumberPrefix(), currentNum++);

            // TODO: member_payments から支払い情報を取得
            ReceiptEntity receipt = ReceiptEntity.builder()
                    .scopeType(scopeType)
                    .scopeId(scopeId)
                    .status(ReceiptStatus.ISSUED)
                    .receiptNumber(receiptNumber)
                    .memberPaymentId(paymentId)
                    .recipientName("支払者#" + paymentId)
                    .issuerName(settings.getIssuerName())
                    .issuerPostalCode(settings.getPostalCode())
                    .issuerAddress(settings.getAddress())
                    .issuerPhone(settings.getPhone())
                    .isQualifiedInvoice(settings.getIsQualifiedInvoicer())
                    .invoiceRegistrationNumber(
                            Boolean.TRUE.equals(settings.getIsQualifiedInvoicer())
                                    ? settings.getInvoiceRegistrationNumber() : null)
                    .description(request.getDescription() != null ? request.getDescription() : "")
                    .amount(BigDecimal.ZERO)
                    .taxRate(new BigDecimal("10.00"))
                    .taxAmount(BigDecimal.ZERO)
                    .amountExclTax(BigDecimal.ZERO)
                    .paymentDate(LocalDate.now())
                    .issuedAt(LocalDateTime.now())
                    .issuedBy(userId)
                    .build();

            ReceiptEntity saved = receiptRepository.save(receipt);
            issuedReceipts.add(BulkResultResponse.IssuedReceipt.builder()
                    .id(saved.getId())
                    .receiptNumber(receiptNumber)
                    .recipientName(saved.getRecipientName())
                    .amount(saved.getAmount())
                    .build());
        }

        log.info("領収書一括発行: scopeType={}, scopeId={}, count={}",
                scopeType, scopeId, issuedReceipts.size());

        return BulkResultResponse.builder()
                .issuedCount(issuedReceipts.size())
                .skippedCount(0)
                .receipts(issuedReceipts)
                .build();
    }

    /**
     * 領収書を無効化する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param receiptId 領収書ID
     * @param userId    無効化したユーザーID
     * @param request   無効化リクエスト
     * @return 無効化後の領収書レスポンス
     */
    @Transactional
    public ReceiptResponse voidReceipt(ReceiptScopeType scopeType, Long scopeId,
                                       Long receiptId, Long userId, VoidReceiptRequest request) {
        ReceiptEntity receipt = findReceiptOrThrow(scopeType, scopeId, receiptId);

        if (receipt.isVoided()) {
            throw new BusinessException(ReceiptErrorCode.ALREADY_VOIDED);
        }

        receipt.voidReceipt(userId, request.getReason());
        ReceiptEntity saved = receiptRepository.save(receipt);

        List<ReceiptLineItemEntity> lineItems = lineItemRepository.findByReceiptIdOrderBySortOrderAsc(receiptId);
        log.info("領収書無効化: receiptId={}, receiptNumber={}, reason={}",
                receiptId, receipt.getReceiptNumber(), request.getReason());

        return buildReceiptResponse(saved, lineItems, Collections.emptyList());
    }

    /**
     * 領収書を一括無効化する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param userId    無効化したユーザーID
     * @param request   一括無効化リクエスト
     * @return 一括無効化結果レスポンス
     */
    @Transactional
    public BulkVoidResultResponse bulkVoidReceipts(ReceiptScopeType scopeType, Long scopeId,
                                                    Long userId, BulkVoidReceiptRequest request) {
        if (request.getReceiptIds().size() > 50) {
            throw new BusinessException(ReceiptErrorCode.BULK_LIMIT_EXCEEDED);
        }

        int voidedCount = 0;
        int skippedCount = 0;

        for (Long id : request.getReceiptIds()) {
            ReceiptEntity receipt = receiptRepository.findByIdAndScopeTypeAndScopeId(id, scopeType, scopeId)
                    .orElse(null);
            if (receipt == null || receipt.isVoided()) {
                skippedCount++;
                continue;
            }
            receipt.voidReceipt(userId, request.getReason());
            receiptRepository.save(receipt);
            voidedCount++;
        }

        log.info("領収書一括無効化: scopeType={}, scopeId={}, voided={}, skipped={}",
                scopeType, scopeId, voidedCount, skippedCount);

        return new BulkVoidResultResponse(voidedCount, skippedCount);
    }

    /**
     * 下書き領収書を承認する（DRAFT → ISSUED）。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param receiptId 領収書ID
     * @param userId    承認者ユーザーID
     * @return 承認後の領収書レスポンス
     */
    @Transactional
    public ReceiptResponse approveReceipt(ReceiptScopeType scopeType, Long scopeId,
                                           Long receiptId, Long userId) {
        ReceiptEntity receipt = findReceiptOrThrow(scopeType, scopeId, receiptId);

        if (receipt.getStatus() != ReceiptStatus.DRAFT) {
            throw new BusinessException(ReceiptErrorCode.NOT_DRAFT);
        }

        ReceiptIssuerSettingsEntity settings = issuerSettingsRepository
                .findByScopeTypeAndScopeIdForUpdate(scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(ReceiptErrorCode.ISSUER_SETTINGS_NOT_CONFIGURED));

        int num = settings.incrementReceiptNumber(1);
        String receiptNumber = formatReceiptNumber(settings.getReceiptNumberPrefix(), num);
        issuerSettingsRepository.save(settings);

        receipt.assignReceiptNumber(receiptNumber);
        receipt.approve();
        ReceiptEntity saved = receiptRepository.save(receipt);

        List<ReceiptLineItemEntity> lineItems = lineItemRepository.findByReceiptIdOrderBySortOrderAsc(receiptId);

        log.info("領収書承認: receiptId={}, receiptNumber={}", receiptId, receiptNumber);

        return buildReceiptResponse(saved, lineItems, Collections.emptyList());
    }

    /**
     * 発行前プレビューを取得する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param request   発行リクエスト（プレビュー用）
     * @return プレビューレスポンス
     */
    public ReceiptPreviewResponse previewReceipt(ReceiptScopeType scopeType, Long scopeId,
                                                  CreateReceiptRequest request) {
        ReceiptIssuerSettingsEntity settings = issuerSettingsRepository
                .findByScopeTypeAndScopeId(scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(ReceiptErrorCode.ISSUER_SETTINGS_NOT_CONFIGURED));

        BigDecimal amount = request.getAmount();
        BigDecimal taxRate = request.getTaxRate() != null ? request.getTaxRate() : new BigDecimal("10.00");
        BigDecimal taxAmount = calculateTaxAmount(amount, taxRate);
        BigDecimal amountExclTax = amount.subtract(taxAmount);

        String receiptNumber = formatReceiptNumber(
                settings.getReceiptNumberPrefix(), settings.getNextReceiptNumber());

        List<ReceiptResponse.WarningResponse> warnings = checkDuplicateWarnings(request.getMemberPaymentId());

        return ReceiptPreviewResponse.builder()
                .receiptNumber(receiptNumber)
                .recipientName(request.getRecipientName())
                .recipientPostalCode(request.getRecipientPostalCode())
                .recipientAddress(request.getRecipientAddress())
                .issuerName(settings.getIssuerName())
                .issuerPostalCode(settings.getPostalCode())
                .issuerAddress(settings.getAddress())
                .isQualifiedInvoice(settings.getIsQualifiedInvoicer())
                .invoiceRegistrationNumber(
                        Boolean.TRUE.equals(settings.getIsQualifiedInvoicer())
                                ? settings.getInvoiceRegistrationNumber() : null)
                .description(request.getDescription())
                .amount(amount)
                .taxRate(taxRate)
                .taxAmount(taxAmount)
                .amountExclTax(amountExclTax)
                .paymentDate(request.getPaymentDate())
                .sealStamp(request.getSealStamp())
                .warnings(warnings)
                .build();
    }

    /**
     * 無効化済み領収書のデータを流用して再発行プレビューを取得する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param receiptId 元の領収書ID
     * @param request   再発行リクエスト
     * @return プレビューレスポンス
     */
    public ReceiptPreviewResponse reissuePreview(ReceiptScopeType scopeType, Long scopeId,
                                                  Long receiptId, ReissueReceiptRequest request) {
        ReceiptEntity original = findReceiptOrThrow(scopeType, scopeId, receiptId);

        if (!original.isVoided()) {
            throw new BusinessException(ReceiptErrorCode.NOT_VOIDED);
        }

        ReceiptIssuerSettingsEntity settings = issuerSettingsRepository
                .findByScopeTypeAndScopeId(scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(ReceiptErrorCode.ISSUER_SETTINGS_NOT_CONFIGURED));

        BigDecimal amount = request.getAmount() != null ? request.getAmount() : original.getAmount();
        BigDecimal taxRate = request.getTaxRate() != null ? request.getTaxRate() : original.getTaxRate();
        BigDecimal taxAmount = calculateTaxAmount(amount, taxRate);
        BigDecimal amountExclTax = amount.subtract(taxAmount);
        String description = request.getDescription() != null
                ? request.getDescription() : original.getDescription();

        String receiptNumber = formatReceiptNumber(
                settings.getReceiptNumberPrefix(), settings.getNextReceiptNumber());

        return ReceiptPreviewResponse.builder()
                .receiptNumber(receiptNumber)
                .recipientName(original.getRecipientName())
                .recipientPostalCode(original.getRecipientPostalCode())
                .recipientAddress(original.getRecipientAddress())
                .issuerName(settings.getIssuerName())
                .issuerPostalCode(settings.getPostalCode())
                .issuerAddress(settings.getAddress())
                .isQualifiedInvoice(settings.getIsQualifiedInvoicer())
                .invoiceRegistrationNumber(
                        Boolean.TRUE.equals(settings.getIsQualifiedInvoicer())
                                ? settings.getInvoiceRegistrationNumber() : null)
                .description(description)
                .amount(amount)
                .taxRate(taxRate)
                .taxAmount(taxAmount)
                .amountExclTax(amountExclTax)
                .paymentDate(original.getPaymentDate())
                .sealStamp(original.getSealStampLogId() != null)
                .reissueSourceId(original.getId())
                .reissueSourceReceiptNumber(original.getReceiptNumber())
                .warnings(Collections.emptyList())
                .build();
    }

    /**
     * 領収書詳細を取得する（ADMIN用）。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param receiptId 領収書ID
     * @return 領収書レスポンス
     */
    public ReceiptResponse getReceipt(ReceiptScopeType scopeType, Long scopeId, Long receiptId) {
        ReceiptEntity receipt = findReceiptOrThrow(scopeType, scopeId, receiptId);
        List<ReceiptLineItemEntity> lineItems = lineItemRepository.findByReceiptIdOrderBySortOrderAsc(receiptId);
        return buildReceiptResponse(receipt, lineItems, Collections.emptyList());
    }

    /**
     * 発行済み領収書一覧を取得する（ADMIN用、ページネーション対応）。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param page      ページ番号
     * @param size      取得件数
     * @return ページネーション付き領収書一覧
     */
    public PagedResponse<ReceiptSummaryResponse> listReceipts(ReceiptScopeType scopeType, Long scopeId,
                                                               int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<ReceiptEntity> receiptPage = receiptRepository
                .findByScopeTypeAndScopeIdOrderByIssuedAtDesc(scopeType, scopeId, pageable);

        List<ReceiptSummaryResponse> data = receiptMapper.toReceiptSummaryResponseList(receiptPage.getContent());
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                receiptPage.getTotalElements(), page, size, receiptPage.getTotalPages());

        return PagedResponse.of(data, meta);
    }

    /**
     * 領収書 PDF のバイト配列を取得する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param receiptId 領収書ID
     * @return PDF バイト配列
     */
    public byte[] getReceiptPdf(ReceiptScopeType scopeType, Long scopeId, Long receiptId) {
        ReceiptEntity receipt = findReceiptOrThrow(scopeType, scopeId, receiptId);
        List<ReceiptLineItemEntity> lineItems = lineItemRepository.findByReceiptIdOrderBySortOrderAsc(receiptId);

        if (receipt.isVoided()) {
            return pdfGenerator.generateVoided(receipt, lineItems, null, null, null);
        }
        return pdfGenerator.generate(receipt, lineItems, null, null, null);
    }

    /**
     * 領収書メールを送信する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param receiptId 領収書ID
     * @param request   メール送信リクエスト
     * @return メール送信結果レスポンス
     */
    @Transactional
    public SendEmailResponse sendEmail(ReceiptScopeType scopeType, Long scopeId,
                                        Long receiptId, SendEmailRequest request) {
        ReceiptEntity receipt = findReceiptOrThrow(scopeType, scopeId, receiptId);

        String email = request.getEmail();
        if ((email == null || email.isBlank()) && receipt.getRecipientUserId() == null) {
            throw new BusinessException(ReceiptErrorCode.EMAIL_REQUIRED_FOR_EXTERNAL);
        }

        // TODO: 実際のメール送信処理を実装
        log.info("領収書メール送信キュー追加: receiptId={}, email={}", receiptId, email);

        return new SendEmailResponse(receiptId, email, "QUEUED");
    }

    /**
     * 領収書エンティティを取得する。存在しない場合は例外をスローする。
     */
    ReceiptEntity findReceiptOrThrow(ReceiptScopeType scopeType, Long scopeId, Long receiptId) {
        return receiptRepository.findByIdAndScopeTypeAndScopeId(receiptId, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(ReceiptErrorCode.RECEIPT_NOT_FOUND));
    }

    /**
     * 税込金額から税額を計算する（1円未満切り捨て）。
     * tax_amount = FLOOR(amount * tax_rate / (100 + tax_rate))
     */
    private BigDecimal calculateTaxAmount(BigDecimal amount, BigDecimal taxRate) {
        if (amount == null || taxRate == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal hundred = new BigDecimal("100");
        return amount.multiply(taxRate)
                .divide(hundred.add(taxRate), 0, RoundingMode.DOWN);
    }

    /**
     * 領収書番号をフォーマットする。
     */
    private String formatReceiptNumber(String prefix, int number) {
        String paddedNumber = String.format("%05d", number);
        return (prefix != null ? prefix : "") + paddedNumber;
    }

    /**
     * 重複発行チェックを行い、警告リストを返す。
     */
    private List<ReceiptResponse.WarningResponse> checkDuplicateWarnings(Long memberPaymentId) {
        if (memberPaymentId == null) {
            return Collections.emptyList();
        }
        List<ReceiptEntity> existing = receiptRepository.findActiveByMemberPaymentId(memberPaymentId);
        if (existing.isEmpty()) {
            return Collections.emptyList();
        }
        List<ReceiptResponse.WarningResponse> warnings = new ArrayList<>();
        for (ReceiptEntity e : existing) {
            warnings.add(ReceiptResponse.WarningResponse.builder()
                    .code("DUPLICATE_PAYMENT")
                    .message("この支払いに対して既に領収書が発行されています（" +
                            e.getReceiptNumber() + "、\u00a5" + e.getAmount() +
                            "）。分割領収書でなければ重複の可能性があります")
                    .build());
        }
        return warnings;
    }

    /**
     * 明細行のバリデーションを行う。
     */
    private void validateLineItems(List<CreateReceiptRequest.LineItemRequest> lineItems, BigDecimal totalAmount) {
        if (lineItems.size() > 10) {
            throw new BusinessException(ReceiptErrorCode.LINE_ITEMS_LIMIT_EXCEEDED);
        }
        BigDecimal sum = lineItems.stream()
                .map(CreateReceiptRequest.LineItemRequest::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(totalAmount) != 0) {
            throw new BusinessException(ReceiptErrorCode.LINE_ITEMS_AMOUNT_MISMATCH);
        }
    }

    /**
     * 領収書レスポンスを構築する。
     */
    private ReceiptResponse buildReceiptResponse(ReceiptEntity receipt,
                                                  List<ReceiptLineItemEntity> lineItems,
                                                  List<ReceiptResponse.WarningResponse> warnings) {
        List<ReceiptResponse.LineItemResponse> lineItemResponses = lineItems.stream()
                .map(li -> ReceiptResponse.LineItemResponse.builder()
                        .id(li.getId())
                        .description(li.getDescription())
                        .amount(li.getAmount())
                        .taxRate(li.getTaxRate())
                        .taxAmount(li.getTaxAmount())
                        .amountExclTax(li.getAmountExclTax())
                        .build())
                .toList();

        String pdfStatus = receipt.getPdfStorageKey() != null ? "READY" : "GENERATING";

        return ReceiptResponse.builder()
                .id(receipt.getId())
                .receiptNumber(receipt.getReceiptNumber())
                .status(receipt.getStatus().name())
                .recipientName(receipt.getRecipientName())
                .recipientPostalCode(receipt.getRecipientPostalCode())
                .recipientAddress(receipt.getRecipientAddress())
                .issuerName(receipt.getIssuerName())
                .issuerPostalCode(receipt.getIssuerPostalCode())
                .issuerAddress(receipt.getIssuerAddress())
                .issuerPhone(receipt.getIssuerPhone())
                .isQualifiedInvoice(receipt.getIsQualifiedInvoice())
                .invoiceRegistrationNumber(receipt.getInvoiceRegistrationNumber())
                .description(receipt.getDescription())
                .amount(receipt.getAmount())
                .taxRate(receipt.getTaxRate())
                .taxAmount(receipt.getTaxAmount())
                .amountExclTax(receipt.getAmountExclTax())
                .lineItems(lineItemResponses)
                .paymentMethodLabel(receipt.getPaymentMethodLabel())
                .paymentDate(receipt.getPaymentDate())
                .issuedAt(receipt.getIssuedAt())
                .issuedBy(ReceiptResponse.IssuedByResponse.builder()
                        .id(receipt.getIssuedBy())
                        .build())
                .sealStamped(receipt.getSealStampLogId() != null)
                .sealStampLogId(receipt.getSealStampLogId())
                .pdfStatus(pdfStatus)
                .pdfDownloadUrl("/api/v1/admin/receipts/" + receipt.getId() + "/pdf")
                .memberPaymentId(receipt.getMemberPaymentId())
                .scheduleId(receipt.getScheduleId())
                .isVoided(receipt.isVoided())
                .voidedAt(receipt.getVoidedAt())
                .voidedBy(receipt.getVoidedBy())
                .voidedReason(receipt.getVoidedReason())
                .warnings(warnings)
                .build();
    }
}
