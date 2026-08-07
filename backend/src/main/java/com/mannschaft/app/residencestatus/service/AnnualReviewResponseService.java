package com.mannschaft.app.residencestatus.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.residencestatus.ResidenceStatusErrorCode;
import com.mannschaft.app.residencestatus.dto.AnnualReviewResponseDto;
import com.mannschaft.app.residencestatus.dto.SubmitAnnualResponseRequest;
import com.mannschaft.app.residencestatus.entity.AnnualReview;
import com.mannschaft.app.residencestatus.entity.AnnualReviewResponse;
import com.mannschaft.app.residencestatus.repository.AnnualReviewRepository;
import com.mannschaft.app.residencestatus.repository.AnnualReviewResponseRepository;
import com.mannschaft.app.resident.service.ResidentRegistryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 年次更新回答サービス（F09.16 S3-A）。
 *
 * <p>居住者の回答送信（UPSERT）と管理者向け回答一覧を提供する。
 * {@code @Transactional} は residencestatus ドメイン内に閉じている。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnnualReviewResponseService {

    private static final Set<String> VALID_RESIDENCE_STATES =
            Set.of("OWNER_RESIDING", "RENTED_OUT", "LONG_ABSENCE", "VACANT", "OTHER");

    private final AnnualReviewRepository annualReviewRepo;
    private final AnnualReviewResponseRepository responseRepo;
    private final AccessControlService accessControlService;
    private final ResidentRegistryService residentRegistryService;

    // ─────────────────────────────────────────────
    // 回答送信（UPSERT）
    // ─────────────────────────────────────────────

    /**
     * 居住者が自分の回答を送信する（UPSERT）。
     *
     * <p>同一キャンペーン × 居住者（residentRegistryId）の回答が既存の場合は更新し、
     * 新規の場合は作成して responseCount をインクリメントする。</p>
     *
     * @param organizationId テナント ID
     * @param reviewId       キャンペーン ID
     * @param requestUserId  操作ユーザー ID
     * @param req            回答内容
     * @return 送信後の AnnualReviewResponseDto
     */
    @Transactional
    public AnnualReviewResponseDto submitResponse(Long organizationId, UUID reviewId,
                                                   Long requestUserId, SubmitAnnualResponseRequest req) {
        // キャンペーン存在確認・組織テナント確認
        AnnualReview review = annualReviewRepo
                .findByIdAndOrganizationIdAndDeletedAtIsNull(reviewId, organizationId)
                .orElseThrow(() -> new BusinessException(ResidenceStatusErrorCode.ANNUAL_REVIEW_NOT_FOUND));

        // クローズ済みチェック
        if (review.getClosedAt() != null) {
            throw new BusinessException(ResidenceStatusErrorCode.ANNUAL_REVIEW_ALREADY_CLOSED);
        }

        // residentRegistryId 所有権確認（BOLA 対策）: req.getResidentRegistryId() はリクエストボディの値であり、
        // 呼び出し元の requestUserId に紐づく居住者台帳であることを検証する。他居住者の residentRegistryId を
        // 指定して回答（居住状態・連絡先確認フラグ）を書き換えられる穴を塞ぐ（存在秘匿のため 404 相当）。
        // resident ドメインへは ResidentRegistryService 経由でのみアクセスする
        // （モジュラーモノリス原則: ドメイン間は ID 参照＋Service 経由のみ。Entity/Repository への直接依存禁止）。
        if (!residentRegistryService.isResidentRegistryOwnedBy(req.getResidentRegistryId(), requestUserId)) {
            throw new BusinessException(ResidenceStatusErrorCode.ANNUAL_REVIEW_RESPONSE_NOT_FOUND);
        }

        // residenceState バリデーション
        if (!VALID_RESIDENCE_STATES.contains(req.getResidenceState())) {
            throw new BusinessException(ResidenceStatusErrorCode.RESIDENCE_STATE_INVALID);
        }

        LocalDateTime now = LocalDateTime.now();

        // UPSERT: 既存回答があれば更新、なければ新規作成
        AnnualReviewResponse existingOpt = responseRepo
                .findByAnnualReviewIdAndResidentRegistryIdAndDeletedAtIsNull(reviewId, req.getResidentRegistryId())
                .orElse(null);

        AnnualReviewResponse saved;
        if (existingOpt != null) {
            // 既存回答を更新（responseCount は変化なし）
            existingOpt.setResidenceState(req.getResidenceState());
            existingOpt.setContactPhoneVerified(
                    req.getContactPhoneVerified() != null ? req.getContactPhoneVerified() : existingOpt.getContactPhoneVerified());
            existingOpt.setContactEmailVerified(
                    req.getContactEmailVerified() != null ? req.getContactEmailVerified() : existingOpt.getContactEmailVerified());
            existingOpt.setEmergencyContactVerified(
                    req.getEmergencyContactVerified() != null ? req.getEmergencyContactVerified() : existingOpt.getEmergencyContactVerified());
            existingOpt.setNote(req.getNote());
            existingOpt.setRespondentUserId(requestUserId);
            existingOpt.setRespondedAt(now);
            saved = responseRepo.save(existingOpt);
            log.info("年次更新回答更新: reviewId={}, residentRegistryId={}", reviewId, req.getResidentRegistryId());
        } else {
            // 新規回答を作成（responseCount をインクリメント）
            AnnualReviewResponse newResponse = AnnualReviewResponse.builder()
                    .organizationId(organizationId)
                    .annualReviewId(reviewId)
                    .dwellingUnitId(req.getDwellingUnitId())
                    .residentRegistryId(req.getResidentRegistryId())
                    .respondentUserId(requestUserId)
                    .residenceState(req.getResidenceState())
                    .contactPhoneVerified(req.getContactPhoneVerified() != null ? req.getContactPhoneVerified() : false)
                    .contactEmailVerified(req.getContactEmailVerified() != null ? req.getContactEmailVerified() : false)
                    .emergencyContactVerified(req.getEmergencyContactVerified() != null ? req.getEmergencyContactVerified() : false)
                    .note(req.getNote())
                    .respondedAt(now)
                    .build();
            saved = responseRepo.save(newResponse);

            // responseCount をインクリメント
            review.setResponseCount(review.getResponseCount() + 1);
            annualReviewRepo.save(review);

            log.info("年次更新回答新規作成: reviewId={}, residentRegistryId={}", reviewId, req.getResidentRegistryId());
        }

        return toDto(saved);
    }

    // ─────────────────────────────────────────────
    // 一覧取得
    // ─────────────────────────────────────────────

    /**
     * キャンペーンの全回答一覧を取得する（ADMIN/DEPUTY_ADMIN のみ）。
     */
    public List<AnnualReviewResponseDto> listResponses(Long organizationId, UUID reviewId, Long requestUserId) {
        accessControlService.checkAdminOrAbove(requestUserId, organizationId, "ORGANIZATION");
        return responseRepo.findByAnnualReviewIdAndDeletedAtIsNull(reviewId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    // ─────────────────────────────────────────────
    // 内部ヘルパー
    // ─────────────────────────────────────────────

    private AnnualReviewResponseDto toDto(AnnualReviewResponse e) {
        return AnnualReviewResponseDto.builder()
                .id(e.getId())
                .annualReviewId(e.getAnnualReviewId())
                .organizationId(e.getOrganizationId())
                .dwellingUnitId(e.getDwellingUnitId())
                .residentRegistryId(e.getResidentRegistryId())
                .respondentUserId(e.getRespondentUserId())
                .residenceState(e.getResidenceState())
                .contactPhoneVerified(e.getContactPhoneVerified())
                .contactEmailVerified(e.getContactEmailVerified())
                .emergencyContactVerified(e.getEmergencyContactVerified())
                .note(e.getNote())
                .respondedAt(e.getRespondedAt())
                .createdAt(e.getCreatedAt())
                .build();
    }
}
