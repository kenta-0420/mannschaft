package com.mannschaft.app.matching.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.matching.CancellationType;
import com.mannschaft.app.matching.MatchProposalStatus;
import com.mannschaft.app.matching.MatchRequestStatus;
import com.mannschaft.app.matching.MatchingErrorCode;
import com.mannschaft.app.matching.dto.AcceptProposalRequest;
import com.mannschaft.app.matching.dto.AcceptProposalResponse;
import com.mannschaft.app.matching.dto.AgreeCancelResponse;
import com.mannschaft.app.matching.dto.CancelProposalRequest;
import com.mannschaft.app.matching.dto.CancellationResponse;
import com.mannschaft.app.matching.dto.CancellationSummaryResponse;
import com.mannschaft.app.matching.dto.CreateProposalRequest;
import com.mannschaft.app.matching.dto.ProposalCreateResponse;
import com.mannschaft.app.matching.dto.ProposalResponse;
import com.mannschaft.app.matching.dto.ProposalStatusResponse;
import com.mannschaft.app.matching.dto.ProposedDateResponse;
import com.mannschaft.app.matching.entity.MatchProposalDateEntity;
import com.mannschaft.app.matching.entity.MatchProposalEntity;
import com.mannschaft.app.matching.entity.MatchRequestEntity;
import com.mannschaft.app.matching.mapper.MatchingMapper;
import com.mannschaft.app.matching.repository.MatchProposalDateRepository;
import com.mannschaft.app.matching.repository.MatchProposalRepository;
import com.mannschaft.app.matching.repository.MatchRequestRepository;
import com.mannschaft.app.matching.repository.NgTeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 応募サービス。応募のCRUD・承諾・拒否・取り下げ・キャンセルを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MatchProposalService {

    private static final int MAX_PROPOSED_DATES = 5;
    private static final int REVIEW_RETENTION_YEARS = 2;

    private final MatchProposalRepository proposalRepository;
    private final MatchProposalDateRepository proposalDateRepository;
    private final MatchRequestRepository requestRepository;
    private final NgTeamRepository ngTeamRepository;
    private final MatchingMapper matchingMapper;

    /**
     * 募集への応募を作成する。
     */
    @Transactional
    public ProposalCreateResponse createProposal(Long teamId, Long requestId, CreateProposalRequest request) {
        MatchRequestEntity matchRequest = requestRepository.findById(requestId)
                .orElseThrow(() -> new BusinessException(MatchingErrorCode.REQUEST_NOT_FOUND));

        // 自チーム応募チェック
        if (matchRequest.getTeamId().equals(teamId)) {
            throw new BusinessException(MatchingErrorCode.SELF_PROPOSAL_NOT_ALLOWED);
        }

        // OPENチェック
        if (matchRequest.getStatus() != MatchRequestStatus.OPEN) {
            throw new BusinessException(MatchingErrorCode.REQUEST_NOT_OPEN);
        }

        // 期限切れチェック
        if (matchRequest.getExpiresAt() != null && matchRequest.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(MatchingErrorCode.REQUEST_NOT_OPEN);
        }

        // NGチームチェック（双方向）
        List<Long> blockedIds = ngTeamRepository.findBidirectionalBlockedTeamIds(teamId);
        if (blockedIds.contains(matchRequest.getTeamId())) {
            throw new BusinessException(MatchingErrorCode.NG_TEAM_BLOCKED);
        }

        // 重複応募チェック
        if (proposalRepository.existsByRequestIdAndProposingTeamId(requestId, teamId)) {
            throw new BusinessException(MatchingErrorCode.DUPLICATE_PROPOSAL);
        }

        // 日程候補数チェック
        if (request.getProposedDates() != null && request.getProposedDates().size() > MAX_PROPOSED_DATES) {
            throw new BusinessException(MatchingErrorCode.TOO_MANY_PROPOSED_DATES);
        }

        MatchProposalEntity proposal = MatchProposalEntity.builder()
                .requestId(requestId)
                .proposingTeamId(teamId)
                .message(request.getMessage())
                .proposedVenue(request.getProposedVenue())
                .build();

        MatchProposalEntity saved = proposalRepository.save(proposal);

        // 日程候補の保存
        if (request.getProposedDates() != null) {
            for (var dateReq : request.getProposedDates()) {
                MatchProposalDateEntity dateEntity = MatchProposalDateEntity.builder()
                        .proposalId(saved.getId())
                        .proposedDate(dateReq.getDate())
                        .proposedTimeFrom(dateReq.getTimeFrom())
                        .proposedTimeTo(dateReq.getTimeTo())
                        .build();
                proposalDateRepository.save(dateEntity);
            }
        }

        // 応募数インクリメント
        matchRequest.incrementProposalCount();
        requestRepository.save(matchRequest);

        log.info("応募作成: teamId={}, requestId={}, proposalId={}", teamId, requestId, saved.getId());
        return new ProposalCreateResponse(saved.getId(), requestId, saved.getStatus().name());
    }

    /**
     * 募集への応募一覧を取得する。
     */
    public Page<ProposalResponse> listProposals(Long requestId, Pageable pageable) {
        Page<MatchProposalEntity> page = proposalRepository.findByRequestIdOrderByCreatedAtDesc(requestId, pageable);
        return page.map(this::toProposalResponse);
    }

    /**
     * 自チームの応募一覧を取得する。
     */
    public Page<ProposalResponse> listTeamProposals(Long teamId, String statusStr, Pageable pageable) {
        Page<MatchProposalEntity> page;
        if (statusStr != null) {
            MatchProposalStatus status = MatchProposalStatus.valueOf(statusStr);
            page = proposalRepository.findByProposingTeamIdAndStatusOrderByCreatedAtDesc(teamId, status, pageable);
        } else {
            page = proposalRepository.findByProposingTeamIdOrderByCreatedAtDesc(teamId, pageable);
        }
        return page.map(this::toProposalResponse);
    }

    /**
     * 応募を承諾する（マッチング成立）。
     */
    @Transactional
    public AcceptProposalResponse acceptProposal(Long proposalId, Long teamId, AcceptProposalRequest request) {
        MatchProposalEntity proposal = findProposalOrThrow(proposalId);

        // 募集を悲観ロックで取得
        MatchRequestEntity matchRequest = requestRepository.findByIdForUpdate(proposal.getRequestId())
                .orElseThrow(() -> new BusinessException(MatchingErrorCode.REQUEST_NOT_FOUND));

        // 権限チェック（募集チームのADMINのみ）
        if (!matchRequest.getTeamId().equals(teamId)) {
            throw new BusinessException(MatchingErrorCode.INSUFFICIENT_PERMISSION);
        }

        // OPENチェック
        if (matchRequest.getStatus() != MatchRequestStatus.OPEN) {
            throw new BusinessException(MatchingErrorCode.REQUEST_ALREADY_MATCHED);
        }

        // PENDINGチェック
        if (proposal.getStatus() != MatchProposalStatus.PENDING) {
            throw new BusinessException(MatchingErrorCode.INVALID_PROPOSAL_STATUS);
        }

        // 応募を承諾
        proposal.accept();
        proposalRepository.save(proposal);

        // 他のPENDING応募を一括REJECTED
        List<MatchProposalEntity> otherPending = proposalRepository.findByRequestIdAndStatus(
                matchRequest.getId(), MatchProposalStatus.PENDING);
        for (MatchProposalEntity other : otherPending) {
            if (!other.getId().equals(proposalId)) {
                other.reject("別の応募が承諾されました");
                proposalRepository.save(other);
            }
        }

        // 募集をMATCHEDに更新
        matchRequest.markMatched(proposalId);
        requestRepository.save(matchRequest);

        // 選択された日程の設定
        if (request != null && request.getConfirmedDate() != null) {
            List<MatchProposalDateEntity> dates = proposalDateRepository
                    .findByProposalIdOrderByProposedDateAsc(proposalId);
            for (MatchProposalDateEntity dateEntity : dates) {
                if (dateEntity.getProposedDate().equals(request.getConfirmedDate())) {
                    dateEntity.select();
                    proposalDateRepository.save(dateEntity);
                    break;
                }
            }
        }

        boolean scheduleCreated = request != null && request.getConfirmedDate() != null;

        log.info("応募承諾: proposalId={}, requestId={}", proposalId, matchRequest.getId());
        return new AcceptProposalResponse(proposalId, matchRequest.getId(), "ACCEPTED", scheduleCreated);
    }

    /**
     * 応募を拒否する。
     */
    @Transactional
    public ProposalStatusResponse rejectProposal(Long proposalId, Long teamId, String statusReason) {
        MatchProposalEntity proposal = findProposalOrThrow(proposalId);
        MatchRequestEntity matchRequest = requestRepository.findById(proposal.getRequestId())
                .orElseThrow(() -> new BusinessException(MatchingErrorCode.REQUEST_NOT_FOUND));

        if (!matchRequest.getTeamId().equals(teamId)) {
            throw new BusinessException(MatchingErrorCode.INSUFFICIENT_PERMISSION);
        }
        if (proposal.getStatus() != MatchProposalStatus.PENDING) {
            throw new BusinessException(MatchingErrorCode.INVALID_PROPOSAL_STATUS);
        }

        proposal.reject(statusReason);
        proposalRepository.save(proposal);

        log.info("応募拒否: proposalId={}", proposalId);
        return new ProposalStatusResponse(proposalId, "REJECTED");
    }

    /**
     * 応募を取り下げる。
     */
    @Transactional
    public ProposalStatusResponse withdrawProposal(Long proposalId, Long teamId, String statusReason) {
        MatchProposalEntity proposal = findProposalOrThrow(proposalId);

        if (!proposal.getProposingTeamId().equals(teamId)) {
            throw new BusinessException(MatchingErrorCode.INSUFFICIENT_PERMISSION);
        }
        if (proposal.getStatus() != MatchProposalStatus.PENDING) {
            throw new BusinessException(MatchingErrorCode.INVALID_PROPOSAL_STATUS);
        }

        proposal.withdraw(statusReason);
        proposalRepository.save(proposal);

        // 応募数デクリメント
        MatchRequestEntity matchRequest = requestRepository.findById(proposal.getRequestId())
                .orElseThrow(() -> new BusinessException(MatchingErrorCode.REQUEST_NOT_FOUND));
        matchRequest.decrementProposalCount();
        requestRepository.save(matchRequest);

        log.info("応募取り下げ: proposalId={}", proposalId);
        return new ProposalStatusResponse(proposalId, "WITHDRAWN");
    }

    /**
     * マッチング成立後のキャンセルを行う。
     */
    @Transactional
    public ProposalStatusResponse cancelProposal(Long proposalId, Long teamId, CancelProposalRequest request) {
        MatchProposalEntity proposal = findProposalOrThrow(proposalId);

        if (proposal.getStatus() != MatchProposalStatus.ACCEPTED) {
            throw new BusinessException(MatchingErrorCode.INVALID_PROPOSAL_STATUS);
        }

        MatchRequestEntity matchRequest = requestRepository.findById(proposal.getRequestId())
                .orElseThrow(() -> new BusinessException(MatchingErrorCode.REQUEST_NOT_FOUND));

        // 募集チームまたは応募チームのADMINのみ
        if (!matchRequest.getTeamId().equals(teamId) && !proposal.getProposingTeamId().equals(teamId)) {
            throw new BusinessException(MatchingErrorCode.INSUFFICIENT_PERMISSION);
        }

        boolean mutual = request.getMutual() != null && request.getMutual();
        proposal.cancel(teamId, request.getReason(), mutual);
        proposalRepository.save(proposal);

        // 募集をOPENに復元
        matchRequest.reopenAfterCancel();
        matchRequest.incrementCancelCount();
        requestRepository.save(matchRequest);

        log.info("マッチングキャンセル: proposalId={}, cancellationType={}", proposalId,
                mutual ? "MUTUAL_PENDING" : "UNILATERAL");
        return new ProposalStatusResponse(proposalId, "CANCELLED");
    }

    /**
     * 合意キャンセルを承認する。
     */
    @Transactional
    public AgreeCancelResponse agreeCancellation(Long proposalId, Long teamId) {
        MatchProposalEntity proposal = findProposalOrThrow(proposalId);

        if (proposal.getStatus() != MatchProposalStatus.CANCELLED) {
            throw new BusinessException(MatchingErrorCode.INVALID_PROPOSAL_STATUS);
        }
        if (proposal.getCancellationType() != CancellationType.MUTUAL_PENDING) {
            throw new BusinessException(MatchingErrorCode.INVALID_CANCELLATION_TYPE);
        }
        if (proposal.getCancelledByTeamId().equals(teamId)) {
            throw new BusinessException(MatchingErrorCode.CANNOT_AGREE_OWN_CANCEL);
        }

        proposal.agreeCancellation();
        proposalRepository.save(proposal);

        // cancel_countを戻す
        MatchRequestEntity matchRequest = requestRepository.findById(proposal.getRequestId())
                .orElseThrow(() -> new BusinessException(MatchingErrorCode.REQUEST_NOT_FOUND));
        matchRequest.decrementCancelCount();
        requestRepository.save(matchRequest);

        log.info("合意キャンセル承認: proposalId={}", proposalId);
        return new AgreeCancelResponse(proposalId, "MUTUAL", proposal.getMutualAgreedAt());
    }

    /**
     * チームのキャンセル履歴一覧を取得する。
     */
    public CancellationSummaryResponse getCancellationHistory(Long teamId, Pageable pageable) {
        LocalDateTime since = LocalDateTime.now().minusYears(REVIEW_RETENTION_YEARS);
        long cancelCount = proposalRepository.countCancellationsByTeam(teamId, since);

        Page<MatchProposalEntity> page = proposalRepository.findCancellationsByTeam(teamId, since, pageable);
        List<CancellationResponse> cancellations = new ArrayList<>();
        for (MatchProposalEntity proposal : page.getContent()) {
            MatchRequestEntity request = requestRepository.findById(proposal.getRequestId()).orElse(null);
            String requestTitle = request != null ? request.getTitle() : null;
            cancellations.add(new CancellationResponse(
                    proposal.getId(), requestTitle,
                    proposal.getCancellationType() != null ? proposal.getCancellationType().name() : null,
                    proposal.getStatusReason(), proposal.getUpdatedAt()));
        }

        return new CancellationSummaryResponse(teamId, cancelCount, cancellations);
    }

    /**
     * 応募エンティティを取得する。存在しない場合は例外をスローする。
     */
    MatchProposalEntity findProposalOrThrow(Long id) {
        return proposalRepository.findById(id)
                .orElseThrow(() -> new BusinessException(MatchingErrorCode.PROPOSAL_NOT_FOUND));
    }

    private ProposalResponse toProposalResponse(MatchProposalEntity entity) {
        List<MatchProposalDateEntity> dates = proposalDateRepository
                .findByProposalIdOrderByProposedDateAsc(entity.getId());
        List<ProposedDateResponse> dateResponses = matchingMapper.toProposedDateResponseList(dates);

        return new ProposalResponse(
                entity.getId(), entity.getRequestId(), entity.getProposingTeamId(),
                entity.getMessage(), entity.getProposedVenue(),
                entity.getStatus().name(), entity.getStatusReason(),
                entity.getCancelledByTeamId(),
                entity.getCancellationType() != null ? entity.getCancellationType().name() : null,
                entity.getMutualAgreedAt(), dateResponses,
                entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
