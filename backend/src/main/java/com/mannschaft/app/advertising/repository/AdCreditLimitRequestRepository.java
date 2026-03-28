package com.mannschaft.app.advertising.repository;

import com.mannschaft.app.advertising.CreditLimitRequestStatus;
import com.mannschaft.app.advertising.entity.AdCreditLimitRequestEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 広告与信限度額変更リクエストリポジトリ。
 */
public interface AdCreditLimitRequestRepository extends JpaRepository<AdCreditLimitRequestEntity, Long> {

    /**
     * 広告主アカウントIDでリクエストを作成日降順で取得する。
     */
    @Query("SELECT e FROM AdCreditLimitRequestEntity e WHERE e.advertiserAccountId = :accountId ORDER BY e.createdAt DESC")
    List<AdCreditLimitRequestEntity> findByAdvertiserAccountId(@Param("accountId") Long accountId);

    /**
     * ステータスでリクエストをページネーション取得する。
     */
    Page<AdCreditLimitRequestEntity> findByStatus(CreditLimitRequestStatus status, Pageable pageable);

    /**
     * 広告主アカウントIDとステータスでリクエストの存在を確認する。
     */
    boolean existsByAdvertiserAccountIdAndStatus(Long accountId, CreditLimitRequestStatus status);
}
