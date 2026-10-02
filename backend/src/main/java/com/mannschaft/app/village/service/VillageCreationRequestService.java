package com.mannschaft.app.village.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.VillageCreationRequestCreateRequest;
import com.mannschaft.app.village.dto.VillageCreationRequestResponse;
import com.mannschaft.app.village.dto.VillageCreationRequestReviewRequest;
import com.mannschaft.app.village.entity.VillageCreationRequestEntity;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageRequestStatus;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.repository.VillageCreationRequestRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * 村作成申請サービス（F17.1 Phase 1 B5）。
 *
 * <p>レートリミット・運営審査・承認時の自動村作成を担う。
 * すべての @Transactional は village ドメイン内（VillageRepository / VillageMembershipRepository /
 * VillageCreationRequestRepository）に閉じている。
 * 申請者が SYSTEM_ADMIN か否かの判定のため {@link UserRoleRepository} を読み取り専用で参照するが、
 * これは権限判定であって write は行わない（CommonErrorCode COMMON_002 と同じ位置づけ）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VillageCreationRequestService {

    /** 1ユーザーあたり直近 24 時間（ローリング）に作成できる申請数の上限。 */
    static final int DAILY_RATE_LIMIT = 3;

    /** ガイドライン同意の有効期間（直近この時間内であること）。 */
    static final long GUIDELINE_AGREED_WITHIN_HOURS = 1L;

    private final VillageCreationRequestRepository requestRepository;
    private final VillageRepository villageRepository;
    private final VillageMembershipRepository membershipRepository;
    private final UserRoleRepository userRoleRepository;

    // ------------------------------------------------------------------
    // 申請者向け
    // ------------------------------------------------------------------

    /**
     * 村作成申請を新規作成し、即時自動承認する。
     *
     * <p>申請保存後、同一トランザクション内で village レコードを自動生成し、
     * 申請者を HEADMAN として membership に追加する。
     * これにより申請者は申請完了後すぐに村を利用できる。</p>
     */
    @Transactional
    public VillageCreationRequestResponse createRequest(Long requesterUserId,
                                                        VillageCreationRequestCreateRequest req) {
        if (requesterUserId == null) {
            throw new BusinessException(CommonErrorCode.COMMON_000);
        }

        // 一般ユーザーは OFFICIAL を申請できない
        if (req.type() == VillageType.OFFICIAL && !isSystemAdmin(requesterUserId)) {
            throw new BusinessException(VillageErrorCode.OFFICIAL_VILLAGE_FORBIDDEN);
        }

        // ガイドライン同意が直近1時間以内であること（OffsetDateTime でタイムゾーンを正しく比較）
        OffsetDateTime nowOffset = OffsetDateTime.now(ZoneOffset.UTC);
        if (req.guidelineAgreedAt() == null
                || req.guidelineAgreedAt().isBefore(nowOffset.minusHours(GUIDELINE_AGREED_WITHIN_HOURS))
                || req.guidelineAgreedAt().isAfter(nowOffset.plusMinutes(5))) {
            throw new BusinessException(VillageErrorCode.GUIDELINE_NOT_AGREED);
        }

        // レートリミット: 直近 24 時間（ローリング）で DAILY_RATE_LIMIT 件まで
        LocalDateTime now = LocalDateTime.now();
        long recentCount = requestRepository.countByRequesterUserIdAndCreatedAtAfter(
                requesterUserId, now.minusDays(1));
        if (recentCount >= DAILY_RATE_LIMIT) {
            throw new BusinessException(VillageErrorCode.CREATION_REQUEST_THROTTLED);
        }

        // NOTE: かつて「保有 PENDING 上限（10 件）」チェックがここにあったが削除した。
        //   本メソッドは申請を同一トランザクション内で即時 APPROVED まで進める（下記参照）ため、
        //   PENDING 申請は蓄積されず、当該チェックは構造的に発火し得ない死条件だった。
        //   死条件がエラーメッセージ（VILLAGE_010）に「保有10件まで」という虚偽の説明を持ち込み、
        //   利用者の誤読を招いていたため根治として撤去する。
        //   【回帰リスク】将来「申請 → 後日の手動承認」の 2 段階フローに戻す場合、PENDING が
        //   複数日にわたり蓄積し得るため保有上限が再び意味を持つ。その際は VILLAGE_010 に
        //   再び相乗りさせず、PENDING 保有上限専用のエラーコードと正確な文言を新設すること。

        // slug 衝突（既存村と）
        if (villageRepository.existsBySlug(req.slug())) {
            throw new BusinessException(VillageErrorCode.CREATION_REQUEST_SLUG_TAKEN);
        }

        VillageCreationRequestEntity entity = VillageCreationRequestEntity.builder()
                .requesterUserId(requesterUserId)
                .proposedName(req.name())
                .proposedSlug(req.slug())
                .proposedCategory(req.category())
                .purpose(req.purpose())
                .proposedGuidelineMd(req.guidelineMd())
                .status(VillageRequestStatus.PENDING)
                .build();

        VillageCreationRequestEntity saved = requestRepository.save(entity);
        log.info("村作成申請を受理: requesterUserId={}, requestId={}, slug={}",
                requesterUserId, saved.getId(), saved.getProposedSlug());

        // 申請直後に自動承認 — 同一トランザクション内で村レコードを生成する
        VillageEntity village = VillageEntity.builder()
                .slug(saved.getProposedSlug())
                .name(saved.getProposedName())
                .description(saved.getPurpose())
                .type(VillageType.COMMUNITY)
                .joinPolicy(com.mannschaft.app.village.entity.enums.VillageJoinPolicy.FREE)
                .visibility(com.mannschaft.app.village.entity.enums.VillageVisibility.PUBLIC)
                .category(saved.getProposedCategory())
                .guidelineMd(saved.getProposedGuidelineMd())
                .memberCountCache(1L)
                .createdByUserId(requesterUserId)
                .build();
        VillageEntity savedVillage = villageRepository.save(village);

        // 申請者を HEADMAN として membership に追加
        VillageMembershipEntity membership = VillageMembershipEntity.builder()
                .villageId(savedVillage.getId())
                .subjectType(VillageSubjectType.USER)
                .subjectId(requesterUserId)
                .role(VillageRole.HEADMAN)
                .build();
        membershipRepository.save(membership);

        // 申請を APPROVED に更新
        saved.setStatus(VillageRequestStatus.APPROVED);
        saved.setReviewerUserId(requesterUserId); // 自動承認のため申請者自身を reviewer とする
        saved.setReviewedAt(LocalDateTime.now());
        saved.setReviewComment("自動承認");
        saved.setCreatedVillageId(savedVillage.getId());

        VillageCreationRequestEntity approved = requestRepository.save(saved);
        log.info("村作成申請を自動承認: requestId={}, villageId={}, requesterUserId={}",
                approved.getId(), savedVillage.getId(), requesterUserId);
        return VillageCreationRequestResponse.from(approved);
    }

    /**
     * 自分の村作成申請一覧。新しい順。
     */
    @Transactional(readOnly = true)
    public List<VillageCreationRequestResponse> listMine(Long requesterUserId) {
        return requestRepository.findByRequesterUserIdOrderByCreatedAtDesc(requesterUserId).stream()
                .map(VillageCreationRequestResponse::from)
                .toList();
    }

    // ------------------------------------------------------------------
    // 運営向け
    // ------------------------------------------------------------------

    /**
     * 運営用一覧。status null は全件。
     */
    @Transactional(readOnly = true)
    public Page<VillageCreationRequestResponse> listForAdmin(VillageRequestStatus status, Pageable pageable) {
        Page<VillageCreationRequestEntity> page = (status == null)
                ? requestRepository.findAll(pageable)
                : requestRepository.findByStatus(status, pageable);
        return page.map(VillageCreationRequestResponse::from);
    }

    /**
     * 運営による承認。村レコードを自動作成し、申請者を HEADMAN として membership に追加する。
     */
    @Transactional
    public VillageCreationRequestResponse approve(UUID requestId,
                                                  Long reviewerUserId,
                                                  VillageCreationRequestReviewRequest review) {
        VillageCreationRequestEntity request = requestRepository.findById(requestId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.CREATION_REQUEST_NOT_FOUND));

        ensurePending(request);

        // 承認時にも slug の最終確認（申請受理〜承認の間に他申請が先に作成し得るため）
        if (villageRepository.existsBySlug(request.getProposedSlug())) {
            throw new BusinessException(VillageErrorCode.CREATION_REQUEST_SLUG_TAKEN);
        }

        // 自動村作成（B2 への侵入を避け、本足軽の責任範囲で直接生成。
        //  B2 が createFromApprovedRequest を提供したら将来差し替え予定）
        VillageEntity village = VillageEntity.builder()
                .slug(request.getProposedSlug())
                .name(request.getProposedName())
                .description(request.getPurpose())
                .type(VillageType.COMMUNITY)
                .joinPolicy(com.mannschaft.app.village.entity.enums.VillageJoinPolicy.FREE)
                .visibility(com.mannschaft.app.village.entity.enums.VillageVisibility.PUBLIC)
                .category(request.getProposedCategory())
                .guidelineMd(request.getProposedGuidelineMd())
                .memberCountCache(1L)
                .createdByUserId(request.getRequesterUserId())
                .build();
        VillageEntity savedVillage = villageRepository.save(village);

        // 申請者を HEADMAN として membership に追加
        VillageMembershipEntity membership = VillageMembershipEntity.builder()
                .villageId(savedVillage.getId())
                .subjectType(VillageSubjectType.USER)
                .subjectId(request.getRequesterUserId())
                .role(VillageRole.HEADMAN)
                .build();
        membershipRepository.save(membership);

        // 申請を APPROVED に更新
        request.setStatus(VillageRequestStatus.APPROVED);
        request.setReviewerUserId(reviewerUserId);
        request.setReviewedAt(LocalDateTime.now());
        request.setReviewComment(review != null ? review.reviewComment() : null);
        request.setCreatedVillageId(savedVillage.getId());

        VillageCreationRequestEntity saved = requestRepository.save(request);
        log.info("村作成申請を承認: requestId={}, villageId={}, reviewer={}",
                saved.getId(), savedVillage.getId(), reviewerUserId);
        return VillageCreationRequestResponse.from(saved);
    }

    /**
     * 運営による拒否。{@code reviewComment} 必須。
     */
    @Transactional
    public VillageCreationRequestResponse reject(UUID requestId,
                                                 Long reviewerUserId,
                                                 VillageCreationRequestReviewRequest review) {
        if (review == null || review.reviewComment() == null || review.reviewComment().isBlank()) {
            // 拒否コメント必須は WARN/400 相当として CommonErrorCode を流用
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }
        VillageCreationRequestEntity request = requestRepository.findById(requestId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.CREATION_REQUEST_NOT_FOUND));

        ensurePending(request);

        request.setStatus(VillageRequestStatus.REJECTED);
        request.setReviewerUserId(reviewerUserId);
        request.setReviewedAt(LocalDateTime.now());
        request.setReviewComment(review.reviewComment());
        VillageCreationRequestEntity saved = requestRepository.save(request);
        log.info("村作成申請を拒否: requestId={}, reviewer={}", saved.getId(), reviewerUserId);
        return VillageCreationRequestResponse.from(saved);
    }

    // ------------------------------------------------------------------
    // 申請者または運営による取り下げ
    // ------------------------------------------------------------------

    /**
     * 取り下げ。申請者本人または運営のみ。
     */
    @Transactional
    public VillageCreationRequestResponse withdraw(UUID requestId, Long actorUserId) {
        VillageCreationRequestEntity request = requestRepository.findById(requestId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.CREATION_REQUEST_NOT_FOUND));

        boolean isOwner = request.getRequesterUserId().equals(actorUserId);
        boolean isAdmin = isSystemAdmin(actorUserId);
        if (!isOwner && !isAdmin) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }

        ensurePending(request);

        request.setStatus(VillageRequestStatus.WITHDRAWN);
        request.setReviewedAt(LocalDateTime.now());
        // 取り下げ時は reviewer は記録しない（運営代理取り下げの場合は actorUserId を記録）
        if (isAdmin && !isOwner) {
            request.setReviewerUserId(actorUserId);
        }
        VillageCreationRequestEntity saved = requestRepository.save(request);
        log.info("村作成申請を取り下げ: requestId={}, actor={}", saved.getId(), actorUserId);
        return VillageCreationRequestResponse.from(saved);
    }

    // ------------------------------------------------------------------
    // ヘルパ
    // ------------------------------------------------------------------

    private void ensurePending(VillageCreationRequestEntity request) {
        switch (request.getStatus()) {
            case PENDING:
                return;
            case REJECTED:
                throw new BusinessException(VillageErrorCode.CREATION_REQUEST_REJECTED);
            case APPROVED:
            case WITHDRAWN:
            default:
                throw new BusinessException(VillageErrorCode.CREATION_REQUEST_ALREADY_REVIEWED);
        }
    }

    private boolean isSystemAdmin(Long userId) {
        if (userId == null) return false;
        return userRoleRepository.existsSystemAdminByUserId(userId) > 0;
    }
}
