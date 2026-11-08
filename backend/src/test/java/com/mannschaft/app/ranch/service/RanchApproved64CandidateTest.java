package com.mannschaft.app.ranch.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.ranch.Habitat;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** 各種の候補が異なる四外見で重複なく揃う。 */
class RanchApproved64CandidateTest {
    @Test
    void 四外見と版を候補自身が保持する() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("ranch-isolated");
        var catalog = new RanchDevelopmentClipCatalog(true, environment, new ObjectMapper());
        Set<String> expected = IntStream.rangeClosed(1, 4).mapToObj(i -> "DEV_APPEARANCE_" + i)
                .collect(Collectors.toSet());
        for (Habitat habitat : Habitat.values()) {
            var rows = catalog.candidates(habitat);
            var bySpecies = rows.stream().collect(Collectors.groupingBy(RanchDevelopmentClipCatalog.Candidate::speciesKey));
            assertThat(rows).hasSize(habitat == Habitat.LAND ? 48 : habitat == Habitat.SEA ? 12 : 4);
            for (var group : bySpecies.values()) {
                assertThat(group).hasSize(4).allSatisfy(row -> assertThat(row.catalogVersion()).isEqualTo(4L));
                assertThat(group.stream().map(RanchDevelopmentClipCatalog.Candidate::variantKey).collect(Collectors.toSet()))
                        .isEqualTo(expected);
            }
        }
    }
}
