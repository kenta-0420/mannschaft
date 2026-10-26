package com.mannschaft.app.team;

import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.service.TeamQueryService.TeamListItem;
import org.mapstruct.Mapper;

/** チームEntityを他ドメイン向け一覧情報へ変換する。 */
@Mapper(componentModel = "spring")
public interface TeamQueryMapper {

    TeamListItem toListItem(TeamEntity entity);
}
