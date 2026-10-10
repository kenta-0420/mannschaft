package com.mannschaft.app.favorite;

import com.mannschaft.app.organization.entity.OrganizationEntity;

/** 同一IDの異種お気に入り契約に用いる組織のフィクスチャ。 */
final class FavoriteTestFixture {
    private FavoriteTestFixture() {
    }

    static OrganizationEntity organization(String slug, String visibility) {
        return OrganizationEntity.builder()
                .name("FAVAUTHZ 同ID組織")
                .slug(slug)
                .orgType(OrganizationEntity.OrgType.OTHER)
                .visibility(OrganizationEntity.Visibility.valueOf(visibility))
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                .supporterEnabled(true)
                .build();
    }
}
