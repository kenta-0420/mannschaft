package com.mannschaft.app.membership;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * セルフチェックイン拠点リポジトリ。
 */
public interface CheckinLocationRepository extends JpaRepository<CheckinLocationEntity, Long> {

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
