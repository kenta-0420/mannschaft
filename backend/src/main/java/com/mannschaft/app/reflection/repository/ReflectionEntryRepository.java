package com.mannschaft.app.reflection.repository;

import com.mannschaft.app.reflection.entity.ReflectionEntryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link ReflectionEntryEntity} のリポジトリ（F06.5・§2.2）。
 */
@Repository
public interface ReflectionEntryRepository extends JpaRepository<ReflectionEntryEntity, UUID> {

    /** テーマ配下エントリ一覧（マスク適用は Service/Mapper 側・§3.2）。 */
    List<ReflectionEntryEntity> findByThemeIdOrderByTargetDateDesc(UUID themeId);

    /** (theme, target_date) 一意の upsert 引き当て（AC-4）。@SQLRestriction で deleted は除外される。 */
    Optional<ReflectionEntryEntity> findByThemeIdAndTargetDate(UUID themeId, LocalDate targetDate);

    /** 本人所有検証用。 */
    Optional<ReflectionEntryEntity> findByIdAndUserId(UUID id, Long userId);

    /** 今日の振り返りビュー（§4.3）でユーザーの当日エントリを一括取得。 */
    List<ReflectionEntryEntity> findByUserIdAndTargetDate(Long userId, LocalDate targetDate);

    /** ユーザーの未削除エントリ件数（運用・統計用）。 */
    long countByUserIdAndDeletedAtIsNull(Long userId);

    /**
     * 論理削除済みも含めて (theme, target_date) の行を引く（復活更新用・§2.2）。
     *
     * <p>{@code @SQLRestriction("deleted_at IS NULL")} は通常の派生クエリに効くため、
     * 削除済み行を復活させる upsert では本ネイティブクエリで取得する。</p>
     */
    @Query(value = "SELECT * FROM reflection_entries "
            + "WHERE theme_id = :themeId AND target_date = :targetDate LIMIT 1",
            nativeQuery = true)
    Optional<ReflectionEntryEntity> findIncludingDeletedByThemeIdAndTargetDate(
            @Param("themeId") UUID themeId, @Param("targetDate") LocalDate targetDate);
}
