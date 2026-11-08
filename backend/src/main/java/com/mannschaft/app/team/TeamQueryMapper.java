package com.mannschaft.app.team;

import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.service.TeamQueryService.TeamListItem;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import java.time.Instant;
import java.time.LocalDateTime;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

/** チームEntityを他ドメイン向け一覧情報へ変換する。 */
@Mapper(componentModel = "spring")
public interface TeamQueryMapper {

    @Mapping(target = "archivedAt", source = "archivedAt", qualifiedByName = "archivedAtInstant")
    TeamListItem toListItem(TeamEntity entity);

    /** JPAが返す既存サーバー壁時計を瞬間へ戻す。既存APIのアーカイブ判定は変えない。 */
    @Named("archivedAtInstant")
    default Instant archivedAtInstant(LocalDateTime archivedAt) {
        return archivedAt == null ? null : archivedAt.atZone(UserZoneLocalDateTimeParser.SERVER_ZONE).toInstant();
    }
}
