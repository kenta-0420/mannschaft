package com.mannschaft.app.matching.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.matching.CancellationType;
import com.mannschaft.app.matching.MatchProposalStatus;
import com.mannschaft.app.matching.MatchingErrorCode;
import com.mannschaft.app.matching.dto.AgreeCancelResponse;
import com.mannschaft.app.matching.dto.CancelProposalRequest;
import com.mannschaft.app.matching.dto.ProposalStatusResponse;
import com.mannschaft.app.matching.entity.MatchProposalEntity;
import com.mannschaft.app.matching.entity.MatchRequestEntity;
import com.mannschaft.app.matching.repository.MatchProposalRepository;
import com.mannschaft.app.matching.repository.MatchRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

/** キャンセル2APIの更新境界。他ドメインの権限判定は段取り側で行う。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MatchProposalCancellationWriter {
    private final MatchProposalRepository proposalRepository;
    private final MatchRequestRepository requestRepository;

    /** 許可は段取り判定時点の値。Writer開始時の権限剥奪との排他は保証しない。 */
    public record Context(Long proposalId, Long requestId, Long proposingTeamId, Long requestingTeamId,
                          boolean independentReader, boolean requestingAdmin, boolean proposingAdmin,
                          Long cancelledByTeamId, boolean cancellerAdmin) { }

    @Transactional
    public ProposalStatusResponse cancel(Context context, CancelProposalRequest request) {
        Target target = resolve(context, false);
        MatchProposalEntity proposal = target.proposal();
        if (proposal.getStatus() != MatchProposalStatus.ACCEPTED) {
            throw new BusinessException(MatchingErrorCode.INVALID_PROPOSAL_STATUS);
        }
        MatchRequestEntity parent = parentOrThrow(target);
        Long actingTeam;
        if (context.requestingAdmin()) actingTeam = parent.getTeamId();
        else if (context.proposingAdmin()) actingTeam = proposal.getProposingTeamId();
        else throw new BusinessException(MatchingErrorCode.INSUFFICIENT_PERMISSION);
        boolean mutual = Boolean.TRUE.equals(request.getMutual());
        proposal.cancel(actingTeam, request.getReason(), mutual);
        proposalRepository.save(proposal);
        parent.reopenAfterCancel();
        parent.incrementCancelCount();
        requestRepository.save(parent);
        log.info("マッチングキャンセル: proposalId={}, cancellationType={}", context.proposalId(),
                mutual ? "MUTUAL_PENDING" : "UNILATERAL");
        return new ProposalStatusResponse(context.proposalId(), "CANCELLED");
    }

    @Transactional
    public AgreeCancelResponse agree(Context context) {
        Target target = resolve(context, true);
        MatchProposalEntity proposal = target.proposal();
        if (proposal.getStatus() != MatchProposalStatus.CANCELLED) {
            throw new BusinessException(MatchingErrorCode.INVALID_PROPOSAL_STATUS);
        }
        if (proposal.getCancellationType() != CancellationType.MUTUAL_PENDING) {
            throw new BusinessException(MatchingErrorCode.INVALID_CANCELLATION_TYPE);
        }
        MatchRequestEntity parent = parentOrThrow(target);
        boolean counterpartyAdmin = proposal.getCancelledByTeamId().equals(parent.getTeamId())
                ? context.proposingAdmin() : context.requestingAdmin();
        if (!counterpartyAdmin) {
            if (context.cancellerAdmin()) throw new BusinessException(MatchingErrorCode.CANNOT_AGREE_OWN_CANCEL);
            throw new BusinessException(MatchingErrorCode.INSUFFICIENT_PERMISSION);
        }
        proposal.agreeCancellation();
        proposalRepository.save(proposal);
        parent.decrementCancelCount();
        requestRepository.save(parent);
        log.info("合意キャンセル承認: proposalId={}", context.proposalId());
        return new AgreeCancelResponse(context.proposalId(), "MUTUAL", proposal.getMutualAgreedAt());
    }

    private Target resolve(Context context, boolean agree) {
        // 同じ募集を親からロックし、段取り側のdetached entityは更新に使わない。
        Optional<MatchRequestEntity> parent = requestRepository.findByIdForUpdate(context.requestId());
        MatchProposalEntity proposal = proposalRepository.findById(context.proposalId())
                .orElseThrow(() -> new BusinessException(MatchingErrorCode.PROPOSAL_NOT_FOUND));
        if (!Objects.equals(proposal.getRequestId(), context.requestId())
                || !Objects.equals(proposal.getProposingTeamId(), context.proposingTeamId())
                || (parent.isPresent() && !Objects.equals(parent.get().getTeamId(), context.requestingTeamId()))
                || (agree && !Objects.equals(proposal.getCancelledByTeamId(), context.cancelledByTeamId()))
                || (parent.isEmpty() && !context.independentReader())) {
            throw new BusinessException(MatchingErrorCode.PROPOSAL_NOT_FOUND);
        }
        return new Target(proposal, parent);
    }

    private MatchRequestEntity parentOrThrow(Target target) {
        // 応募側reader／SYSTEMは状態・type診断の後に既存の募集不在001を返す。
        return target.parent().orElseThrow(() -> new BusinessException(MatchingErrorCode.REQUEST_NOT_FOUND));
    }

    private record Target(MatchProposalEntity proposal, Optional<MatchRequestEntity> parent) { }
}
