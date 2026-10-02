package com.mannschaft.app.timeline.repository;

import com.mannschaft.app.timeline.entity.TimelinePostAttachmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/**
 * タイムライン投稿添付ファイルリポジトリ。
 */
public interface TimelinePostAttachmentRepository extends JpaRepository<TimelinePostAttachmentEntity, Long> {

    /**
     * 投稿IDに紐付く添付ファイルを表示順で取得する。
     */
    List<TimelinePostAttachmentEntity> findByTimelinePostIdOrderBySortOrderAsc(Long timelinePostId);

    /**
     * 複数投稿の添付ファイルを一括取得する（issue #2424・feed の N+1 回避）。
     *
     * <p>フィード N 件分の添付を 1 クエリでまとめて引き、投稿 ID 昇順・表示順（sortOrder）昇順で返す。
     * 呼び出し元（{@code TimelinePostService#attachFeedAttachments}）が {@code timelinePostId} で
     * グルーピングして各投稿へ割り当てる。画像 URL の署名解決も全添付をまとめて 1 回で行う。</p>
     */
    List<TimelinePostAttachmentEntity> findByTimelinePostIdInOrderByTimelinePostIdAscSortOrderAsc(
            Collection<Long> timelinePostIds);

    /**
     * 投稿IDに紐付く添付ファイルを削除する。
     */
    void deleteByTimelinePostId(Long timelinePostId);
}
