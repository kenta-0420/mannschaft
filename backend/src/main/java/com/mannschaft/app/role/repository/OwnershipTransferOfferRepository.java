package com.mannschaft.app.role.repository;

import com.mannschaft.app.role.entity.OwnershipTransferOfferEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * オーナー委譲 承諾型オファーのリポジトリ（F01.2）。
 *
 * <p>本テーブルは {@code organization_id}（NULL 可）と {@code team_id}（NULL 可）の XOR を持ち、
 * {@code organization_id} 単独でのテナント絞り込みが常には成立しない（チーム委譲時は NULL）。
 * よって原則7 の {@code AbstractTenantAwareRepository} は適合せず、通常の {@link JpaRepository} とする。
 * 検索は idx_oto_target_user（宛先起点）/ idx_oto_team / idx_oto_org（スコープ起点）で行う。</p>
 *
 * <p>設計書: docs/features/F01.2_org_team_member_role/01_db_design.md #ownership_transfer_offers</p>
 */
public interface OwnershipTransferOfferRepository
        extends JpaRepository<OwnershipTransferOfferEntity, UUID> {

    /** 自分宛ての指定ステータスのオファー一覧。 */
    List<OwnershipTransferOfferEntity> findByTargetUserIdAndStatus(Long targetUserId, String status);

    /** チーム × ステータスでオファーを検索（重複 PENDING 検出等）。 */
    List<OwnershipTransferOfferEntity> findByTeamIdAndStatus(Long teamId, String status);

    /** 組織 × ステータスでオファーを検索（重複 PENDING 検出等）。 */
    List<OwnershipTransferOfferEntity> findByOrganizationIdAndStatus(Long organizationId, String status);

    /** チームスコープ内の当該オファー（BOLA 防止のスコープ整合チェック用）。 */
    Optional<OwnershipTransferOfferEntity> findByIdAndTeamId(UUID id, Long teamId);

    /** 組織スコープ内の当該オファー（BOLA 防止のスコープ整合チェック用）。 */
    Optional<OwnershipTransferOfferEntity> findByIdAndOrganizationId(UUID id, Long organizationId);
}
