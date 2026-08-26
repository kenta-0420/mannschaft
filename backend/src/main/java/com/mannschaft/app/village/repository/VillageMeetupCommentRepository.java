package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageMeetupCommentEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * 寄合コメントリポジトリ（F17.2 Wave1 ②寄合後半戦・設計書 §4.2.2）。
 *
 * <p>原則7 適用外（村ドメインは全テナント横断のため）。
 * 標準 {@link JpaRepository} を継承し、必要最小限のクエリのみ追加する。</p>
 */
public interface VillageMeetupCommentRepository extends JpaRepository<VillageMeetupCommentEntity, UUID> {

    /** 寄合の生きているコメント一覧（作成日昇順・設計書 §4.4/§13.5）。 */
    Page<VillageMeetupCommentEntity> findByMeetupIdAndDeletedAtIsNullOrderByCreatedAtAsc(
            UUID meetupId, Pageable pageable);
}
