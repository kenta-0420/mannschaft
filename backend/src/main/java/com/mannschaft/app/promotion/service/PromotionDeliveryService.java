package com.mannschaft.app.promotion.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.promotion.PromotionErrorCode;
import com.mannschaft.app.promotion.dto.UserPromotionResponse;
import com.mannschaft.app.promotion.entity.PromotionDeliveryEntity;
import com.mannschaft.app.promotion.entity.PromotionEntity;
import com.mannschaft.app.promotion.repository.PromotionDeliveryRepository;
import com.mannschaft.app.promotion.repository.PromotionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * プロモーション配信サービス（ユーザー向け）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PromotionDeliveryService {

    private final PromotionDeliveryRepository deliveryRepository;
    private final PromotionRepository promotionRepository;

    /**
     * ユーザーの受信プロモーション一覧を取得する。
     */
    public Page<UserPromotionResponse> listByUser(Long userId, Pageable pageable) {
        return deliveryRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable)
                .map(this::toUserPromotionResponse);
    }

    /**
     * プロモーションを既読にする。
     */
    @Transactional
    public void markAsRead(Long userId, Long deliveryId) {
        PromotionDeliveryEntity delivery = deliveryRepository.findByIdAndUserId(deliveryId, userId)
                .orElseThrow(() -> new BusinessException(PromotionErrorCode.DELIVERY_NOT_FOUND));
        delivery.markOpened();
        deliveryRepository.save(delivery);
        log.info("プロモーション既読: userId={}, deliveryId={}", userId, deliveryId);
    }

    private UserPromotionResponse toUserPromotionResponse(PromotionDeliveryEntity delivery) {
        PromotionEntity promotion = promotionRepository.findById(delivery.getPromotionId()).orElse(null);
        return new UserPromotionResponse(
                delivery.getId(), delivery.getPromotionId(),
                promotion != null ? promotion.getTitle() : null,
                promotion != null ? promotion.getBody() : null,
                promotion != null ? promotion.getImageUrl() : null,
                delivery.getChannel(), delivery.getStatus(),
                delivery.getDeliveredAt(), delivery.getOpenedAt(),
                delivery.getCreatedAt());
    }
}
