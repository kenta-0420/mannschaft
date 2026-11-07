package com.mannschaft.app.organization;

import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.service.OrganizationQueryService.OrganizationListItem;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import java.time.Instant;
import java.time.LocalDateTime;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

/** 組織Entityを他ドメイン向け一覧情報へ変換する。 */
@Mapper(componentModel = "spring")
public interface OrganizationQueryMapper {

    @Mapping(target = "archivedAt", source = "archivedAt", qualifiedByName = "archivedAtInstant")
    OrganizationListItem toListItem(OrganizationEntity entity);

    /** JPAが返す既存サーバー壁時計を瞬間へ戻す。既存APIのアーカイブ判定は変えない。 */
    @Named("archivedAtInstant")
    default Instant archivedAtInstant(LocalDateTime archivedAt) {
        return archivedAt == null ? null : archivedAt.atZone(UserZoneLocalDateTimeParser.SERVER_ZONE).toInstant();
    }
}
