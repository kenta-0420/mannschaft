package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.recruitment.RecruitmentErrorCode;
import com.mannschaft.app.recruitment.RecruitmentListingStatus;
import com.mannschaft.app.recruitment.RecruitmentMapper;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.dto.CancelRecruitmentListingRequest;
import com.mannschaft.app.recruitment.dto.CreateRecruitmentListingRequest;
import com.mannschaft.app.recruitment.dto.RecruitmentListingResponse;
import com.mannschaft.app.recruitment.dto.RecruitmentListingSummaryResponse;
import com.mannschaft.app.recruitment.dto.UpdateRecruitmentListingRequest;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentCategoryRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

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
    private final AccessControlService accessControlService;
    private final RecruitmentMapper mapper;

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
        // DRAFT は作成者・ADMIN のみ閲覧可
        if (entity.getStatus() == RecruitmentListingStatus.DRAFT) {
            boolean isCreator = entity.getCreatedBy().equals(userId);
            boolean isAdmin = accessControlService.isAdminOrAbove(userId, entity.getScopeId(), entity.getScopeType().name());
            if (!isCreator && !isAdmin) {
                throw new BusinessException(RecruitmentErrorCode.DRAFT_VIEW_DENIED);
            }
        }
        // SCOPE_ONLY/SUPPORTERS_ONLY 等の visibility チェックは Phase 2 で本格実装
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

        // Phase 2 以降: §5.1 ステップ4 visibility と distribution_targets の整合性チェック
        // Phase 2 以降: 配信通知のキューイング

        try {
            entity.publish();
        } catch (IllegalStateException e) {
            throw new BusinessException(RecruitmentErrorCode.INVALID_STATE_TRANSITION);
        }

        RecruitmentListingEntity saved = listingRepository.save(entity);
        log.info("F03.11 募集枠公開: id={} → OPEN", listingId);
        return mapper.toListingResponse(saved);
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
    // §5.6 予約ライン衝突チェック (Phase 4 で本実装)
    // ===========================================

    /**
     * Phase 1 ではスタブ。常に false (衝突なし) を返す。
     * Phase 4 で reservation_lines / 既存 recruitment_listings との衝突を SQL で判定する予定。
     */
    private boolean checkLineCollision(Long lineId, LocalDateTime startAt, LocalDateTime endAt) {
        return false;
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
