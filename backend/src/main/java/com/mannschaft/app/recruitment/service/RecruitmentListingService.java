package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.market.MarketErrorCode;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.payment.connect.ConnectPaymentErrorCode;
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
import com.mannschaft.app.recruitment.dto.CreateRecruitmentListingRequest.AudienceScopeRequest;
import com.mannschaft.app.recruitment.dto.CreateRecruitmentListingRequest.RecruitmentAudienceScopeType;
import com.mannschaft.app.recruitment.entity.RecruitmentListingAudienceScopeEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentCancellationPolicyEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentDistributionTargetEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentParticipantEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentParticipantHistoryEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentReminderEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentTemplateEntity;
import com.mannschaft.app.recruitment.event.RecruitmentParticipantConfirmedEvent;
import com.mannschaft.app.recruitment.repository.RecruitmentCategoryRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentDistributionTargetRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentParticipantHistoryRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentParticipantRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentReminderRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentTemplateRepository;
import com.mannschaft.app.recruitment.util.LikeEscapeUtil;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.social.FollowerType;
import com.mannschaft.app.social.repository.FollowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
    private final ApplicationEventPublisher eventPublisher;
    private final UserRoleRepository userRoleRepository;
    private final FollowRepository followRepository;
    private final NotificationHelper notificationHelper;
    private final AccessControlService accessControlService;
    // Issue #2715 ロットA: 通知本文の i18n 化。auth の UserRepository を直接呼ばず、
    // common.i18n 配下の共有サービス経由で受信者 locale を解決する（ArchUnit D-5 対応）。
    private final UserLocaleCache userLocaleCache;
    private final MessageSource messageSource;
    private final RecruitmentMapper mapper;
    private final RecruitmentTemplateService templateService;
    private final RecruitmentTemplateRepository templateRepository;
    // #2497: 募集枠の論理削除に伴う「未解決の異議」自動取下げ（同一 recruitment ドメイン内の委譲）
    private final RecruitmentNoShowService noShowService;
    private final ContentVisibilityChecker visibilityChecker;
    // F22.1 市: 地域整合・フレンド宛先
    private final MarketRegionValidator marketRegionValidator;
    private final MarketFriendTargetService marketFriendTargetService;
    private final MarketResponseEnricher marketResponseEnricher;
    private final com.mannschaft.app.recruitment.repository.RecruitmentFriendTargetRepository friendTargetRepository;
    // F22.1 市 Phase 2 足場C: 札立て地域の team 既定補完（read-only 横断クエリ）
    private final com.mannschaft.app.team.service.TeamService teamService;
    // F22.1 市 Phase 2 D: 複数地域募集（N:N）の中間表
    private final com.mannschaft.app.recruitment.repository.RecruitmentListingRegionRepository listingRegionRepository;
    private final com.mannschaft.app.recruitment.repository.RecruitmentListingAudienceScopeRepository audienceScopeRepository;

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
        // PERSONAL の公開後レスポンスは閲覧者別の表示名・PII 抑制・no-store を担う
        // /api/v1/public/market/** に一本化する。汎用詳細 DTO は scopeId / createdBy 等の
        // 内部 ID を含むため、OPEN/FULL の PERSONAL をここから返してはならない。
        if (entity.getScopeType() == RecruitmentScopeType.PERSONAL
                && entity.getStatus() != RecruitmentListingStatus.DRAFT) {
            throw new BusinessException(MarketErrorCode.LISTING_NOT_FOUND);
        }
        // DRAFT は作成者・スコープ ADMIN のみ閲覧可（機能側ローカル要件）。
        // F00 共通基盤の DRAFT 規約は「作成者 + SystemAdmin のみ」だが、
        // Recruitment 機能では従来から TEAM/ORG ADMIN にも DRAFT 閲覧を許可しており、
        // ローカル要件として本ガードで先に通過判定する。
        if (entity.getStatus() == RecruitmentListingStatus.DRAFT) {
            boolean isCreator = entity.getCreatedBy().equals(userId);
            if (entity.getScopeType() == RecruitmentScopeType.PERSONAL) {
                if (!isCreator || !entity.getScopeId().equals(userId)) {
                    throw new BusinessException(RecruitmentErrorCode.DRAFT_VIEW_DENIED);
                }
                return mapper.toListingResponse(entity);
            }
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
        validatePersonalCreate(scopeType, userId, request);
        checkListingManagementAccess(scopeType, scopeId, userId, null);

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

        // F22.1 市 謝礼決済: 受領主体（payeeKind/payeeUserId）の検証・正規化（02_api_design §3）。
        //   payment_enabled=true ⇒ payee_kind 必須（PAYMENT_C011）／payee_kind=USER ⇒ payee_user_id 必須（C012）
        //   ＆札主 scope 所属者に限定（C013・IDOR 防止）／非 USER は payee_user_id を NULL に正規化。
        boolean paymentEnabled = Boolean.TRUE.equals(request.getPaymentEnabled());
        String effectivePayeeKind = paymentEnabled ? request.getPayeeKind() : null;
        Long effectivePayeeUserId = validateAndNormalizePayee(
                scopeType, scopeId, paymentEnabled,
                request.getPayeeKind(), request.getPayeeUserId());

        // §5.6 予約ライン衝突チェック (Phase 4 で本実装、Phase 1 はスタブ)
        if (request.getReservationLineId() != null && checkLineCollision(
                request.getReservationLineId(), request.getStartAt(), request.getEndAt())) {
            throw new BusinessException(RecruitmentErrorCode.LINE_TIME_CONFLICT);
        }

        // F22.1 市 Phase 2 D: 複数地域募集（N:N）。
        //   request.regions 指定があればそれを正規化・検証し、代表（先頭）を旧単一列に同期する。
        //   未指定なら従来どおり単一 prefectureCode/cityCode を 1 件として扱う（後方互換）。
        //   scope=TEAM かつ地域指定が一切なければ team の地域を既定補完（足場C）。
        List<MarketRegionValidator.ResolvedRegion> resolvedRegions =
                resolveCreateRegions(scopeType, scopeId, request);

        // 代表（先頭）を旧単一列へ同期（後方互換読み）。地域なしは両 null。
        MarketRegionValidator.ResolvedRegion representative = resolvedRegions.isEmpty()
                ? new MarketRegionValidator.ResolvedRegion(null, null)
                : resolvedRegions.get(0);

        // F22.1 市: フレンド宛先・配信対象の整合検証（MARKET_002〜005）
        boolean isFriendOnly = request.getVisibility() == RecruitmentVisibility.FRIEND_TEAMS_ONLY;
        marketFriendTargetService.validate(
                scopeType, scopeId, isFriendOnly,
                request.getFriendTargets(), request.getDistributionTargets());

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
                .paymentEnabled(paymentEnabled)
                .price(request.getPrice())
                .payeeKind(effectivePayeeKind)
                .payeeUserId(effectivePayeeUserId)
                .visibility(request.getVisibility())
                // PERSONAL は将来の呼出側変更でも Phase 2 の DRAFT 不変条件を失わない。
                .status(RecruitmentListingStatus.DRAFT)
                .location(request.getLocation())
                .prefectureCode(representative.prefectureCode())
                .cityCode(representative.cityCode())
                .reservationLineId(request.getReservationLineId())
                .imageUrl(request.getImageUrl())
                .cancellationPolicyId(request.getCancellationPolicyId())
                .createdBy(userId)
                .build();

        RecruitmentListingEntity saved = listingRepository.save(entity);

        // F22.1 市: フレンド宛先を保存（検証済み）
        marketFriendTargetService.replaceTargets(
                saved.getId(), isFriendOnly, request.getFriendTargets());

        // F22.1 市 Phase 2 D: 複数地域（N:N）を中間表へ replace（検証済み・代表は旧単一列に同期済み）。
        replaceListingRegions(saved.getId(), resolvedRegions);
        if (scopeType == RecruitmentScopeType.PERSONAL) {
            replaceAudienceScopes(saved.getId(), request.getAudienceScopes());
        }

        log.info("F03.11 募集枠作成: id={}, scope={}/{}, status=DRAFT, regions={}",
                saved.getId(), scopeType, scopeId, resolvedRegions.size());
        return marketResponseEnricher.enrich(mapper.toListingResponse(saved), saved);
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

        // テンプレートのスコープと一致することを確認。
        // 越境は TEMPLATE_SCOPE_MISMATCH = 404 で、不在（TEMPLATE_NOT_FOUND = 404）と同一ステータス。
        // 従来は本コードが ERROR_CODE_STATUS_MAP 未登録で既定 400 に落ちており、templateId の列挙で
        // 他スコープのテンプレートの実在が判別できた（存在オラクル）。
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
                // F22.1 市 謝礼決済: テンプレートには受領主体（payee）の既定列が無いため、テンプレート経由作成では
                // 謝礼決済を無効固定にする（保守的既定）。template.default_payment_enabled=true でも payee 未指定では
                // CHECK（chk_rl_payee）を満たせないため、payment_enabled=true で起票せず DRAFT は決済無効で作る。
                // 謝礼決済を有効にしたい札は通常の create/update で payeeKind を指定して設定する。
                false,
                template.getDefaultPrice(),
                template.getDefaultVisibility(),
                template.getDefaultLocation(),
                template.getDefaultReservationLineId(),
                template.getDefaultImageUrl(),
                policyId,
                // F22.1 市: テンプレート経由作成では地域・フレンド宛先・配信対象は未指定
                null,   // prefectureCode
                null,   // cityCode
                null,   // friendTargets
                null,   // distributionTargets
                null,   // regions（複数地域・Phase2 D）
                // F22.1 市 謝礼決済: テンプレートに payee 既定列が無いため受領主体は未指定（payee null）。
                // テンプレート default_payment_enabled=true が来ても、payeeKind 未指定では検証で弾かれるため、
                // create() 側で payment_enabled を payeeKind 整合に倒さず素直に検証へ通す（CHECK 違反を起こさない）。
                null,   // payeeKind
                null    // payeeUserId
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
        return updateInternal(listingId, userId, request, false);
    }

    @Transactional
    public RecruitmentListingResponse updatePersonalDraft(Long listingId, Long userId,
            UpdateRecruitmentListingRequest request) {
        return updateInternal(listingId, userId, request, true);
    }

    private RecruitmentListingResponse updateInternal(Long listingId, Long userId,
            UpdateRecruitmentListingRequest request, boolean personalRoute) {
        // §5.7 編集時の制約 — PESSIMISTIC_WRITE で行ロック取得
        RecruitmentListingEntity entity = personalRoute
                ? listingRepository.findByIdAndScopeTypeAndScopeIdForUpdate(
                        listingId, RecruitmentScopeType.PERSONAL, userId)
                        .orElseThrow(() -> new BusinessException(
                                com.mannschaft.app.market.MarketErrorCode.LISTING_NOT_FOUND))
                : listingRepository.findByIdForUpdate(listingId)
                        .orElseThrow(() -> new BusinessException(RecruitmentErrorCode.LISTING_NOT_FOUND));
        if (!personalRoute && entity.getScopeType() == RecruitmentScopeType.PERSONAL) {
            throw new BusinessException(com.mannschaft.app.market.MarketErrorCode.LISTING_NOT_FOUND);
        }
        checkListingManagementAccess(entity.getScopeType(), entity.getScopeId(), userId, entity.getCreatedBy());
        validatePersonalUpdate(entity, userId, request);
        if (personalRoute && entity.getStatus() != RecruitmentListingStatus.DRAFT) {
            throw new BusinessException(RecruitmentErrorCode.INVALID_STATE_TRANSITION);
        }

        // Service 層でも事前検証 (Entity 内に防御的二重検証あり)
        if (entity.getStatus() == RecruitmentListingStatus.COMPLETED) {
            throw new BusinessException(RecruitmentErrorCode.COMPLETED_NOT_EDITABLE);
        }
        if (request.getCapacity() != null && request.getCapacity() < entity.getConfirmedCount()) {
            throw new BusinessException(RecruitmentErrorCode.CAPACITY_BELOW_CONFIRMED);
        }

        // F22.1 市 謝礼決済: 受領主体の編集検証（PATCH・02_api_design §3）。
        //   effective 値（リクエスト未指定なら現状維持）で検証する。payee_kind=USER の受領者は札主 scope 所属者に
        //   限定（C013・IDOR 防止）。entity.updateForEdit も防御的に再検証するが、所属検証（repository 要）は Service。
        boolean effectivePaymentEnabled = request.getPaymentEnabled() != null
                ? request.getPaymentEnabled() : entity.getPaymentEnabled();
        String effectivePayeeKind = request.getPayeeKind() != null
                ? request.getPayeeKind() : entity.getPayeeKind();
        Long effectivePayeeUserId = request.getPayeeUserId() != null
                ? request.getPayeeUserId() : entity.getPayeeUserId();
        validateAndNormalizePayee(
                entity.getScopeType(), entity.getScopeId(),
                Boolean.TRUE.equals(effectivePaymentEnabled),
                effectivePayeeKind, effectivePayeeUserId);

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
                    request.getCancellationPolicyId(),
                    request.getPayeeKind(),
                    request.getPayeeUserId()
            );
        } catch (IllegalStateException e) {
            log.warn("F03.11 募集枠編集失敗: id={}, reason={}", listingId, e.getMessage());
            throw new BusinessException(RecruitmentErrorCode.INVALID_STATE_TRANSITION);
        }

        // F22.1 市 Phase 2 D: 地域コードの変更（§6.5・複数地域 N:N 対応）。
        //   regions 指定（非 null）→ 中間表を全置換し代表を旧単一列に同期。空配列はクリア。
        //   regions 未指定（null）で単一フィールドのいずれか指定 → 後方互換で 1 件として置換。
        //   いずれも未指定 → 地域変更なし。
        List<MarketRegionValidator.ResolvedRegion> updatedRegions = resolveUpdateRegions(request);
        if (updatedRegions != null) {
            MarketRegionValidator.ResolvedRegion representative = updatedRegions.isEmpty()
                    ? new MarketRegionValidator.ResolvedRegion(null, null)
                    : updatedRegions.get(0);
            entity.updateRegion(representative.prefectureCode(), representative.cityCode());
            replaceListingRegions(listingId, updatedRegions);
        }

        if (entity.getScopeType() == RecruitmentScopeType.PERSONAL) {
            replacePersonalAudienceScopesIfNeeded(entity, request);
        }

        RecruitmentListingEntity saved = listingRepository.save(entity);
        log.info("F03.11 募集枠編集: id={}", listingId);
        return marketResponseEnricher.enrich(mapper.toListingResponse(saved), saved);
    }

    @Transactional
    public RecruitmentListingResponse publish(Long listingId, Long userId) {
        RecruitmentListingEntity entity = listingRepository.findByIdForUpdate(listingId)
                .orElseThrow(() -> new BusinessException(RecruitmentErrorCode.LISTING_NOT_FOUND));
        checkListingManagementAccess(entity.getScopeType(), entity.getScopeId(), userId, entity.getCreatedBy());
        RecruitmentOperationalScopeGuard.requireVisibilityConfigurable(entity);

        // F22.1 市: FRIEND_TEAMS_ONLY は distribution_targets を使わず、フレンド宛先で配信する（§3 / §7）。
        boolean isFriendOnly = entity.getVisibility() == RecruitmentVisibility.FRIEND_TEAMS_ONLY;
        if (isFriendOnly) {
            // 宛先0件は MARKET_002（OPEN 遷移を許さない）。
            if (friendTargetRepository.countByListingId(listingId) == 0) {
                throw new BusinessException(
                        com.mannschaft.app.market.MarketErrorCode.FRIEND_TARGETS_REQUIRED);
            }
            try {
                entity.publish();
            } catch (IllegalStateException e) {
                throw new BusinessException(RecruitmentErrorCode.INVALID_STATE_TRANSITION);
            }
            RecruitmentListingEntity saved = listingRepository.save(entity);
            // フレンドチーム管理者へ「届いた札」配信（§7）。
            marketFriendTargetService.distributeFriendListing(saved);
            log.info("F22.1 市: フレンド宛非公開札を公開: id={} → OPEN", listingId);
            return marketResponseEnricher.enrich(mapper.toListingResponse(saved), saved);
        }

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
        return marketResponseEnricher.enrich(mapper.toListingResponse(saved), saved);
    }

    /** 個人札だけの公開。汎用 publish は PERSONAL を引き続き拒否する。 */
    @Transactional
    public RecruitmentListingResponse publishPersonal(Long listingId, Long userId) {
        RecruitmentListingEntity entity = listingRepository
                .findByIdAndScopeTypeAndScopeIdForUpdate(listingId, RecruitmentScopeType.PERSONAL, userId)
                .orElseThrow(() -> new BusinessException(MarketErrorCode.LISTING_NOT_FOUND));
        checkListingManagementAccess(entity.getScopeType(), entity.getScopeId(), userId, entity.getCreatedBy());
        if (entity.getCreatedBy() == null || !entity.getCreatedBy().equals(userId)) {
            throw new BusinessException(MarketErrorCode.LISTING_NOT_FOUND);
        }
        validatePersonalPaymentState(entity);
        if (entity.getVisibility() != RecruitmentVisibility.PUBLIC
                && entity.getVisibility() != RecruitmentVisibility.SELECTED_SCOPES) {
            throw new BusinessException(MarketErrorCode.PERSONAL_VISIBILITY_NOT_ALLOWED);
        }
        if (entity.getVisibility() == RecruitmentVisibility.SELECTED_SCOPES
                && audienceScopeRepository.countByListingId(entity.getId()) == 0) {
            throw new BusinessException(MarketErrorCode.PERSONAL_VISIBILITY_NOT_ALLOWED);
        }
        try {
            entity.publish();
        } catch (IllegalStateException e) {
            throw new BusinessException(RecruitmentErrorCode.INVALID_STATE_TRANSITION);
        }
        RecruitmentListingEntity saved = listingRepository.save(entity);
        // 個人札の公開は個別通知・配信対象を持たない。
        return marketResponseEnricher.enrich(mapper.toListingResponse(saved), saved);
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
        RecruitmentOperationalScopeGuard.requireTeamOrOrganization(listing);
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

        // 旧来の管理者確定経路でも、応募者本人が決済確認を再開できるよう謝礼の与信を起票する。
        if (Boolean.TRUE.equals(listing.getPaymentEnabled())
                && listing.getPrice() != null
                && participant.getUserId() != null) {
            eventPublisher.publishEvent(new RecruitmentParticipantConfirmedEvent(
                    listing.getId(),
                    participant.getId(),
                    participant.getUserId(),
                    listing.getScopeType().name(),
                    listing.getScopeId(),
                    listing.getPayeeKind(),
                    listing.getPayeeUserId(),
                    listing.getPrice().longValue(),
                    listing.getStartAt()));
        }

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
            Locale locale = Locale.forLanguageTag(userLocaleCache.getLocale(participant.getUserId()));
            String title = messageSource.getMessage(
                    "notification.recruitment.confirmed.title", null, "参加が確定しました", locale);
            String body = messageSource.getMessage(
                    "notification.recruitment.confirmed.body", new Object[]{listing.getTitle()},
                    listing.getTitle() + " の参加が確定しました。", locale);
            notificationHelper.notify(
                    participant.getUserId(),
                    "RECRUITMENT_CONFIRMED",
                    title,
                    body,
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

        // 自身の所属チーム・組織IDも追加（CMP-027: user_roles ∪ memberships の在籍。SUPPORTER 含む）
        allScopeIds.addAll(userRoleRepository.findTeamIdsByUserId(userId));
        allScopeIds.addAll(userRoleRepository.findOrganizationIdsByUserId(userId));

        if (allScopeIds.isEmpty()) {
            return List.of();
        }

        List<RecruitmentListingEntity> listings = listingRepository.findOpenByScopeIds(
                new ArrayList<>(allScopeIds), PageRequest.of(0, 20));
        return mapper.toFeedItemResponseList(listings);
    }

    @Transactional
    public RecruitmentListingResponse cancelByAdmin(Long listingId, Long userId, CancelRecruitmentListingRequest request) {
        return cancelInternal(listingId, userId, request, false);
    }

    @Transactional
    public RecruitmentListingResponse cancelPersonalDraft(Long listingId, Long userId,
            CancelRecruitmentListingRequest request) {
        return cancelInternal(listingId, userId, request, true);
    }

    private RecruitmentListingResponse cancelInternal(Long listingId, Long userId,
            CancelRecruitmentListingRequest request, boolean personalRoute) {
        RecruitmentListingEntity entity = personalRoute
                ? listingRepository.findByIdAndScopeTypeAndScopeIdForUpdate(
                        listingId, RecruitmentScopeType.PERSONAL, userId)
                        .orElseThrow(() -> new BusinessException(
                                com.mannschaft.app.market.MarketErrorCode.LISTING_NOT_FOUND))
                : listingRepository.findByIdForUpdate(listingId)
                        .orElseThrow(() -> new BusinessException(RecruitmentErrorCode.LISTING_NOT_FOUND));
        if (!personalRoute) {
            RecruitmentOperationalScopeGuard.requireTeamOrOrganization(entity);
        }
        checkListingManagementAccess(entity.getScopeType(), entity.getScopeId(), userId, entity.getCreatedBy());
        if (personalRoute && entity.getStatus() != RecruitmentListingStatus.DRAFT) {
            throw new BusinessException(RecruitmentErrorCode.INVALID_STATE_TRANSITION);
        }

        try {
            entity.cancelByAdmin(userId, request != null ? request.getReason() : null);
        } catch (IllegalStateException e) {
            throw new BusinessException(RecruitmentErrorCode.ALREADY_CANCELLED);
        }

        RecruitmentListingEntity saved = listingRepository.save(entity);
        log.info("F03.11 募集枠キャンセル(主催者): id={}", listingId);
        return mapper.toListingResponse(saved);
    }

    /**
     * 募集枠を論理削除する。
     *
     * <p><b>#2497: 配下の未解決異議を巻き取る。</b> 募集枠を論理削除すると、NO_SHOW 記録の
     * スコープ帰属を得るための JOIN 先（{@code RecruitmentListingEntity}）が
     * {@code @SQLRestriction("deleted_at IS NULL")} で引けなくなり、
     * <b>異議解決 EP が二度と通らなくなる</b>。一方 {@code countConfirmedNoShows} は
     * 「{@code REVOKED} 以外は算入」のため、未解決の異議はペナルティに算入され続ける。
     * 放置すると利用者は「異議を申し立てたのに永久に裁かれず、ペナルティだけ負う」状態になるため、
     * 論理削除と同一トランザクションで未解決の異議を {@code REVOKED}（認容）として取り下げる。
     * 詳細な根拠は {@link RecruitmentNoShowService#autoRevokeOpenDisputesOnListingArchived} を参照。</p>
     *
     * @param listingId 募集枠 ID
     * @param userId    実行ユーザー ID（スコープ管理者以上）
     */
    @Transactional
    public void archive(Long listingId, Long userId) {
        RecruitmentListingEntity entity = listingRepository.findByIdForUpdate(listingId)
                .orElseThrow(() -> new BusinessException(RecruitmentErrorCode.LISTING_NOT_FOUND));
        RecruitmentOperationalScopeGuard.requireTeamOrOrganization(entity);
        checkListingManagementAccess(entity.getScopeType(), entity.getScopeId(), userId, entity.getCreatedBy());

        entity.softDelete();
        listingRepository.save(entity);

        // #2497: 裁定の根拠（募集枠）が消える前に、未解決の異議をまとめて取り下げる。
        // 同一 recruitment ドメイン内の委譲であり、トランザクションはドメインを越えない。
        int autoRevoked = noShowService.autoRevokeOpenDisputesOnListingArchived(
                listingId, entity.getScopeType(), entity.getScopeId(), userId);

        log.info("F03.11 募集枠論理削除: id={}, 異議自動取下げ={}件", listingId, autoRevoked);
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
        checkListingManagementAccess(entity.getScopeType(), entity.getScopeId(), userId, entity.getCreatedBy());
        RecruitmentOperationalScopeGuard.requireVisibilityConfigurable(entity);

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
        checkListingManagementAccess(entity.getScopeType(), entity.getScopeId(), userId, entity.getCreatedBy());
        RecruitmentOperationalScopeGuard.requireVisibilityConfigurable(entity);
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

        String actionUrl = "/recruitment-listings/" + listing.getId();

        // Issue #2715 ロットA / 検分是正(PR #2764): 受信者ごとに locale を解決して本文を組み立てる必要が
        // あるため、単一文面固定の notifyAll ではなく NotificationHelper#notifyAllLocalized を用いる。
        // 訂正(2026-08-14): 当初「notify を受信者数分ループで直呼びすると可視性フィルタを迂回し
        // 情報漏洩する」としていたが、これは誤り。NotificationService#createNotification は単発経路
        // でも canView による可視性ガードを既に担保しており、notify 直呼びループでも漏洩は無かった。
        // notifyAllLocalized を使う本当の理由は (1) 一括経路でも受信者別 locale の本文を組み立てられる
        // ようにすること、(2) locale をまとめて解決し N+1 を避けること、(3) 前段の
        // filterAccessibleRecipients で閲覧不可ユーザーを先に除外し、どのみち createNotification 側の
        // 可視性ガードで捨てられる分の本文組み立て・notify 呼び出しを無駄に行わないこと、の 3 点。
        // ロットB・C の同種要求にも同じ経路を使う。
        notificationHelper.notifyAllLocalized(
                new ArrayList<>(notifiedUserIds),
                "RECRUITMENT_PUBLISHED",
                "RECRUITMENT_LISTING", listing.getId(),
                scopeType, scopeId,
                actionUrl, listing.getCreatedBy(),
                (userId, locale) -> {
                    String title = messageSource.getMessage(
                            "notification.recruitment.published.title", new Object[]{listing.getTitle()},
                            "新着募集: " + listing.getTitle(), locale);
                    String body = messageSource.getMessage(
                            "notification.recruitment.published.body", new Object[]{listing.getTitle()},
                            listing.getTitle() + " の募集が公開されました。", locale);
                    return new NotificationHelper.LocalizedMessage(title, body);
                });
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
        // 呼び出し側（Controller）で trim・空文字→null 済み。ここで LIKE ワイルドカード
        // （% / _ / \）をエスケープしてフィルタ無効化を防ぐ（JPQL の ESCAPE '\' と対）。null は透過。
        String escapedKeyword = LikeEscapeUtil.escape(keyword);
        String escapedLocation = LikeEscapeUtil.escape(location);
        Page<RecruitmentListingEntity> page = listingRepository.searchPublicListings(
                categoryId, subcategoryId, fromDt, toDt, participationType,
                escapedKeyword, escapedLocation, pageable);
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

    /** null または空白文字列なら {@code true}（F22.1 地域コード未指定判定）。 */
    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // ===========================================
    // F22.1 市 Phase 2 D: 複数地域募集（N:N）ヘルパー
    // ===========================================

    /**
     * 作成リクエストから複数地域を解決・検証する（後方互換 + team 既定補完）。
     *
     * <ol>
     *   <li>{@code request.regions} 指定（非 null かつ非空）→ それを正規化・重複排除して返す</li>
     *   <li>未指定 → 単一 {@code prefectureCode}/{@code cityCode} を 1 ペアとして扱う（後方互換）。
     *       scope=TEAM かつ単一指定も無ければ team の地域で既定補完（足場C）。</li>
     * </ol>
     *
     * @return 正規化・重複排除済みの地域リスト（空＝地域を問わない札）
     */
    private List<MarketRegionValidator.ResolvedRegion> resolveCreateRegions(
            RecruitmentScopeType scopeType, Long scopeId, CreateRecruitmentListingRequest request) {
        // (1) regions 明示指定が優先。
        if (request.getRegions() != null && !request.getRegions().isEmpty()) {
            List<MarketRegionValidator.RegionPair> pairs = request.getRegions().stream()
                    .map(r -> new MarketRegionValidator.RegionPair(r.prefectureCode(), r.cityCode()))
                    .toList();
            return marketRegionValidator.validateAndNormalizeAll(pairs);
        }

        // (2) 後方互換: 単一フィールド + team 既定補完。
        String requestedPrefectureCode = request.getPrefectureCode();
        String requestedCityCode = request.getCityCode();
        if (scopeType == RecruitmentScopeType.TEAM
                && isBlank(requestedPrefectureCode) && isBlank(requestedCityCode)) {
            var teamRegion = teamService.findRegionCodes(scopeId);
            if (teamRegion.isPresent()) {
                requestedPrefectureCode = teamRegion.get().prefectureCode();
                requestedCityCode = teamRegion.get().cityCode();
            }
        }
        MarketRegionValidator.ResolvedRegion single =
                marketRegionValidator.validateAndNormalize(requestedPrefectureCode, requestedCityCode);
        if (single.prefectureCode() == null && single.cityCode() == null) {
            return List.of();
        }
        return List.of(single);
    }

    /**
     * 編集リクエストから複数地域を解決・検証する（後方互換）。
     *
     * <ul>
     *   <li>{@code regions} 非 null（空配列含む）→ それを正規化（空配列はクリア = 空リスト）</li>
     *   <li>{@code regions} null かつ単一フィールドいずれか指定 → 1 ペアとして正規化（後方互換）</li>
     *   <li>いずれも未指定 → {@code null} を返す（地域変更なしの意）</li>
     * </ul>
     *
     * @return 正規化済みの地域リスト（地域変更なしは {@code null}・クリアは空リスト）
     */
    private List<MarketRegionValidator.ResolvedRegion> resolveUpdateRegions(
            UpdateRecruitmentListingRequest request) {
        if (request.getRegions() != null) {
            List<MarketRegionValidator.RegionPair> pairs = request.getRegions().stream()
                    .map(r -> new MarketRegionValidator.RegionPair(r.prefectureCode(), r.cityCode()))
                    .toList();
            return marketRegionValidator.validateAndNormalizeAll(pairs);
        }
        if (request.getPrefectureCode() != null || request.getCityCode() != null) {
            MarketRegionValidator.ResolvedRegion single =
                    marketRegionValidator.validateAndNormalize(
                            request.getPrefectureCode(), request.getCityCode());
            if (single.prefectureCode() == null && single.cityCode() == null) {
                return List.of();
            }
            return List.of(single);
        }
        return null;
    }

    /**
     * 札の地域中間表を全置換する（friendTargets の replace パターン踏襲）。
     * 検証済みの地域リストを前提とする。空リストは全削除のみ（地域を問わない札）。
     *
     * @param listingId 札ID
     * @param regions   正規化済みの地域リスト
     */
    private void replaceListingRegions(
            Long listingId, List<MarketRegionValidator.ResolvedRegion> regions) {
        listingRegionRepository.deleteByListingId(listingId);
        if (regions == null || regions.isEmpty()) {
            return;
        }
        for (MarketRegionValidator.ResolvedRegion r : regions) {
            // 県必須: 中間表は prefecture_code NOT NULL。validateAndNormalize が補完済み。
            listingRegionRepository.save(
                    com.mannschaft.app.recruitment.entity.RecruitmentListingRegionEntity.of(
                            listingId, r.prefectureCode(), r.cityCode()));
        }
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

    /**
     * F22.1 市 謝礼決済: 受領主体（payeeKind/payeeUserId）を検証し、正規化した {@code payeeUserId} を返す
     * （02_api_design §3 / 01_data_model §4.1・DB chk_rl_payee / chk_rl_payee_user 相当）。
     *
     * <p>検証規約:</p>
     * <ul>
     *   <li>{@code paymentEnabled=true} かつ {@code payeeKind} 未指定 → {@code PAYMENT_C011 PAYEE_REQUIRED}</li>
     *   <li>{@code payeeKind=USER} かつ {@code payeeUserId} 未指定 → {@code PAYMENT_C012 PAYEE_USER_REQUIRED}</li>
     *   <li>{@code payeeKind=USER} の {@code payeeUserId} が札主 scope 非所属 → {@code PAYMENT_C013 PAYEE_NOT_IN_SCOPE}
     *       （個人受領者は札主に紐づく者に限定・IDOR 防止）</li>
     *   <li>{@code payeeKind=TEAM/ORG} が札主 scope_type と不一致（例: TEAM 札に ORG 指定）→ {@code PAYMENT_C013}
     *       （TEAM/ORG は札主自身の scope が受領するため scope_type と一致必須・01 §4.1）</li>
     * </ul>
     *
     * <p>{@code paymentEnabled=false} のとき payee は無視する（呼出側で payeeKind=null に倒す）。
     * 戻り値は CHECK 整合を取った {@code payeeUserId}（非 USER は常に {@code null}）。</p>
     *
     * @param scopeType      札主スコープ種別（{@code TEAM}/{@code ORGANIZATION}）
     * @param scopeId        札主スコープ ID
     * @param paymentEnabled 実効 payment_enabled
     * @param payeeKind      受領主体種別（{@code USER}/{@code TEAM}/{@code ORG}・null 可）
     * @param payeeUserId    {@code payeeKind=USER} の受領者（null 可）
     * @return CHECK 整合を取った {@code payeeUserId}（非 USER または決済無効なら {@code null}）
     */
    /**
     * TEAM/ORGANIZATION の既存認可を温存しつつ、PERSONAL は本人だけに束縛する。
     * scopeId だけでは不十分なため、既存札では createdBy との三者一致も確認する。
     */
    private void checkListingManagementAccess(
            RecruitmentScopeType scopeType, Long scopeId, Long userId, Long createdBy) {
        if (scopeType == RecruitmentScopeType.PERSONAL) {
            if (!scopeId.equals(userId) || (createdBy != null && !createdBy.equals(userId))) {
                throw new BusinessException(com.mannschaft.app.common.CommonErrorCode.COMMON_002);
            }
            return;
        }
        accessControlService.checkAdminOrAbove(userId, scopeId, scopeType.name());
    }

    private void validatePersonalCreate(
            RecruitmentScopeType scopeType, Long userId, CreateRecruitmentListingRequest request) {
        if (scopeType != RecruitmentScopeType.PERSONAL) {
            return;
        }
        if (Boolean.TRUE.equals(request.getPaymentEnabled())
                || request.getPayeeKind() != null || request.getPayeeUserId() != null) {
            throw new BusinessException(com.mannschaft.app.market.MarketErrorCode.PERSONAL_PAYMENT_DISABLED);
        }
        if (request.getVisibility() != RecruitmentVisibility.SCOPE_ONLY
                && request.getVisibility() != RecruitmentVisibility.PUBLIC
                && request.getVisibility() != RecruitmentVisibility.SELECTED_SCOPES) {
            throw new BusinessException(com.mannschaft.app.market.MarketErrorCode.PERSONAL_VISIBILITY_NOT_ALLOWED);
        }
        validatePersonalAudienceScopes(userId, request.getVisibility(), request.getAudienceScopes(), false);
    }

    private void validatePersonalUpdate(
            RecruitmentListingEntity entity, Long userId, UpdateRecruitmentListingRequest request) {
        if (entity.getScopeType() != RecruitmentScopeType.PERSONAL) {
            return;
        }
        validatePersonalPaymentState(entity);
        if (Boolean.TRUE.equals(request.getPaymentEnabled())
                || request.getPayeeKind() != null || request.getPayeeUserId() != null) {
            throw new BusinessException(MarketErrorCode.PERSONAL_PAYMENT_DISABLED);
        }
        RecruitmentVisibility effectiveVisibility = request.getVisibility() == null
                ? entity.getVisibility() : request.getVisibility();
        if (effectiveVisibility != RecruitmentVisibility.SCOPE_ONLY
                && effectiveVisibility != RecruitmentVisibility.PUBLIC
                && effectiveVisibility != RecruitmentVisibility.SELECTED_SCOPES) {
            throw new BusinessException(com.mannschaft.app.market.MarketErrorCode.PERSONAL_VISIBILITY_NOT_ALLOWED);
        }
        boolean hasStoredScopes = audienceScopeRepository.countByListingId(entity.getId()) > 0;
        validatePersonalAudienceScopes(
                userId, effectiveVisibility, request.getAudienceScopes(), hasStoredScopes);
    }

    private void validatePersonalPaymentState(RecruitmentListingEntity entity) {
        if (Boolean.TRUE.equals(entity.getPaymentEnabled())
                || entity.getPayeeKind() != null || entity.getPayeeUserId() != null) {
            throw new BusinessException(MarketErrorCode.PERSONAL_PAYMENT_DISABLED);
        }
    }

    /** 公開先は本人の現在の active user_roles ∪ memberships に限る。 */
    private void validatePersonalAudienceScopes(
            Long userId,
            RecruitmentVisibility visibility,
            List<AudienceScopeRequest> requestedScopes,
            boolean hasStoredScopes) {
        if (visibility == RecruitmentVisibility.SELECTED_SCOPES) {
            if (requestedScopes == null) {
                if (!hasStoredScopes) {
                    throw new BusinessException(MarketErrorCode.PERSONAL_VISIBILITY_NOT_ALLOWED);
                }
                return;
            }
            if (requestedScopes.isEmpty()) {
                throw new BusinessException(MarketErrorCode.PERSONAL_VISIBILITY_NOT_ALLOWED);
            }
            Set<Long> activeTeamIds = new LinkedHashSet<>(userRoleRepository.findTeamIdsByUserId(userId));
            Set<Long> activeOrganizationIds = new LinkedHashSet<>(
                    userRoleRepository.findOrganizationIdsByUserId(userId));
            Set<String> seen = new LinkedHashSet<>();
            for (AudienceScopeRequest scope : requestedScopes) {
                if (scope == null || scope.scopeType() == null || scope.scopeId() == null
                        || scope.scopeId() <= 0) {
                    throw new BusinessException(MarketErrorCode.PERSONAL_VISIBILITY_NOT_ALLOWED);
                }
                boolean active = scope.scopeType() == RecruitmentAudienceScopeType.TEAM
                        ? activeTeamIds.contains(scope.scopeId())
                        : activeOrganizationIds.contains(scope.scopeId());
                if (!active || !seen.add(scope.scopeType() + ":" + scope.scopeId())) {
                    throw new BusinessException(MarketErrorCode.PERSONAL_VISIBILITY_NOT_ALLOWED);
                }
            }
            return;
        }
        if (requestedScopes != null && !requestedScopes.isEmpty()) {
            throw new BusinessException(MarketErrorCode.PERSONAL_VISIBILITY_NOT_ALLOWED);
        }
    }

    private void replacePersonalAudienceScopesIfNeeded(
            RecruitmentListingEntity entity, UpdateRecruitmentListingRequest request) {
        RecruitmentVisibility effectiveVisibility = request.getVisibility() == null
                ? entity.getVisibility() : request.getVisibility();
        if (effectiveVisibility != RecruitmentVisibility.SELECTED_SCOPES) {
            audienceScopeRepository.deleteByListingId(entity.getId());
            return;
        }
        if (request.getAudienceScopes() != null) {
            replaceAudienceScopes(entity.getId(), request.getAudienceScopes());
        }
    }

    private void replaceAudienceScopes(Long listingId, List<AudienceScopeRequest> scopes) {
        audienceScopeRepository.deleteByListingId(listingId);
        if (scopes == null) {
            return;
        }
        for (AudienceScopeRequest scope : scopes) {
            audienceScopeRepository.save(RecruitmentListingAudienceScopeEntity.of(
                    listingId, scope.scopeType(), scope.scopeId()));
        }
    }

    private Long validateAndNormalizePayee(
            RecruitmentScopeType scopeType, Long scopeId,
            boolean paymentEnabled, String payeeKind, Long payeeUserId) {
        // 決済無効札では payee を保持しない（CHECK: payment_enabled=FALSE ⇒ 制約なし。安全側で全 null）。
        if (!paymentEnabled) {
            return null;
        }
        if (payeeKind == null || payeeKind.isBlank()) {
            throw new BusinessException(ConnectPaymentErrorCode.PAYEE_REQUIRED);
        }
        switch (payeeKind) {
            case "USER" -> {
                if (payeeUserId == null) {
                    throw new BusinessException(ConnectPaymentErrorCode.PAYEE_USER_REQUIRED);
                }
                // IDOR 防止: 個人受領者は札主 scope の所属者に限定する（02 §3 PAYMENT_C013）。
                if (!accessControlService.isMember(payeeUserId, scopeId, scopeType.name())) {
                    throw new BusinessException(ConnectPaymentErrorCode.PAYEE_NOT_IN_SCOPE);
                }
                return payeeUserId;
            }
            case "TEAM" -> {
                // TEAM 受領は札主自身の scope が TEAM のときのみ整合する（01 §4.1）。
                if (scopeType != RecruitmentScopeType.TEAM) {
                    throw new BusinessException(ConnectPaymentErrorCode.PAYEE_NOT_IN_SCOPE);
                }
                return null; // 非 USER は payee_user_id を NULL に正規化（chk_rl_payee_user）
            }
            case "ORG" -> {
                // payee_kind=ORG は RecruitmentScopeType.ORGANIZATION に対応（文字列不一致・設計書 §4.1 実装注意）。
                if (scopeType != RecruitmentScopeType.ORGANIZATION) {
                    throw new BusinessException(ConnectPaymentErrorCode.PAYEE_NOT_IN_SCOPE);
                }
                return null;
            }
            default -> throw new BusinessException(ConnectPaymentErrorCode.PAYEE_REQUIRED);
        }
    }
}
