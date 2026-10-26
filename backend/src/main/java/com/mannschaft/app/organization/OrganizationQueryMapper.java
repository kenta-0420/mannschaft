package com.mannschaft.app.organization;

import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.service.OrganizationQueryService.OrganizationListItem;
import org.mapstruct.Mapper;

/** 組織Entityを他ドメイン向け一覧情報へ変換する。 */
@Mapper(componentModel = "spring")
public interface OrganizationQueryMapper {

    OrganizationListItem toListItem(OrganizationEntity entity);
}
