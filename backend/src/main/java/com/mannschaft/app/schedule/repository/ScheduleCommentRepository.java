package com.mannschaft.app.schedule.repository;

import com.mannschaft.app.schedule.entity.ScheduleCommentEntity;
import com.mannschaft.app.schedule.visibility.ScheduleCommentVisibilityProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 予定コメントリポジトリ（F03.16 予定コメントスレッド）。
 *
 * <p>設計書: {@code docs/features/F03.16_schedule_comment_thread.md} §3.3 / §4.3 / §4.5.0。</p>
 *
 * <p><b>{@code deleted_at} の明示条件について</b>: 本エンティティは {@code @SQLRestriction} を
 * 意図的に付けていない（{@code ScheduleCommentEntity} クラス Javadoc 参照。トゥームストーン表示が
 * 一覧の本流のため）。したがって<b>本リポジトリの全メソッドは {@code deleted_at} を明示的に
 * 扱う必要がある</b>（含める／除外するを必ずどちらか意図して書く。設計書 AC-34）。
 * ここに定義するメソッドは骨格（Wave 1）のみで、一覧・返信・件数クエリ等の詳細な絞り込みは
 * 後続隊（試練・Service 実装）が受け入れ条件から追加する。</p>
 *
 * <p>{@code commentId} 単独の finder はここに生やさない（{@code scheduleId} を伴わない検索は、
 * 他予定のコメント ID を渡す IDOR 経路になる・設計書 §4.1）。</p>
 */
public interface ScheduleCommentRepository extends JpaRepository<ScheduleCommentEntity, UUID> {

    /**
     * {@code commentId} が {@code scheduleId} に属することを検証しながら取得する（IDOR 防御）。
     */
    Optional<ScheduleCommentEntity> findByIdAndScheduleId(UUID id, Long scheduleId);

    /**
     * 返信の親コメントを取得する（{@code scheduleId} 込み。他予定への返信作成を防ぐ・設計書 §3.3）。
     */
    Optional<ScheduleCommentEntity> findByIdAndScheduleIdAndDeletedAtIsNull(UUID id, Long scheduleId);

    /**
     * F00 共通可視性基盤 — {@link ScheduleCommentVisibilityProjection} を 1 SQL でバルク取得する。
     *
     * <p>設計書 §4.5.0。可視性判定そのものは {@code ScheduleCommentVisibilityResolver.evaluateCustom} が
     * {@code contentVisibilityChecker.canView(ReferenceType.SCHEDULE, scheduleId, viewerUserId)} を
     * そのまま呼ぶため、本メソッドは {@code id} / {@code scheduleId} の2列のみを返す。</p>
     *
     * @param ids 取得対象 schedule_comments.id 集合
     * @return 実在する予定コメントの射影（削除済みも含む。可視性判定に削除有無は不要なため）
     */
    List<ScheduleCommentVisibilityProjection> findVisibilityProjectionsByIdIn(Collection<UUID> ids);

    /**
     * 是正2【P1】: {@code reply_count} を<b>原子的な UPDATE</b>でインクリメントする（読み取り→加算→書き込み
     * のレース是正）。{@code parent.incrementReplyCount()} → {@code save()} だった旧実装は、同じ親へ
     * 同時に返信されると両者が同じ値を読み、片方の加算が消えていた（設計書は {@code replyCount} と
     * 実データの一致を不変条件として明示）。
     *
     * <p>{@code @Modifying(clearAutomatically = true)} で 1次キャッシュ上の古い {@code ScheduleCommentEntity}
     * を破棄する（本メソッド呼び出し後に同一トランザクション内で親を再読込する場合、DB の最新値を返す）。</p>
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ScheduleCommentEntity c SET c.replyCount = c.replyCount + 1 WHERE c.id = :id")
    void incrementReplyCount(@Param("id") UUID id);

    /**
     * 是正2【P1】: {@code reply_count} を<b>原子的な UPDATE</b>でデクリメントする（増加側と対称）。
     * {@code GREATEST(reply_count - 1, 0)} で 0 下限ガードを SQL 側に持たせる
     * （{@link ScheduleCommentEntity#decrementReplyCount()} のメモリ上のガードと同じ規律を
     * 原子的 UPDATE でも維持する。二重削除・並行削除でカウンタが負に落ちるとトゥームストーン表示が破綻するため必須）。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ScheduleCommentEntity c SET c.replyCount = CASE WHEN c.replyCount > 0 THEN c.replyCount - 1 ELSE 0 END WHERE c.id = :id")
    void decrementReplyCount(@Param("id") UUID id);
}
