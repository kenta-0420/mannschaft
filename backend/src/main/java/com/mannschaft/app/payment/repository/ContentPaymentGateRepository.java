package com.mannschaft.app.payment.repository;

import com.mannschaft.app.payment.entity.ContentPaymentGateEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * コンテンツゲートリポジトリ。
 */
public interface ContentPaymentGateRepository extends JpaRepository<ContentPaymentGateEntity, Long> {

    /**
     * コンテンツ種別と ID でゲート一覧を取得する。
     */
    List<ContentPaymentGateEntity> findByContentTypeAndContentId(String contentType, Long contentId);

    /**
     * コンテンツ種別と ID で全件削除する（一括設定の置換用）。
     */
    void deleteByContentTypeAndContentId(String contentType, Long contentId);

    /**
     * 支払い項目 ID で全件削除する（支払い項目論理削除時のクリーンアップ用）。
     */
    void deleteByPaymentItemId(Long paymentItemId);

    /**
     * 支払い項目 ID でゲート一覧をページング取得する。
     */
    Page<ContentPaymentGateEntity> findByPaymentItemIdIn(List<Long> paymentItemIds, Pageable pageable);

    /**
     * コンテンツ種別でフィルタしてゲート一覧をページング取得する。
     */
    Page<ContentPaymentGateEntity> findByPaymentItemIdInAndContentType(
            List<Long> paymentItemIds, String contentType, Pageable pageable);
}
