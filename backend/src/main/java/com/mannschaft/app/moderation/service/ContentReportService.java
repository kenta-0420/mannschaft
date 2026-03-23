package com.mannschaft.app.moderation.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.moderation.ModerationErrorCode;
import com.mannschaft.app.moderation.ModerationMapper;
import com.mannschaft.app.moderation.ReportReason;
import com.mannschaft.app.moderation.ReportStatus;
import com.mannschaft.app.moderation.ReportTargetType;
import com.mannschaft.app.moderation.dto.CreateReportRequest;
import com.mannschaft.app.moderation.dto.ReportResponse;
import com.mannschaft.app.moderation.entity.ContentReportEntity;
import com.mannschaft.app.moderation.repository.ContentReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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

    private final ContentReportRepository reportRepository;
    private final UserRepository userRepository;
    private final ModerationMapper moderationMapper;

    /**
     * コンテンツを通報する。
     */
    @Transactional
    public ReportResponse createReport(CreateReportRequest req, Long userId) {
        ReportTargetType targetType = ReportTargetType.valueOf(req.getTargetType());

        if (reportRepository.existsByReportedByAndTargetTypeAndTargetId(
                userId, targetType, req.getTargetId())) {
            throw new BusinessException(ModerationErrorCode.REPORT_ALREADY_EXISTS);
        }

        ContentReportEntity report = ContentReportEntity.builder()
                .targetType(targetType)
                .targetId(req.getTargetId())
                .reportedBy(userId)
                .scopeType(req.getScopeType() != null ? req.getScopeType() : "TEAM")
                .scopeId(req.getScopeId() != null ? req.getScopeId() : 0L)
                .reason(ReportReason.valueOf(req.getReason()))
                .description(req.getDescription())
                .targetUserId(req.getTargetUserId())
                .contentSnapshot(req.getContentSnapshot())
                .build();
        report = reportRepository.save(report);

        log.info("コンテンツ通報作成: id={}, targetType={}, targetId={}, userId={}",
                report.getId(), req.getTargetType(), req.getTargetId(), userId);
        return moderationMapper.toReportResponse(report);
    }

    /**
     * 未対応の通報件数を取得する。
     */
    public long countPendingReports() {
        return reportRepository.countByStatus(ReportStatus.PENDING);
    }

    /**
     * 未対応の通報一覧を取得する（管理者用）。
     */
    public List<ReportResponse> getPendingReports(int size) {
        int reportSize = size > 0 ? size : DEFAULT_REPORT_SIZE;
        return moderationMapper.toReportResponseList(
                reportRepository.findByStatusOrderByCreatedAtAsc(
                        ReportStatus.PENDING, PageRequest.of(0, reportSize)));
    }

    /**
     * スコープ別の通報一覧を取得する。
     */
    public Page<ReportResponse> getReportsByScope(String scopeType, Long scopeId, Pageable pageable) {
        return reportRepository.findByScopeTypeAndScopeId(scopeType, scopeId, pageable)
                .map(moderationMapper::toReportResponse);
    }

    /**
     * 通報詳細を取得する。
     */
    public ReportResponse getReport(Long reportId) {
        ContentReportEntity report = reportRepository.findById(reportId)
                .orElseThrow(() -> new BusinessException(ModerationErrorCode.REPORT_NOT_FOUND));
        return moderationMapper.toReportResponse(report);
    }

    /**
     * コンテンツを非表示にする。
     */
    @Transactional
    public void hideContent(Long reportId) {
        ContentReportEntity report = reportRepository.findById(reportId)
                .orElseThrow(() -> new BusinessException(ModerationErrorCode.REPORT_NOT_FOUND));
        report.hideContent();
        reportRepository.save(report);
        log.info("コンテンツ非表示: reportId={}, targetType={}, targetId={}",
                reportId, report.getTargetType(), report.getTargetId());
    }

    /**
     * コンテンツの非表示を解除する。
     */
    @Transactional
    public void unhideContent(Long reportId) {
        ContentReportEntity report = reportRepository.findById(reportId)
                .orElseThrow(() -> new BusinessException(ModerationErrorCode.REPORT_NOT_FOUND));
        report.unhideContent();
        reportRepository.save(report);
        log.info("コンテンツ非表示解除: reportId={}, targetType={}, targetId={}",
                reportId, report.getTargetType(), report.getTargetId());
    }

    /**
     * ユーザーの通報権限を制限/解除する。
     */
    @Transactional
    public void restrictReporting(Long userId, boolean restricted) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ModerationErrorCode.REPORT_NOT_FOUND));
        user.setReportingRestricted(restricted);
        userRepository.save(user);
        log.info("通報権限更新: userId={}, restricted={}", userId, restricted);
    }
}
