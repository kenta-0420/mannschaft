package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageMeetupAttendanceEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
