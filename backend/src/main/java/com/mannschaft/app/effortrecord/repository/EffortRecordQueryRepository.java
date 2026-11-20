package com.mannschaft.app.effortrecord.repository;

import com.mannschaft.app.effortrecord.entity.EffortRecordEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * F06.4.1 個人原本の一覧・集計用の読み取りクエリ。
 *
 * <p>すべて {@code owner_user_id} を必須条件とし（他人の行は構造的に読めない）、
 * {@code @SQLRestriction} により削除済み・purged は除外される。一覧は
 * {@code idx_effort_owner_date}（owner, deleted_at, recorded_on DESC, created_at DESC, id DESC）の範囲走査で、
 * 1 page につき 1 クエリ（行ごとの追加照会なし）。</p>
 */
@Repository
public class EffortRecordQueryRepository {

    @PersistenceContext
    private EntityManager entityManager;

    /** 一覧のカーソル位置（この位置より後ろを読む）。 */
    public record After(LocalDate recordedOn, Instant createdAt, UUID id) {
    }

    /**
     * 一覧。{@code recordedOn DESC, createdAt DESC, id DESC} の順で after より後ろを limit 件（呼出し側が +1 件を要求する）。
     */
    public List<EffortRecordEntity> findPage(Long ownerUserId, LocalDate dateFrom, LocalDate dateTo, UUID definitionUuid,
                                             After after, int fetchSize) {
        StringBuilder jpql = new StringBuilder("SELECT r FROM EffortRecordEntity r WHERE r.ownerUserId = :owner");
        if (dateFrom != null) {
            jpql.append(" AND r.recordedOn >= :dateFrom");
        }
        if (dateTo != null) {
            jpql.append(" AND r.recordedOn <= :dateTo");
        }
        if (definitionUuid != null) {
            jpql.append(" AND r.definitionId = :definition");
        }
        if (after != null) {
            jpql.append(" AND (r.recordedOn < :cursorDate"
                    + " OR (r.recordedOn = :cursorDate AND r.createdAt < :cursorCreated)"
                    + " OR (r.recordedOn = :cursorDate AND r.createdAt = :cursorCreated AND r.id < :cursorId))");
        }
        jpql.append(" ORDER BY r.recordedOn DESC, r.createdAt DESC, r.id DESC");
        TypedQuery<EffortRecordEntity> query = entityManager.createQuery(jpql.toString(), EffortRecordEntity.class)
                .setParameter("owner", ownerUserId)
                .setMaxResults(fetchSize);
        if (dateFrom != null) {
            query.setParameter("dateFrom", dateFrom);
        }
        if (dateTo != null) {
            query.setParameter("dateTo", dateTo);
        }
        if (definitionUuid != null) {
            query.setParameter("definition", definitionUuid);
        }
        if (after != null) {
            query.setParameter("cursorDate", after.recordedOn())
                    .setParameter("cursorCreated", after.createdAt())
                    .setParameter("cursorId", after.id());
        }
        return query.getResultList();
    }

    /** 集計対象の行（期間は両端包含）。 */
    public List<EffortStatsRow> findStatsRows(Long ownerUserId, UUID definitionUuid, LocalDate dateFrom, LocalDate dateTo) {
        return entityManager.createQuery("SELECT new com.mannschaft.app.effortrecord.repository."
                        + "EffortStatsRow(r.recordedOn, r.createdAt, r.id, r.definitionRevision, r.valuesJson) "
                        + "FROM EffortRecordEntity r WHERE r.ownerUserId = :owner AND r.definitionId = :definition "
                        + "AND r.recordedOn >= :dateFrom AND r.recordedOn <= :dateTo", EffortStatsRow.class)
                .setParameter("owner", ownerUserId)
                .setParameter("definition", definitionUuid)
                .setParameter("dateFrom", dateFrom)
                .setParameter("dateTo", dateTo)
                .getResultList();
    }

    /** 本人のその定義の原本が凍結している revision 番号（重複なし）。 */
    public List<Integer> findRevisionNumbers(Long ownerUserId, UUID definitionUuid) {
        return entityManager.createQuery("SELECT DISTINCT r.definitionRevision FROM EffortRecordEntity r "
                        + "WHERE r.ownerUserId = :owner AND r.definitionId = :definition", Integer.class)
                .setParameter("owner", ownerUserId)
                .setParameter("definition", definitionUuid)
                .getResultList();
    }

    /** 本人がその定義の原本を 1 件でも持つか（TEAM 定義のメタ情報を他人へ見せないための判定）。 */
    public boolean existsForOwner(Long ownerUserId, UUID definitionUuid) {
        return !entityManager.createQuery("SELECT r.id FROM EffortRecordEntity r "
                        + "WHERE r.ownerUserId = :owner AND r.definitionId = :definition", UUID.class)
                .setParameter("owner", ownerUserId)
                .setParameter("definition", definitionUuid)
                .setMaxResults(1)
                .getResultList().isEmpty();
    }
}
