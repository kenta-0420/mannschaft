package com.mannschaft.app.payment.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.PaymentErrorCode;
import com.mannschaft.app.payment.PaymentItemType;
import com.mannschaft.app.payment.PaymentMapper;
import com.mannschaft.app.payment.dto.CreatePaymentItemRequest;
import com.mannschaft.app.payment.dto.PaymentItemResponse;
import com.mannschaft.app.payment.dto.UpdatePaymentItemRequest;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.payment.repository.ContentPaymentGateRepository;
import com.mannschaft.app.payment.repository.OrganizationAccessRequirementRepository;
import com.mannschaft.app.payment.repository.PaymentItemRepository;
import com.mannschaft.app.payment.repository.TeamAccessRequirementRepository;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 支払い項目サービス。支払い項目の CRUD と Stripe 連携を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentItemService {

    private final PaymentItemRepository paymentItemRepository;
    private final TeamAccessRequirementRepository teamAccessRequirementRepository;
    private final OrganizationAccessRequirementRepository organizationAccessRequirementRepository;
    private final ContentPaymentGateRepository contentPaymentGateRepository;
    private final StripePaymentProvider stripePaymentProvider;
    private final PaymentMapper paymentMapper;

    /**
     * チーム支払い項目一覧を取得する。
     */
    public Page<PaymentItemResponse> listTeamPaymentItems(Long teamId, Pageable pageable) {
        return paymentItemRepository.findByTeamIdOrderByDisplayOrderAsc(teamId, pageable)
                .map(paymentMapper::toPaymentItemResponse);
    }

    /**
     * 組織支払い項目一覧を取得する。
     */
    public Page<PaymentItemResponse> listOrganizationPaymentItems(Long organizationId, Pageable pageable) {
        return paymentItemRepository.findByOrganizationIdOrderByDisplayOrderAsc(organizationId, pageable)
                .map(paymentMapper::toPaymentItemResponse);
    }

    /**
     * チーム支払い項目を作成する。
     */
    @Transactional
    public PaymentItemResponse createTeamPaymentItem(Long teamId, Long userId, CreatePaymentItemRequest request) {
        return createPaymentItem(teamId, null, userId, request);
    }

    /**
     * 組織支払い項目を作成する。
     */
    @Transactional
    public PaymentItemResponse createOrganizationPaymentItem(Long organizationId, Long userId,
                                                              CreatePaymentItemRequest request) {
        return createPaymentItem(null, organizationId, userId, request);
    }

    private PaymentItemResponse createPaymentItem(Long teamId, Long organizationId, Long userId,
                                                   CreatePaymentItemRequest request) {
        PaymentItemType type = PaymentItemType.valueOf(request.getType());
        boolean isActive = request.getIsActive() != null ? request.getIsActive() : true;
        short gracePeriodDays = request.getGracePeriodDays() != null ? request.getGracePeriodDays() : 0;
        short displayOrder = request.getDisplayOrder() != null ? request.getDisplayOrder() : 0;
        String currency = request.getCurrency() != null ? request.getCurrency() : "JPY";

        PaymentItemEntity entity = PaymentItemEntity.builder()
                .teamId(teamId)
                .organizationId(organizationId)
                .name(request.getName())
                .description(request.getDescription())
                .type(type)
                .amount(request.getAmount())
                .currency(currency)
                .isActive(isActive)
                .displayOrder(displayOrder)
                .gracePeriodDays(gracePeriodDays)
                .createdBy(userId)
                .build();

        PaymentItemEntity saved = paymentItemRepository.save(entity);

        // Stripe Price の処理
        if (request.getStripePriceId() != null) {
            // 手動紐付けフロー B
            handleManualPriceBinding(saved, request.getStripePriceId());
        } else if (isActive) {
            // 自動作成フロー A
            handleAutoStripeCreation(saved);
        }

        saved = paymentItemRepository.save(saved);
        log.info("支払い項目作成: id={}, teamId={}, orgId={}, type={}",
                saved.getId(), teamId, organizationId, type);
        return paymentMapper.toPaymentItemResponse(saved);
    }

    /**
     * チーム支払い項目を更新する。
     */
    @Transactional
    public PaymentItemResponse updateTeamPaymentItem(Long teamId, Long itemId, UpdatePaymentItemRequest request) {
        PaymentItemEntity entity = paymentItemRepository.findByIdAndTeamId(itemId, teamId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_ITEM_NOT_FOUND));
        return updatePaymentItem(entity, request);
    }

    /**
     * 組織支払い項目を更新する。
     */
    @Transactional
    public PaymentItemResponse updateOrganizationPaymentItem(Long organizationId, Long itemId,
                                                              UpdatePaymentItemRequest request) {
        PaymentItemEntity entity = paymentItemRepository.findByIdAndOrganizationId(itemId, organizationId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_ITEM_NOT_FOUND));
        return updatePaymentItem(entity, request);
    }

    private PaymentItemResponse updatePaymentItem(PaymentItemEntity entity, UpdatePaymentItemRequest request) {
        // type 変更を検出して拒否
        if (request.getType() != null && !request.getType().equals(entity.getType().name())) {
            throw new BusinessException(PaymentErrorCode.TYPE_IMMUTABLE);
        }

        boolean amountChanged = request.getAmount() != null
                && request.getAmount().compareTo(entity.getAmount()) != 0;
        boolean currencyChanged = request.getCurrency() != null
                && !request.getCurrency().equals(entity.getCurrency());
        Boolean oldIsActive = entity.getIsActive();
        Boolean newIsActive = request.getIsActive();

        entity.update(
                request.getName(), request.getDescription(),
                request.getAmount(), request.getCurrency(),
                request.getIsActive(), request.getDisplayOrder(),
                request.getGracePeriodDays()
        );

        // Stripe Price の手動紐付け
        if (request.getStripePriceId() != null) {
            handleManualPriceBinding(entity, request.getStripePriceId());
        } else if (amountChanged || currencyChanged) {
            // 金額/通貨変更時のフロー C
            handlePriceChange(entity);
        } else if (newIsActive != null && !newIsActive.equals(oldIsActive)) {
            // is_active 変更時のフロー D
            handleIsActiveChange(entity, oldIsActive, newIsActive);
        }

        PaymentItemEntity saved = paymentItemRepository.save(entity);
        log.info("支払い項目更新: id={}", saved.getId());
        return paymentMapper.toPaymentItemResponse(saved);
    }

    /**
     * チーム支払い項目を論理削除する。
     */
    @Transactional
    public void deleteTeamPaymentItem(Long teamId, Long itemId) {
        PaymentItemEntity entity = paymentItemRepository.findByIdAndTeamId(itemId, teamId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_ITEM_NOT_FOUND));
        softDeletePaymentItem(entity);
    }

    /**
     * 組織支払い項目を論理削除する。
     */
    @Transactional
    public void deleteOrganizationPaymentItem(Long organizationId, Long itemId) {
        PaymentItemEntity entity = paymentItemRepository.findByIdAndOrganizationId(itemId, organizationId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_ITEM_NOT_FOUND));
        softDeletePaymentItem(entity);
    }

    private void softDeletePaymentItem(PaymentItemEntity entity) {
        // 関連テーブルのクリーンアップ（アプリ層で制御）
        teamAccessRequirementRepository.deleteByPaymentItemId(entity.getId());
        organizationAccessRequirementRepository.deleteByPaymentItemId(entity.getId());
        contentPaymentGateRepository.deleteByPaymentItemId(entity.getId());

        entity.softDelete();
        paymentItemRepository.save(entity);
        log.info("支払い項目削除: id={}", entity.getId());
    }

    /**
     * チーム支払い項目一覧を取得する（ページネーションなし）。
     */
    public List<PaymentItemEntity> findTeamPaymentItems(Long teamId) {
        return paymentItemRepository.findByTeamIdOrderByDisplayOrderAsc(teamId);
    }

    /**
     * 組織支払い項目一覧を取得する（ページネーションなし）。
     */
    public List<PaymentItemEntity> findOrganizationPaymentItems(Long organizationId) {
        return paymentItemRepository.findByOrganizationIdOrderByDisplayOrderAsc(organizationId);
    }

    /**
     * 支払い項目を ID で取得する。存在しない場合は例外をスローする。
     */
    public PaymentItemEntity findByIdOrThrow(Long id) {
        return paymentItemRepository.findById(id)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_ITEM_NOT_FOUND));
    }

    // --- Stripe Price 管理フロー ---

    private void handleAutoStripeCreation(PaymentItemEntity entity) {
        try {
            String productId = stripePaymentProvider.createProduct(entity.getName(), entity.getId());
            String priceId = stripePaymentProvider.createPrice(productId, entity.getAmount(), entity.getCurrency());
            entity.updateStripeIds(productId, priceId);
        } catch (Exception e) {
            log.warn("Stripe リソース作成失敗。補償処理を実行します: {}", e.getMessage());
            // 補償処理: 作成済みリソースをアーカイブ
            if (entity.getStripeProductId() != null) {
                try {
                    stripePaymentProvider.archiveProduct(entity.getStripeProductId());
                } catch (Exception ex) {
                    log.warn("Stripe Product アーカイブ失敗（手動削除が必要）: {}", ex.getMessage());
                }
            }
            throw new BusinessException(PaymentErrorCode.STRIPE_API_ERROR, e);
        }
    }

    private void handleManualPriceBinding(PaymentItemEntity entity, String stripePriceId) {
        try {
            StripePaymentProvider.PriceInfo priceInfo = stripePaymentProvider.retrievePrice(stripePriceId);
            // 金額・通貨バリデーション（簡略化: 厳密な通貨換算はTODO）
            entity.updateStripeIds(priceInfo.productId(), stripePriceId);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(PaymentErrorCode.STRIPE_API_ERROR, e);
        }
    }

    private void handlePriceChange(PaymentItemEntity entity) {
        if (entity.getStripePriceId() != null && entity.getIsActive()) {
            try {
                stripePaymentProvider.archivePrice(entity.getStripePriceId());
                String newPriceId = stripePaymentProvider.createPrice(
                        entity.getStripeProductId(), entity.getAmount(), entity.getCurrency());
                entity.updateStripePriceId(newPriceId);
            } catch (Exception e) {
                throw new BusinessException(PaymentErrorCode.STRIPE_API_ERROR, e);
            }
        }
    }

    private void handleIsActiveChange(PaymentItemEntity entity, Boolean oldIsActive, Boolean newIsActive) {
        if (Boolean.TRUE.equals(oldIsActive) && Boolean.FALSE.equals(newIsActive)) {
            // TRUE → FALSE: Price をアーカイブ
            if (entity.getStripePriceId() != null) {
                try {
                    stripePaymentProvider.archivePrice(entity.getStripePriceId());
                } catch (Exception e) {
                    log.warn("Stripe Price アーカイブ失敗: {}", e.getMessage());
                }
            }
        } else if (Boolean.FALSE.equals(oldIsActive) && Boolean.TRUE.equals(newIsActive)) {
            // FALSE → TRUE: 新規 Price を作成
            handleAutoStripeCreation(entity);
        }
    }
}
