package com.mannschaft.app.advertising.repository;

import com.mannschaft.app.advertising.AdvertiserAccountStatus;
import com.mannschaft.app.advertising.entity.AdvertiserAccountEntity;
import com.mannschaft.app.membership.domain.ScopeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 広告主アカウントリポジトリ。
 */
public interface AdvertiserAccountRepository extends JpaRepository<AdvertiserAccountEntity, Long> {

    /**
     * ステータスで広告主アカウントをページネーション取得する。
     */
    Page<AdvertiserAccountEntity> findByStatus(AdvertiserAccountStatus status, Pageable pageable);

    /**
     * F09.17 Phase 11-d-1: スコープで広告主アカウントを検索する。
     *
     * <p>{@code scope_type=ORGANIZATION} の場合は組織直結アカウント、
     * {@code scope_type=TEAM} の場合はチーム直結アカウントを取得する。</p>
     *
     * @param scopeType {@link ScopeType#ORGANIZATION} または {@link ScopeType#TEAM}
     * @param scopeId スコープ ID (organization_id または team_id)
     * @return 該当アカウント (なければ空)
     */
    Optional<AdvertiserAccountEntity> findByScopeTypeAndScopeIdAndDeletedAtIsNull(
            ScopeType scopeType, Long scopeId);

    /**
     * F09.17 Phase 11-d-1: スコープで広告主アカウントの存在を確認する。
     */
    boolean existsByScopeTypeAndScopeIdAndDeletedAtIsNull(ScopeType scopeType, Long scopeId);
}
