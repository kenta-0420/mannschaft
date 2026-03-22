package com.mannschaft.app.promotion.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.promotion.PromotionErrorCode;
import com.mannschaft.app.promotion.dto.AudienceEstimateResponse;
import com.mannschaft.app.promotion.dto.CreatePromotionRequest;
import com.mannschaft.app.promotion.dto.EstimateAudienceRequest;
import com.mannschaft.app.promotion.dto.PromotionResponse;
import com.mannschaft.app.promotion.dto.PromotionStatsResponse;
import com.mannschaft.app.promotion.dto.SchedulePromotionRequest;
import com.mannschaft.app.promotion.dto.SegmentCondition;
import com.mannschaft.app.promotion.dto.UpdatePromotionRequest;
import com.mannschaft.app.promotion.entity.PromotionDeliverySummaryEntity;
import com.mannschaft.app.promotion.entity.PromotionEntity;
import com.mannschaft.app.promotion.entity.PromotionSegmentEntity;
import com.mannschaft.app.promotion.repository.PromotionDeliverySummaryRepository;
import com.mannschaft.app.promotion.repository.PromotionRepository;
import com.mannschaft.app.promotion.repository.PromotionSegmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * プロモーション管理サービス。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PromotionService {

    private final PromotionRepository promotionRepository;
    private final PromotionSegmentRepository segmentRepository;
    private final PromotionDeliverySummaryRepository summaryRepository;

    /**
     * プロモーション一覧を取得する。
     */
    public Page<PromotionResponse> list(String scopeType, Long scopeId, String status, Pageable pageable) {
        return promotionRepository.findByScopeTypeAndScopeId(scopeType, scopeId, status, pageable)
                .map(entity -> toResponse(entity));
    }

    /**
     * プロモーションを作成する。
     */
    @Transactional
    public PromotionResponse create(String scopeType, Long scopeId, Long userId, CreatePromotionRequest request) {
        PromotionEntity entity = PromotionEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .createdBy(userId)
                .title(request.getTitle())
                .body(request.getBody())
                .imageUrl(request.getImageUrl())
                .couponId(request.getCouponId())
                .expiresAt(request.getExpiresAt())
                .build();
        PromotionEntity saved = promotionRepository.save(entity);

        if (request.getSegments() != null) {
            saveSegments(saved.getId(), request.getSegments());
        }

        log.info("プロモーション作成: scopeType={}, scopeId={}, id={}", scopeType, scopeId, saved.getId());
        return toResponse(saved);
    }

    /**
     * プロモーション詳細を取得する。
     */
    public PromotionResponse get(String scopeType, Long scopeId, Long id) {
        PromotionEntity entity = findOrThrow(scopeType, scopeId, id);
        return toResponse(entity);
    }

    /**
     * プロモーションを更新する。
     */
    @Transactional
    public PromotionResponse update(String scopeType, Long scopeId, Long id, UpdatePromotionRequest request) {
        PromotionEntity entity = findOrThrow(scopeType, scopeId, id);
        if (!entity.isEditable()) {
            throw new BusinessException(PromotionErrorCode.PROMOTION_NOT_EDITABLE);
        }
        entity.update(request.getTitle(), request.getBody(), request.getImageUrl(),
                request.getCouponId(), request.getExpiresAt());
        PromotionEntity saved = promotionRepository.save(entity);

        segmentRepository.deleteByPromotionId(id);
        if (request.getSegments() != null) {
            saveSegments(id, request.getSegments());
        }

        log.info("プロモーション更新: id={}", id);
        return toResponse(saved);
    }

    /**
     * プロモーションを削除する。
     */
    @Transactional
    public void delete(String scopeType, Long scopeId, Long id) {
        PromotionEntity entity = findOrThrow(scopeType, scopeId, id);
        entity.softDelete();
        promotionRepository.save(entity);
        log.info("プロモーション削除: id={}", id);
    }

    /**
     * 即時配信する。
     */
    @Transactional
    public PromotionResponse publish(String scopeType, Long scopeId, Long id) {
        PromotionEntity entity = findOrThrow(scopeType, scopeId, id);
        if (!entity.isPublishable()) {
            throw new BusinessException(PromotionErrorCode.PROMOTION_NOT_PUBLISHABLE);
        }
        // TODO: セグメントに基づいて対象ユーザー数を算出
        int targetCount = 0;
        entity.publish(targetCount);
        PromotionEntity saved = promotionRepository.save(entity);
        log.info("プロモーション即時配信: id={}", id);
        return toResponse(saved);
    }

    /**
     * 予約配信を設定する。
     */
    @Transactional
    public PromotionResponse schedule(String scopeType, Long scopeId, Long id, SchedulePromotionRequest request) {
        PromotionEntity entity = findOrThrow(scopeType, scopeId, id);
        if (!entity.isPublishable()) {
            throw new BusinessException(PromotionErrorCode.PROMOTION_NOT_PUBLISHABLE);
        }
        // TODO: セグメントに基づいて対象ユーザー数を算出
        int targetCount = 0;
        entity.schedule(request.getScheduledAt(), targetCount);
        PromotionEntity saved = promotionRepository.save(entity);
        log.info("プロモーション予約配信: id={}, scheduledAt={}", id, request.getScheduledAt());
        return toResponse(saved);
    }

    /**
     * 配信をキャンセルする。
     */
    @Transactional
    public PromotionResponse cancel(String scopeType, Long scopeId, Long id) {
        PromotionEntity entity = findOrThrow(scopeType, scopeId, id);
        if (!entity.isCancellable()) {
            throw new BusinessException(PromotionErrorCode.PROMOTION_NOT_CANCELLABLE);
        }
        entity.cancel();
        PromotionEntity saved = promotionRepository.save(entity);
        log.info("プロモーションキャンセル: id={}", id);
        return toResponse(saved);
    }

    /**
     * 承認する。
     */
    @Transactional
    public PromotionResponse approve(String scopeType, Long scopeId, Long id, Long approverId) {
        PromotionEntity entity = findOrThrow(scopeType, scopeId, id);
        if (!entity.isApprovable()) {
            throw new BusinessException(PromotionErrorCode.PROMOTION_NOT_APPROVABLE);
        }
        entity.approve(approverId);
        PromotionEntity saved = promotionRepository.save(entity);
        log.info("プロモーション承認: id={}, approvedBy={}", id, approverId);
        return toResponse(saved);
    }

    /**
     * 効果測定データを取得する。
     */
    public PromotionStatsResponse getStats(String scopeType, Long scopeId, Long id) {
        PromotionEntity entity = findOrThrow(scopeType, scopeId, id);
        List<PromotionDeliverySummaryEntity> summaries =
                summaryRepository.findByPromotionIdOrderBySummaryDateAsc(id);

        List<PromotionStatsResponse.DailySummary> dailySummaries = summaries.stream()
                .map(s -> new PromotionStatsResponse.DailySummary(
                        s.getSummaryDate().toString(),
                        s.getDeliveredCount(),
                        s.getOpenedCount(),
                        s.getFailedCount()))
                .collect(Collectors.toList());

        double openRate = entity.getDeliveredCount() > 0
                ? (double) entity.getOpenedCount() / entity.getDeliveredCount() * 100
                : 0.0;

        return new PromotionStatsResponse(
                entity.getId(), entity.getTargetCount(), entity.getDeliveredCount(),
                entity.getOpenedCount(), entity.getSkippedCount(), entity.getFailedCount(),
                openRate, dailySummaries);
    }

    /**
     * 配信対象を見積もる。
     */
    public AudienceEstimateResponse estimateAudience(String scopeType, Long scopeId, EstimateAudienceRequest request) {
        // TODO: セグメント条件に基づいて対象ユーザー数を計算
        int estimatedCount = 0;
        return new AudienceEstimateResponse(estimatedCount);
    }

    private PromotionEntity findOrThrow(String scopeType, Long scopeId, Long id) {
        return promotionRepository.findByIdAndScope(id, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(PromotionErrorCode.PROMOTION_NOT_FOUND));
    }

    private void saveSegments(Long promotionId, List<SegmentCondition> segments) {
        List<PromotionSegmentEntity> entities = new ArrayList<>();
        for (SegmentCondition seg : segments) {
            entities.add(PromotionSegmentEntity.builder()
                    .promotionId(promotionId)
                    .segmentType(seg.getSegmentType())
                    .segmentValue(seg.getSegmentValue())
                    .build());
        }
        segmentRepository.saveAll(entities);
    }

    private PromotionResponse toResponse(PromotionEntity entity) {
        List<PromotionSegmentEntity> segments = segmentRepository.findByPromotionId(entity.getId());
        List<SegmentCondition> segmentDtos = segments.stream()
                .map(s -> new SegmentCondition(s.getSegmentType(), s.getSegmentValue()))
                .collect(Collectors.toList());
        return new PromotionResponse(
                entity.getId(), entity.getScopeType(), entity.getScopeId(),
                entity.getCreatedBy(), entity.getTitle(), entity.getBody(),
                entity.getImageUrl(), entity.getCouponId(), entity.getStatus(),
                entity.getApprovedBy(), entity.getApprovedAt(),
                entity.getScheduledAt(), entity.getPublishedAt(), entity.getExpiresAt(),
                entity.getTargetCount(), entity.getDeliveredCount(),
                entity.getOpenedCount(), entity.getSkippedCount(), entity.getFailedCount(),
                segmentDtos, entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
