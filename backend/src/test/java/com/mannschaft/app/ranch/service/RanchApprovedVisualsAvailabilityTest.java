package com.mannschaft.app.ranch.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.ranch.dto.RanchState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 公開flagと検証済み登録のANDだけを許可し、旧ACKを公開承認へ読み替えない。 */
class RanchApprovedVisualsAvailabilityTest {
    @Test
    void 明示公開flagと全登録整合の両方だけで正式素材を公開可能にする() {
        var readiness = mock(RanchPublicationReadiness.class);
        for (boolean published : List.of(false, true)) {
            for (boolean registered : List.of(false, true)) {
                when(readiness.current()).thenReturn(new RanchPublicationReadiness.Snapshot(
                        registered ? "registered" : null, registered, registered ? "READY" : "MASTER_UNREGISTERED"));
                assertThat(new RanchApprovedVisualsAvailability(published, readiness).isAvailable())
                        .isEqualTo(published && registered);
            }
        }
    }

    @Test
    void 公開flagの既定と旧ACKの追加field欠損はfalseを維持する() throws Exception {
        assertThat(new RanchApprovedVisualsAvailability(false, null).isAvailable()).isFalse();
        var mapper = new ObjectMapper().findAndRegisterModules();
        var old = new RanchState("AVAILABLE", false, "ACTIVE", true, null, null, null,
                null, null, List.of(), null, Instant.EPOCH, null);
        var json = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(old);
        json.remove("approvedVisualsAvailable");
        assertThat(mapper.treeToValue(json, RanchState.class).approvedVisualsAvailable()).isFalse();
    }
}
