package com.mannschaft.app.joinrequest.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.joinrequest.JoinRequestErrorCode;
import com.mannschaft.app.joinrequest.dto.JoinRequestCreateRequest;
import com.mannschaft.app.joinrequest.dto.JoinRequestResponse;
import com.mannschaft.app.joinrequest.dto.JoinRequestReviewRequest;
import com.mannschaft.app.joinrequest.entity.JoinRequestEntity;
import com.mannschaft.app.joinrequest.entity.JoinRequestStatus;
import com.mannschaft.app.joinrequest.event.JoinRequestCreatedEvent;
import com.mannschaft.app.joinrequest.event.JoinRequestReviewedEvent;
import com.mannschaft.app.joinrequest.repository.JoinRequestRepository;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.role.service.MembershipGrantService;
import com.mannschaft.app.team.service.TeamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 柱③-A「MEMBER 参加申請（join request）」サービス（CMP-260901-1538）。
 *
 * <p>PUBLIC な TEAM/ORGANIZATION（{@code lifecycle_status = ACTIVE} のみ）への MEMBER としての
 * 参加申請の受付・審査（承認/却下）を担う。金型: {@code VillageJoinRequestService}
 * （F17.1 Phase 1 B6）。</p>
 *
 * <p><strong>存在秘匿（IDOR/情報漏洩対策）:</strong> スコープが不存在・PRIVATE・PROVISIONED・
 * アーカイブ済みのいずれであっても同一の {@link JoinRequestErrorCode#SCOPE_NOT_FOUND} を返す
 * （{@code VillageAccessGate} と同じ流儀。判定順序は不存在 → 可視性 → PROVISIONED/アーカイブ の順）。</p>
 *
 * <p><strong>承認時のメンバーシップ付与:</strong> 招待承諾（{@code InviteService#joinByInvite}）と
 * 同一の {@link MembershipGrantService#grantRole} を経由する（重複実装しない・障害対応の原則）。
 * 付与ロールは常に MEMBER 固定（ADMIN_CLAIM 相当の管理者申立は対象外）。</p>
 *
 * <p><strong>通知の TX 境界:</strong> 申請受理・承認・却下の各メソッドは業務トランザクションの内側で
 * イベントを publish するだけに留め、実配送は {@code JoinRequestNotificationListener}
 * （{@code AFTER_COMMIT}）側で行う（通知のトランザクション境界番人対応）。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JoinRequestService {

    private static final String SCOPE_TEAM = "TEAM";
    private static final String SCOPE_ORGANIZATION = "ORGANIZATION";
    private static final Set<String> VALID_SCOPE_TYPES = Set.of(SCOPE_TEAM, SCOPE_ORGANIZATION);
    private static final String GRANT_SOURCE = "JOIN_REQUEST";

    private final JoinRequestRepository joinRequestRepository;
    private final TeamService teamService;
    private final OrganizationService organizationService;
    private final AccessControlService accessControlService;
    private final MembershipGrantService membershipGrantService;
    private final ApplicationEventPublisher eventPublisher;

    // ========================================================================
    // 申請作成
    // ========================================================================

    /**
     * PUBLIC な ACTIVE スコープへ MEMBER 参加申請を行う。
     *
     * <ul>
     *   <li>スコープが不存在/PRIVATE/PROVISIONED/アーカイブ済みなら {@code SCOPE_NOT_FOUND}（404・存在秘匿）</li>
     *   <li>既にメンバーなら {@code ALREADY_MEMBER}（409）</li>
     *   <li>同一申請者の PENDING 申請が既にあれば、新規作成せず既存の申請をそのまま返す（冪等）</li>
     * </ul>
     */
    @Transactional
    public JoinRequestResponse createRequest(String scopeType, Long scopeId, Long actorUserId,
                                              JoinRequestCreateRequest request) {
        String normalizedScopeType = validateScopeType(scopeType);
        ScopeJoinability joinability = loadJoinableScope(normalizedScopeType, scopeId);

        if (accessControlService.isMember(actorUserId, scopeId, normalizedScopeType)) {
            throw new BusinessException(JoinRequestErrorCode.ALREADY_MEMBER, HttpStatus.CONFLICT);
        }

        Optional<JoinRequestEntity> existingPending = findPending(normalizedScopeType, scopeId, actorUserId);
        if (existingPending.isPresent()) {
            // 冪等: PENDING 中の再申請は新規作成せず同一申請を返す。
            log.info("参加申請の冪等応答（PENDING 既存）: scopeType={}, scopeId={}, requesterUserId={}",
                    normalizedScopeType, scopeId, actorUserId);
            return JoinRequestResponse.from(existingPending.get());
        }

        var builder = JoinRequestEntity.builder()
                .requesterUserId(actorUserId)
                .message(request != null ? request.message() : null)
                .status(JoinRequestStatus.PENDING);
        if (SCOPE_TEAM.equals(normalizedScopeType)) {
            builder.teamId(scopeId);
        } else {
            builder.organizationId(scopeId);
        }

        JoinRequestEntity saved;
        try {
            // saveAndFlush で INSERT を即時実行し、UNIQUE 制約違反をこの try 節内で検出する
            // （UUIDv7 は assigned generator のため save() だけでは flush が遅延し得る）。
            saved = joinRequestRepository.saveAndFlush(builder.build());
        } catch (DataIntegrityViolationException ex) {
            // 競合: 直前の findPending と save の間に他リクエストが同じ PENDING 行を先に
            // 作成した（TEAM/ORGANIZATION いずれの UNIQUE 制約でも起こり得る）。
            // MySQL は UNIQUE 制約違反だけではトランザクションを中断しないため、
            // 同一トランザクション内で再照会し、既存 PENDING 行へ冪等応答する（二重防御）。
            Optional<JoinRequestEntity> raceWinner = findPending(normalizedScopeType, scopeId, actorUserId);
            if (raceWinner.isPresent()) {
                log.info("参加申請の冪等応答（UNIQUE制約競合からの復旧）: scopeType={}, scopeId={}, requesterUserId={}",
                        normalizedScopeType, scopeId, actorUserId);
                return JoinRequestResponse.from(raceWinner.get());
            }
            throw ex;
        }

        eventPublisher.publishEvent(new JoinRequestCreatedEvent(
                saved.getId(), normalizedScopeType, scopeId, joinability.name(), actorUserId));

        log.info("参加申請を受理: id={}, scopeType={}, scopeId={}, requesterUserId={}",
                saved.getId(), normalizedScopeType, scopeId, actorUserId);
        return JoinRequestResponse.from(saved);
    }

    // ========================================================================
    // 一覧（ADMIN/DEPUTY_ADMIN 向け）
    // ========================================================================

    /**
     * スコープの参加申請一覧を取得する。ADMIN/DEPUTY_ADMIN のみ閲覧可。
     */
    @Transactional(readOnly = true)
    public Page<JoinRequestResponse> listForReviewers(String scopeType, Long scopeId, Long actorUserId,
                                                       JoinRequestStatus status, int page, int size) {
        String normalizedScopeType = validateScopeType(scopeType);
        loadJoinableScope(normalizedScopeType, scopeId);
        accessControlService.checkAdminOrAbove(actorUserId, scopeId, normalizedScopeType);

        JoinRequestStatus targetStatus = status != null ? status : JoinRequestStatus.PENDING;
        Pageable pageable = PageRequest.of(page, size);
        Page<JoinRequestEntity> result = SCOPE_TEAM.equals(normalizedScopeType)
                ? joinRequestRepository.findByTeamIdAndStatus(scopeId, targetStatus, pageable)
                : joinRequestRepository.findByOrganizationIdAndStatus(scopeId, targetStatus, pageable);
        return result.map(JoinRequestResponse::from);
    }

    // ========================================================================
    // 自分の申請（申請者向け）
    // ========================================================================

    /**
     * 操作者自身が出した参加申請の履歴を取得する（新しい順）。
     *
     * <p>IDOR 閉塞: 「誰の申請を返すか」を引数の {@code actorUserId}（認証済みユーザー）だけで
     * 決める。他人の申請を要求する余地が構造的に存在しない（金型: {@code VillageJoinRequestService#listMine}）。</p>
     */
    @Transactional(readOnly = true)
    public List<JoinRequestResponse> listMine(String scopeType, Long scopeId, Long actorUserId) {
        String normalizedScopeType = validateScopeType(scopeType);
        loadJoinableScope(normalizedScopeType, scopeId);

        List<JoinRequestEntity> requests = SCOPE_TEAM.equals(normalizedScopeType)
                ? joinRequestRepository.findByTeamIdAndRequesterUserIdOrderByCreatedAtDesc(scopeId, actorUserId)
                : joinRequestRepository.findByOrganizationIdAndRequesterUserIdOrderByCreatedAtDesc(scopeId, actorUserId);
        return requests.stream().map(JoinRequestResponse::from).toList();
    }

    // ========================================================================
    // 承認
    // ========================================================================

    /**
     * 参加申請を承認する。ADMIN/DEPUTY_ADMIN のみ実行可。
     *
     * <ul>
     *   <li>PENDING でない場合は {@code ALREADY_REVIEWED}（409）</li>
     *   <li>承認と同時に MEMBER ロール付与＋membership 入会（招待承諾と共通の
     *       {@link MembershipGrantService#grantRole} 経由）</li>
     *   <li>レビュー時に既に他経路でメンバーになっていれば {@code ALREADY_MEMBER}（409）</li>
     * </ul>
     */
    @Transactional
    public JoinRequestResponse approve(String scopeType, Long scopeId, UUID requestId, Long actorUserId,
                                        JoinRequestReviewRequest review) {
        String normalizedScopeType = validateScopeType(scopeType);
        ScopeJoinability joinability = loadJoinableScope(normalizedScopeType, scopeId);
        accessControlService.checkAdminOrAbove(actorUserId, scopeId, normalizedScopeType);

        JoinRequestEntity req = lockRequestForScope(normalizedScopeType, scopeId, requestId);
        ensurePending(req);

        if (accessControlService.isMember(req.getRequesterUserId(), scopeId, normalizedScopeType)) {
            throw new BusinessException(JoinRequestErrorCode.ALREADY_MEMBER, HttpStatus.CONFLICT);
        }

        membershipGrantService.grantMemberRole(
                normalizedScopeType, scopeId, req.getRequesterUserId(), actorUserId, GRANT_SOURCE);

        req.setStatus(JoinRequestStatus.APPROVED);
        req.setReviewerUserId(actorUserId);
        req.setReviewedAt(Instant.now());
        req.setReviewComment(review != null ? review.reviewComment() : null);
        JoinRequestEntity saved = joinRequestRepository.save(req);

        eventPublisher.publishEvent(new JoinRequestReviewedEvent(
                saved.getId(), normalizedScopeType, scopeId, joinability.name(),
                saved.getRequesterUserId(), true, saved.getReviewComment()));

        log.info("参加申請を承認: id={}, scopeType={}, scopeId={}, reviewer={}",
                requestId, normalizedScopeType, scopeId, actorUserId);
        return JoinRequestResponse.from(saved);
    }

    // ========================================================================
    // 却下
    // ========================================================================

    /**
     * 参加申請を却下する。ADMIN/DEPUTY_ADMIN のみ実行可。
     */
    @Transactional
    public JoinRequestResponse reject(String scopeType, Long scopeId, UUID requestId, Long actorUserId,
                                       JoinRequestReviewRequest review) {
        String normalizedScopeType = validateScopeType(scopeType);
        ScopeJoinability joinability = loadJoinableScope(normalizedScopeType, scopeId);
        accessControlService.checkAdminOrAbove(actorUserId, scopeId, normalizedScopeType);

        JoinRequestEntity req = lockRequestForScope(normalizedScopeType, scopeId, requestId);
        ensurePending(req);

        req.setStatus(JoinRequestStatus.REJECTED);
        req.setReviewerUserId(actorUserId);
        req.setReviewedAt(Instant.now());
        req.setReviewComment(review != null ? review.reviewComment() : null);
        JoinRequestEntity saved = joinRequestRepository.save(req);

        eventPublisher.publishEvent(new JoinRequestReviewedEvent(
                saved.getId(), normalizedScopeType, scopeId, joinability.name(),
                saved.getRequesterUserId(), false, saved.getReviewComment()));

        log.info("参加申請を却下: id={}, scopeType={}, scopeId={}, reviewer={}",
                requestId, normalizedScopeType, scopeId, actorUserId);
        return JoinRequestResponse.from(saved);
    }

    // ========================================================================
    // 共通ヘルパー
    // ========================================================================

    private String validateScopeType(String scopeType) {
        String normalized = scopeType == null ? null : scopeType.toUpperCase();
        if (!VALID_SCOPE_TYPES.contains(normalized)) {
            throw new BusinessException(JoinRequestErrorCode.INVALID_SCOPE_TYPE, HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    /**
     * PUBLIC かつ ACTIVE（非 PROVISIONED）かつ非アーカイブのスコープのみ通す。
     * それ以外（不存在含む）は同一の 404 に畳んで存在を秘匿する。
     */
    private ScopeJoinability loadJoinableScope(String scopeType, Long scopeId) {
        if (scopeId == null) {
            throw new BusinessException(JoinRequestErrorCode.SCOPE_NOT_FOUND, HttpStatus.NOT_FOUND);
        }
        if (SCOPE_TEAM.equals(scopeType)) {
            TeamService.JoinabilitySummary summary = teamService.findJoinabilitySummary(scopeId)
                    .orElseThrow(() -> new BusinessException(JoinRequestErrorCode.SCOPE_NOT_FOUND, HttpStatus.NOT_FOUND));
            if (!summary.joinable()) {
                throw new BusinessException(JoinRequestErrorCode.SCOPE_NOT_FOUND, HttpStatus.NOT_FOUND);
            }
            return new ScopeJoinability(summary.name());
        }
        OrganizationService.JoinabilitySummary summary = organizationService.findJoinabilitySummary(scopeId)
                .orElseThrow(() -> new BusinessException(JoinRequestErrorCode.SCOPE_NOT_FOUND, HttpStatus.NOT_FOUND));
        if (!summary.joinable()) {
            throw new BusinessException(JoinRequestErrorCode.SCOPE_NOT_FOUND, HttpStatus.NOT_FOUND);
        }
        return new ScopeJoinability(summary.name());
    }

    private Optional<JoinRequestEntity> findPending(String scopeType, Long scopeId, Long requesterUserId) {
        return SCOPE_TEAM.equals(scopeType)
                ? joinRequestRepository.findByTeamIdAndRequesterUserIdAndStatus(
                        scopeId, requesterUserId, JoinRequestStatus.PENDING)
                : joinRequestRepository.findByOrganizationIdAndRequesterUserIdAndStatus(
                        scopeId, requesterUserId, JoinRequestStatus.PENDING);
    }

    /**
     * 申請を悲観ロック付きで取得し scope が一致することを確認する
     * （IDOR 対策・不一致は不在と同一コード）。
     *
     * <p>approve/reject の直列化（レビューP1-2）: {@code findByIdForUpdate} で行ロックを取得後に
     * 呼び出し元が {@link #ensurePending} で PENDING 状態を再確認することで、同時 approve/reject が
     * 双方とも PENDING を確認してしまう競合状態を防ぐ。片方がロックを取得している間、もう片方は
     * ロック解放（コミット）を待ってから読み直すため、後着は必ず更新後の状態を見る。</p>
     */
    private JoinRequestEntity lockRequestForScope(String scopeType, Long scopeId, UUID requestId) {
        JoinRequestEntity req = joinRequestRepository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new BusinessException(JoinRequestErrorCode.REQUEST_NOT_FOUND, HttpStatus.NOT_FOUND));
        Long reqScopeId = SCOPE_TEAM.equals(scopeType) ? req.getTeamId() : req.getOrganizationId();
        if (reqScopeId == null || !reqScopeId.equals(scopeId)) {
            throw new BusinessException(JoinRequestErrorCode.REQUEST_NOT_FOUND, HttpStatus.NOT_FOUND);
        }
        return req;
    }

    private void ensurePending(JoinRequestEntity req) {
        if (req.getStatus() != JoinRequestStatus.PENDING) {
            throw new BusinessException(JoinRequestErrorCode.ALREADY_REVIEWED, HttpStatus.CONFLICT);
        }
    }

    private record ScopeJoinability(String name) {
    }
}
