package com.mannschaft.app.ranch.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.diagnosis.service.DiagnosisPublicationReadiness;
import com.mannschaft.app.ranch.AssignmentMethod;
import com.mannschaft.app.ranch.Habitat;
import com.mannschaft.app.ranch.dto.RanchAssignmentRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 実resolverが選んだ外見と版を選定結果とbasisへ記録する。 */
class RanchApproved64SelectionAcceptanceTest {
    @Test
    void 各生息地の選定結果とbasisは実際の候補に一致する() throws Exception {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("ranch-isolated");
        var catalog = new RanchDevelopmentClipCatalog(true, environment, new ObjectMapper());
        var production = mock(RanchProductionMasterRegistry.class);
        var rules = mock(RanchRuleProvider.class);
        when(production.current()).thenReturn(Optional.empty());
        when(rules.currentCareRule(any())).thenReturn(Optional.of(new RanchRuleProvider.CareRuleSnapshot(
                UUID.fromString("00000000-0000-0000-0000-000000000001"), "rule", 1, 1, 1, 1, 1, 1, 1)));
        var resolver = new RanchVerifiedAssignmentResolver(mock(RanchDevelopmentVisualCatalog.class), catalog,
                production, mock(DiagnosisPublicationReadiness.class), rules,
                Clock.fixed(Instant.parse("2032-01-06T12:00:00Z"), ZoneOffset.UTC));
        for (Habitat habitat : Habitat.values()) {
            var request = new RanchAssignmentRequest(AssignmentMethod.HABITAT_RANDOM, habitat, null, null, "0");
            var selected = resolver.resolve(1L, request, null, null);
            assertThat(selected.habitat()).isEqualTo(habitat);
            assertThat(catalog.candidates(habitat)).extracting(RanchDevelopmentClipCatalog.Candidate::speciesKey)
                    .contains(selected.speciesKey());
            assertThat(selected.catalogVersion()).isEqualTo(4L);
            assertThat(selected.variantKey()).isIn("DEV_APPEARANCE_1", "DEV_APPEARANCE_2",
                    "DEV_APPEARANCE_3", "DEV_APPEARANCE_4");
            String basis = "HABITAT_RANDOM|" + habitat + "|4|" + selected.speciesKey() + "|" + selected.variantKey();
            String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(basis.getBytes(StandardCharsets.US_ASCII)));
            assertThat(selected.inputHash()).isEqualTo(expected);
        }
    }
}

