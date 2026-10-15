package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageFestivalRsvpEntity;
import com.mannschaft.app.village.entity.enums.VillageFestivalRsvpStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * お祭りの参加表明（RSVP）リポジトリ（F17.2 Wave2 ③・設計書 §5.2）。
 *
 * <p>原則7 適用外（村ドメインは全テナント横断のため）。
 * 標準 {@link JpaRepository} を継承し、必要最小限のクエリのみ追加する。</p>
 */
public interface VillageFestivalRsvpRepository extends JpaRepository<VillageFestivalRsvpEntity, UUID> {

    /** 祭 × 村人で既存 RSVP を検索（upsert 用・設計書 §4.4.1）。 */
    Optional<VillageFestivalRsvpEntity> findByFestivalIdAndUserId(UUID festivalId, Long userId);

    /** 祭に紐づく RSVP 一覧（作成順・設計書 §13.5）。 */
    Page<VillageFestivalRsvpEntity> findByFestivalIdOrderByCreatedAtAsc(UUID festivalId, Pageable pageable);

    /** 参加取消（レコード削除）。SCHEDULED/ACTIVE のみ許可の判定はサービス層で行う。 */
    void deleteByFestivalIdAndUserId(UUID festivalId, Long userId);

    /** 村史編纂用: 祭の RSVP 総数。 */
    long countByFestivalId(UUID festivalId);

    /** 村史編纂用: 祭の RSVP をステータス別に集計する（GOING/MAYBE）。 */
    long countByFestivalIdAndStatus(UUID festivalId, VillageFestivalRsvpStatus status);
}
