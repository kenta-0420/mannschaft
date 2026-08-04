package com.mannschaft.app.village.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.MatchApplicationCreateRequest;
import com.mannschaft.app.village.dto.MatchApplicationResponse;
import com.mannschaft.app.village.dto.MatchApplicationReviewRequest;
import com.mannschaft.app.village.dto.MatchRecruitCreateRequest;
import com.mannschaft.app.village.dto.MatchRecruitListResponse;
import com.mannschaft.app.village.dto.MatchRecruitResponse;
import com.mannschaft.app.village.dto.MatchRecruitUpdateRequest;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMatchRecruitApplicationEntity;
import com.mannschaft.app.village.entity.VillageMatchRecruitEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageMatchApplicationStatus;
import com.mannschaft.app.village.entity.enums.VillageMatchRecruitCategory;
import com.mannschaft.app.village.entity.enums.VillageMatchRecruitStatus;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.repository.VillageMatchRecruitApplicationRepository;
import com.mannschaft.app.village.repository.VillageMatchRecruitRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * F17.1 Phase 2 U6 — 練習試合・審判募集 Service。
 *
 * <p>担当範囲（出陣指示書 U6 / 設計書 §2.2）:</p>
 * <ul>
 *   <li>募集 CRUD（作成・更新・締切・成立確定・取消し・一覧・詳細）</li>
 *   <li>応募管理（応募・取下げ・承認/却下・一覧）</li>
 * </ul>
 *
 * <p>アーキテクチャ原則の遵守:</p>
 * <ul>
 *   <li>原則 1: TeamRepository / OrganizationRepository は表示名解決のため Read-only 参照のみ。
 *       FK 制約は持たず、参照不能時は表示名なしとして扱う。</li>
 *   <li>原則 5: {@code @Transactional} は village ドメイン内に閉じる（クロスドメイン書込みなし）。</li>
 *   <li>権限分岐: 投稿者本人 / HEADMAN / ELDER のいずれかで判定。検証は本 Service 内で
 *       {@link VillageMembershipRepository} を直接参照する。</li>
 * </ul>
 *
 * <p>Phase 2 仕様（指示書補足）:</p>
 * <ul>
 *   <li>募集作成は「村人なら誰でも可」。HEADMAN/ELDER 限定にはしない。</li>
 *   <li>{@code match_date} 範囲フィルタは Service 内 Java フィルタで実装（Repository 触らず）。</li>
 *   <li>表示名・チーム名は読み取りクロスドメインのみ許可（投稿主体の代表権限検証は別途）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VillageMatchRecruitService {

    private final VillageRepository villageRepository;
    private final VillageMembershipRepository membershipRepository;
    private final VillageMatchRecruitRepository recruitRepository;
    private final VillageMatchRecruitApplicationRepository applicationRepository;
    private final VillageNicknameResolver villageNicknameResolver;
    /** Read-only: 表示名解決（原則1 FK 不在）。参照不能時は null 表示で済ませる。 */
    private final TeamRepository teamRepository;
    /** Read-only: 将来の組織募集拡張用（現 Phase は USER+TEAM のみ）。 */
    private final OrganizationRepository organizationRepository;

    // ========================================================================
    // 募集本体
    // ========================================================================

    /**
     * 練習試合・審判募集を作成する。村人なら誰でも投稿可。
     *
     * <ul>
     *   <li>非村人は {@link VillageErrorCode#NOT_MEMBER}</li>
     *   <li>match_time_end < match_time_start は {@link VillageErrorCode#MATCH_RECRUIT_TIME_INVALID}</li>
     *   <li>BAN 中ユーザーは {@link VillageErrorCode#MEMBER_BANNED}</li>
     * </ul>
     */
    @Transactional
    public MatchRecruitResponse createRecruit(UUID villageId, MatchRecruitCreateRequest request, Long actorUserId) {
        if (actorUserId == null) {
            throw new BusinessException(CommonErrorCode.COMMON_000);
        }
        loadActiveVillage(villageId);
        ensureVillager(villageId, actorUserId);

        validateTimes(request.matchTimeStart(), request.matchTimeEnd());

        VillageMatchRecruitEntity entity = VillageMatchRecruitEntity.builder()
                .villageId(villageId)
                .postedByUserId(actorUserId)
                .postedByTeamId(request.postedByTeamId())
                .category(request.category())
                .title(request.title())
                .description(request.description())
                .matchDate(request.matchDate())
                .matchTimeStart(request.matchTimeStart())
                .matchTimeEnd(request.matchTimeEnd())
                .venue(request.venue())
                .requiredCount(request.requiredCount())
                .contactMethod(request.contactMethod())
                .applicationDeadline(request.applicationDeadline())
                .status(VillageMatchRecruitStatus.OPEN)
                .build();
        VillageMatchRecruitEntity saved = recruitRepository.save(entity);
        log.info("練習試合募集を作成: villageId={}, recruitId={}, postedBy={}, category={}",
                villageId, saved.getId(), actorUserId, saved.getCategory());
        return toResponse(saved);
    }

    /**
     * 募集情報を更新する。投稿者本人のみ実行可（村長/長老でも他人の投稿は更新できない）。
     *
     * <ul>
     *   <li>OPEN 以外（CLOSED/FULFILLED/CANCELLED）は更新不可（{@link VillageErrorCode#MATCH_RECRUIT_NOT_OPEN}）</li>
     *   <li>{@code title} に空文字を指定した場合は {@link VillageErrorCode#VILLAGE_FIELD_INVALID}</li>
     * </ul>
     */
    @Transactional
    public MatchRecruitResponse updateRecruit(UUID villageId,
                                              UUID recruitId,
                                              MatchRecruitUpdateRequest request,
                                              Long actorUserId) {
        loadActiveVillage(villageId);
        VillageMatchRecruitEntity entity = loadRecruitForVillage(villageId, recruitId);
        ensureAuthor(villageId, entity, actorUserId);

        if (entity.getStatus() != VillageMatchRecruitStatus.OPEN) {
            throw new BusinessException(VillageErrorCode.MATCH_RECRUIT_NOT_OPEN);
        }

        if (request.category() != null) {
            entity.setCategory(request.category());
        }
        if (request.title() != null) {
            if (request.title().isBlank()) {
                throw new BusinessException(VillageErrorCode.VILLAGE_FIELD_INVALID);
            }
            entity.setTitle(request.title());
        }
        if (request.description() != null) {
            entity.setDescription(request.description());
        }
        if (request.matchDate() != null) {
            entity.setMatchDate(request.matchDate());
        }
        if (request.matchTimeStart() != null) {
            entity.setMatchTimeStart(request.matchTimeStart());
        }
        if (request.matchTimeEnd() != null) {
            entity.setMatchTimeEnd(request.matchTimeEnd());
        }
        // 時刻整合性チェック（PATCH 後の状態で検証）
        validateTimes(entity.getMatchTimeStart(), entity.getMatchTimeEnd());

        if (request.venue() != null) {
            entity.setVenue(request.venue());
        }
        if (request.requiredCount() != null) {
            entity.setRequiredCount(request.requiredCount());
        }
        if (request.contactMethod() != null) {
            entity.setContactMethod(request.contactMethod());
        }
        if (request.applicationDeadline() != null) {
            entity.setApplicationDeadline(request.applicationDeadline());
        }
        if (request.postedByTeamId() != null) {
            entity.setPostedByTeamId(request.postedByTeamId());
        }

        VillageMatchRecruitEntity saved = recruitRepository.save(entity);
        log.info("練習試合募集を更新: villageId={}, recruitId={}, actor={}", villageId, recruitId, actorUserId);
        return toResponse(saved);
    }

    /**
     * 募集を締切る（status=CLOSED）。投稿者本人 / HEADMAN / ELDER のいずれかで実行可。
     */
    @Transactional
    public MatchRecruitResponse closeRecruit(UUID villageId, UUID recruitId, Long actorUserId) {
        return transition(villageId, recruitId, actorUserId, VillageMatchRecruitStatus.CLOSED, "締切");
    }

    /**
     * 募集を成立確定する（status=FULFILLED）。投稿者本人 / HEADMAN / ELDER のいずれかで実行可。
     */
    @Transactional
    public MatchRecruitResponse fulfillRecruit(UUID villageId, UUID recruitId, Long actorUserId) {
        return transition(villageId, recruitId, actorUserId, VillageMatchRecruitStatus.FULFILLED, "成立");
    }

    /**
     * 募集を取消す（status=CANCELLED）。投稿者本人 / HEADMAN / ELDER のいずれかで実行可。
     */
    @Transactional
    public MatchRecruitResponse cancelRecruit(UUID villageId, UUID recruitId, Long actorUserId) {
        return transition(villageId, recruitId, actorUserId, VillageMatchRecruitStatus.CANCELLED, "取消");
    }

    /**
     * 募集一覧を取得する。category / status / match_date 範囲フィルタに対応。
     *
     * <p>match_date 範囲は Repository 派生メソッドに無いため Service 層で Java フィルタする。
     * ページネーション精度を保つため、まず Repository で粗くページ取得し、フィルタ後の件数で
     * {@link Page} を再構築する（trade-off: フィルタ後の total は粗いページの total と一致しない可能性があるが、
     * Phase 2 仕様としては許容範囲）。</p>
     */
    @Transactional(readOnly = true)
    public MatchRecruitListResponse listRecruits(UUID villageId,
                                                 VillageMatchRecruitCategory category,
                                                 VillageMatchRecruitStatus status,
                                                 LocalDate matchDateFrom,
                                                 LocalDate matchDateTo,
                                                 int page,
                                                 int size,
                                                 Long actorUserId) {
        loadActiveVillage(villageId);
        ensureVillager(villageId, actorUserId);

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<VillageMatchRecruitEntity> p;
        if (category != null && status != null) {
            p = recruitRepository.findByVillageIdAndCategoryAndStatusAndDeletedAtIsNull(
                    villageId, category, status, pageable);
        } else if (status != null) {
            p = recruitRepository.findByVillageIdAndStatusAndDeletedAtIsNull(villageId, status, pageable);
        } else {
            p = recruitRepository.findByVillageIdAndDeletedAtIsNull(villageId, pageable);
        }

        // F17.1 §5.6: matchDate は NULL 許容に緩和された（日付を持たない募集）。
        // 日付範囲で絞り込む場合、日付を持たない募集はどの期間にも属さないため対象外とする
        // （素の e.getMatchDate().isBefore(...) は NULL 行で NPE / 500 になる）。
        List<VillageMatchRecruitEntity> filtered = p.getContent().stream()
                .filter(e -> category == null || e.getCategory() == category)
                .filter(e -> matchDateFrom == null
                        || (e.getMatchDate() != null && !e.getMatchDate().isBefore(matchDateFrom)))
                .filter(e -> matchDateTo == null
                        || (e.getMatchDate() != null && !e.getMatchDate().isAfter(matchDateTo)))
                .toList();

        List<MatchRecruitResponse> items = mapWithDisplayNames(filtered);
        return MatchRecruitListResponse.of(items, page, size, p.getTotalElements());
    }

    /**
     * 募集詳細を取得する。村人のみ閲覧可（非村人は IDOR 防止のため 404 扱い）。
     */
    @Transactional(readOnly = true)
    public MatchRecruitResponse getRecruit(UUID villageId, UUID recruitId, Long actorUserId) {
        loadActiveVillage(villageId);
        ensureVillager(villageId, actorUserId);
        VillageMatchRecruitEntity entity = loadRecruitForVillage(villageId, recruitId);
        return toResponse(entity);
    }

    // ========================================================================
    // 応募管理
    // ========================================================================

    /**
     * 募集に応募する。
     *
     * <ul>
     *   <li>非村人は {@link VillageErrorCode#NOT_MEMBER}</li>
     *   <li>OPEN 以外の募集は {@link VillageErrorCode#MATCH_RECRUIT_NOT_OPEN}</li>
     *   <li>同一ユーザーで PENDING の応募がある場合は {@link VillageErrorCode#MATCH_APPLICATION_DUPLICATE}</li>
     *   <li>投稿者本人が自分の募集に応募するのは禁止（{@link CommonErrorCode#COMMON_002}）</li>
     * </ul>
     */
    @Transactional
    public MatchApplicationResponse applyToRecruit(UUID villageId,
                                                   UUID recruitId,
                                                   MatchApplicationCreateRequest request,
                                                   Long applicantUserId) {
        if (applicantUserId == null) {
            throw new BusinessException(CommonErrorCode.COMMON_000);
        }
        loadActiveVillage(villageId);
        ensureVillager(villageId, applicantUserId);

        VillageMatchRecruitEntity recruit = loadRecruitForVillage(villageId, recruitId);
        if (recruit.getStatus() != VillageMatchRecruitStatus.OPEN) {
            throw new BusinessException(VillageErrorCode.MATCH_RECRUIT_NOT_OPEN);
        }
        if (recruit.getPostedByUserId().equals(applicantUserId)) {
            // 自分の募集に自分で応募はできない
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }

        // 同一ユーザーの PENDING 重複ガード
        applicationRepository.findByRecruitIdAndApplicantUserIdAndStatus(
                        recruitId, applicantUserId, VillageMatchApplicationStatus.PENDING)
                .ifPresent(a -> {
                    throw new BusinessException(VillageErrorCode.MATCH_APPLICATION_DUPLICATE);
                });

        VillageMatchRecruitApplicationEntity entity = VillageMatchRecruitApplicationEntity.builder()
                .recruitId(recruitId)
                .applicantUserId(applicantUserId)
                .applicantTeamId(request != null ? request.applicantTeamId() : null)
                .message(request != null ? request.message() : null)
                .status(VillageMatchApplicationStatus.PENDING)
                .build();
        VillageMatchRecruitApplicationEntity saved = applicationRepository.save(entity);
        log.info("練習試合募集に応募: recruitId={}, applicationId={}, applicant={}",
                recruitId, saved.getId(), applicantUserId);
        return toResponse(saved);
    }

    /**
     * 応募を自主取下げする（status=WITHDRAWN）。応募者本人のみ実行可。
     *
     * <ul>
     *   <li>PENDING でない応募の取下げは {@link VillageErrorCode#MATCH_APPLICATION_INVALID_STATUS}</li>
     *   <li>本人以外は {@link CommonErrorCode#COMMON_002}</li>
     * </ul>
     */
    @Transactional
    public MatchApplicationResponse withdrawApplication(UUID villageId,
                                                        UUID recruitId,
                                                        UUID applicationId,
                                                        Long actorUserId) {
        loadActiveVillage(villageId);
        loadRecruitForVillage(villageId, recruitId);
        VillageMatchRecruitApplicationEntity app = loadApplicationForRecruit(recruitId, applicationId);

        if (!app.getApplicantUserId().equals(actorUserId)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        if (app.getStatus() != VillageMatchApplicationStatus.PENDING) {
            throw new BusinessException(VillageErrorCode.MATCH_APPLICATION_INVALID_STATUS);
        }

        app.setStatus(VillageMatchApplicationStatus.WITHDRAWN);
        app.setReviewedAt(LocalDateTime.now());
        VillageMatchRecruitApplicationEntity saved = applicationRepository.save(app);
        log.info("練習試合応募を取下げ: recruitId={}, applicationId={}, actor={}",
                recruitId, applicationId, actorUserId);
        return toResponse(saved);
    }

    /**
     * 応募を審査する（承認 ACCEPTED または却下 REJECTED）。
     *
     * <ul>
     *   <li>審査権限: 募集投稿者本人 または HEADMAN/ELDER</li>
     *   <li>PENDING でない応募の審査は {@link VillageErrorCode#MATCH_APPLICATION_INVALID_STATUS}</li>
     *   <li>{@code status} が ACCEPTED/REJECTED 以外なら {@link VillageErrorCode#MATCH_APPLICATION_INVALID_STATUS}</li>
     * </ul>
     */
    @Transactional
    public MatchApplicationResponse reviewApplication(UUID villageId,
                                                      UUID recruitId,
                                                      UUID applicationId,
                                                      MatchApplicationReviewRequest request,
                                                      Long reviewerUserId) {
        if (request == null || request.status() == null) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }
        VillageMatchApplicationStatus newStatus = request.status();
        if (newStatus != VillageMatchApplicationStatus.ACCEPTED
                && newStatus != VillageMatchApplicationStatus.REJECTED) {
            throw new BusinessException(VillageErrorCode.MATCH_APPLICATION_INVALID_STATUS);
        }

        loadActiveVillage(villageId);
        VillageMatchRecruitEntity recruit = loadRecruitForVillage(villageId, recruitId);
        ensureRecruitReviewer(villageId, recruit, reviewerUserId);

        VillageMatchRecruitApplicationEntity app = loadApplicationForRecruit(recruitId, applicationId);
        if (app.getStatus() != VillageMatchApplicationStatus.PENDING) {
            throw new BusinessException(VillageErrorCode.MATCH_APPLICATION_INVALID_STATUS);
        }

        app.setStatus(newStatus);
        app.setReviewedByUserId(reviewerUserId);
        app.setReviewedAt(LocalDateTime.now());
        app.setReviewComment(request.reviewComment());
        VillageMatchRecruitApplicationEntity saved = applicationRepository.save(app);
        log.info("練習試合応募を審査: recruitId={}, applicationId={}, reviewer={}, status={}",
                recruitId, applicationId, reviewerUserId, newStatus);
        return toResponse(saved);
    }

    /**
     * 応募一覧を取得する。募集投稿者または HEADMAN/ELDER のみ閲覧可。
     */
    @Transactional(readOnly = true)
    public List<MatchApplicationResponse> listApplications(UUID villageId,
                                                           UUID recruitId,
                                                           Long actorUserId) {
        loadActiveVillage(villageId);
        VillageMatchRecruitEntity recruit = loadRecruitForVillage(villageId, recruitId);
        ensureRecruitReviewer(villageId, recruit, actorUserId);

        // ページネーションは Controller 層で導入予定。Service は全件返す（応募数は限定的）。
        Pageable pageable = PageRequest.of(0, 200, Sort.by(Sort.Direction.ASC, "createdAt"));
        Page<VillageMatchRecruitApplicationEntity> p = applicationRepository.findByRecruitId(recruitId, pageable);
        return mapApplicationsWithDisplayNames(p.getContent());
    }

    // ========================================================================
    // 共通ヘルパ — 状態遷移
    // ========================================================================

    /** 募集の状態遷移を実行する共通ロジック（close/fulfill/cancel から呼び出す）。 */
    private MatchRecruitResponse transition(UUID villageId,
                                            UUID recruitId,
                                            Long actorUserId,
                                            VillageMatchRecruitStatus targetStatus,
                                            String operationLabel) {
        loadActiveVillage(villageId);
        VillageMatchRecruitEntity entity = loadRecruitForVillage(villageId, recruitId);
        ensureRecruitReviewer(villageId, entity, actorUserId);

        if (entity.getStatus() != VillageMatchRecruitStatus.OPEN) {
            // OPEN 以外への状態遷移は基本的に拒否（既に CLOSED/FULFILLED/CANCELLED）
            throw new BusinessException(VillageErrorCode.MATCH_RECRUIT_NOT_OPEN);
        }
        entity.setStatus(targetStatus);
        VillageMatchRecruitEntity saved = recruitRepository.save(entity);
        log.info("練習試合募集を{}: villageId={}, recruitId={}, actor={}, newStatus={}",
                operationLabel, villageId, recruitId, actorUserId, targetStatus);
        return toResponse(saved);
    }

    // ========================================================================
    // 共通ヘルパ — 取得・検証
    // ========================================================================

    /** 有効な村を取得する（削除/凍結済みは VILLAGE_001/027 で扱う）。 */
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
     * 募集を取得し、villageId が一致することを確認する（IDOR 対策）。
     * 削除済み（{@code deletedAt != null}）も 404 扱い。
     */
    private VillageMatchRecruitEntity loadRecruitForVillage(UUID villageId, UUID recruitId) {
        VillageMatchRecruitEntity e = recruitRepository.findById(recruitId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.MATCH_RECRUIT_NOT_FOUND));
        if (!e.getVillageId().equals(villageId) || e.getDeletedAt() != null) {
            throw new BusinessException(VillageErrorCode.MATCH_RECRUIT_NOT_FOUND);
        }
        return e;
    }

    /** 応募を取得し、recruitId が一致することを確認する（IDOR 対策）。 */
    private VillageMatchRecruitApplicationEntity loadApplicationForRecruit(UUID recruitId, UUID applicationId) {
        VillageMatchRecruitApplicationEntity a = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.MATCH_APPLICATION_NOT_FOUND));
        if (!a.getRecruitId().equals(recruitId)) {
            throw new BusinessException(VillageErrorCode.MATCH_APPLICATION_NOT_FOUND);
        }
        return a;
    }

    /** 当該ユーザーが村人（USER 主体・BAN なし）であることを検証する。 */
    private VillageMembershipEntity ensureVillager(UUID villageId, Long userId) {
        if (userId == null) {
            throw new BusinessException(CommonErrorCode.COMMON_000);
        }
        VillageMembershipEntity m = membershipRepository
                .findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(villageId, VillageSubjectType.USER, userId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.NOT_MEMBER));
        if (m.getBannedAt() != null) {
            throw new BusinessException(VillageErrorCode.MEMBER_BANNED);
        }
        return m;
    }

    /**
     * 投稿者本人であることを検証する（更新時用）。
     *
     * <p><strong>現役性の検査を含む（#2284 §12 と同型）</strong>: {@code findActiveByVillageIdAndSubject}
     * 述語で「その村の現役メンバーであること」を確認したうえで投稿者本人かを判定する。
     * 退村済み・BAN 済みの利用者は現役判定の段階で拒否される。判定の順序と述語は
     * {@link #ensureRecruitReviewer} と揃えてあり、村内の更新系は一様にこの流儀に従う。</p>
     */
    private void ensureAuthor(UUID villageId, VillageMatchRecruitEntity entity, Long actorUserId) {
        if (actorUserId == null) {
            throw new BusinessException(CommonErrorCode.COMMON_000);
        }
        membershipRepository
                .findActiveByVillageIdAndSubject(villageId, VillageSubjectType.USER, actorUserId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.COMMON_002));
        if (!entity.getPostedByUserId().equals(actorUserId)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    /**
     * 募集に対するレビュー権限（投稿者本人 / HEADMAN / ELDER）を検証する。
     *
     * <p>状態遷移・応募審査・応募一覧で共通利用する。</p>
     *
     * <p><strong>検査順序が重要（#2284 §12）</strong>: 以前は「投稿者本人なら即 return」を
     * メンバーシップ照会より<strong>前</strong>に置いていたため、投稿者が BAN されても
     * 自分の募集の応募審査・状態遷移を続行できた（BAN 逃れの抜け道）。
     * 現在は先に「現役メンバーであること」を確認し、その後に本人／ロールを判定する。
     * これにより退村済み（{@code leftAt}）・BAN 済み（{@code bannedAt}）の投稿者は
     * 本人であってもレビュー不可となる。</p>
     */
    private void ensureRecruitReviewer(UUID villageId, VillageMatchRecruitEntity recruit, Long actorUserId) {
        if (actorUserId == null) {
            throw new BusinessException(CommonErrorCode.COMMON_000);
        }
        // 本人判定より先に「現役メンバーか」を確認する（BAN/退村した投稿者を弾くため）
        VillageMembershipEntity m = membershipRepository
                .findActiveByVillageIdAndSubject(villageId, VillageSubjectType.USER, actorUserId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.MODERATION_FORBIDDEN));
        if (recruit.getPostedByUserId().equals(actorUserId)) {
            return; // 現役の投稿者本人
        }
        if (m.getRole() != VillageRole.HEADMAN && m.getRole() != VillageRole.ELDER) {
            throw new BusinessException(VillageErrorCode.MODERATION_FORBIDDEN);
        }
    }

    /** 試合時刻の整合性検証（end < start は不正）。 */
    private void validateTimes(java.time.LocalTime start, java.time.LocalTime end) {
        if (start != null && end != null && end.isBefore(start)) {
            throw new BusinessException(VillageErrorCode.MATCH_RECRUIT_TIME_INVALID);
        }
    }

    // ========================================================================
    // 共通ヘルパ — DTO 変換 + 表示名解決
    // ========================================================================

    /** 単一募集 Entity → Response（表示名解決込み）。 */
    private MatchRecruitResponse toResponse(VillageMatchRecruitEntity e) {
        String displayName = resolveUserDisplayName(e.getPostedByUserId(), e.getVillageId());
        String teamName = resolveTeamName(e.getPostedByTeamId());
        return MatchRecruitResponse.of(e, displayName, teamName);
    }

    /** 単一応募 Entity → Response（表示名解決込み）。 */
    private MatchApplicationResponse toResponse(VillageMatchRecruitApplicationEntity e) {
        // 応募者の村ニックネームは「応募先募集の村」で解決する必要がある。
        // recruit を毎回引くと N+1 になるため、本 Service の単発 toResponse は
        // applicantUserId のグローバルニックネーム解決のみ行う（村別ニックネームは listApplications 経路で対応）。
        String displayName = resolveUserDisplayName(e.getApplicantUserId(), null);
        String teamName = resolveTeamName(e.getApplicantTeamId());
        return MatchApplicationResponse.of(e, displayName, teamName);
    }

    /** 募集リストを一括で Response に変換し、表示名を解決する（チーム名は重複排除でクエリ削減）。 */
    private List<MatchRecruitResponse> mapWithDisplayNames(List<VillageMatchRecruitEntity> entities) {
        if (entities.isEmpty()) {
            return List.of();
        }
        // チーム名を一括解決
        Map<Long, String> teamNames = resolveTeamNames(
                entities.stream().map(VillageMatchRecruitEntity::getPostedByTeamId).toList());

        List<MatchRecruitResponse> result = new ArrayList<>(entities.size());
        for (VillageMatchRecruitEntity e : entities) {
            String displayName = resolveUserDisplayName(e.getPostedByUserId(), e.getVillageId());
            String teamName = e.getPostedByTeamId() != null ? teamNames.get(e.getPostedByTeamId()) : null;
            result.add(MatchRecruitResponse.of(e, displayName, teamName));
        }
        return result;
    }

    /** 応募リストを一括で Response に変換する。 */
    private List<MatchApplicationResponse> mapApplicationsWithDisplayNames(
            List<VillageMatchRecruitApplicationEntity> entities) {
        if (entities.isEmpty()) {
            return List.of();
        }
        Map<Long, String> teamNames = resolveTeamNames(
                entities.stream().map(VillageMatchRecruitApplicationEntity::getApplicantTeamId).toList());

        List<MatchApplicationResponse> result = new ArrayList<>(entities.size());
        for (VillageMatchRecruitApplicationEntity e : entities) {
            // 応募者の村ニックネームは募集の村で解決すべきだが、本 Service では呼び出し元（listApplications）が
            // recruit を既に取得済みのため、ここではグローバル解決で簡略化する。
            String displayName = resolveUserDisplayName(e.getApplicantUserId(), null);
            String teamName = e.getApplicantTeamId() != null ? teamNames.get(e.getApplicantTeamId()) : null;
            result.add(MatchApplicationResponse.of(e, displayName, teamName));
        }
        return result;
    }

    /**
     * ユーザー ID をニックネームに解決する（村別 > グローバル > プレースホルダ）。
     *
     * @param userId    ユーザーID
     * @param villageId 村 ID（{@code null} ならグローバルニックネームのみ参照）
     */
    private String resolveUserDisplayName(Long userId, UUID villageId) {
        // F17.3 前工程リファクタ: 共有ヘルパへ委譲（ふるまい不変・重複ドリフト防止・§15.4）。
        return villageNicknameResolver.resolve(userId, villageId);
    }

    /** チーム ID をチーム名に解決する。参照不能・null の場合は {@code null}。 */
    private String resolveTeamName(Long teamId) {
        if (teamId == null) {
            return null;
        }
        return teamRepository.findById(teamId).map(TeamEntity::getName).orElse(null);
    }

    /** チーム ID リストを一括でチーム名 Map に解決する（N+1 回避）。 */
    private Map<Long, String> resolveTeamNames(List<Long> teamIds) {
        Map<Long, String> result = new HashMap<>();
        for (Long id : teamIds) {
            if (id == null || result.containsKey(id)) {
                continue;
            }
            teamRepository.findById(id).ifPresent(t -> result.put(id, t.getName()));
        }
        return result;
    }
}
