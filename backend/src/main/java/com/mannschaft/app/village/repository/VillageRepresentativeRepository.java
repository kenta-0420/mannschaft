package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageRepresentativeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 村代表委任リポジトリ（F17.1 Phase 2）。
 *
 * <p><b>原則7 適用外:</b> 全テナント横断ドメインのため
 * {@code AbstractTenantAwareRepository} は継承せず、
 * 標準 {@link JpaRepository} を継承する。</p>
 *
 * <p>「現役の委任」は {@code revoked_at IS NULL} で識別する。</p>
 */
@Repository
public interface VillageRepresentativeRepository
        extends JpaRepository<VillageRepresentativeEntity, UUID> {

    /**
     * 指定メンバーシップに紐づく現役の代表委任一覧を取得する。
     *
     * @param membershipId village_memberships.id
     * @return 現役（revoked_at IS NULL）の委任エンティティ一覧
     */
    List<VillageRepresentativeEntity> findByMembershipIdAndRevokedAtIsNull(UUID membershipId);

    /**
     * 指定村に紐づく現役の代表委任一覧を取得する。
     *
     * @param villageId villages.id
     * @return 現役の委任エンティティ一覧
     */
    List<VillageRepresentativeEntity> findByVillageIdAndRevokedAtIsNull(UUID villageId);

    /**
     * 指定ユーザーが現役で受けている代表委任一覧を取得する。
     *
     * @param userId users.id（FK は張っていない）
     * @return 現役の委任エンティティ一覧
     */
    List<VillageRepresentativeEntity> findByRepresentativeUserIdAndRevokedAtIsNull(Long userId);

    /**
     * 特定メンバーシップ × 特定ユーザーの現役委任が存在するか判定する。
     *
     * <p>{@link #findByMembershipIdAndRevokedAtIsNull(UUID)} の特定ユーザー絞り込み版。
     * 投稿主体検証（§5.4）で利用する想定。</p>
     *
     * @param membershipId         village_memberships.id
     * @param representativeUserId users.id
     * @return 現役の委任が存在すれば true
     */
    boolean existsByMembershipIdAndRepresentativeUserIdAndRevokedAtIsNull(
            UUID membershipId,
            Long representativeUserId
    );
}
