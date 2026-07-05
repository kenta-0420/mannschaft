package com.mannschaft.app.reservation.repository;

import com.mannschaft.app.reservation.entity.ReservationMenuEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 予約メニューリポジトリ（F03.4.1 機能E）。
 *
 * <p>Entity は {@code @SQLRestriction("deleted_at IS NULL")} を持つが、メソッド名にも
 * {@code DeletedAtIsNull} を明示して設計書 §5 の意図（論理削除の除外）を二重に固定する。</p>
 */
public interface ReservationMenuRepository extends JpaRepository<ReservationMenuEntity, UUID> {

    /**
     * チームの未削除メニュー数を数える（上限 20 件判定・E-4。論理削除済みは数えない）。
     */
    long countByTeamIdAndDeletedAtIsNull(Long teamId);

    /**
     * チームの未削除メニューを三段ソート（§4 並び順の確定規則）で取得する。
     *
     * <p>{@code ORDER BY display_order ASC, created_at ASC, id ASC}。display_order は
     * 一意を強制しないため、同値時の順序が呼び出しごとに揺れないよう決定的にする
     * （UUIDv7 の id は時刻順のため最終タイブレークとして安定）。</p>
     */
    List<ReservationMenuEntity> findByTeamIdAndDeletedAtIsNullOrderByDisplayOrderAscCreatedAtAscIdAsc(Long teamId);

    /**
     * ID とチーム ID でメニューを解決する（IDOR 秘匿・§6。他チームは empty → 404 = RESERVATION_032）。
     */
    Optional<ReservationMenuEntity> findByIdAndTeamId(UUID id, Long teamId);

    /**
     * チームの未削除メニューの display_order 最大値を返す（displayOrder 省略時の MAX+1 用・§4）。
     *
     * <p>「<b>未削除行の</b>最大値+1」と対称に {@code deleted_at IS NULL} 修飾を必須とする —
     * 論理削除行の display_order を拾うと歯抜け回避の意図が壊れる（§5・精査 A4）。
     * 既存 0 件のときは {@code null}。</p>
     */
    @Query("SELECT MAX(m.displayOrder) FROM ReservationMenuEntity m "
            + "WHERE m.teamId = :teamId AND m.deletedAt IS NULL")
    Integer findMaxDisplayOrderByTeamIdAndDeletedAtIsNull(@Param("teamId") Long teamId);

    /**
     * 論理削除済みを含めて ID で解決する（{@code @SQLRestriction} 迂回のネイティブクエリ・E-8 足場）。
     *
     * <p>削除済みメニューを参照する既存予約グループ（F03.4.3 第二弾 {@code reservations.menu_id}）の
     * 名前解決に使う（§3 備考「履歴表示用に menu 行は物理削除しない」）。</p>
     */
    @Query(value = "SELECT * FROM reservation_menus WHERE id = :id", nativeQuery = true)
    Optional<ReservationMenuEntity> findByIdIncludingDeleted(@Param("id") UUID id);
}
