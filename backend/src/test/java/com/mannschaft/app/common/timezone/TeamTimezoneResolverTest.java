package com.mannschaft.app.common.timezone;

import com.mannschaft.app.team.repository.TeamRepository;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TeamTimezoneResolverTest {
    @Test
    void resolvesTrimmedValidTimezone() {
        var repository = mock(TeamRepository.class);
        var team = com.mannschaft.app.team.entity.TeamEntity.builder()
                .name("team").timezone("  America/New_York  ").build();
        when(repository.findById(1L)).thenReturn(Optional.of(team));
        assertThat(new TeamTimezoneResolver(repository).resolveZone(1L))
                .isEqualTo(ZoneId.of("America/New_York"));
    }
}
