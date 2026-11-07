package com.mannschaft.app.team;

import com.mannschaft.app.team.dto.SystemAdminTeamSummaryResponse;
import com.mannschaft.app.team.dto.DeletedTeamSummaryResponse;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.team.entity.TeamEntity;
import java.time.Instant;
import java.time.LocalDateTime;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

/**
 * システム管理ダッシュボード用のチームサマリ変換マッパー（team ドメイン所有）。
 *
 * <p>{@link TeamEntity} を、システム管理画面で必要な項目のみに絞った
 * {@link SystemAdminTeamSummaryResponse} へ変換する。フィールド名を Entity と一致させているため
 * MapStruct が名前解決で自動マッピングし、DTO に存在しない項目（version・deletedAt 等）は変換対象から
 * 自然に除外される。</p>
 *
 * <p><b>配置理由（ドメイン境界の原則）</b>: {@link TeamEntity} を読む変換ロジックは team ドメイン内に
 * 置く。admin ドメインへ置くと D-1 クロスドメイン Entity 参照違反となるため、admin コントローラーは
 * 本マッパーを注入して利用する。</p>
 */
@Mapper(componentModel = "spring")
public interface SystemAdminTeamSummaryMapper {

    SystemAdminTeamSummaryResponse toSummary(TeamEntity entity);

    @Mapping(target = "deletedAt", source = "deletedAt", qualifiedByName = "deletedAtInstant")
    DeletedTeamSummaryResponse toDeletedSummary(TeamEntity entity);

    /** JPA変換済みの日時を既存サーバーゾーンで瞬間へ戻す。DB scalarの壁時計を推測しない。 */
    @Named("deletedAtInstant")
    default Instant deletedAtInstant(LocalDateTime deletedAt) {
        return deletedAt == null ? null : deletedAt.atZone(UserZoneLocalDateTimeParser.SERVER_ZONE).toInstant();
    }
}
