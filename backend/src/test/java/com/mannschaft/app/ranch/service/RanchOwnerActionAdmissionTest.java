package com.mannschaft.app.ranch.service;

import com.mannschaft.app.auth.UserOperationErrorCode;
import com.mannschaft.app.auth.service.UserOperationGuard;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.ranch.dto.HatchResponse;
import com.mannschaft.app.ranch.dto.HatchResult;
import com.mannschaft.app.ranch.DinosaurStage;
import com.mannschaft.app.ranch.dto.RanchHatchRequest;
import com.mannschaft.app.ranch.dto.RanchPurchaseRequest;
import com.mannschaft.app.ranch.dto.RanchVersionRequest;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** fresh本人guardと保存済みACKの呼出順だけを検証する。実DB接続・認可の証拠ではない。 */
class RanchOwnerActionAdmissionTest {
    private final UserOperationGuard guard = mock(UserOperationGuard.class);
    private final RanchHatchWriter hatch = mock(RanchHatchWriter.class);
    private final RanchFeedingWriter feed = mock(RanchFeedingWriter.class);
    private final RanchPurchaseWriter purchase = mock(RanchPurchaseWriter.class);
    private final RanchExternalProjectionProvider projection = mock(RanchExternalProjectionProvider.class);
    private final RanchOwnerActionFacade facade = new RanchOwnerActionFacade(guard, null, null, null,
            feed, hatch, purchase, projection, null, Clock.systemUTC());

    @Test void savedHatchAckDoesNotRequireCurrentExternalProjection() {
        UUID key = UUID.randomUUID(); var body = new RanchHatchRequest("1", "テスト", true);
        Instant now = Instant.parse("2026-10-05T00:00:00Z");
        var result = new HatchResult(UUID.randomUUID(), UUID.randomUUID(), DinosaurStage.BABY, "テスト", now, now, "1");
        var saved = new HatchResponse(HatchResponse.Kind.HATCH_RESULT, result, null);
        when(guard.withActiveUser(eq(7L), org.mockito.ArgumentMatchers.<Supplier<HatchResponse>>any()))
                .thenAnswer(invocation -> invocation.<Supplier<HatchResponse>>getArgument(1).get());
        when(hatch.savedReplay(7L, key, body)).thenReturn(Optional.of(saved));
        assertThat(facade.hatch(7L, key, body)).isSameAs(saved);
        verify(guard).withActiveUser(eq(7L), org.mockito.ArgumentMatchers.<Supplier<HatchResponse>>any());
        verifyNoInteractions(projection);
        verify(hatch, never()).hatch(any(), any(), any(), any(), any());
    }

    @Test void freshGuardRejectionStopsAllThreeOperationsBeforeAnyWriterOrProjection() {
        var rejection = new BusinessException(UserOperationErrorCode.UNAVAILABLE);
        doThrow(rejection).when(guard).withActiveUser(eq(7L), org.mockito.ArgumentMatchers.<Supplier<Object>>any());
        UUID key = UUID.randomUUID();
        assertThatThrownBy(() -> facade.hatch(7L, key, new RanchHatchRequest("1", "テスト", true))).isSameAs(rejection);
        assertThatThrownBy(() -> facade.feedOutcome(7L, key, new RanchVersionRequest("1"))).isSameAs(rejection);
        assertThatThrownBy(() -> facade.purchase(7L, key, new RanchPurchaseRequest("SYNTHETIC", "1", "1"))).isSameAs(rejection);
        verifyNoInteractions(hatch, feed, purchase, projection);
    }
}
