package com.mannschaft.app.organization;

import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import static org.assertj.core.api.Assertions.assertThat;

/** 組織一覧用境界のアーカイブ瞬間と既存APIのnull判定を固定する。 */
class OrganizationQueryMapperTest {
    private final OrganizationQueryMapper mapper = Mappers.getMapper(OrganizationQueryMapper.class);

    @Test
    void 既存壁時計と同じ瞬間をマイクロ秒精度で返す() {
        var wallClock = LocalDateTime.of(2026, 10, 26, 12, 34, 56, 123456000);
        var org = OrganizationEntity.builder().name("一覧fixture").slug("query-time-fixture")
                .archivedAt(wallClock).build();

        var item = mapper.toListItem(org);

        assertThat(item.archivedAt()).isEqualTo(Instant.parse("2026-10-26T03:34:56.123456Z"));
        assertThat(LocalDateTime.ofInstant(item.archivedAt(), UserZoneLocalDateTimeParser.SERVER_ZONE))
                .isEqualTo(wallClock);
        assertThat(item.name()).isEqualTo(org.getName());
        assertThat(item.slug()).isEqualTo(org.getSlug());
    }

    @Test
    void 未アーカイブのnullを維持する() {
        assertThat(mapper.toListItem(OrganizationEntity.builder().build()).archivedAt()).isNull();
    }
}
