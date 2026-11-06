package com.mannschaft.app.ranch.service;

import com.mannschaft.app.auth.UserOperationErrorCode;
import com.mannschaft.app.auth.service.UserOperationGuard;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.ranch.dto.RanchRecord;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** AC26/32: 本人guardの内側でRanch読取完了後に源閲覧判定を行う。実TXは別ITで確認する。 */
class RanchPrivateRecordFacadeTest {
    @Test
    void sourceResolutionRunsAfterReaderReturnsInsideActiveUserGuard() {
        var guard = mock(UserOperationGuard.class);
        var records = mock(RanchRecordQueryReader.class);
        var links = mock(RanchRecordSourceLinkResolver.class);
        var page = CursorPagedResponse.<RanchRecord>of(List.of(),
                new CursorPagedResponse.CursorMeta(null, false, 20));
        var read = new RanchRecordQueryReader.ReadPage(page, Map.of());
        when(guard.withActiveUser(eq(21L), any())).thenAnswer(invocation -> {
            Supplier<?> operation = invocation.getArgument(1);
            return operation.get();
        });
        when(records.readPage(21L, null, 20)).thenReturn(read);
        when(links.resolve(21L, read)).thenReturn(page);

        assertThat(facade(guard, records, links).records(21L, null, 20)).isSameAs(page);
        var order = inOrder(guard, records, links);
        order.verify(guard).withActiveUser(eq(21L), any());
        order.verify(records).readPage(21L, null, 20);
        order.verify(links).resolve(21L, read);
    }

    @Test
    void withdrawalGuardDenialPreventsRanchReadAndSourcePermissionCall() {
        var guard = mock(UserOperationGuard.class);
        var records = mock(RanchRecordQueryReader.class);
        var links = mock(RanchRecordSourceLinkResolver.class);
        when(guard.withActiveUser(eq(21L), any()))
                .thenThrow(new BusinessException(UserOperationErrorCode.NOT_ALLOWED));

        assertThatThrownBy(() -> facade(guard, records, links).records(21L, null, 20))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(records, links);
    }

    private static RanchPrivateQueryFacade facade(UserOperationGuard guard,
                                                  RanchRecordQueryReader records,
                                                  RanchRecordSourceLinkResolver links) {
        return new RanchPrivateQueryFacade(guard, mock(RanchAccessGuard.class),
                mock(RanchCommandQueryReader.class), records, links,
                mock(RanchInventoryQueryReader.class), mock(RanchShopQueryReader.class), Clock.systemUTC());
    }
}
