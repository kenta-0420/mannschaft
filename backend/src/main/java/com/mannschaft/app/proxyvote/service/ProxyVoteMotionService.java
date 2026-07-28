package com.mannschaft.app.proxyvote.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.proxyvote.DelegationStatus;
import com.mannschaft.app.proxyvote.MotionResult;
import com.mannschaft.app.proxyvote.ProxyVoteErrorCode;
import com.mannschaft.app.proxyvote.ProxyVoteMapper;
import com.mannschaft.app.proxyvote.ResolutionMode;
import com.mannschaft.app.proxyvote.SessionStatus;
import com.mannschaft.app.proxyvote.VoteType;
import com.mannschaft.app.proxyvote.VotingStatus;
import com.mannschaft.app.proxyvote.dto.EndVoteResponse;
import com.mannschaft.app.proxyvote.dto.MotionResponse;
import com.mannschaft.app.proxyvote.dto.SessionResponse;
import com.mannschaft.app.proxyvote.dto.StartVoteRequest;
import com.mannschaft.app.proxyvote.entity.ProxyDelegationEntity;
import com.mannschaft.app.proxyvote.entity.ProxyVoteEntity;
import com.mannschaft.app.proxyvote.entity.ProxyVoteMotionEntity;
import com.mannschaft.app.proxyvote.entity.ProxyVoteSessionEntity;
import com.mannschaft.app.proxyvote.repository.ProxyDelegationRepository;
import com.mannschaft.app.proxyvote.repository.ProxyVoteMotionRepository;
import com.mannschaft.app.proxyvote.repository.ProxyVoteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 議案投票制御サービス。MEETING モードの議案別投票開始/終了を担当する。
 *
 * <p><b>認可方針（認可根治戦役 Wave7）:</b> 議決の開始・終了は議事の完全性に関わる重い書込である。
 * 同一ドメインの兄弟である {@code ProxyVoteSessionService#addMotion / updateMotion / deleteMotion}
 * が一貫して採用している {@link AccessControlService#checkOwnerOrAdmin}（セッション作成者
 * または当該スコープの ADMIN/DEPUTY_ADMIN）に揃える。</p>
 *
 * <p><b>BOLA 厳禁:</b> 認可スコープは path の ID ではなく、motionId から fetch した議案 →
 * その議案が属するセッション entity 由来の {@code resolveScopeId()} /
 * {@code scopeTypeName()} を用いる。</p>
 *
 * <p><b>バッチ巻き添え回避:</b> 投票タイマーによる自動終了（{@code ProxyVoteScheduledService}）は
 * 実行ユーザーを持たないシステム起点の処理である。認可ゲートは利用者が到達する public 入口
 * （{@link #endVote(Long, Long)}）にのみ敷き、認可を伴わない中核処理は
 * {@link #endVoteBySystem(Long)} として明示的に切り出す
 * （共有メソッドに認可を埋めるとバッチが巻き添えで落ちるため）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProxyVoteMotionService {

    private final ProxyVoteSessionService sessionService;
    private final ProxyVoteMotionRepository motionRepository;
    private final ProxyVoteRepository voteRepository;
    private final ProxyDelegationRepository delegationRepository;
    private final ProxyVoteMapper mapper;
    private final AccessControlService accessControlService;

    /**
     * 議案の投票を開始する（MEETING モード。PENDING → VOTING）。
     *
     * @param motionId      議案 ID
     * @param request       投票時間指定（任意）
     * @param currentUserId 操作ユーザー（セッション作成者またはスコープ ADMIN のみ許可）
     */
    @Transactional
    public MotionResponse startVote(Long motionId, StartVoteRequest request, Long currentUserId) {
        ProxyVoteMotionEntity motion = sessionService.findMotionOrThrow(motionId);
        ProxyVoteSessionEntity session = sessionService.findSessionOrThrow(motion.getSessionId());
        // 認可: 作成者またはスコープ管理者のみ投票開始可（entity 由来スコープで BOLA 防止）。
        accessControlService.checkOwnerOrAdmin(currentUserId, session.getCreatedBy(),
                session.resolveScopeId(), session.scopeTypeName());

        if (session.getResolutionMode() != ResolutionMode.MEETING) {
            throw new BusinessException(ProxyVoteErrorCode.MEETING_MODE_ONLY);
        }
        if (session.getStatus() != SessionStatus.OPEN) {
            throw new BusinessException(ProxyVoteErrorCode.STATUS_MUST_BE_OPEN);
        }
        if (motion.getVotingStatus() != VotingStatus.PENDING) {
            throw new BusinessException(ProxyVoteErrorCode.MOTION_NOT_PENDING);
        }

        motion.changeVotingStatus(VotingStatus.VOTING);

        if (request != null && request.getDurationSeconds() != null) {
            LocalDateTime deadline = LocalDateTime.now().plusSeconds(request.getDurationSeconds());
            motion.setVoteDeadline(deadline);
        }

        motion = motionRepository.save(motion);
        log.info("議案投票開始: motionId={}, sessionId={}", motionId, session.getId());
        return mapper.toMotionResponse(motion);
    }

    /**
     * 議案の投票を終了する（MEETING モード。VOTING → VOTED）。利用者起点の public 入口。
     *
     * @param motionId      議案 ID
     * @param currentUserId 操作ユーザー（セッション作成者またはスコープ ADMIN のみ許可）
     */
    @Transactional
    public EndVoteResponse endVote(Long motionId, Long currentUserId) {
        ProxyVoteMotionEntity motion = sessionService.findMotionOrThrow(motionId);
        ProxyVoteSessionEntity session = sessionService.findSessionOrThrow(motion.getSessionId());
        // 認可: 作成者またはスコープ管理者のみ投票終了可（entity 由来スコープで BOLA 防止）。
        accessControlService.checkOwnerOrAdmin(currentUserId, session.getCreatedBy(),
                session.resolveScopeId(), session.scopeTypeName());
        return endVoteInternal(motion, session, motionId);
    }

    /**
     * 投票タイマー満了によるシステム起点の投票終了（{@code ProxyVoteScheduledService} 専用）。
     *
     * <p>実行ユーザーが存在しないバッチ経路のため認可チェックを行わない。
     * <b>利用者リクエストから呼び出してはならない</b>（必ず {@link #endVote(Long, Long)} を使うこと）。</p>
     */
    @Transactional
    public EndVoteResponse endVoteBySystem(Long motionId) {
        ProxyVoteMotionEntity motion = sessionService.findMotionOrThrow(motionId);
        ProxyVoteSessionEntity session = sessionService.findSessionOrThrow(motion.getSessionId());
        return endVoteInternal(motion, session, motionId);
    }

    /**
     * 投票終了の中核処理（認可判定は呼び出し元の責務）。
     */
    private EndVoteResponse endVoteInternal(ProxyVoteMotionEntity motion,
                                             ProxyVoteSessionEntity session, Long motionId) {
        if (session.getResolutionMode() != ResolutionMode.MEETING) {
            throw new BusinessException(ProxyVoteErrorCode.MEETING_MODE_ONLY);
        }
        if (motion.getVotingStatus() != VotingStatus.VOTING) {
            throw new BusinessException(ProxyVoteErrorCode.MOTION_NOT_VOTING);
        }

        // 委任票を加算（代理人の投票内容を委任者に適用）
        List<ProxyDelegationEntity> acceptedDelegations =
                delegationRepository.findBySessionIdAndStatus(session.getId(), DelegationStatus.ACCEPTED);
        for (ProxyDelegationEntity delegation : acceptedDelegations) {
            if (voteRepository.existsByMotionIdAndUserId(motionId, delegation.getDelegatorId())) {
                continue; // 既に代理投票済み or 本人投票済み
            }
            Long delegateId = delegation.getDelegateId();
            if (delegateId != null) {
                // 代理人の投票を取得
                voteRepository.findByMotionIdAndUserId(motionId, delegateId).ifPresent(delegateVote -> {
                    ProxyVoteEntity proxyVote = ProxyVoteEntity.builder()
                            .motionId(motionId)
                            .userId(delegation.getDelegatorId())
                            .voteType(delegateVote.getVoteType())
                            .isProxyVote(true)
                            .delegationId(delegation.getId())
                            .votedAt(LocalDateTime.now())
                            .build();
                    voteRepository.save(proxyVote);
                    motion.incrementVoteCount(delegateVote.getVoteType());
                });
                // 代理人が未投票の場合は棄権扱い
                if (!voteRepository.existsByMotionIdAndUserId(motionId, delegation.getDelegatorId())) {
                    ProxyVoteEntity abstainVote = ProxyVoteEntity.builder()
                            .motionId(motionId)
                            .userId(delegation.getDelegatorId())
                            .voteType(VoteType.ABSTAIN)
                            .isProxyVote(true)
                            .delegationId(delegation.getId())
                            .votedAt(LocalDateTime.now())
                            .build();
                    voteRepository.save(abstainVote);
                    motion.incrementVoteCount(VoteType.ABSTAIN);
                }
            } else {
                // 白紙委任: 棄権扱い
                ProxyVoteEntity abstainVote = ProxyVoteEntity.builder()
                        .motionId(motionId)
                        .userId(delegation.getDelegatorId())
                        .voteType(VoteType.ABSTAIN)
                        .isProxyVote(true)
                        .delegationId(delegation.getId())
                        .votedAt(LocalDateTime.now())
                        .build();
                voteRepository.save(abstainVote);
                motion.incrementVoteCount(VoteType.ABSTAIN);
            }
        }

        // 未投票の出席者は棄権扱い（eligible メンバー一覧からの差分計算は将来対応）

        motion.changeVotingStatus(VotingStatus.VOTED);
        MotionResult result = sessionService.judgeMotionResult(motion);
        motion.setResult(result);
        motionRepository.save(motion);

        int total = motion.getApproveCount() + motion.getRejectCount() + motion.getAbstainCount();
        BigDecimal approveRate = total > 0
                ? BigDecimal.valueOf(motion.getApproveCount() * 100.0 / total).setScale(1, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        log.info("議案投票終了: motionId={}, result={}", motionId, result);
        return EndVoteResponse.builder()
                .motionId(motionId)
                .votingStatus(VotingStatus.VOTED.name())
                .result(result.name())
                .approveCount(motion.getApproveCount())
                .rejectCount(motion.getRejectCount())
                .abstainCount(motion.getAbstainCount())
                .approveRate(approveRate)
                .totalVotes(total)
                .build();
    }

    /**
     * 全議案の一括投票開始（MEETING モード）。
     */
    @Transactional
    public SessionResponse startAllVotes(Long sessionId, Long currentUserId) {
        ProxyVoteSessionEntity session = sessionService.findSessionOrThrow(sessionId);
        // 認可: 作成者またはスコープ管理者のみ一括投票開始可（entity 由来スコープで BOLA 防止）。
        // 従来は末尾の getSession() が副次的に checkMembership を行うのみで、書込後に一般会員でも
        // 通過し得た。個別の startVote/endVote と同じ粒度（checkOwnerOrAdmin）へ引き上げる。
        accessControlService.checkOwnerOrAdmin(currentUserId, session.getCreatedBy(),
                session.resolveScopeId(), session.scopeTypeName());

        if (session.getResolutionMode() != ResolutionMode.MEETING) {
            throw new BusinessException(ProxyVoteErrorCode.MEETING_MODE_ONLY);
        }
        if (session.getStatus() != SessionStatus.OPEN) {
            throw new BusinessException(ProxyVoteErrorCode.STATUS_MUST_BE_OPEN);
        }

        long pendingCount = motionRepository.countBySessionIdAndVotingStatus(sessionId, VotingStatus.PENDING);
        if (pendingCount == 0) {
            throw new BusinessException(ProxyVoteErrorCode.NO_PENDING_MOTIONS);
        }

        List<ProxyVoteMotionEntity> motions = motionRepository.findBySessionIdOrderByMotionNumberAsc(sessionId);
        motions.stream()
                .filter(m -> m.getVotingStatus() == VotingStatus.PENDING)
                .forEach(m -> m.changeVotingStatus(VotingStatus.VOTING));
        motionRepository.saveAll(motions);

        log.info("全議案一括投票開始: sessionId={}", sessionId);
        return sessionService.getSession(sessionId, currentUserId);
    }
}
