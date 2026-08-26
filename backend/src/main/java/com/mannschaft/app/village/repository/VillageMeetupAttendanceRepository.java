package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageMeetupAttendanceEntity;
import com.mannschaft.app.village.entity.enums.VillageMeetupAttendanceStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 寄合出欠リポジトリ（F17.2 Wave1 ②寄合後半戦・設計書 §4.2.1）。
 *
 * <p>原則7 適用外（村ドメインは全テナント横断のため）。
 * 標準 {@link JpaRepository} を継承し、必要最小限のクエリのみ追加する。</p>
 */
public interface VillageMeetupAttendanceRepository extends JpaRepository<VillageMeetupAttendanceEntity, UUID> {

    /** 寄合 × 村人で既存出欠を検索（upsert 用・設計書 §4.4.1）。 */
    Optional<VillageMeetupAttendanceEntity> findByMeetupIdAndUserId(UUID meetupId, Long userId);

    /** 寄合に紐づく出欠一覧（作成順・設計書 §13.5）。 */
    Page<VillageMeetupAttendanceEntity> findByMeetupIdOrderByCreatedAtAsc(UUID meetupId, Pageable pageable);

    /**
     * 寄合 × 出欠ステータスの件数（定員判定の単票用・F17.2 追補）。
     *
     * <p>capacity 強制は GOING の件数のみを見る。定員強制ロジックの結線は出陣フェーズで行う。</p>
     */
    long countByMeetupIdAndStatus(UUID meetupId, VillageMeetupAttendanceStatus status);

    /**
     * 複数寄合の指定ステータス件数を GROUP BY で一括取得する（一覧の N+1 回避・F17.2 追補・AC-19）。
     *
     * <p>{@code meetupId → 件数} の射影を返す。実際のバッチ結線（一覧レスポンスへの goingCount 供給）は
     * 出陣フェーズで行う。件数が 0 の寄合は結果に含まれない点に注意（呼び出し側で 0 埋めする）。</p>
     */
    @Query("select a.meetupId as meetupId, count(a) as count "
            + "from VillageMeetupAttendanceEntity a "
            + "where a.meetupId in :meetupIds and a.status = :status "
            + "group by a.meetupId")
    List<MeetupAttendanceStatusCount> countByMeetupIdInAndStatusGrouped(
            @Param("meetupIds") Collection<UUID> meetupIds,
            @Param("status") VillageMeetupAttendanceStatus status);

    /** {@link #countByMeetupIdInAndStatusGrouped} の射影（meetupId → 件数）。 */
    interface MeetupAttendanceStatusCount {
        UUID getMeetupId();

        long getCount();
    }
}
