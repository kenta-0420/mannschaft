package com.mannschaft.app.payment.repository;

import com.mannschaft.app.payment.entity.PaymentItemEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 支払い項目リポジトリ。
 */
public interface PaymentItemRepository extends JpaRepository<PaymentItemEntity, Long> {

    /**
     * チーム指定で支払い項目一覧を取得する（表示順序昇順）。
     */
    Page<PaymentItemEntity> findByTeamIdOrderByDisplayOrderAsc(Long teamId, Pageable pageable);

    /**
     * 組織指定で支払い項目一覧を取得する（表示順序昇順）。
     */
    Page<PaymentItemEntity> findByOrganizationIdOrderByDisplayOrderAsc(Long organizationId, Pageable pageable);

    /**
     * チーム指定で支払い項目一覧を取得する（ページネーションなし）。
     */
    List<PaymentItemEntity> findByTeamIdOrderByDisplayOrderAsc(Long teamId);

    /**
     * 組織指定で支払い項目一覧を取得する（ページネーションなし）。
     */
    List<PaymentItemEntity> findByOrganizationIdOrderByDisplayOrderAsc(Long organizationId);

    /**
     * ID とチーム ID で支払い項目を取得する。
     */
    Optional<PaymentItemEntity> findByIdAndTeamId(Long id, Long teamId);

    /**
     * ID と組織 ID で支払い項目を取得する。
     */
    Optional<PaymentItemEntity> findByIdAndOrganizationId(Long id, Long organizationId);

    /**
     * Stripe Price ID で支払い項目を取得する（Webhook 受信時の逆引き用）。
     */
    Optional<PaymentItemEntity> findByStripePriceId(String stripePriceId);
}
