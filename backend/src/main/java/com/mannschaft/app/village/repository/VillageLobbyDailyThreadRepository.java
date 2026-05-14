package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageLobbyDailyThreadEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 井戸端会議日次スレッドリポジトリ（F17.1 Phase 1）。
 *
 * <p>B9 で追加された範囲取得メソッドは §4.10.2 日次スレッド一覧 API（直近 N 日 / from-to 期間）で利用する。
 * いずれも論理削除 (deletedAt) を除外する。</p>
 */
public interface VillageLobbyDailyThreadRepository extends JpaRepository<VillageLobbyDailyThreadEntity, UUID> {

    /** 単一日のスレッド取得（既存 B1）。 */
    Optional<VillageLobbyDailyThreadEntity> findByVillageIdAndThreadDate(UUID villageId, LocalDate threadDate);

    /**
     * 期間内の村ロビー日次スレッドを取得（新しい日付が先頭）。
     * 論理削除 (deletedAt IS NOT NULL) は除外する。
     */
    List<VillageLobbyDailyThreadEntity>
            findByVillageIdAndThreadDateBetweenAndDeletedAtIsNullOrderByThreadDateDesc(
                    UUID villageId, LocalDate from, LocalDate to);
}
