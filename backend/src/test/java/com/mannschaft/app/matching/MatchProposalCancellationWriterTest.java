package com.mannschaft.app.matching;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.matching.dto.CancelProposalRequest;
import com.mannschaft.app.matching.entity.MatchProposalEntity;
import com.mannschaft.app.matching.entity.MatchRequestEntity;
import com.mannschaft.app.matching.repository.MatchProposalRepository;
import com.mannschaft.app.matching.repository.MatchRequestRepository;
import com.mannschaft.app.matching.service.MatchProposalCancellationWriter;
import com.mannschaft.app.matching.service.MatchProposalCancellationWriter.Context;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 段取り後のbinding・状態・親変化を実Writerで再検査する。 */
@ExtendWith(MockitoExtension.class)
class MatchProposalCancellationWriterTest {
    @Mock private MatchProposalRepository proposals;
    @Mock private MatchRequestRepository requests;
    private MatchProposalCancellationWriter writer;

    @BeforeEach
    void 実Writerを作る() { writer = new MatchProposalCancellationWriter(proposals, requests); }

    private Context context(boolean independent, boolean requesterAdmin, boolean proposerAdmin, Long canceller, boolean cancellerAdmin) {
        return new Context(20L, 10L, 2L, 1L, independent, requesterAdmin, proposerAdmin, canceller, cancellerAdmin);
    }
    private MatchProposalEntity proposal(Long request, Long proposer, MatchProposalStatus state, CancellationType type, Long canceller) {
        return MatchProposalEntity.builder().id(20L).requestId(request).proposingTeamId(proposer)
                .status(state).cancellationType(type).cancelledByTeamId(canceller).build();
    }
    private void parent(Long team) {
        when(requests.findByIdForUpdate(10L)).thenReturn(Optional.of(MatchRequestEntity.builder()
                .id(10L).teamId(team).title("募集").cancelCount((short) 1).build()));
    }
    private void rejected(Runnable action, MatchingErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(code);
        verify(proposals, never()).save(any());
        verify(requests, never()).save(any());
    }

    @Test
    void 段取り後の応募binding変化は両APIとも002でsaveゼロ() {
        parent(1L);
        for (MatchProposalEntity fresh : java.util.List.of(
                proposal(11L, 2L, MatchProposalStatus.ACCEPTED, null, 1L),
                proposal(10L, 3L, MatchProposalStatus.ACCEPTED, null, 1L))) {
            when(proposals.findById(20L)).thenReturn(Optional.of(fresh));
            rejected(() -> writer.cancel(context(true, true, false, 1L, true), new CancelProposalRequest(null, null)), MatchingErrorCode.PROPOSAL_NOT_FOUND);
            rejected(() -> writer.agree(context(true, true, false, 1L, true)), MatchingErrorCode.PROPOSAL_NOT_FOUND);
        }
        when(proposals.findById(20L)).thenReturn(Optional.empty());
        rejected(() -> writer.cancel(context(true, true, false, 1L, true), new CancelProposalRequest(null, null)), MatchingErrorCode.PROPOSAL_NOT_FOUND);
        rejected(() -> writer.agree(context(true, true, false, 1L, true)), MatchingErrorCode.PROPOSAL_NOT_FOUND);
    }

    @Test
    void 募集team変更と合意認可対象変更は002でsaveゼロ() {
        parent(3L);
        when(proposals.findById(20L)).thenReturn(Optional.of(proposal(10L, 2L, MatchProposalStatus.CANCELLED, CancellationType.MUTUAL_PENDING, 1L)));
        rejected(() -> writer.cancel(context(true, true, false, 1L, true), new CancelProposalRequest(null, false)), MatchingErrorCode.PROPOSAL_NOT_FOUND);
        rejected(() -> writer.agree(context(true, true, false, 1L, true)), MatchingErrorCode.PROPOSAL_NOT_FOUND);
        parent(1L);
        when(proposals.findById(20L)).thenReturn(Optional.of(proposal(10L, 2L, MatchProposalStatus.CANCELLED, CancellationType.MUTUAL_PENDING, 2L)));
        rejected(() -> writer.agree(context(true, false, true, 1L, false)), MatchingErrorCode.PROPOSAL_NOT_FOUND);
    }

