package com.mannschaft.app.residencestatus.repository;

import com.mannschaft.app.common.repository.AbstractTenantAwareRepository;
import com.mannschaft.app.residencestatus.entity.ResidentActivitySnapshot;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * F09.16 居住者アクティビティ日次スナップショット リポジトリ。
 */
public interface ResidentActivitySnapshotRepository
        extends AbstractTenantAwareRepository<ResidentActivitySnapshot, UUID> {

    /** 居住者 × 日付の一意な snapshot を取得（UPSERT 用）。 */
    Optional<ResidentActivitySnapshot> findBySubjectUserIdAndSnapshotDateAndDeletedAtIsNull(
            Long subjectUserId, LocalDate snapshotDate);

    /** 居住者の直近 N 件 snapshot を新しい順に取得（テナント二重防御付き）。 */
    List<ResidentActivitySnapshot> findByResidentRegistryIdAndOrganizationIdAndDeletedAtIsNullOrderBySnapshotDateDesc(
            Long residentRegistryId, Long organizationId);

    /**
     * 30 日ローテ用: 指定日より古い snapshot を最大 {@code batchSize} 件まで一括論理削除する（Issue #2601）。
     *
     * <p>ID をアプリ層に持ち上げず、DB 側で {@code LIMIT} 付き一括 UPDATE を行う。
     * 呼び出し側（バッチループ）が影響行数 0 になるまで繰り返し呼び出す前提のクエリ。
     *
     * @return 実際に更新された件数（0 なら対象なし）
     */
    @Modifying
    @Query(value = """
            UPDATE resident_activity_snapshots
               SET deleted_at = :now,
                   updated_at = :now
             WHERE snapshot_date < :cutoff
               AND deleted_at IS NULL
             LIMIT :batchSize
            """, nativeQuery = true)
    int softDeleteBatchOlderThan(@Param("cutoff") LocalDate cutoff,
                                  @Param("now") LocalDateTime now,
                                  @Param("batchSize") int batchSize);
}
