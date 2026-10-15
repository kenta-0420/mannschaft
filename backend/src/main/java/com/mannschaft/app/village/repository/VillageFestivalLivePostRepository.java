package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageFestivalLivePostEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * お祭りの実況投稿の紐付けリポジトリ（F17.2 Wave2 ③・設計書 §5.4）。
 *
 * <p>主キーは複合自然キー {@link VillageFestivalLivePostEntity.VillageFestivalLivePostId}
 * （原則6例外・{@code festival_id}+{@code timeline_post_id}）。原則7 適用外（村ドメインは
 * 全テナント横断のため）。標準 {@link JpaRepository} を継承し、必要最小限のクエリのみ追加する。</p>
 */
public interface VillageFestivalLivePostRepository
        extends JpaRepository<VillageFestivalLivePostEntity, VillageFestivalLivePostEntity.VillageFestivalLivePostId> {

    /** 二重タグ付け防止の存在チェック（設計書 §5.6・PK 違反前の事前判定）。 */
    boolean existsByFestivalIdAndTimelinePostId(UUID festivalId, Long timelinePostId);

    /** 祭に紐づく実況投稿の紐付け一覧（新しい順・設計書 §5.6）。 */
    Page<VillageFestivalLivePostEntity> findByFestivalIdOrderByCreatedAtDesc(UUID festivalId, Pageable pageable);

    /** 村史編纂用: 祭に紐づく実況投稿の紐付け全件（timeline 側 deleted_at 除外はサービス層で行う・設計書 §5.5）。 */
    java.util.List<VillageFestivalLivePostEntity> findByFestivalId(UUID festivalId);
}
