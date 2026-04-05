package com.mannschaft.app.advertising.service;

import com.mannschaft.app.advertising.AdvertisingErrorCode;
import com.mannschaft.app.advertising.AdvertisingMapper;
import com.mannschaft.app.advertising.InvoiceStatus;
import com.mannschaft.app.advertising.dto.InvoiceDetailResponse;
import com.mannschaft.app.advertising.dto.InvoiceItemResponse;
import com.mannschaft.app.advertising.dto.InvoiceSummaryResponse;
import com.mannschaft.app.advertising.dto.MarkInvoicePaidRequest;
import com.mannschaft.app.advertising.entity.AdInvoiceEntity;
import com.mannschaft.app.advertising.entity.AdInvoiceItemEntity;
import com.mannschaft.app.advertising.repository.AdInvoiceItemRepository;
import com.mannschaft.app.advertising.repository.AdInvoiceRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 広告請求書サービス。
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AdInvoiceService {

    private final AdInvoiceRepository adInvoiceRepository;
    private final AdInvoiceItemRepository adInvoiceItemRepository;
    private final AdvertisingMapper advertisingMapper;

    /**
     * 広告主の請求書一覧を取得する。
     */
    public Page<InvoiceSummaryResponse> findByAccountId(Long advertiserAccountId,
            InvoiceStatus status, Pageable pageable) {
        Page<AdInvoiceEntity> page = (status != null)
                ? adInvoiceRepository.findByAdvertiserAccountIdAndStatus(advertiserAccountId, status, pageable)
                : adInvoiceRepository.findByAdvertiserAccountId(advertiserAccountId, pageable);
        return page.map(advertisingMapper::toInvoiceSummary);
    }

    /**
     * 請求書詳細を取得する（明細付き）。
     */
    public InvoiceDetailResponse getDetail(Long invoiceId, Long advertiserAccountId) {
        AdInvoiceEntity invoice = adInvoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new BusinessException(AdvertisingErrorCode.AD_013));
        if (!invoice.getAdvertiserAccountId().equals(advertiserAccountId)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        List<AdInvoiceItemEntity> items = adInvoiceItemRepository.findByInvoiceId(invoiceId);
        List<InvoiceItemResponse> itemResponses = items.stream()
                .map(advertisingMapper::toInvoiceItemResponse)
                .toList();
        return new InvoiceDetailResponse(
                invoice.getId(),
                invoice.getInvoiceNumber(),
                invoice.getInvoiceMonth().toString().substring(0, 7),
                invoice.getTotalAmount(),
                invoice.getTaxRate(),
                invoice.getTaxAmount(),
                invoice.getTotalWithTax(),
                invoice.getStatus(),
                invoice.getIssuedAt(),
                invoice.getDueDate(),
                invoice.getNote(),
                itemResponses
        );
    }

    /**
     * 請求書を手動入金確認する（SYSTEM_ADMIN用）。
     */
    @Transactional
    public InvoiceSummaryResponse markPaid(Long invoiceId, MarkInvoicePaidRequest request) {
        AdInvoiceEntity invoice = adInvoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new BusinessException(AdvertisingErrorCode.AD_013));
        try {
            invoice.markPaid(request.paidAt(), request.note());
        } catch (IllegalStateException e) {
            throw new BusinessException(AdvertisingErrorCode.AD_014, e);
        }
        return advertisingMapper.toInvoiceSummary(invoice);
    }
}
