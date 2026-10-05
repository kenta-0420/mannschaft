package com.mannschaft.app.organization.teamgroup.repository;

import com.mannschaft.app.common.repository.AbstractTenantAwareRepository;
import com.mannschaft.app.organization.teamgroup.entity.OrgTeamGroupEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * チームグループのリポジトリ（F01.2.1 §5.1）。{@code organization_id} をテナントキーとする。
 *
 * <p>ID 指定の取得は必ず {@code (id, organization_id)} の組で行う（越境を 404 に畳むため）。</p>
 */
public interface OrgTeamGroupRepository
        extends AbstractTenantAwareRepository<OrgTeamGroupEntity, UUID> {

    /** 組織の生存グループを並び順（同値は ID＝作成順）で取得する。 */
    List<OrgTeamGroupEntity> findByOrganizationIdAndDeletedAtIsNullOrderBySortOrderAscIdAsc(Long organizationId);

    /** 組織の生存グループの sort_order の最大値（無ければ -1）。 */
    @Query("SELECT COALESCE(MAX(g.sortOrder), -1) FROM OrgTeamGroupEntity g "
            + "WHERE g.organizationId = :organizationId AND g.deletedAt IS NULL")
    int findMaxSortOrder(@Param("organizationId") Long organizationId);

    /** 組織の生存グループに同名（DB の照合順序で同一視されるもの）が存在するか。 */
    boolean existsByOrganizationIdAndNameAndDeletedAtIsNull(Long organizationId, String name);

    /** 自分自身を除いて、組織の生存グループに同名が存在するか（改名用）。 */
    boolean existsByOrganizationIdAndNameAndDeletedAtIsNullAndIdNot(Long organizationId, String name, UUID id);

    /** 指定した組織群の生存グループの ID（告知の表示判定。機能の有効・無効に関わらず返す）。 */
    @Query("SELECT g.id FROM OrgTeamGroupEntity g "
            + "WHERE g.organizationId IN :organizationIds AND g.deletedAt IS NULL")
    List<UUID> findLiveIdsByOrganizationIdIn(@Param("organizationIds") java.util.Collection<Long> organizationIds);
}
