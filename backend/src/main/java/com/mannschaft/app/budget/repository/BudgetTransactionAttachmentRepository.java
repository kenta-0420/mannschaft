package com.mannschaft.app.budget.repository;

import com.mannschaft.app.budget.entity.BudgetTransactionAttachmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 予算取引添付ファイルリポジトリ。
 */
public interface BudgetTransactionAttachmentRepository extends JpaRepository<BudgetTransactionAttachmentEntity, Long> {

    /**
     * 取引IDで添付ファイルを検索する。
     */
    List<BudgetTransactionAttachmentEntity> findByTransactionId(Long transactionId);

    /**
     * 取引IDで添付ファイル数をカウントする。
     */
    long countByTransactionId(Long transactionId);
}