    @Test
    void fresh状態とtypeの診断は009から021でsaveゼロ() {
        parent(1L);
        when(proposals.findById(20L)).thenReturn(Optional.of(proposal(10L, 2L, MatchProposalStatus.PENDING, CancellationType.UNILATERAL, 1L)));
        rejected(() -> writer.cancel(context(true, true, true, 1L, true), new CancelProposalRequest(null, true)), MatchingErrorCode.INVALID_PROPOSAL_STATUS);
        rejected(() -> writer.agree(context(true, true, true, 1L, true)), MatchingErrorCode.INVALID_PROPOSAL_STATUS);
        when(proposals.findById(20L)).thenReturn(Optional.of(proposal(10L, 2L, MatchProposalStatus.CANCELLED, CancellationType.MUTUAL, 1L)));
        rejected(() -> writer.agree(context(true, true, true, 1L, true)), MatchingErrorCode.INVALID_CANCELLATION_TYPE);
    }

    @Test
    void 親消失はreader由来で002と状態type親001を区別する() {
        when(requests.findByIdForUpdate(10L)).thenReturn(Optional.empty());
        when(proposals.findById(20L)).thenReturn(Optional.of(proposal(10L, 2L, MatchProposalStatus.PENDING, null, 1L)));
        rejected(() -> writer.cancel(context(false, true, false, 1L, true), new CancelProposalRequest(null, false)), MatchingErrorCode.PROPOSAL_NOT_FOUND);
        rejected(() -> writer.agree(context(false, true, false, 1L, true)), MatchingErrorCode.PROPOSAL_NOT_FOUND);
        rejected(() -> writer.cancel(context(true, false, true, 1L, false), new CancelProposalRequest(null, false)), MatchingErrorCode.INVALID_PROPOSAL_STATUS);
        rejected(() -> writer.agree(context(true, false, true, 1L, false)), MatchingErrorCode.INVALID_PROPOSAL_STATUS);
        when(proposals.findById(20L)).thenReturn(Optional.of(proposal(10L, 2L, MatchProposalStatus.ACCEPTED, null, 1L)));
        rejected(() -> writer.cancel(context(true, false, true, 1L, false), new CancelProposalRequest(null, false)), MatchingErrorCode.REQUEST_NOT_FOUND);
        when(proposals.findById(20L)).thenReturn(Optional.of(proposal(10L, 2L, MatchProposalStatus.CANCELLED, CancellationType.UNILATERAL, 1L)));
        rejected(() -> writer.agree(context(true, false, true, 1L, false)), MatchingErrorCode.INVALID_CANCELLATION_TYPE);
        when(proposals.findById(20L)).thenReturn(Optional.of(proposal(10L, 2L, MatchProposalStatus.CANCELLED, CancellationType.MUTUAL_PENDING, 1L)));
        rejected(() -> writer.agree(context(true, false, true, 1L, false)), MatchingErrorCode.REQUEST_NOT_FOUND);
    }

    @Test
    void 旧第三teamキャンセルの自己診断とcounterparty優先を維持する() {
        parent(1L);
        MatchProposalEntity fresh = proposal(10L, 2L, MatchProposalStatus.CANCELLED, CancellationType.MUTUAL_PENDING, 3L);
        when(proposals.findById(20L)).thenReturn(Optional.of(fresh));
        rejected(() -> writer.agree(context(true, false, false, 3L, true)), MatchingErrorCode.CANNOT_AGREE_OWN_CANCEL);
        rejected(() -> writer.agree(context(true, false, true, 3L, false)), MatchingErrorCode.INSUFFICIENT_PERMISSION);
        clearInvocations(proposals, requests);
        assertThat(writer.agree(context(true, true, false, 3L, true)).getCancellationType()).isEqualTo("MUTUAL");
        assertThat(fresh.getCancellationType()).isEqualTo(CancellationType.MUTUAL);
        verify(proposals).save(fresh);
        verify(requests).save(any());
    }
}
