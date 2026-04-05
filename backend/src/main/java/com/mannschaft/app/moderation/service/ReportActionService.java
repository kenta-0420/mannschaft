package com.mannschaft.app.moderation.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.moderation.ModerationErrorCode;
import com.mannschaft.app.moderation.ReportActionType;
import com.mannschaft.app.moderation.ReportStatus;
import com.mannschaft.app.moderation.dto.BulkResolveRequest;
import com.mannschaft.app.moderation.dto.EscalateRequest;
import com.mannschaft.app.moderation.dto.ReportActionResponse;
import com.mannschaft.app.moderation.dto.ReportStatsResponse;
import com.mannschaft.app.moderation.dto.ResolveReportRequest;
import com.mannschaft.app.moderation.entity.ContentReportEntity;
import com.mannschaft.app.moderation.entity.ReportActionEntity;
import com.mannschaft.app.moderation.repository.ContentReportRepository;
import com.mannschaft.app.moderation.repository.ReportActionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 通報対応アクションサービス。WARNING/CONTENT_DELETE/USER_FREEZE/DISMISS/ESCALATE/REOPEN の実行ロジックを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportActionService {

    private final ContentReportRepository reportRepository;
    private final ReportActionRepository actionRepository;

    /**
     * 通報をレビュー中に変更する。
     *
     * @param reportId   通報ID
     * @param reviewerId レビュアーID
     */
    @Transactional
    public void startReview(Long reportId, Long reviewerId) {
        ContentReportEntity report = findReportOrThrow(reportId);

        if (report.getStatus() != ReportStatus.PENDING) {
            throw new BusinessException(ModerationErrorCode.INVALID_REPORT_STATUS);
        }

        report.startReview(reviewerId);
        reportRepository.save(report);
        log.info("通報レビュー開始: reportId={}, reviewerId={}", reportId, reviewerId);
    }

    /**
     * 通報に対して対応アクションを実行する。
     *
     * @param reportId 通報ID
     * @param req      対応リクエスト
     * @param userId   対応者ID
     * @return アクション情報
     */
    @Transactional
    public ReportActionResponse resolveReport(Long reportId, ResolveReportRequest req, Long userId) {
        ContentReportEntity report = findReportOrThrow(reportId);
        validateReportActionable(report);

        ReportActionType actionType = ReportActionType.valueOf(req.getActionType());
        ReportActionEntity action = createAction(reportId, actionType, userId, req.getNote(),
                req.getFreezeUntil(), req.getGuidelineSection());

        report.resolve(userId);
        reportRepository.save(report);

        log.info("通報対応完了: reportId={}, actionType={}, userId={}", reportId, actionType, userId);
        return toResponse(action);
    }

    /**
     * 通報を却下する。
     *
     * @param reportId 通報ID
     * @param note     却下理由
     * @param userId   対応者ID
     * @return アクション情報
     */
    @Transactional
    public ReportActionResponse dismissReport(Long reportId, String note, Long userId) {
        ContentReportEntity report = findReportOrThrow(reportId);
        validateReportActionable(report);

        ReportActionEntity action = createAction(reportId, ReportActionType.DISMISS, userId, note, null, null);

        report.dismiss(userId);
        reportRepository.save(report);

        log.info("通報却下: reportId={}, userId={}", reportId, userId);
        return toResponse(action);
    }

    /**
     * 通報を差し戻す（再オープン）。
     *
     * @param reportId 通報ID
     * @param note     差し戻し理由
     * @param userId   対応者ID
     * @return アクション情報
     */
    @Transactional
    public ReportActionResponse reopenReport(Long reportId, String note, Long userId) {
        ContentReportEntity report = findReportOrThrow(reportId);

        if (report.getStatus() != ReportStatus.RESOLVED && report.getStatus() != ReportStatus.DISMISSED) {
            throw new BusinessException(ModerationErrorCode.INVALID_REPORT_STATUS);
        }

        ReportActionEntity action = createAction(reportId, ReportActionType.REOPEN, userId, note, null, null);

        report.reopen(userId);
        reportRepository.save(report);

        log.info("通報差し戻し: reportId={}, userId={}", reportId, userId);
        return toResponse(action);
    }

    /**
     * 通報をエスカレーションする。
     *
     * @param reportId 通報ID
     * @param req      エスカレーションリクエスト
     * @param userId   対応者ID
     * @return アクション情報
     */
    @Transactional
    public ReportActionResponse escalateReport(Long reportId, EscalateRequest req, Long userId) {
        ContentReportEntity report = findReportOrThrow(reportId);
        validateReportActionable(report);

        ReportActionEntity action = createAction(reportId, ReportActionType.ESCALATE, userId,
                req.getReason(), null, req.getGuidelineSection());

        report.escalate();
        reportRepository.save(report);

        log.info("通報エスカレーション: reportId={}, userId={}", reportId, userId);
        return toResponse(action);
    }

    /**
     * 通報を一括対応する。
     *
     * @param req    一括対応リクエスト
     * @param userId 対応者ID
     * @return 対応件数
     */
    @Transactional
    public int bulkResolve(BulkResolveRequest req, Long userId) {
        ReportActionType actionType = ReportActionType.valueOf(req.getActionType());
        int count = 0;

        for (Long reportId : req.getReportIds()) {
            ContentReportEntity report = reportRepository.findById(reportId).orElse(null);
            if (report == null || (report.getStatus() != ReportStatus.PENDING
                    && report.getStatus() != ReportStatus.REVIEWING)) {
                continue;
            }

            createAction(reportId, actionType, userId, req.getNote(), null, req.getGuidelineSection());
            report.resolve(userId);
            reportRepository.save(report);
            count++;
        }

        log.info("通報一括対応: count={}, actionType={}, userId={}", count, actionType, userId);
        return count;
    }

    /**
     * コンテンツを復元する（対応取消）。
     *
     * @param reportId 通報ID
     * @param note     復元理由
     * @param userId   対応者ID
     * @return アクション情報
     */
    @Transactional
    public ReportActionResponse restoreContent(Long reportId, String note, Long userId) {
        ContentReportEntity report = findReportOrThrow(reportId);

        if (report.getStatus() != ReportStatus.RESOLVED) {
            throw new BusinessException(ModerationErrorCode.INVALID_REPORT_STATUS);
        }

        ReportActionEntity action = createAction(reportId, ReportActionType.REOPEN, userId,
                "コンテンツ復元: " + (note != null ? note : ""), null, null);

        report.reopen(userId);
        reportRepository.save(report);

        log.info("コンテンツ復元: reportId={}, userId={}", reportId, userId);
        return toResponse(action);
    }

    /**
     * 通報統計を取得する。
     *
     * @return 統計情報
     */
    public ReportStatsResponse getStats() {
        long pending = reportRepository.countByStatus(ReportStatus.PENDING);
        long reviewing = reportRepository.countByStatus(ReportStatus.REVIEWING);
        long escalated = reportRepository.countByStatus(ReportStatus.ESCALATED);
        long resolved = reportRepository.countByStatus(ReportStatus.RESOLVED);
        long dismissed = reportRepository.countByStatus(ReportStatus.DISMISSED);
        long total = pending + reviewing + escalated + resolved + dismissed;
        return new ReportStatsResponse(pending, reviewing, escalated, resolved, dismissed, total);
    }

    /**
     * 通報に紐づくアクション履歴を取得する。
     *
     * @param reportId 通報ID
     * @return アクション一覧
     */
    public List<ReportActionResponse> getActions(Long reportId) {
        findReportOrThrow(reportId);
        return actionRepository.findByReportIdOrderByCreatedAtDesc(reportId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 全通報を取得する（管理者用）。
     *
     * @param pageable ページング
     * @return 通報ページ
     */
    public Page<ContentReportEntity> getAllReports(Pageable pageable) {
        return reportRepository.findAll(pageable);
    }

    // ---- private helpers ----

    private ContentReportEntity findReportOrThrow(Long reportId) {
        return reportRepository.findById(reportId)
                .orElseThrow(() -> new BusinessException(ModerationErrorCode.REPORT_NOT_FOUND));
    }

    private void validateReportActionable(ContentReportEntity report) {
        if (report.getStatus() == ReportStatus.RESOLVED || report.getStatus() == ReportStatus.DISMISSED) {
            throw new BusinessException(ModerationErrorCode.INVALID_REPORT_STATUS);
        }
    }

    private ReportActionEntity createAction(Long reportId, ReportActionType actionType,
                                            Long actionBy, String note,
                                            java.time.LocalDateTime freezeUntil,
                                            String guidelineSection) {
        ReportActionEntity action = ReportActionEntity.builder()
                .reportId(reportId)
                .actionType(actionType)
                .actionBy(actionBy)
                .note(note)
                .freezeUntil(freezeUntil)
                .guidelineSection(guidelineSection)
                .build();
        return actionRepository.save(action);
    }

    private ReportActionResponse toResponse(ReportActionEntity entity) {
        return new ReportActionResponse(
                entity.getId(),
                entity.getReportId(),
                entity.getActionType().name(),
                entity.getActionBy(),
                entity.getNote(),
                entity.getFreezeUntil(),
                entity.getGuidelineSection(),
                entity.getCreatedAt());
    }
}
