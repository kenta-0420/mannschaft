package com.mannschaft.app.ticket.repository;

import com.mannschaft.app.ticket.entity.TicketProductEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 回数券商品リポジトリ。
 */
public interface TicketProductRepository extends JpaRepository<TicketProductEntity, Long> {

    /**
     * チーム別の販売中商品一覧を表示順で取得する。
     */
    List<TicketProductEntity> findByTeamIdAndIsActiveTrueOrderBySortOrderAsc(Long teamId);

    /**
     * チーム別の全商品一覧（論理削除除外済み: SQLRestriction）を表示順で取得する。
     */
    List<TicketProductEntity> findByTeamIdOrderBySortOrderAsc(Long teamId);

    /**
     * チームとIDで商品を取得する。
     */
    Optional<TicketProductEntity> findByIdAndTeamId(Long id, Long teamId);

    /**
     * チームの商品数を取得する。
     */
    long countByTeamId(Long teamId);
}
