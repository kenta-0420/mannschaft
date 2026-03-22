package com.mannschaft.app.promotion.service;

import com.mannschaft.app.promotion.dto.BillingRecordResponse;
import com.mannschaft.app.promotion.mapper.PromotionMapper;
import com.mannschaft.app.promotion.repository.PromotionBillingRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * プロモーション課金サービス。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PromotionBillingService {

    private final PromotionBillingRecordRepository billingRepository;
    private final PromotionMapper promotionMapper;

    /**
     * 課金状況一覧を取得する（SYSTEM_ADMIN用）。
     */
    public Page<BillingRecordResponse> listBillingRecords(String billingStatus, Pageable pageable) {
        return billingRepository.findAllWithFilter(billingStatus, pageable)
                .map(promotionMapper::toBillingRecordResponse);
    }
}
