package com.mannschaft.app.moderation.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.moderation.ModerationErrorCode;
import com.mannschaft.app.moderation.ModerationMapper;
import com.mannschaft.app.moderation.ReportReason;
import com.mannschaft.app.moderation.ReportStatus;
import com.mannschaft.app.moderation.ReportTargetType;
import com.mannschaft.app.moderation.dto.CreateReportRequest;
import com.mannschaft.app.moderation.dto.ReportResponse;
import com.mannschaft.app.moderation.dto.ReviewReportRequest;
import com.mannschaft.app.moderation.entity.ContentReportEntity;
import com.mannschaft.app.moderation.repository.ContentReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * コンテンツ通報サービス。通報の作成・レビュー・一覧取得を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ContentReportService {

    private static final int DEFAULT_REPORT_SIZE = 20;
    private static final String REPORTER_TYPE_USER = "USER";

    private final ContentReportRepository reportRepository;
    private final ModerationMapper moderationMapper;

    /**
     * コンテンツを通報する。
     *
     * @param req    通報作成リクエスト
     * @param userId 通報者のユーザーID
     * @return 作成された通報
     */
    @Transactional
    public ReportResponse createReport(CreateReportRequest req, Long userId) {
        ReportTargetType targetType = ReportTargetType.valueOf(req.getTargetType());

        if (reportRepository.existsByReporterTypeAndReporterIdAndTargetTypeAndTargetId(
                REPORTER_TYPE_USER, userId, targetType, req.getTargetId())) {
            throw new BusinessException(ModerationErrorCode.REPORT_ALREADY_EXISTS);
        }

        ContentReportEntity report = ContentReportEntity.builder()
                .targetType(targetType)
                .targetId(req.getTargetId())
                .reporterType(REPORTER_TYPE_USER)
                .reporterId(userId)
                .reason(ReportReason.valueOf(req.getReason()))
                .description(req.getDescription())
                .build();
        report = reportRepository.save(report);

        log.info("コンテンツ通報作成: id={}, targetType={}, targetId={}, userId={}",
                report.getId(), req.getTargetType(), req.getTargetId(), userId);
        return moderationMapper.toReportResponse(report);
    }

    /**
     * 通報をレビューする（管理者用）。
     *
     * @param reportId   通報ID
     * @param req        レビューリクエスト
     * @param reviewerId レビュアーのユーザーID
     * @return レビュー後の通報
     */
    @Transactional
    public ReportResponse reviewReport(Long reportId, ReviewReportRequest req, Long reviewerId) {
        ContentReportEntity report = reportRepository.findById(reportId)
                .orElseThrow(() -> new BusinessException(ModerationErrorCode.REPORT_NOT_FOUND));

        if (report.getStatus() == ReportStatus.RESOLVED || report.getStatus() == ReportStatus.DISMISSED) {
            throw new BusinessException(ModerationErrorCode.INVALID_REPORT_STATUS);
        }

        ReportStatus newStatus = ReportStatus.valueOf(req.getStatus());
        report.review(reviewerId, req.getReviewNote(), newStatus);
        report = reportRepository.save(report);

        log.info("通報レビュー: id={}, newStatus={}, reviewerId={}", reportId, newStatus, reviewerId);
        return moderationMapper.toReportResponse(report);
    }

    /**
     * 未対応の通報一覧を取得する（管理者用）。
     *
     * @param size 取得件数
     * @return 通報一覧
     */
    public List<ReportResponse> getPendingReports(int size) {
        int reportSize = size > 0 ? size : DEFAULT_REPORT_SIZE;
        return moderationMapper.toReportResponseList(
                reportRepository.findByStatusOrderByCreatedAtAsc(
                        ReportStatus.PENDING, PageRequest.of(0, reportSize)));
    }

    /**
     * 通報詳細を取得する（管理者用）。
     *
     * @param reportId 通報ID
     * @return 通報詳細
     */
    public ReportResponse getReport(Long reportId) {
        ContentReportEntity report = reportRepository.findById(reportId)
                .orElseThrow(() -> new BusinessException(ModerationErrorCode.REPORT_NOT_FOUND));
        return moderationMapper.toReportResponse(report);
    }
}
