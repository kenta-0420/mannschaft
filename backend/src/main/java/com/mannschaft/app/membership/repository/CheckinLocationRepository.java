package com.mannschaft.app.membership.repository;

import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.membership.entity.CheckinLocationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;

import java.util.List;
import java.util.Optional;

/**
 * セルフチェックイン拠点リポジトリ。
 */
public interface CheckinLocationRepository extends JpaRepository<CheckinLocationEntity, Long> {

    /** 他ドメインへEntityを漏らさない受付投影用scalar行。 */
    interface TeamLocationStateRow {
        Long getId();
        Long getTeamId();
        Boolean getActive();
        String getName();
    }

    @Query("SELECT l.id AS id, l.scopeId AS teamId, l.isActive AS active, l.name AS name "
            + "FROM CheckinLocationEntity l WHERE l.id IN :ids AND l.deletedAt IS NULL "
            + "AND l.scopeType = com.mannschaft.app.membership.ScopeType.TEAM")
    List<TeamLocationStateRow> findTeamLocationStateRows(@Param("ids") Collection<Long> ids);

    /**
     * スコープ別の拠点一覧を取得する（論理削除除外）。
     */
    List<CheckinLocationEntity> findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByCreatedAtAsc(
            ScopeType scopeType, Long scopeId);

    /**
     * 拠点コードで検索する（論理削除除外）。
     */
    Optional<CheckinLocationEntity> findByLocationCodeAndDeletedAtIsNull(String locationCode);

    /**
     * IDとスコープで検索する（論理削除除外）。
     */
    Optional<CheckinLocationEntity> findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(
            Long id, ScopeType scopeType, Long scopeId);

    /**
     * スコープ内の有効な拠点数を取得する。
     */
    long countByScopeTypeAndScopeIdAndDeletedAtIsNull(ScopeType scopeType, Long scopeId);
}
