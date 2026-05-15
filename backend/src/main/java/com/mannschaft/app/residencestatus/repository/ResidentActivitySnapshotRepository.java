package com.mannschaft.app.residencestatus.repository;

import com.mannschaft.app.common.repository.AbstractTenantAwareRepository;
import com.mannschaft.app.residencestatus.entity.ResidentActivitySnapshot;

import java.time.LocalDate;
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

    /** 30 日ローテ用: 指定日より古い snapshot を削除候補として取得。 */
    List<ResidentActivitySnapshot> findBySnapshotDateLessThanAndDeletedAtIsNull(LocalDate threshold);
}
