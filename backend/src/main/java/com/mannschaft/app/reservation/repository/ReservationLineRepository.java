package com.mannschaft.app.reservation.repository;

import com.mannschaft.app.reservation.entity.ReservationLineEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 予約ラインリポジトリ。
 */
public interface ReservationLineRepository extends JpaRepository<ReservationLineEntity, Long> {

    /**
     * 本人予約履歴の対象名を一括解決する。通常検索の論理削除除外は維持するため、
     * 履歴に限り native SQL で @SQLRestriction を迂回する。ID は本人予約から導出し、
     * 呼出側で予約とラインのチーム帰属を照合する。
     */
    @Query(value = "SELECT * FROM reservation_lines WHERE id IN (:ids)", nativeQuery = true)
    List<ReservationLineEntity> findAllByIdIncludingDeletedForHistory(@Param("ids") Collection<Long> ids);

    /**
     * チームの予約ラインを表示順で取得する。
     */
    List<ReservationLineEntity> findByTeamIdOrderByDisplayOrderAsc(Long teamId);

    /**
     * チームの有効な予約ラインを表示順で取得する。
     */
    List<ReservationLineEntity> findByTeamIdAndIsActiveTrueOrderByDisplayOrderAsc(Long teamId);

    /**
     * IDとチームIDで予約ラインを取得する。
     */
    Optional<ReservationLineEntity> findByIdAndTeamId(Long id, Long teamId);

    /**
     * チームのアクティブな（論理削除されていない）予約ライン数を数える。
     *
     * <p>{@link ReservationLineEntity} は {@code @SQLRestriction("deleted_at IS NULL")} を持つため、
     * このカウントは自動的に論理削除済みラインを除外する。F03.4 §1/§2 のライン上限（5 本）判定に用いる。</p>
     */
    long countByTeamId(Long teamId);
}
