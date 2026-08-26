package com.mannschaft.app.timeline.repository;

import com.mannschaft.app.timeline.entity.TimelineBookmarkEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * タイムラインブックマークリポジトリ。
 */
public interface TimelineBookmarkRepository extends JpaRepository<TimelineBookmarkEntity, Long> {

    /**
     * ユーザーのブックマーク一覧を新着順で取得する。
     */
    List<TimelineBookmarkEntity> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /**
     * ユーザー・投稿IDでブックマークを取得する。
     */
    Optional<TimelineBookmarkEntity> findByUserIdAndTimelinePostId(Long userId, Long timelinePostId);

    /**
     * ユーザーが投稿をブックマーク済みかを判定する。
     */
    boolean existsByUserIdAndTimelinePostId(Long userId, Long timelinePostId);

    /**
     * 指定ユーザーのブックマークを全件削除する（クロスドメインFK撤廃キャンペーン 第二陣E）。
     *
     * <p>{@code TimelineBookmarkAnonymizationEventListener#onAccountPurged} が退会30日後の物理削除完了
     * （{@code AccountPurgedEvent}）に呼び出し、users 物理削除より前に当該ユーザーのブックマークを
     * 先行削除する安全弁メソッド。これにより V100.001 で撤廃する {@code fk_bookmarks_user}
     * （ON DELETE CASCADE）が冗長になる。</p>
     *
     * <p>ブックマーク（お気に入り）はユーザーが意図的に登録した個人「設定」で退会撤回時に復元価値があるため、
     * 即時ではなく30日撤回ウィンドウ保持後（AccountPurgedEvent）に削除する（§13.12 二層削除）。</p>
     *
     * <p>{@code TimelineBookmarkEntity} は {@code @SQLRestriction} を持たず
     * （論理削除カラム deleted_at なし）、派生 delete でも消し残しは発生しないため通常の派生 delete を用いる。</p>
     *
     * @param userId 退会ユーザーID
     * @return 削除された行数
     */
    int deleteByUserId(Long userId);
}
