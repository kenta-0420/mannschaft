package com.mannschaft.app.team;

import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.team.entity.TeamEntity;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import static org.assertj.core.api.Assertions.assertThat;

/** 一覧用境界のアーカイブ瞬間と既存APIが使うnull判定の互換性を固定する。 */
class TeamQueryMapperTest {
    private final TeamQueryMapper mapper = Mappers.getMapper(TeamQueryMapper.class);

    @Test
    void 既存壁時計と同じ瞬間をマイクロ秒精度で返す() {
        var wallClock = LocalDateTime.of(2026, 10, 26, 12, 34, 56, 123456000);
        var team = TeamEntity.builder().name("一覧fixture").slug("query-time-fixture")
                .archivedAt(wallClock).build();

        var item = mapper.toListItem(team);

        assertThat(item.archivedAt()).isEqualTo(Instant.parse("2026-10-26T03:34:56.123456Z"));
        assertThat(LocalDateTime.ofInstant(item.archivedAt(), UserZoneLocalDateTimeParser.SERVER_ZONE))
                .isEqualTo(wallClock);
        assertThat(item.name()).isEqualTo(team.getName());
        assertThat(item.slug()).isEqualTo(team.getSlug());
    }

    @Test
    void 未アーカイブのnullを維持する() {
        assertThat(mapper.toListItem(TeamEntity.builder().build()).archivedAt()).isNull();
    }
}
