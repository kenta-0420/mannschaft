package com.mannschaft.app.advertising.repository;

import com.mannschaft.app.advertising.InvoiceStatus;
import com.mannschaft.app.advertising.entity.AdInvoiceEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 広告請求書リポジトリ。
 */
public interface AdInvoiceRepository extends JpaRepository<AdInvoiceEntity, Long> {

    /**
     * 広告主アカウントIDと請求月で請求書を検索する。
     */
    Optional<AdInvoiceEntity> findByAdvertiserAccountIdAndInvoiceMonth(Long accountId, LocalDate month);

    /**
     * 広告主アカウントIDで請求書をページネーション取得する。
     */
    Page<AdInvoiceEntity> findByAdvertiserAccountId(Long accountId, Pageable pageable);

    /**
     * 広告主アカウントIDとステータスで請求書をページネーション取得する。
     */
    Page<AdInvoiceEntity> findByAdvertiserAccountIdAndStatus(Long accountId, InvoiceStatus status, Pageable pageable);

    /**
     * ステータスと期日前の請求書を取得する（期限超過検出用）。
     */
    List<AdInvoiceEntity> findByStatusAndDueDateBefore(InvoiceStatus status, LocalDate date);
}
