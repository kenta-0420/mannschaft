package com.mannschaft.app.schedule.repository;

import com.mannschaft.app.schedule.entity.ScheduleCommentEntity;
import com.mannschaft.app.schedule.visibility.ScheduleCommentVisibilityProjection;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
