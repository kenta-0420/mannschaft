package com.mannschaft.app.reservation.repository;

import com.mannschaft.app.reservation.entity.ReservationMenuLineEntity;
import com.mannschaft.app.reservation.entity.ReservationMenuLineId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * メニュー×ライン提供可否リポジトリ（F03.4.1 機能E）。
 */
public interface ReservationMenuLineRepository
        extends JpaRepository<ReservationMenuLineEntity, ReservationMenuLineId> {

    /**
     * 複数メニューの提供可否行を一括取得する（一覧の lineIds 一括解決・N+1 回避・§5。
     * メニュー20件×ライン20本でも最大400行・1クエリ）。
     */
    List<ReservationMenuLineEntity> findByMenuIdIn(Collection<UUID> menuIds);

    /**
     * 単一メニューの提供可否行を取得する。
     */
    List<ReservationMenuLineEntity> findByMenuId(UUID menuId);

    /**
     * メニューの提供可否行を全削除する（PATCH {@code lineIds} 全置換用・§4）。
     */
    void deleteByMenuId(UUID menuId);

    /**
     * 当該ラインを参照する提供可否行を全削除する（F03.4.2 §5.5 ライン削除フロー手順4）。
     *
     * <p>{@code line_id} の FK は ON DELETE RESTRICT（ライン論理削除運用の番人）のため、
     * ライン論理削除時はアプリ層でこの明示削除を行う（F03.4.1 §3 の RESTRICT 判断に対応）。</p>
     */
    void deleteByLineId(Long lineId);
}
