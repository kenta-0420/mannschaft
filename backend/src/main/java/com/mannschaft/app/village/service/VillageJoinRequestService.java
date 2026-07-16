package com.mannschaft.app.village.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.JoinRequestCreateRequest;
import com.mannschaft.app.village.dto.JoinRequestResponse;
import com.mannschaft.app.village.dto.JoinRequestReviewRequest;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageJoinRequestEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageRequestStatus;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.repository.VillageJoinRequestRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * F17.1 Phase 1 B6 — 村参加申請 Service（APPROVAL 村のみ）。
 *
 * <p>担当範囲（出陣指示書 §B6 / 設計書 §4.4.4）:</p>
 * <ul>
 *   <li>申請作成: USER は自分のみ、TEAM/ORG は代表権限必要</li>
 *   <li>申請一覧: 村長/長老用（B7 以降で UI 接続）</li>
 *   <li>承認: PENDING → APPROVED + メンバーシップ作成</li>
 *   <li>拒否: PENDING → REJECTED + コメント必須</li>
 *   <li>取下げ: 申請者本人のみ PENDING → WITHDRAWN</li>
 * </ul>
 *
 * <p>アーキテクチャ原則の遵守:</p>
 * <ul>
 *   <li>原則 5: {@code @Transactional} は village ドメイン内のみ（VillageRepository /
 *       VillageJoinRequestRepository / VillageMembershipRepository）。</li>
 *   <li>{@link VillageMembershipService} を DI して subject の代表権限検証を委譲する（
 *       検証ロジックの一元化）。B3 の {@code join} は FREE 村専用のため、承認時の
 *       実メンバー登録は本 Service 内で {@link VillageMembershipRepository#save} を直接呼ぶ
 *       （B5 と同じ村ドメイン内パターン）。</li>
 *   <li>審査権限（HEADMAN / ELDER）は本 Service 内で {@link VillageMembershipRepository}
 *       を直接参照して判定する（クロスドメインなし）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VillageJoinRequestService {

    private final VillageJoinRequestRepository joinRequestRepository;
    private final VillageRepository villageRepository;
    private final VillageMembershipRepository membershipRepository;
    /** 代表権限検証ロジックを委譲する（B3 既存メソッド再利用）。 */
    private final VillageMembershipService membershipService;

    // ========================================================================
    // 4.4.4 申請作成
    // ========================================================================

    /**
     * APPROVAL 村への参加申請を作成する。
     *
     * <ul>
     *   <li>FREE 村への申請は VILLAGE_041 で拒否（直接参加 API を使うべき）</li>
     *   <li>既に現役メンバーなら VILLAGE_006</li>
     *   <li>同一主体の PENDING 申請が既にあれば VILLAGE_039</li>
     * </ul>
     */
    @Transactional
    public JoinRequestResponse createRequest(UUID villageId,
                                             Long actorUserId,
                                             JoinRequestCreateRequest request) {
        if (actorUserId == null) {
            throw new BusinessException(CommonErrorCode.COMMON_000);
        }

        VillageEntity village = loadActiveVillage(villageId);

        // FREE 村は申請不要。直接参加 API を使うべき
        if (village.getJoinPolicy() == VillageJoinPolicy.FREE) {
            throw new BusinessException(VillageErrorCode.VILLAGE_FREE_VILLAGE_DIRECT_JOIN);
        }

        // 主体権限検証（B3 と同じロジックを委譲）
        membershipService.validateSubjectAuthorization(actorUserId, request.subjectType(), request.subjectId());

        // 既に現役メンバーか
        membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                        villageId, request.subjectType(), request.subjectId())
                .ifPresent(m -> {
                    if (m.getBannedAt() != null) {
                        throw new BusinessException(VillageErrorCode.MEMBER_BANNED);
                    }
                    throw new BusinessException(VillageErrorCode.ALREADY_MEMBER);
                });

        // 同一主体の PENDING 申請が既にあるか
        joinRequestRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndStatus(
                        villageId, request.subjectType(), request.subjectId(), VillageRequestStatus.PENDING)
                .ifPresent(r -> {
                    throw new BusinessException(VillageErrorCode.VILLAGE_JOIN_REQUEST_PENDING_DUPLICATE);
                });

        VillageJoinRequestEntity entity = VillageJoinRequestEntity.builder()
                .villageId(villageId)
                .subjectType(request.subjectType())
                .subjectId(request.subjectId())
                .requesterUserId(actorUserId)
                .message(request.message())
                .status(VillageRequestStatus.PENDING)
                .build();
        VillageJoinRequestEntity saved = joinRequestRepository.save(entity);
        log.info("村参加申請を受理: villageId={}, requestId={}, subjectType={}, subjectId={}, requester={}",
                villageId, saved.getId(), saved.getSubjectType(), saved.getSubjectId(), actorUserId);
        return JoinRequestResponse.from(saved);
    }

    // ========================================================================
    // 4.4.4 申請一覧（村長/長老向け）
    // ========================================================================

    /**
     * 村の参加申請一覧を取得する。村長または長老のみ閲覧可。
     */
    @Transactional(readOnly = true)
    public Page<JoinRequestResponse> listForReviewers(UUID villageId,
                                                     Long actorUserId,
                                                     VillageRequestStatus status,
                                                     int page,
                                                     int size) {
        loadActiveVillage(villageId);
        ensureReviewer(villageId, actorUserId);

        VillageRequestStatus targetStatus = status != null ? status : VillageRequestStatus.PENDING;
        Pageable pageable = PageRequest.of(page, size);
        Page<VillageJoinRequestEntity> p =
                joinRequestRepository.findByVillageIdAndStatus(villageId, targetStatus, pageable);
        return p.map(JoinRequestResponse::from);
    }

    // ========================================================================
    // 4.4.4 自分の申請一覧（申請者向け）
    // ========================================================================

    /**
     * 操作者自身が出した参加申請の履歴を取得する（新しい順）。
     *
     * <h3>なぜ審査者向け一覧と別 EP なのか</h3>
     * <p>{@link #listForReviewers} は {@link #ensureReviewer} により HEADMAN/ELDER 限定である。
     * 一方、申請者は<b>定義上まだ村の非メンバー</b>であり、審査者一覧を開放するわけにはいかない。
     * そのため「自分の申請だけ」を返す専用 EP を設ける。</p>
     *
     * <h3>認可（IDOR 閉塞）</h3>
     * <p>本メソッドは「誰の申請を返すか」を引数の {@code actorUserId}（= Controller が
     * {@code SecurityUtils.getCurrentUserId()} から解決した認証済みユーザー）だけで決める。
     * リポジトリ側で {@code requesterUserId} を絞り込むため、<b>他人の行はそもそも読まない</b>。
     * 「取得してから所有者を検証する」方式は検証漏れがそのまま漏洩になるため採らない。
     * 独自の認可述語も新設しない（絞り込みキーは取下げの認可条件と同一）。</p>
     *
     * <h3>メンバーシップ判定を行わない理由</h3>
     * <p>申請者は承認されるまで非メンバーである。ここでメンバーシップを要求すると
     * 「申請中は自分の申請を見られない」という矛盾が生じるため、メンバーシップ層には触れない。</p>
     *
     * @param villageId   対象の村
     * @param actorUserId 認証済みユーザー ID（クライアント指定値を渡してはならない）
     * @return 自分の申請（0 件なら空リスト。「申請が無い」は 404 ではない）
     */
    @Transactional(readOnly = true)
    public List<JoinRequestResponse> listMine(UUID villageId, Long actorUserId) {
        if (actorUserId == null) {
            throw new BusinessException(CommonErrorCode.COMMON_000);
        }
        // 村の存在・凍結チェックは本 Service の他メソッドと同一の流儀に揃える
        loadActiveVillage(villageId);

        return joinRequestRepository
                .findByVillageIdAndRequesterUserIdOrderByCreatedAtDesc(villageId, actorUserId)
                .stream()
                .map(JoinRequestResponse::from)
                .toList();
    }

    // ========================================================================
    // 4.4.4 承認
    // ========================================================================

    /**
     * 参加申請を承認する。
     *
     * <ul>
     *   <li>村長または長老のみ実行可</li>
     *   <li>PENDING でない場合は VILLAGE_040</li>
     *   <li>承認と同時にメンバーシップを作成（VILLAGER として）</li>
     *   <li>レビュー時に既に他経路でメンバーになっていれば VILLAGE_006</li>
     * </ul>
     */
    @Transactional
    public JoinRequestResponse approve(UUID villageId,
                                       UUID requestId,
                                       Long actorUserId,
                                       JoinRequestReviewRequest review) {
        loadActiveVillage(villageId);
        VillageMembershipEntity reviewer = ensureReviewer(villageId, actorUserId);

        VillageJoinRequestEntity req = loadRequestForVillage(villageId, requestId);
        ensurePending(req);

        // 承認時にも membership 重複ガード（申請受付〜承認の間に直接参加された可能性）
        membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                        villageId, req.getSubjectType(), req.getSubjectId())
                .ifPresent(m -> {
                    if (m.getBannedAt() != null) {
                        throw new BusinessException(VillageErrorCode.MEMBER_BANNED);
                    }
                    throw new BusinessException(VillageErrorCode.ALREADY_MEMBER);
                });

        // メンバーシップ作成（村ドメイン内で完結）
        VillageMembershipEntity membership = VillageMembershipEntity.builder()
                .villageId(villageId)
                .subjectType(req.getSubjectType())
                .subjectId(req.getSubjectId())
                .role(VillageRole.VILLAGER)
                .joinedAt(LocalDateTime.now())
                .invitedByMembershipId(reviewer.getId())
                .build();
        membershipRepository.save(membership);

        // 申請を APPROVED に更新
        LocalDateTime now = LocalDateTime.now();
        req.setStatus(VillageRequestStatus.APPROVED);
        req.setReviewerMembershipId(reviewer.getId());
        req.setReviewedAt(now);
        req.setReviewComment(review != null ? review.reviewComment() : null);
        VillageJoinRequestEntity saved = joinRequestRepository.save(req);
        log.info("村参加申請を承認: villageId={}, requestId={}, reviewer={}, newMembershipId={}",
                villageId, requestId, reviewer.getId(), membership.getId());
        return JoinRequestResponse.from(saved);
    }

    // ========================================================================
    // 4.4.4 拒否
    // ========================================================================

    /**
     * 参加申請を拒否する。村長または長老のみ。{@code reviewComment} 必須。
     */
    @Transactional
    public JoinRequestResponse reject(UUID villageId,
                                      UUID requestId,
                                      Long actorUserId,
                                      JoinRequestReviewRequest review) {
        if (review == null || review.reviewComment() == null || review.reviewComment().isBlank()) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }
        loadActiveVillage(villageId);
        VillageMembershipEntity reviewer = ensureReviewer(villageId, actorUserId);

        VillageJoinRequestEntity req = loadRequestForVillage(villageId, requestId);
        ensurePending(req);

        req.setStatus(VillageRequestStatus.REJECTED);
        req.setReviewerMembershipId(reviewer.getId());
        req.setReviewedAt(LocalDateTime.now());
        req.setReviewComment(review.reviewComment());
        VillageJoinRequestEntity saved = joinRequestRepository.save(req);
        log.info("村参加申請を拒否: villageId={}, requestId={}, reviewer={}",
                villageId, requestId, reviewer.getId());
        return JoinRequestResponse.from(saved);
    }

    // ========================================================================
    // 4.4.4 取下げ
    // ========================================================================

    /**
     * 申請を取り下げる。申請者本人のみ実行可。
     *
     * <ul>
     *   <li>PENDING でない場合は VILLAGE_040</li>
     *   <li>申請者以外が実行した場合は COMMON_002 (403)</li>
     * </ul>
     */
    @Transactional
    public JoinRequestResponse withdraw(UUID villageId, UUID requestId, Long actorUserId) {
        loadActiveVillage(villageId);

        VillageJoinRequestEntity req = loadRequestForVillage(villageId, requestId);

        if (!req.getRequesterUserId().equals(actorUserId)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }

        ensurePending(req);

        req.setStatus(VillageRequestStatus.WITHDRAWN);
        req.setReviewedAt(LocalDateTime.now());
        VillageJoinRequestEntity saved = joinRequestRepository.save(req);
        log.info("村参加申請を取り下げ: villageId={}, requestId={}, actor={}",
                villageId, requestId, actorUserId);
        return JoinRequestResponse.from(saved);
    }

    // ========================================================================
    // 共通ヘルパ
    // ========================================================================

    /** 有効な村を取得する（削除/凍結済みは VILLAGE_001 で扱う）。 */
    private VillageEntity loadActiveVillage(UUID villageId) {
        VillageEntity v = villageRepository.findById(villageId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.VILLAGE_NOT_FOUND));
        if (v.getDeletedAt() != null) {
            throw new BusinessException(VillageErrorCode.VILLAGE_NOT_FOUND);
        }
        if (v.getArchivedAt() != null) {
            throw new BusinessException(VillageErrorCode.VILLAGE_ALREADY_ARCHIVED);
        }
        return v;
    }

    /**
     * 申請を取得し、villageId が一致することを確認する（IDOR 対策）。
     */
    private VillageJoinRequestEntity loadRequestForVillage(UUID villageId, UUID requestId) {
        VillageJoinRequestEntity req = joinRequestRepository.findById(requestId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.VILLAGE_JOIN_REQUEST_NOT_FOUND));
        if (!req.getVillageId().equals(villageId)) {
            // パス villageId とレコードの villageId 不一致は不存在扱い（IDOR）
            throw new BusinessException(VillageErrorCode.VILLAGE_JOIN_REQUEST_NOT_FOUND);
        }
        return req;
    }

    /**
     * 操作者が<strong>現役</strong>の村長または長老（審査権限保持者）であることを検証する。
     *
     * <p>「現役」の判定（退村済み {@code leftAt} / BAN 済み {@code bannedAt} の除外）は
     * {@code findActiveByVillageIdAndSubject} のクエリに委譲する（#2284 §12）。
     * 以前は BAN を検査しておらず、BAN された長老が参加申請の審査一覧を閲覧し、
     * 承認・却下まで実行できた（＝BAN されたまま村の門番を続けられた）。</p>
     *
     * @return 操作者のメンバーシップ（{@code reviewer_membership_id} 記録に使用）
     */
    private VillageMembershipEntity ensureReviewer(UUID villageId, Long actorUserId) {
        if (actorUserId == null) {
            throw new BusinessException(CommonErrorCode.COMMON_000);
        }
        VillageMembershipEntity m = membershipRepository
                .findActiveByVillageIdAndSubject(villageId, VillageSubjectType.USER, actorUserId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.MODERATION_FORBIDDEN));
        if (m.getRole() != VillageRole.HEADMAN && m.getRole() != VillageRole.ELDER) {
            throw new BusinessException(VillageErrorCode.MODERATION_FORBIDDEN);
        }
        return m;
    }

    /** PENDING でない申請への審査・取下げ操作を弾く。 */
    private void ensurePending(VillageJoinRequestEntity req) {
        if (req.getStatus() != VillageRequestStatus.PENDING) {
            throw new BusinessException(VillageErrorCode.VILLAGE_JOIN_REQUEST_ALREADY_REVIEWED);
        }
    }
}
