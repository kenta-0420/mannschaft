package com.mannschaft.app.schedule.repository;

import com.mannschaft.app.common.repository.AbstractTenantAwareRepository;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.visibility.ScheduleVisibilityProjection;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * スケジュールリポジトリ。
 */
public interface ScheduleRepository extends AbstractTenantAwareRepository<ScheduleEntity, Long> {

    /**
     * チームスコープのスケジュールを期間指定で取得する。
     */
    List<ScheduleEntity> findByTeamIdAndStartAtBetweenOrderByStartAtAsc(
            Long teamId, LocalDateTime from, LocalDateTime to);

    /**
     * 複数チームスコープのスケジュールを期間指定で一括取得する（N+1 解消用）。
     *
     * <p>ダッシュボードのカレンダー集計は所属チーム数 N に対して
     * {@link #findByTeamIdAndStartAtBetweenOrderByStartAtAsc} を期間×N 回呼び出していたため、
     * teamId 集合の IN 句で 1 クエリにまとめる。複合インデックス
     * {@code idx_sch_team_start(team_id, start_at)} がそのまま効く。</p>
     *
     * <p>呼び出し側は {@code teamIds} が空の場合に本メソッドを呼ばないこと
     * （{@code IN ()} の発行を避けるため）。</p>
     *
     * @param teamIds 取得対象のチーム ID 集合（空集合で呼ばないこと）
     * @param from    期間開始
     * @param to      期間終了
     * @return 期間内のチームスケジュール（start_at 昇順）
     */
    @Query("SELECT s FROM ScheduleEntity s "
            + "WHERE s.teamId IN (:teamIds) AND s.startAt BETWEEN :from AND :to "
            + "ORDER BY s.startAt ASC")
    List<ScheduleEntity> findByTeamIdInAndStartAtBetween(
            @Param("teamIds") Collection<Long> teamIds,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /**
     * 組織スコープのスケジュールを期間指定で取得する。
     */
    List<ScheduleEntity> findByOrganizationIdAndStartAtBetweenOrderByStartAtAsc(
            Long orgId, LocalDateTime from, LocalDateTime to);

    /**
     * 個人スコープのスケジュールを期間指定で取得する。
     */
    List<ScheduleEntity> findByUserIdAndStartAtBetweenOrderByStartAtAsc(
            Long userId, LocalDateTime from, LocalDateTime to);

    /**
     * チーム・組織に紐付かない純粋な個人スケジュールを期間指定で取得する。
     */
    List<ScheduleEntity> findByUserIdAndTeamIdIsNullAndOrganizationIdIsNullAndStartAtBetweenOrderByStartAtAsc(
            Long userId, LocalDateTime from, LocalDateTime to);

    /**
     * IDとチームIDでスケジュールを取得する。
     */
    Optional<ScheduleEntity> findByIdAndTeamId(Long id, Long teamId);

    /**
     * IDと組織IDでスケジュールを取得する。
     */
    Optional<ScheduleEntity> findByIdAndOrganizationId(Long id, Long orgId);

    /**
     * 親スケジュールに紐付く子スケジュールを取得する。
     */
    List<ScheduleEntity> findByParentScheduleIdOrderByStartAtAsc(Long parentId);

    /**
     * 親スケジュールに紐付く子スケジュール数を取得する。
     */
    long countByParentScheduleId(Long parentId);

    /**
     * 完了可能なスケジュール（終了日時を過ぎた予定ステータス）を取得する。
     */
    @Query("SELECT s FROM ScheduleEntity s WHERE s.status = 'SCHEDULED' AND s.endAt < :now AND s.endAt IS NOT NULL")
    List<ScheduleEntity> findCompletableSchedules(@Param("now") LocalDateTime now);

    /**
     * チームスコープの今後のスケジュール数を取得する。
     */
    long countByTeamIdAndStartAtAfter(Long teamId, LocalDateTime after);

    /**
     * 組織スコープの今後のスケジュール数を取得する。
     */
    long countByOrganizationIdAndStartAtAfter(Long orgId, LocalDateTime after);

    /**
     * 未同期のスコープ指定スケジュールを取得する（Google Calendar同期用）。
     */
    @Query(value = "SELECT s.* FROM schedules s " +
            "WHERE CASE WHEN :scopeType = 'TEAM' THEN s.team_id = :scopeId " +
            "           WHEN :scopeType = 'ORGANIZATION' THEN s.organization_id = :scopeId END " +
            "AND s.deleted_at IS NULL " +
            "AND s.id NOT IN (SELECT ge.schedule_id FROM user_schedule_google_events ge WHERE ge.user_id = :userId)",
            nativeQuery = true)
    List<ScheduleEntity> findUnsyncedByUserAndScope(
            @Param("userId") Long userId,
            @Param("scopeType") String scopeType,
            @Param("scopeId") Long scopeId);

    /**
     * 未同期の個人スケジュールを取得する（Google Calendar同期用）。
     */
    @Query(value = "SELECT s.* FROM schedules s " +
            "WHERE s.user_id = :userId AND s.team_id IS NULL AND s.organization_id IS NULL " +
            "AND s.deleted_at IS NULL " +
            "AND s.id NOT IN (SELECT ge.schedule_id FROM user_schedule_google_events ge WHERE ge.user_id = :userId)",
            nativeQuery = true)
    List<ScheduleEntity> findUnsyncedPersonalSchedules(@Param("userId") Long userId);

    /**
     * 横断検索（グローバル検索）用のキーワード検索。閲覧者の可視スコープに限定する。
     *
     * <p>可視範囲は「所属チームのスケジュール」「所属組織のスケジュール」「自分の個人スケジュール」の
     * 和集合とする。閲覧者依存の可視性解決であり、所属していれば非公開スケジュールも通常どおりヒットする
     * （横断検索の正常系を維持する）。</p>
     *
     * <p>{@code CUSTOM_TEMPLATE}（F01.7 カスタム公開範囲テンプレート）は、テンプレート評価が
     * SQL 述語に落とせずクエリ段階で判定できないため、本検索の対象から除外する（fail-closed）。
     * 当該スケジュールは各ドメインの詳細取得 API（{@code ContentVisibilityChecker} 経由）で参照する。</p>
     *
     * <p>呼び出し側は {@code teamIds} / {@code orgIds} が空の場合、{@code IN ()} の発行を避けるため
     * ダミー値（{@code -1L}）で埋めること。</p>
     *
     * @param keyword  検索キーワード
     * @param teamIds  閲覧者が所属するチーム ID 集合（非空・空ならダミー値）
     * @param orgIds   閲覧者が所属する組織 ID 集合（非空・空ならダミー値）
     * @param userId   閲覧者ユーザー ID（個人スケジュール一致判定用）
     * @param pageable 取得件数
     * @return 可視スコープ内の検索結果
     */
    @Query("""
            SELECT s FROM ScheduleEntity s
            WHERE (s.title LIKE %:keyword% OR s.description LIKE %:keyword% OR s.location LIKE %:keyword%)
              AND s.deletedAt IS NULL
              AND s.visibility <> com.mannschaft.app.schedule.ScheduleVisibility.CUSTOM_TEMPLATE
              AND (s.teamId IN :teamIds
                OR s.organizationId IN :orgIds
                OR s.userId = :userId)
            """)
    List<ScheduleEntity> searchByKeyword(@Param("keyword") String keyword,
                                         @Param("teamIds") Collection<Long> teamIds,
                                         @Param("orgIds") Collection<Long> orgIds,
                                         @Param("userId") Long userId,
                                         org.springframework.data.domain.Pageable pageable);

    /**
     * チームの最頻利用施設（venue_id）を取得する（広告セグメント用）。
     */
    @Query(value = "SELECT s.venue_id, COUNT(*) AS cnt FROM schedules s " +
            "WHERE s.team_id = :teamId AND s.venue_id IS NOT NULL AND s.deleted_at IS NULL " +
            "GROUP BY s.venue_id ORDER BY cnt DESC LIMIT 1",
            nativeQuery = true)
    List<Object[]> findTopVenueByTeamId(@Param("teamId") Long teamId);

    /**
     * F03.15 Phase 4: external_ref に紐付くスケジュールを取得する（idempotency 用）。
     */
    Optional<ScheduleEntity> findByExternalRef(String externalRef);

    /**
     * F03.15 Phase 4: 指定 external_ref のスケジュールを論理削除する。
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE ScheduleEntity s SET s.deletedAt = CURRENT_TIMESTAMP WHERE s.externalRef = :externalRef AND s.deletedAt IS NULL")
    int softDeleteByExternalRef(@Param("externalRef") String externalRef);

    /**
     * F03.15 Phase 4: external_ref のプレフィックス検索（取消フロー用）。
     */
    @Query("SELECT s FROM ScheduleEntity s WHERE s.externalRef LIKE :prefix AND s.deletedAt IS NULL")
    List<ScheduleEntity> findByExternalRefPrefix(@Param("prefix") String prefix);

    /**
     * F00 ContentVisibilityResolver Phase B — schedules の可視性判定用射影を 1 SQL で取得する。
     *
     * <p>{@code @SQLRestriction("deleted_at IS NULL")} により論理削除済みは除外される。
     * 設計書 §4.6 の「実存確認込み射影取得」原則に従う（IDOR 防止 §11.3）。</p>
     *
     * @param ids 取得対象のスケジュール ID 集合
     * @return 実存する {@link ScheduleVisibilityProjection} の List（空でも null 不可）
     */
    @Query("SELECT new com.mannschaft.app.schedule.visibility.ScheduleVisibilityProjection("
            + "s.id, s.teamId, s.organizationId, s.userId, s.createdBy, "
            + "s.visibility, s.visibilityTemplateId, s.status) "
            + "FROM ScheduleEntity s WHERE s.id IN :ids")
    List<ScheduleVisibilityProjection> findVisibilityProjectionsByIdIn(@Param("ids") Collection<Long> ids);
}
