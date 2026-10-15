package com.mannschaft.app.ticket.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.ticket.TicketErrorCode;
import com.mannschaft.app.ticket.TicketMapper;
import com.mannschaft.app.ticket.dto.CreateTicketProductRequest;
import com.mannschaft.app.ticket.dto.TicketProductResponse;
import com.mannschaft.app.ticket.dto.UpdateTicketProductRequest;
import com.mannschaft.app.ticket.entity.TicketProductEntity;
import com.mannschaft.app.ticket.repository.TicketProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 回数券商品サービス。商品の CRUD を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TicketProductService {

    private static final int MAX_PRODUCTS_PER_TEAM = 30;

    private final TicketProductRepository productRepository;
    private final StripeTicketService stripeTicketService;
    private final TicketMapper ticketMapper;

    /**
     * 商品一覧を取得する。
     *
     * @param teamId          チームID
     * @param includeInactive 販売停止中の商品を含めるか（ADMIN 用）
     * @return 商品レスポンスリスト
     */
    public List<TicketProductResponse> listProducts(Long teamId, boolean includeInactive) {
        List<TicketProductEntity> products;
        if (includeInactive) {
            products = productRepository.findByTeamIdOrderBySortOrderAsc(teamId);
        } else {
            products = productRepository.findByTeamIdAndIsActiveTrueOrderBySortOrderAsc(teamId);
        }
        return ticketMapper.toProductResponseList(products);
    }

    /**
     * 商品を作成する。
     *
     * @param teamId  チームID
     * @param userId  作成者ID
     * @param request 作成リクエスト
     * @return 作成された商品レスポンス
     */
    @Transactional
    public TicketProductResponse createProduct(Long teamId, Long userId, CreateTicketProductRequest request) {
        // 商品数上限チェック
        long count = productRepository.countByTeamId(teamId);
        if (count >= MAX_PRODUCTS_PER_TEAM) {
            throw new BusinessException(TicketErrorCode.PRODUCT_LIMIT_EXCEEDED);
        }

        BigDecimal taxRate = request.getTaxRate() != null ? request.getTaxRate() : new BigDecimal("10.00");
        Boolean isOnlinePurchasable = request.getIsOnlinePurchasable() != null ? request.getIsOnlinePurchasable() : true;

        TicketProductEntity entity = TicketProductEntity.builder()
                .teamId(teamId)
                .name(request.getName())
                .description(request.getDescription())
                .totalTickets(request.getTotalTickets())
                .price(request.getPrice())
                .taxRate(taxRate)
                .validityDays(request.getValidityDays())
                .isOnlinePurchasable(isOnlinePurchasable)
                .imageUrl(request.getImageUrl())
                .createdBy(userId)
                .build();

        TicketProductEntity saved = productRepository.save(entity);

        // Stripe Product + Price 作成（オンライン購入可能な場合）
        if (Boolean.TRUE.equals(isOnlinePurchasable)) {
            try {
                StripeTicketService.StripeProductResult result = stripeTicketService.createStripeProduct(saved);
                saved.updateStripeIds(result.productId(), result.priceId());
                saved = productRepository.save(saved);
            } catch (Exception e) {
                log.error("Stripe Product 作成失敗: teamId={}, productName={}", teamId, request.getName(), e);
                throw new BusinessException(TicketErrorCode.STRIPE_API_ERROR, e);
            }
        }

        log.info("回数券商品作成: teamId={}, productId={}, name={}", teamId, saved.getId(), saved.getName());
        return ticketMapper.toProductResponse(saved);
    }

    /**
     * 商品を更新する。
     *
     * @param teamId    チームID
     * @param productId 商品ID
     * @param request   更新リクエスト
     * @return 更新された商品レスポンス
     */
    @Transactional
    public TicketProductResponse updateProduct(Long teamId, Long productId, UpdateTicketProductRequest request) {
        TicketProductEntity entity = findProductOrThrow(teamId, productId);

        boolean priceChanged = request.getPrice() != null && !request.getPrice().equals(entity.getPrice());
        boolean taxRateChanged = request.getTaxRate() != null && !request.getTaxRate().equals(entity.getTaxRate());

        entity.update(
                request.getName(),
                request.getDescription(),
                request.getPrice() != null ? request.getPrice() : entity.getPrice(),
                request.getTaxRate() != null ? request.getTaxRate() : entity.getTaxRate(),
                request.getValidityDays(),
                request.getIsOnlinePurchasable() != null ? request.getIsOnlinePurchasable() : entity.getIsOnlinePurchasable(),
                request.getIsActive() != null ? request.getIsActive() : entity.getIsActive(),
                request.getSortOrder() != null ? request.getSortOrder() : entity.getSortOrder(),
                request.getImageUrl()
        );

        // 価格変更時は Stripe Price を再作成
        if ((priceChanged || taxRateChanged) && entity.getStripePriceId() != null) {
            try {
                String newPriceId = stripeTicketService.recreateStripePrice(entity, entity.getStripePriceId());
                entity.updateStripePriceId(newPriceId);
            } catch (Exception e) {
                log.error("Stripe Price 再作成失敗: productId={}", productId, e);
                throw new BusinessException(TicketErrorCode.STRIPE_API_ERROR, e);
            }
        }

        TicketProductEntity saved = productRepository.save(entity);
        log.info("回数券商品更新: productId={}", productId);
        return ticketMapper.toProductResponse(saved);
    }

    /**
     * 商品を論理削除する。
     *
     * @param teamId    チームID
     * @param productId 商品ID
     * @return 削除された商品レスポンス
     */
    @Transactional
    public TicketProductResponse deleteProduct(Long teamId, Long productId) {
        TicketProductEntity entity = findProductOrThrow(teamId, productId);
        entity.softDelete();

        // Stripe Product を非アクティブ化
        if (entity.getStripeProductId() != null) {
            try {
                stripeTicketService.deactivateStripeProduct(entity.getStripeProductId());
            } catch (Exception e) {
                log.warn("Stripe Product 非アクティブ化失敗（続行）: productId={}", productId, e);
            }
        }

        TicketProductEntity saved = productRepository.save(entity);
        log.info("回数券商品削除: productId={}", productId);
        return ticketMapper.toProductResponse(saved);
    }

    /**
     * 商品エンティティを取得する。存在しない場合は例外をスローする。
     */
    TicketProductEntity findProductOrThrow(Long teamId, Long productId) {
        return productRepository.findByIdAndTeamId(productId, teamId)
                .orElseThrow(() -> new BusinessException(TicketErrorCode.PRODUCT_NOT_FOUND));
    }
}
