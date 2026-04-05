package com.mannschaft.app.ticket.repository;

import com.mannschaft.app.ticket.entity.TicketConsumptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * チケット消化履歴リポジトリ。
 */
public interface TicketConsumptionRepository extends JpaRepository<TicketConsumptionEntity, Long> {

    /**
     * チケットの消化履歴を時系列で取得する。
     */
    List<TicketConsumptionEntity> findByBookIdOrderByConsumedAtAsc(Long bookId);

    /**
     * チケットの有効な（取消されていない）消化件数を取得する。
     */
    long countByBookIdAndIsVoidedFalse(Long bookId);

    /**
     * チケットIDと消化IDで消化レコードを取得する。
     */
    Optional<TicketConsumptionEntity> findByIdAndBookId(Long id, Long bookId);
}
