package com.mannschaft.app.ranch.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.security.PrivateSelfAccessGuard;
import com.mannschaft.app.ranch.DinosaurStage;
import com.mannschaft.app.ranch.dto.FeedingResult;
import com.mannschaft.app.ranch.dto.RanchPurchaseRequest;
import com.mannschaft.app.ranch.dto.RanchPurchaseResult;
import com.mannschaft.app.ranch.dto.RanchVersionRequest;
import com.mannschaft.app.ranch.service.RanchFeedingWriter;
import com.mannschaft.app.ranch.service.RanchOwnerActionFacade;
import com.mannschaft.app.ranch.service.RanchPurchaseWriter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** transportの201/200・Location・不変bodyだけを検証する。実filterとDBは別HTTP ITの責務。 */
class RanchOwnerActionResponseTest {
    private final RanchOwnerActionFacade facade = mock(RanchOwnerActionFacade.class);
    private final PrivateSelfAccessGuard guard = mock(PrivateSelfAccessGuard.class);
    private final RanchOwnerActionController controller = new RanchOwnerActionController(facade, guard);
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final Instant now = Instant.parse("2026-10-05T00:00:00Z");

    @Test void purchaseCreatedLocationAndReplay200ReturnSameFrozenResult() {
        UUID key = UUID.randomUUID(); UUID command = UUID.randomUUID();
        var body = new RanchPurchaseRequest("SYNTHETIC", "1", "1");
        var result = new RanchPurchaseResult(command, UUID.randomUUID(), "SYNTHETIC", "10", "1", "90", now);
        when(guard.requireSelfAccess(request, response)).thenReturn(7L);
        when(facade.purchase(7L, key, body)).thenReturn(new RanchPurchaseWriter.PurchaseOutcome(result, true),
                new RanchPurchaseWriter.PurchaseOutcome(result, false));
        var first = controller.purchase(key, body, request, response);
        var replay = controller.purchase(key, body, request, response);
        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(first.getHeaders().getLocation().toString()).isEqualTo("/api/v1/me/ranch/commands/" + command);
        assertThat(replay.getStatusCode().value()).isEqualTo(200);
        assertThat(replay.getHeaders().getLocation()).isNull();
        var firstBody = json.valueToTree(first.getBody()); var replayBody = json.valueToTree(replay.getBody());
        assertThat(replayBody).isEqualTo(firstBody);
        assertThat(first.getHeaders().getCacheControl()).isEqualTo("private, no-store");
        assertThat(replay.getHeaders().getCacheControl()).isEqualTo("private, no-store");
        verify(facade, times(2)).purchase(7L, key, body);
    }

    @Test void feedingCreatedLocationAndReplay200ReturnSameFrozenResult() {
        UUID key = UUID.randomUUID(); UUID command = UUID.randomUUID(); var body = new RanchVersionRequest("1");
        var result = new FeedingResult(command, UUID.randomUUID(), "FREE_BASIC", "0", "20", false,
                DinosaurStage.BABY, DinosaurStage.BABY, "0", "SYNTHETIC", now);
        when(guard.requireSelfAccess(request, response)).thenReturn(7L);
        when(facade.feedOutcome(7L, key, body)).thenReturn(new RanchFeedingWriter.FeedOutcome(result, true),
                new RanchFeedingWriter.FeedOutcome(result, false));
        var first = controller.feed(key, body, request, response);
        var replay = controller.feed(key, body, request, response);
        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(first.getHeaders().getLocation().toString()).isEqualTo("/api/v1/me/ranch/commands/" + command);
        assertThat(replay.getStatusCode().value()).isEqualTo(200);
        var firstBody = json.valueToTree(first.getBody()); var replayBody = json.valueToTree(replay.getBody());
        assertThat(replayBody).isEqualTo(firstBody);
        assertThat(first.getHeaders().getCacheControl()).isEqualTo("private, no-store");
        assertThat(replay.getHeaders().getCacheControl()).isEqualTo("private, no-store");
    }
}
