package com.mannschaft.app.reflection.repository;

import com.mannschaft.app.reflection.dto.ArchiveFolderResponse;
import com.mannschaft.app.reflection.entity.ReflectionThemeEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link ReflectionThemeEntity} のリポジトリ（F06.5・§2.0）。
 *
 * <p>個人所有で organization_id を持たないため {@code AbstractTenantAwareRepository} は適用しない。</p>
 *
 * <p>Phase 3: メソッド3系統を維持する（§12.5 変更対象）:
 * <ol>
 *   <li>既存 {@link #findByUserIdOrderByCreatedAtDesc} — archived 含む全テーマ（CalendarEnricher 継続）</li>
 *   <li>新規 {@link #findByUserIdAndArchivedAtIsNullOrderByCreatedAtDesc} — アクティブのみ（listMyThemes/Today）</li>
 *   <li>アーカイブ返却 {@code @Query} — folders/search 専用</li>
 * </ol>
 * </p>
 */
@Repository
public interface ReflectionThemeRepository extends JpaRepository<ReflectionThemeEntity, UUID> {

    /**
     * 自分のテーマ一覧・archived 含む（論理削除除外は {@code @SQLRestriction} が担保）。
     * {@code ReflectionCalendarEnricher} が継続使用する（archived 込みでタイトル解決が必要）。
     */
    List<ReflectionThemeEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * Phase 3: アクティブテーマのみ（archived_at IS NULL）を返す新メソッド（§12.5）。
     * {@code ReflectionThemeService.listMyThemes} と {@code ReflectionTodayService} が使用する。
     */
    List<ReflectionThemeEntity> findByUserIdAndArchivedAtIsNullOrderByCreatedAtDesc(Long userId);

    /** 本人所有検証用（id＋user_id）。 */
    Optional<ReflectionThemeEntity> findByIdAndUserId(UUID id, Long userId);

    /** テーマ数上限（§2.5.1 (b)・100）判定用。 */
    long countByUserId(Long userId);

    /**
     * Phase 3 EP #17: アーカイブ済みテーマを 学年×学期×教科 GROUP BY したフォルダ集計（§12.4）。
     *
     * <p>論理削除済み（deleted_at IS NOT NULL）は除外する。
     * user_id 先頭でテナントスコープを保証。</p>
     */
    @Query("SELECT new com.mannschaft.app.reflection.dto.ArchiveFolderResponse("
            + "  t.academicYear, t.termLabel, t.linkedSubjectName, CAST(COUNT(t) AS int)"
            + ") FROM ReflectionThemeEntity t"
            + " WHERE t.userId = :userId"
            + "   AND t.archivedAt IS NOT NULL"
            + "   AND t.deletedAt IS NULL"
            + " GROUP BY t.academicYear, t.termLabel, t.linkedSubjectName"
            + " ORDER BY t.academicYear DESC, t.termLabel, t.linkedSubjectName")
    List<ArchiveFolderResponse> findArchivedFolders(@Param("userId") Long userId);

    /**
     * Phase 3 EP #18: アーカイブ済みテーマ横断検索（§12.4）。
     *
     * <p>全条件 AND 結合。null パラメータはフィルタに加えない（JPQL COALESCE 相当の条件分岐が難しいため
     * 各条件を null チェックで付与する動的クエリパターン）。
     * keyword LIKE エスケープ: アプリ層で % / _ / \ をエスケープ済みの値を受け取ること（§12.4 参照）。
     * archived=true: archived_at IS NOT NULL / archived=false: archived_at IS NULL。</p>
     */
    @Query("SELECT t FROM ReflectionThemeEntity t"
            + " WHERE t.userId = :userId"
            + "   AND t.deletedAt IS NULL"
            + "   AND (:archived IS NULL OR (:archived = TRUE AND t.archivedAt IS NOT NULL) OR (:archived = FALSE AND t.archivedAt IS NULL))"
            + "   AND (:academicYear IS NULL OR t.academicYear = :academicYear)"
            + "   AND (:termLabel IS NULL OR t.termLabel = :termLabel)"
            + "   AND (:subjectName IS NULL OR t.linkedSubjectName = :subjectName)"
            + "   AND (:keyword IS NULL OR t.title LIKE CONCAT('%', :keyword, '%') ESCAPE '\\\\' OR t.description LIKE CONCAT('%', :keyword, '%') ESCAPE '\\\\')"
            + " ORDER BY t.createdAt DESC")
    Page<ReflectionThemeEntity> searchArchived(
            @Param("userId") Long userId,
            @Param("archived") Boolean archived,
            @Param("academicYear") Integer academicYear,
            @Param("termLabel") String termLabel,
            @Param("subjectName") String subjectName,
            @Param("keyword") String keyword,
            Pageable pageable);

    /**
     * Phase 3 EP #21 bulk-archive 用: 条件に合致するアクティブテーマを取得する。
     *
     * <p>null パラメータは条件に含めない。アクティブ（archived_at IS NULL）＋論理削除なしで絞る。</p>
     */
    @Query("SELECT t FROM ReflectionThemeEntity t"
            + " WHERE t.userId = :userId"
            + "   AND t.archivedAt IS NULL"
            + "   AND t.deletedAt IS NULL"
            + "   AND (:academicYear IS NULL OR t.academicYear = :academicYear)"
            + "   AND (:termLabel IS NULL OR t.termLabel = :termLabel)"
            + "   AND (:subjectName IS NULL OR t.linkedSubjectName = :subjectName)")
    List<ReflectionThemeEntity> findActiveByCondition(
            @Param("userId") Long userId,
            @Param("academicYear") Integer academicYear,
            @Param("termLabel") String termLabel,
            @Param("subjectName") String subjectName);
}
