package com.mannschaft.app.advertising.repository;

import com.mannschaft.app.advertising.entity.AdInvoiceItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 広告請求書明細リポジトリ。
 */
public interface AdInvoiceItemRepository extends JpaRepository<AdInvoiceItemEntity, Long> {

    /**
     * 請求書IDで明細を取得する。
     */
    List<AdInvoiceItemEntity> findByInvoiceId(Long invoiceId);
}
