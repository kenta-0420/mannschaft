package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.recruitment.RecruitmentDistributionTargetType;
import com.mannschaft.app.recruitment.RecruitmentErrorCode;
import com.mannschaft.app.recruitment.RecruitmentListingStatus;
import com.mannschaft.app.recruitment.RecruitmentMapper;
import com.mannschaft.app.recruitment.RecruitmentParticipantStatus;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.RecruitmentVisibility;
import com.mannschaft.app.recruitment.dto.CancelRecruitmentListingRequest;
import com.mannschaft.app.recruitment.dto.CreateFromTemplateRequest;
import com.mannschaft.app.recruitment.dto.CreateRecruitmentListingRequest;
import com.mannschaft.app.recruitment.dto.RecruitmentFeedItemResponse;
import com.mannschaft.app.recruitment.dto.RecruitmentListingResponse;
import com.mannschaft.app.recruitment.dto.RecruitmentListingSummaryResponse;
import com.mannschaft.app.recruitment.dto.RecruitmentParticipantResponse;
import com.mannschaft.app.recruitment.dto.UpdateRecruitmentListingRequest;
import com.mannschaft.app.recruitment.entity.RecruitmentCancellationPolicyEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentDistributionTargetEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentParticipantEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentParticipantHistoryEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentReminderEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentTemplateEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentCategoryRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentDistributionTargetRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentParticipantHistoryRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentParticipantRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentReminderRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentTemplateRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.social.FollowerType;
import com.mannschaft.app.social.repository.FollowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * F03.11 募集型予約: 募集枠 中核サービス。
 *
 * 設計書参照:
 * - §5.1 募集作成
 * - §5.6 予約ライン衝突 (Phase 1 ではスタブ)
 * - §5.7 編集時の制約
 * - §9.1 募集 CRUD API
 * - §13 認可
 * - §14.1 認可 / §14.2 トランザクション
 *
 * Phase 1 の限定:
 * - distribution_targets / 通知 / リマインダー → Phase 2
 * - 予約ライン衝突チェック → Phase 4 (スタブのみ)
 * - 自動キャンセルバッチ → Phase 3
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecruitmentListingService {

    private final RecruitmentListingRepository listingRepository;
    private final RecruitmentCategoryRepository categoryRepository;
    private final RecruitmentDistributionTargetRepository distributionTargetRepository;
    private final RecruitmentReminderRepository reminderRepository;
    private final RecruitmentParticipantRepository participantRepository;
    private final RecruitmentParticipantHistoryRepository participantHistoryRepository;
    private final UserRoleRepository userRoleRepository;
    private final FollowRepository followRepository;
    private final NotificationHelper notificationHelper;
    private final AccessControlService accessControlService;
    private final RecruitmentMapper mapper;
    private final RecruitmentTemplateService templateService;
    private final RecruitmentTemplateRepository templateRepository;
    private final ContentVisibilityChecker visibilityChecker;

    // ===========================================
    // 取得系
    // ===========================================

    public Page<RecruitmentListingSummaryResponse> listByScope(
            RecruitmentScopeType scopeType, Long scopeId, String status, Long userId, Pageable pageable) {
        accessControlService.checkMembership(userId, scopeId, scopeType.name());

        Page<RecruitmentListingEntity> page;
        if (status != null) {
            RecruitmentListingStatus parsed = RecruitmentListingStatus.valueOf(status);
            page = listingRepository.findByScopeTypeAndScopeIdAndStatusOrderByStartAtDesc(
                    scopeType, scopeId, parsed, pageable);
        } else {
            page = listingRepository.findByScopeTypeAndScopeIdOrderByStartAtDesc(
                    scopeType, scopeId, pageable);
        }
        return page.map(mapper::toListingSummaryResponse);
    }

    public RecruitmentListingResponse getListing(Long listingId, Long userId) {
        RecruitmentListingEntity entity = findOrThrow(listingId);
        // DRAFT は作成者・スコープ ADMIN のみ閲覧可（機能側ローカル要件）。
        // F00 共通基盤の DRAFT 規約は「作成者 + SystemAdmin のみ」だが、
        // Recruitment 機能では従来から TEAM/ORG ADMIN にも DRAFT 閲覧を許可しており、
        // ローカル要件として本ガードで先に通過判定する。
        if (entity.getStatus() == RecruitmentListingStatus.DRAFT) {
            boolean isCreator = entity.getCreatedBy().equals(userId);
            boolean isAdmin = accessControlService.isAdminOrAbove(
                    userId, entity.getScopeId(), entity.getScopeType().name());
            if (!isCreator && !isAdmin) {
                throw new BusinessException(RecruitmentErrorCode.DRAFT_VIEW_DENIED);
            }
            // DRAFT で creator/admin が確認できた場合は F00 ガードをスキップ
            // (F00 側はこの分岐を SystemAdmin と author 以外で deny するため)
            return mapper.toListingResponse(entity);
        }
        // F00 共通可視性ガード: PUBLIC / SCOPE_ONLY / SUPPORTERS_ONLY / CUSTOM_TEMPLATE を
        // ContentVisibilityChecker.assertCanView 経由で判定する (NOT_FOUND → 404, deny → 403)。
        // F00 Phase C 試験的置換 (2026-05-04): Phase 2 留保コードを本格実装に昇格。
        visibilityChecker.assertCanView(ReferenceType.RECRUITMENT_LISTING, listingId, userId);
        return mapper.toListingResponse(entity);
    }

    /** Service 内部用: ID で取得 (アプリ側ヘルパー)。 */
    public RecruitmentListingEntity findOrThrow(Long listingId) {
        return listingRepository.findById(listingId)
                .orElseThrow(() -> new BusinessException(RecruitmentErrorCode.LISTING_NOT_FOUND));
    }

    // ===========================================
    // 書込系 (§5.1, §5.7, §9.1)
    // ===========================================

    @Transactional
    public RecruitmentListingResponse create(
            RecruitmentScopeType scopeType, Long scopeId, Long userId,
            CreateRecruitmentListingRequest request) {
        accessControlService.checkAdminOrAbove(userId, scopeId, scopeType.name());

        // §5.1 必須カテゴリ + 存在チェック
        if (request.getCategoryId() == null) {
            throw new BusinessException(RecruitmentErrorCode.CATEGORY_NOT_SPECIFIED);
        }
        if (!categoryRepository.existsById(request.getCategoryId())) {
            throw new BusinessException(RecruitmentErrorCode.CATEGORY_NOT_SPECIFIED);
        }

        // §5.1 CHECK 制約相当の Java 側検証
        validateListingFields(
                request.getStartAt(), request.getEndAt(),
                request.getApplicationDeadline(), request.getAutoCancelAt(),
                request.getCapacity(), request.getMinCapacity(),
                request.getPaymentEnabled(), request.getPrice());

        // §5.6 予約ライン衝突チェック (Phase 4 で本実装、Phase 1 はスタブ)
        if (request.getReservationLineId() != null && checkLineCollision(
                request.getReservationLineId(), request.getStartAt(), request.getEndAt())) {
            throw new BusinessException(RecruitmentErrorCode.LINE_TIME_CONFLICT);
        }

        RecruitmentListingEntity entity = RecruitmentListingEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .categoryId(request.getCategoryId())
                .subcategoryId(request.getSubcategoryId())
                .title(request.getTitle())
                .description(request.getDescription())
                .participationType(request.getParticipationType())
                .startAt(request.getStartAt())
                .endAt(request.getEndAt())
                .applicationDeadline(request.getApplicationDeadline())
                .autoCancelAt(request.getAutoCancelAt())
                .capacity(request.getCapacity())
                .minCapacity(request.getMinCapacity())
                .paymentEnabled(Boolean.TRUE.equals(request.getPaymentEnabled()))
                .price(request.getPrice())
                .visibility(request.getVisibility())
                .location(request.getLocation())
                .reservationLineId(request.getReservationLineId())
                .imageUrl(request.getImageUrl())
                .cancellationPolicyId(request.getCancellationPolicyId())
                .createdBy(userId)
                .build();

        RecruitmentListingEntity saved = listingRepository.save(entity);
        log.info("F03.11 募集枠作成: id={}, scope={}/{}, status=DRAFT", saved.getId(), scopeType, scopeId);
        return mapper.toListingResponse(saved);
    }

    /**
     * §5.1.2 テンプレートから募集枠を作成する。
     * テンプレートの default_* フィールドをベースに、リクエストの値で上書きする。
     */
    @Transactional
    public RecruitmentListingResponse createFromTemplate(
            RecruitmentScopeType scopeType, Long scopeId, Long userId,
            CreateFromTemplateRequest request) {
        Long templateId = request.getTemplateId();
        accessControlService.checkAdminOrAbove(userId, scopeId, scopeType.name());

        RecruitmentTemplateEntity template = templateRepository.findActiveById(templateId)
                .orElseThrow(() -> new BusinessException(RecruitmentErrorCode.TEMPLATE_NOT_FOUND));

        // テンプレートのスコープと一致することを確認
        if (template.getScopeType() != scopeType || !template.getScopeId().equals(scopeId)) {
            throw new BusinessException(RecruitmentErrorCode.TEMPLATE_SCOPE_MISMATCH);
        }

        // キャンセルポリシーが設定されていれば DEEP COPY
        RecruitmentCancellationPolicyEntity copiedPolicy =
                templateService.deepCopyPolicyIfNeeded(template, userId);
        Long policyId = copiedPolicy != null ? copiedPolicy.getId() : null;

        // テンプレートのデフォルト値とリクエストの値をマージ
        LocalDateTime startAt = request.getStartAt();
        // endAt: 指定がなければ startAt + durationMinutes
        LocalDateTime endAt = request.getEndAt() != null
                ? request.getEndAt()
                : startAt.plusMinutes(template.getDefaultDurationMinutes());
        LocalDateTime deadline = request.getApplicationDeadline() != null
                ? request.getApplicationDeadline()
                : startAt.minusHours(template.getDefaultApplicationDeadlineHours());
        LocalDateTime autoCancelAt = request.getAutoCancelAt() != null
                ? request.getAutoCancelAt()
                : deadline.minusHours(template.getDefaultAutoCancelHours());

        CreateRecruitmentListingRequest createReq = new CreateRecruitmentListingRequest(
                template.getCategoryId(),
                template.getSubcategoryId(),
                template.getTitle(),
                template.getDescription(),
                template.getParticipationType(),
                startAt,
                endAt,
                deadline,
                autoCancelAt,
                request.getCapacity() != null ? request.getCapacity() : template.getDefaultCapacity(),
                request.getMinCapacity() != null ? request.getMinCapacity() : template.getDefaultMinCapacity(),
                template.getDefaultPaymentEnabled(),
                template.getDefaultPrice(),
                template.getDefaultVisibility(),
                template.getDefaultLocation(),
                template.getDefaultReservationLineId(),
                template.getDefaultImageUrl(),
                policyId
        );

        RecruitmentListingResponse response = create(scopeType, scopeId, userId, createReq);

        // templateId をセット（create()後にエンティティを更新）
        listingRepository.findByIdForUpdate(response.getId()).ifPresent(entity -> {
            entity.assignTemplate(templateId);
            listingRepository.save(entity);
        });

        log.info("F03.11 テンプレートから募集枠作成: templateId={}, listingId={}", templateId, response.getId());
        return response;
    }

    @Transactional
    public RecruitmentListingResponse update(Long listingId, Long userId, UpdateRecruitmentListingRequest request) {
        // §5.7 編集時の制約 — PESSIMISTIC_WRITE で行ロック取得
        RecruitmentListingEntity entity = listingRepository.findByIdForUpdate(listingId)
                .orElseThrow(() -> new BusinessException(RecruitmentErrorCode.LISTING_NOT_FOUND));
        accessControlService.checkAdminOrAbove(userId, entity.getScopeId(), entity.getScopeType().name());

        // Service 層でも事前検証 (Entity 内に防御的二重検証あり)
        if (entity.getStatus() == RecruitmentListingStatus.COMPLETED) {
            throw new BusinessException(RecruitmentErrorCode.COMPLETED_NOT_EDITABLE);
        }
        if (request.getCapacity() != null && request.getCapacity() < entity.getConfirmedCount()) {
            throw new BusinessException(RecruitmentErrorCode.CAPACITY_BELOW_CONFIRMED);
        }

        try {
            entity.updateForEdit(
                    request.getTitle(),
                    request.getDescription(),
                    request.getSubcategoryId(),
                    request.getStartAt(),
                    request.getEndAt(),
                    request.getApplicationDeadline(),
                    request.getAutoCancelAt(),
                    request.getCapacity(),
                    request.getMinCapacity(),
                    request.getPaymentEnabled(),
                    request.getPrice(),
                    request.getVisibility(),
                    request.getLocation(),
                    request.getReservationLineId(),
                    request.getImageUrl(),
                    request.getCancellationPolicyId()
            );
        } catch (IllegalStateException e) {
            log.warn("F03.11 募集枠編集失敗: id={}, reason={}", listingId, e.getMessage());
            throw new BusinessException(RecruitmentErrorCode.INVALID_STATE_TRANSITION);
        }

        RecruitmentListingEntity saved = listingRepository.save(entity);
        log.info("F03.11 募集枠編集: id={}", listingId);
        return mapper.toListingResponse(saved);
    }

    @Transactional
    public RecruitmentListingResponse publish(Long listingId, Long userId) {
        RecruitmentListingEntity entity = listingRepository.findByIdForUpdate(listingId)
                .orElseThrow(() -> new BusinessException(RecruitmentErrorCode.LISTING_NOT_FOUND));
        accessControlService.checkAdminOrAbove(userId, entity.getScopeId(), entity.getScopeType().name());

        // Phase 2: §5.1 ステップ4 配信対象0件チェック (RECRUITMENT_204)
        int targetCount = distributionTargetRepository.countByListingId(listingId);
        if (targetCount == 0) {
            throw new BusinessException(RecruitmentErrorCode.EMPTY_DISTRIBUTION_TARGETS);
        }

        // Phase 2: §5.1 visibility と distribution_targets の整合性チェック (RECRUITMENT_207)
        List<RecruitmentDistributionTargetEntity> targets = distributionTargetRepository.findByListingId(listingId);
        validateVisibilityAndTargets(entity.getVisibility(), targets);

        try {
            entity.publish();
        } catch (IllegalStateException e) {
            throw new BusinessException(RecruitmentErrorCode.INVALID_STATE_TRANSITION);
        }

        RecruitmentListingEntity saved = listingRepository.save(entity);

        // Phase 2: RECRUITMENT_PUBLISHED 通知送信
        sendPublishedNotifications(saved, targets);

        log.info("F03.11 募集枠公開: id={} → OPEN, targets={}", listingId, targetCount);
        return mapper.toListingResponse(saved);
    }

    /**
     * Phase 2: 管理者による申込確定 + リマインダー作成 + RECRUITMENT_CONFIRMED 通知。
     *
     * @param participantId 参加者ID
     * @param adminId       実行管理者ID
     * @return 更新された参加者レスポンス
     */
    @Transactional
    public RecruitmentParticipantResponse confirmApplication(Long participantId, Long adminId) {
        RecruitmentParticipantEntity participant = participantRepository.findByIdForUpdate(participantId)
                .orElseThrow(() -> new BusinessException(RecruitmentErrorCode.LISTING_NOT_FOUND));

        RecruitmentListingEntity listing = listingRepository.findByIdForUpdate(participant.getListingId())
                .orElseThrow(() -> new BusinessException(RecruitmentErrorCode.LISTING_NOT_FOUND));
        accessControlService.checkAdminOrAbove(adminId, listing.getScopeId(), listing.getScopeType().name());

        if (participant.getStatus() != RecruitmentParticipantStatus.APPLIED) {
            throw new BusinessException(RecruitmentErrorCode.INVALID_STATE_TRANSITION);
        }

        participant.confirm();
        participantRepository.save(participant);

        // 履歴記録
        participantHistoryRepository.save(RecruitmentParticipantHistoryEntity.builder()
                .participantId(participant.getId())
                .listingId(participant.getListingId())
                .oldStatus(RecruitmentParticipantStatus.APPLIED)
                .newStatus(RecruitmentParticipantStatus.CONFIRMED)
                .changedBy(adminId)
                .changeReason(com.mannschaft.app.recruitment.ParticipantHistoryReason.ADMIN_ACTION)
                .build());

        // listing の confirmed_count をインクリメント
        listingRepository.incrementConfirmedAtomic(participant.getListingId());

        // リマインダー作成 (start_at - 24h UTC)
        LocalDateTime remindAt = listing.getStartAt().minusHours(24);
        if (remindAt.isAfter(LocalDateTime.now())) {
            reminderRepository.save(RecruitmentReminderEntity.builder()
                    .listingId(listing.getId())
                    .participantId(participant.getId())
                    .remindAt(remindAt)
                    .build());
        }

        // RECRUITMENT_CONFIRMED 通知送信
        if (participant.getUserId() != null) {
            NotificationScopeType scopeType = listing.getScopeType() == RecruitmentScopeType.TEAM
                    ? NotificationScopeType.TEAM : NotificationScopeType.ORGANIZATION;
            notificationHelper.notify(
                    participant.getUserId(),
                    "RECRUITMENT_CONFIRMED",
                    "参加が確定しました",
                    listing.getTitle() + " の参加が確定しました。",
                    "RECRUITMENT_LISTING",
                    listing.getId(),
                    scopeType,
                    listing.getScopeId(),
                    "/recruitment-listings/" + listing.getId(),
                    adminId
            );
        }

        log.info("F03.11 申込確定: participantId={}, listingId={}", participantId, listing.getId());
        return mapper.toParticipantResponse(participant);
    }

    /**
     * Phase 2: 自分の参加予定一覧 (CONFIRMED/WAITLISTED/APPLIED)。
     *
     * @param userId ユーザーID
     * @return 参加予定レスポンスリスト
     */
    public List<RecruitmentParticipantResponse> getMyListings(Long userId) {
        return mapper.toParticipantResponseList(
                participantRepository.findMyActiveParticipations(userId));
    }

    /**
     * Phase 2: フォロー先・サポーター先スコープの最新 OPEN 募集20件。
     *
     * @param userId ユーザーID
     * @return フィードアイテムレスポンスリスト (最大20件)
     */
    public List<RecruitmentFeedItemResponse> getMyFeed(Long userId) {
        // フォロー先チーム・組織の scopeId を収集
        List<Long> followedTeamIds = followRepository.findFollowedIdsByFollowerAndType(
                FollowerType.USER, userId, FollowerType.TEAM);
        List<Long> followedOrgIds = followRepository.findFollowedIdsByFollowerAndType(
                FollowerType.USER, userId, FollowerType.ORGANIZATION);

        // サポーター所属スコープの scopeId を収集 (user_roles から直接取得)
        Set<Long> allScopeIds = new java.util.LinkedHashSet<>();
        allScopeIds.addAll(followedTeamIds);
        allScopeIds.addAll(followedOrgIds);

        // 自身のロール所属チーム・組織IDも追加（サポーターを含む）
        userRoleRepository.findByUserIdAndTeamIdIsNotNull(userId).stream()
                .map(ur -> ur.getTeamId())
                .forEach(allScopeIds::add);
        userRoleRepository.findByUserIdAndOrganizationIdIsNotNull(userId).stream()
                .map(ur -> ur.getOrganizationId())
                .forEach(allScopeIds::add);

        if (allScopeIds.isEmpty()) {
            return List.of();
        }

        List<RecruitmentListingEntity> listings = listingRepository.findOpenByScopeIds(
                new ArrayList<>(allScopeIds), PageRequest.of(0, 20));
        return mapper.toFeedItemResponseList(listings);
    }

    @Transactional
    public RecruitmentListingResponse cancelByAdmin(Long listingId, Long userId, CancelRecruitmentListingRequest request) {
        RecruitmentListingEntity entity = listingRepository.findByIdForUpdate(listingId)
                .orElseThrow(() -> new BusinessException(RecruitmentErrorCode.LISTING_NOT_FOUND));
        accessControlService.checkAdminOrAbove(userId, entity.getScopeId(), entity.getScopeType().name());

        try {
            entity.cancelByAdmin(userId, request != null ? request.getReason() : null);
        } catch (IllegalStateException e) {
            throw new BusinessException(RecruitmentErrorCode.ALREADY_CANCELLED);
        }

        RecruitmentListingEntity saved = listingRepository.save(entity);
        log.info("F03.11 募集枠キャンセル(主催者): id={}", listingId);
        return mapper.toListingResponse(saved);
    }

    @Transactional
    public void archive(Long listingId, Long userId) {
        RecruitmentListingEntity entity = listingRepository.findByIdForUpdate(listingId)
                .orElseThrow(() -> new BusinessException(RecruitmentErrorCode.LISTING_NOT_FOUND));
        accessControlService.checkAdminOrAbove(userId, entity.getScopeId(), entity.getScopeType().name());

        entity.softDelete();
        listingRepository.save(entity);
        log.info("F03.11 募集枠論理削除: id={}", listingId);
    }

    // ===========================================
    // Phase 2: 配信対象設定 (§9.3)
    // ===========================================

    /**
     * 募集の配信対象を設定する (再設定は全削除→再INSERT)。
     *
     * @param listingId   募集ID
     * @param userId      実行ユーザーID
     * @param targetTypes 配信対象種別リスト
     * @return 設定後の配信対象レスポンスリスト
     */
    @Transactional
    public List<com.mannschaft.app.recruitment.dto.RecruitmentDistributionTargetResponse> setDistributionTargets(
            Long listingId, Long userId,
            List<RecruitmentDistributionTargetType> targetTypes) {
        RecruitmentListingEntity entity = findOrThrow(listingId);
        accessControlService.checkAdminOrAbove(userId, entity.getScopeId(), entity.getScopeType().name());

        // 全削除→再INSERT
        distributionTargetRepository.deleteByListingId(listingId);
        List<RecruitmentDistributionTargetEntity> saved = targetTypes.stream()
                .distinct()
                .map(type -> distributionTargetRepository.save(
                        RecruitmentDistributionTargetEntity.builder()
                                .listingId(listingId)
                                .targetType(type)
                                .build()))
                .collect(Collectors.toList());

        log.info("F03.11 配信対象設定: listingId={}, types={}", listingId, targetTypes);
        return saved.stream()
                .map(t -> new com.mannschaft.app.recruitment.dto.RecruitmentDistributionTargetResponse(
                        t.getId(), t.getListingId(), t.getTargetType().name(), t.getCreatedAt()))
                .collect(Collectors.toList());
    }

    /**
     * 募集の配信対象を取得する。
     */
    public List<com.mannschaft.app.recruitment.dto.RecruitmentDistributionTargetResponse> getDistributionTargets(
            Long listingId, Long userId) {
        RecruitmentListingEntity entity = findOrThrow(listingId);
        accessControlService.checkAdminOrAbove(userId, entity.getScopeId(), entity.getScopeType().name());
        return distributionTargetRepository.findByListingId(listingId).stream()
                .map(t -> new com.mannschaft.app.recruitment.dto.RecruitmentDistributionTargetResponse(
                        t.getId(), t.getListingId(), t.getTargetType().name(), t.getCreatedAt()))
                .collect(Collectors.toList());
    }

    // ===========================================
    // Phase 2 プライベートヘルパー
    // ===========================================

    /**
     * visibility と distribution_targets の整合性を検証する (RECRUITMENT_207)。
     * - PUBLIC → PUBLIC_FEED が含まれていること
     * - SUPPORTERS_ONLY → SUPPORTERS が含まれていること
     */
    private void validateVisibilityAndTargets(
            RecruitmentVisibility visibility,
            List<RecruitmentDistributionTargetEntity> targets) {
        // CUSTOM_TEMPLATE は distribution_targets の制約なし（テンプレートが判定を担う）
        if (visibility == RecruitmentVisibility.CUSTOM_TEMPLATE) {
            return;
        }

        Set<RecruitmentDistributionTargetType> typeSet = targets.stream()
                .map(RecruitmentDistributionTargetEntity::getTargetType)
                .collect(Collectors.toSet());

        if (visibility == RecruitmentVisibility.PUBLIC
                && !typeSet.contains(RecruitmentDistributionTargetType.PUBLIC_FEED)) {
            throw new BusinessException(RecruitmentErrorCode.VISIBILITY_TARGETS_INCONSISTENT);
        }
        if (visibility == RecruitmentVisibility.SUPPORTERS_ONLY
                && !typeSet.contains(RecruitmentDistributionTargetType.SUPPORTERS)) {
            throw new BusinessException(RecruitmentErrorCode.VISIBILITY_TARGETS_INCONSISTENT);
        }
    }

    /**
     * RECRUITMENT_PUBLISHED 通知を配信対象ユーザーに送信する。
     */
    private void sendPublishedNotifications(
            RecruitmentListingEntity listing,
            List<RecruitmentDistributionTargetEntity> targets) {
        NotificationScopeType scopeType = listing.getScopeType() == RecruitmentScopeType.TEAM
                ? NotificationScopeType.TEAM : NotificationScopeType.ORGANIZATION;
        String scopeTypeName = listing.getScopeType().name();
        Long scopeId = listing.getScopeId();

        Set<Long> notifiedUserIds = new java.util.LinkedHashSet<>();

        for (RecruitmentDistributionTargetEntity target : targets) {
            List<Long> userIds;
            switch (target.getTargetType()) {
                case MEMBERS -> userIds = userRoleRepository.findUserIdsByScope(scopeTypeName, scopeId);
                case SUPPORTERS -> userIds = userRoleRepository.findUserIdsByScope(scopeTypeName, scopeId);
                case FOLLOWERS -> {
                    FollowerType followedType = listing.getScopeType() == RecruitmentScopeType.TEAM
                            ? FollowerType.TEAM : FollowerType.ORGANIZATION;
                    // フォロワー全員のuserIdを取得 (FollowEntity の followerId)
                    userIds = followRepository.findByFollowedTypeAndFollowedIdOrderByCreatedAtDesc(
                                    followedType, scopeId,
                                    org.springframework.data.domain.PageRequest.of(0, 10000))
                            .stream()
                            .filter(f -> f.getFollowerType() == FollowerType.USER)
                            .map(com.mannschaft.app.social.entity.FollowEntity::getFollowerId)
                            .collect(Collectors.toList());
                }
                case PUBLIC_FEED -> {
                    // PUBLIC_FEED は通知ではなく公開フィード掲載のため個別通知はしない
                    userIds = List.of();
                }
                default -> userIds = List.of();
            }
            notifiedUserIds.addAll(userIds);
        }

        String title = "新着募集: " + listing.getTitle();
        String body = listing.getTitle() + " の募集が公開されました。";
        String actionUrl = "/recruitment-listings/" + listing.getId();

        notificationHelper.notifyAll(
                new ArrayList<>(notifiedUserIds),
                "RECRUITMENT_PUBLISHED",
                title, body,
                "RECRUITMENT_LISTING", listing.getId(),
                scopeType, scopeId,
                actionUrl, listing.getCreatedBy()
        );
        log.info("F03.11 RECRUITMENT_PUBLISHED 通知送信: listingId={}, targetUsers={}",
                listing.getId(), notifiedUserIds.size());
    }

    // ===========================================
    // §5.6 予約ライン衝突チェック (Phase 4 で本実装)
    // ===========================================

    /**
     * Phase 1 ではスタブ。常に false (衝突なし) を返す。
     * Phase 4 で reservation_lines / 既存 recruitment_listings との衝突を SQL で判定する予定。
     */
    // ===========================================
    // §Phase4 全体検索
    // ===========================================

    /**
     * Phase 4 全体検索 — 認証不要。
     * startFrom / startTo は ISO8601 文字列 or null。
     * null の場合は条件を無視する（全期間）。
     */
    public Page<RecruitmentListingSummaryResponse> searchPublicListings(
            Long categoryId, Long subcategoryId,
            String startFrom, String startTo,
            String participationType,
            String keyword, String location,
            Pageable pageable) {
        LocalDateTime fromDt = startFrom != null ? LocalDateTime.parse(startFrom) : null;
        LocalDateTime toDt = startTo != null ? LocalDateTime.parse(startTo) : null;
        Page<RecruitmentListingEntity> page = listingRepository.searchPublicListings(
                categoryId, subcategoryId, fromDt, toDt, participationType, keyword, location, pageable);
        return page.map(mapper::toListingSummaryResponse);
    }

    // ===========================================
    // §5.6 予約ライン衝突チェック (Phase 4 本実装)
    // ===========================================

    /**
     * §5.6 予約ライン衝突チェック。
     * 同じ予約ライン上の既存募集（キャンセル以外）と時間帯が重複するか確認する。
     * excludeId は更新時に自分自身を除外するために使用。新規作成時は null を渡す。
     */
    private boolean checkLineCollision(Long lineId, LocalDateTime startAt, LocalDateTime endAt) {
        if (lineId == null || startAt == null || endAt == null) {
            return false;
        }
        return listingRepository.countOverlappingByLine(lineId, startAt, endAt, null) > 0;
    }

    // ===========================================
    // §5.1 CHECK 制約相当の防御的検証
    // ===========================================

    private void validateListingFields(
            LocalDateTime startAt, LocalDateTime endAt,
            LocalDateTime applicationDeadline, LocalDateTime autoCancelAt,
            Integer capacity, Integer minCapacity,
            Boolean paymentEnabled, Integer price) {
        if (minCapacity > capacity) {
            throw new BusinessException(RecruitmentErrorCode.INVALID_CAPACITY);
        }
        if (!startAt.isBefore(endAt)) {
            throw new BusinessException(RecruitmentErrorCode.INVALID_STATE_TRANSITION);
        }
        if (!applicationDeadline.isBefore(startAt)) {
            throw new BusinessException(RecruitmentErrorCode.INVALID_STATE_TRANSITION);
        }
        if (autoCancelAt.isAfter(applicationDeadline)) {
            throw new BusinessException(RecruitmentErrorCode.INVALID_STATE_TRANSITION);
        }
        if (Boolean.TRUE.equals(paymentEnabled) && price == null) {
            throw new BusinessException(RecruitmentErrorCode.PRICE_REQUIRED);
        }
    }
}
