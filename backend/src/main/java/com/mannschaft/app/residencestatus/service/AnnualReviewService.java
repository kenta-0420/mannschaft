package com.mannschaft.app.residencestatus.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.residencestatus.ResidenceStatusErrorCode;
import com.mannschaft.app.residencestatus.dto.AnnualReviewDto;
import com.mannschaft.app.residencestatus.dto.CreateAnnualReviewRequest;
import com.mannschaft.app.residencestatus.entity.AnnualReview;
import com.mannschaft.app.residencestatus.event.AnnualReviewClosedEvent;
import com.mannschaft.app.residencestatus.event.AnnualReviewStartedEvent;
import com.mannschaft.app.residencestatus.repository.AnnualReviewRepository;
import com.mannschaft.app.resident.repository.ResidentRegistryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 年次更新キャンペーンサービス（F09.16 S3-A）。
 *
 * <p>キャンペーンの起動・クローズ・一覧取得・締切バッチを提供する。
 * {@code @Transactional} は residencestatus ドメイン内に閉じている。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnnualReviewService {

    private final AnnualReviewRepository annualReviewRepo;
    private final AccessControlService accessControlService;
    private final ApplicationEventPublisher eventPublisher;
    private final ResidentRegistryRepository residentRegistryRepository;

    // ─────────────────────────────────────────────
    // キャンペーン起動
    // ─────────────────────────────────────────────

    /**
     * 年次更新キャンペーンを起動する（ADMIN/DEPUTY_ADMIN のみ）。
     *
     * <p>同年度のキャンペーンが既に存在する場合は {@link ResidenceStatusErrorCode#ANNUAL_REVIEW_YEAR_CONFLICT} をスロー。</p>
     *
     * @param organizationId テナント ID
     * @param requestUserId  操作ユーザー ID
     * @param req            キャンペーン作成リクエスト
     * @return 作成された AnnualReviewDto
     */
    @Transactional
    public AnnualReviewDto createReview(Long organizationId, Long requestUserId, CreateAnnualReviewRequest req) {
        accessControlService.checkAdminOrAbove(requestUserId, organizationId, "ORGANIZATION");

        annualReviewRepo.findByOrganizationIdAndReviewYearAndDeletedAtIsNull(organizationId, req.getReviewYear())
                .ifPresent(existing -> {
                    throw new BusinessException(ResidenceStatusErrorCode.ANNUAL_REVIEW_YEAR_CONFLICT);
                });

        LocalDateTime now = LocalDateTime.now();
        AnnualReview review = AnnualReview.builder()
                .organizationId(organizationId)
                .reviewYear(req.getReviewYear())
                .startedAt(now)
                .deadlineAt(req.getDeadlineAt())
                .targetCount(0)
                .responseCount(0)
                .createdBy(requestUserId)
                .build();
        AnnualReview saved = annualReviewRepo.save(review);

        eventPublisher.publishEvent(
                new AnnualReviewStartedEvent(this, saved.getId(), organizationId, saved.getTargetCount()));

        log.info("年次更新キャンペーン起動: organizationId={}, year={}, id={}", organizationId, req.getReviewYear(), saved.getId());
        return toDto(saved);
    }

    // ─────────────────────────────────────────────
    // 一覧・詳細
    // ─────────────────────────────────────────────

    /**
     * 組織のキャンペーン一覧を取得する（ADMIN/DEPUTY_ADMIN のみ）。
     */
    public List<AnnualReviewDto> listReviews(Long organizationId, Long requestUserId) {
        accessControlService.checkAdminOrAbove(requestUserId, organizationId, "ORGANIZATION");
        return annualReviewRepo.findByOrganizationIdAndDeletedAtIsNull(organizationId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * キャンペーン詳細を取得する（ADMIN/DEPUTY_ADMIN のみ）。
     */
    public AnnualReviewDto getReview(Long organizationId, UUID reviewId, Long requestUserId) {
        accessControlService.checkAdminOrAbove(requestUserId, organizationId, "ORGANIZATION");
        AnnualReview review = findReview(reviewId, organizationId);
        return toDto(review);
    }

    // ─────────────────────────────────────────────
    // クローズ
    // ─────────────────────────────────────────────

    /**
     * キャンペーンを手動クローズする（ADMIN/DEPUTY_ADMIN のみ）。
     */
    @Transactional
    public AnnualReviewDto closeReview(Long organizationId, UUID reviewId, Long requestUserId) {
        accessControlService.checkAdminOrAbove(requestUserId, organizationId, "ORGANIZATION");

        AnnualReview review = findReview(reviewId, organizationId);
        if (review.getClosedAt() != null) {
            throw new BusinessException(ResidenceStatusErrorCode.ANNUAL_REVIEW_ALREADY_CLOSED);
        }

        review.close();
        AnnualReview saved = annualReviewRepo.save(review);

        eventPublisher.publishEvent(new AnnualReviewClosedEvent(
                this, saved.getId(), organizationId, saved.getResponseCount(), saved.getTargetCount()));

        log.info("年次更新キャンペーン手動クローズ: organizationId={}, id={}", organizationId, reviewId);
        return toDto(saved);
    }

    /**
     * 締切バッチから呼ばれる自動クローズ処理。
     *
     * <p>締切日時を過ぎていて未クローズのキャンペーンを全件クローズする。</p>
     */
    @Transactional
    public void autoCloseExpiredReviews() {
        LocalDateTime now = LocalDateTime.now();
        List<AnnualReview> expired = annualReviewRepo
                .findByDeadlineAtLessThanEqualAndClosedAtIsNullAndDeletedAtIsNull(now);

        for (AnnualReview review : expired) {
            review.close();
            AnnualReview saved = annualReviewRepo.save(review);
            eventPublisher.publishEvent(new AnnualReviewClosedEvent(
                    this, saved.getId(), saved.getOrganizationId(),
                    saved.getResponseCount(), saved.getTargetCount()));
            log.info("年次更新キャンペーン自動クローズ: id={}, organizationId={}", saved.getId(), saved.getOrganizationId());
        }

        log.info("自動クローズ完了: {}件", expired.size());
    }

    // ─────────────────────────────────────────────
    // 居住者向け
    // ─────────────────────────────────────────────

    /**
     * 居住者向け: 組織の進行中キャンペーン一覧を取得する。
     *
     * <p>未クローズ（closedAt IS NULL）のキャンペーンを返す。
     * {@link org.hibernate.annotations.SQLRestriction} により deleted_at IS NULL は自動適用される。</p>
     *
     * <p><b>認可:</b> 呼び出し元が当該組織の現居住者（{@code ResidentRegistryRepository#findActiveByUserIdAndOrganizationId}
     * が空でない）であることを要求する。組織 ID はパス変数のためクライアントが任意の値を指定できるが、
     * 居住者台帳に紐づかない組織を指定した場合は存在秘匿（{@link ResidenceStatusErrorCode#ANNUAL_REVIEW_NOT_FOUND}）
     * として 404 相当のエラーで返し、他組織のキャンペーン一覧を非居住者へ開示しない。</p>
     */
    public List<AnnualReviewDto> listMyReviews(Long organizationId, Long requestUserId) {
        residentRegistryRepository.findActiveByUserIdAndOrganizationId(requestUserId, organizationId)
                .orElseThrow(() -> new BusinessException(ResidenceStatusErrorCode.ANNUAL_REVIEW_NOT_FOUND));
        return annualReviewRepo.findByOrganizationIdAndDeletedAtIsNull(organizationId)
                .stream()
                .filter(r -> r.getClosedAt() == null)
                .map(this::toDto)
                .toList();
    }

    // ─────────────────────────────────────────────
    // 内部ヘルパー（package-private for testing）
    // ─────────────────────────────────────────────

    AnnualReview findReview(UUID reviewId, Long organizationId) {
        return annualReviewRepo.findByIdAndOrganizationIdAndDeletedAtIsNull(reviewId, organizationId)
                .orElseThrow(() -> new BusinessException(ResidenceStatusErrorCode.ANNUAL_REVIEW_NOT_FOUND));
    }

    private AnnualReviewDto toDto(AnnualReview e) {
        return AnnualReviewDto.builder()
                .id(e.getId())
                .organizationId(e.getOrganizationId())
                .reviewYear(e.getReviewYear())
                .startedAt(e.getStartedAt())
                .deadlineAt(e.getDeadlineAt())
                .closedAt(e.getClosedAt())
                .targetCount(e.getTargetCount())
                .responseCount(e.getResponseCount())
                .createdBy(e.getCreatedBy())
                .createdAt(e.getCreatedAt())
                .build();
    }
}
