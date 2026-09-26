package com.mannschaft.app.moderation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.EnumInputParser;
import com.mannschaft.app.moderation.ModerationErrorCode;
import com.mannschaft.app.moderation.ModerationMapper;
import com.mannschaft.app.moderation.ReportReason;
import com.mannschaft.app.moderation.ReportStatus;
import com.mannschaft.app.moderation.ReportTargetType;
import com.mannschaft.app.moderation.dto.CreateReportRequest;
import com.mannschaft.app.moderation.dto.ReportResponse;
import com.mannschaft.app.moderation.entity.ContentReportEntity;
import com.mannschaft.app.moderation.repository.ContentReportRepository;
import com.mannschaft.app.recruitment.service.RecruitmentListingModerationService;
import com.mannschaft.app.timeline.service.TimelinePostModerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * コンテンツ通報サービス。通報の作成・レビュー・一覧取得を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ContentReportService {

    private static final int DEFAULT_REPORT_SIZE = 20;

    /** 控え（content_snapshot）の本文の上限文字数（設計書 F10.1 §content_reports）。 */
    private static final int SNAPSHOT_TEXT_MAX_LENGTH = 10_000;

    /** 控えの JSON 化専用（状態を持たない単純な Map の直列化のみ）。 */
    private static final ObjectMapper SNAPSHOT_MAPPER = new ObjectMapper();

    private final ContentReportRepository reportRepository;
    private final UserRepository userRepository;
    private final ModerationMapper moderationMapper;
    private final RecruitmentListingModerationService recruitmentListingModerationService;
    private final TimelinePostModerationService timelinePostModerationService;

    /**
     * コンテンツを通報する。
     *
     * <p><b>宛先・作成者・控えの導出（CMP-260917-1135・設計書 F10.1 §content_reports）</b>:
     * {@code scope_type} / {@code scope_id} / {@code target_user_id} / {@code content_snapshot} は
     * 対象種別ごとに <b>DB 上の対象コンテンツ</b>から導出し、リクエスト本文の値は受け取らない。
     * 所属は要件にせず、対象を閲覧できる利用者だけが通報できる。閲覧できない対象・存在しない対象は
     * 区別せず {@link ModerationErrorCode#REPORT_TARGET_NOT_FOUND}（404）で拒否する。
     * 宛先スコープを導出できない種別（USER / SOCIAL_PROFILE）は
     * {@link ModerationErrorCode#REPORT_TARGET_TYPE_NOT_SUPPORTED}（400）。</p>
     */
    @Transactional
    public ReportResponse createReport(CreateReportRequest req, Long userId) {
        ReportTargetType targetType = EnumInputParser.parse(ReportTargetType.class, req.getTargetType(), "targetType");
        ReportReason reason = EnumInputParser.parse(ReportReason.class, req.getReason(), "reason");

        DerivedTarget target = deriveTarget(targetType, req.getTargetId(), userId);
        if (userId.equals(target.targetUserId())) {
            throw new BusinessException(ModerationErrorCode.CANNOT_REPORT_OWN_CONTENT);
        }

        if (reportRepository.existsByReportedByAndTargetTypeAndTargetId(
                userId, targetType, req.getTargetId())) {
            throw new BusinessException(ModerationErrorCode.REPORT_ALREADY_EXISTS);
        }

        ContentReportEntity report = ContentReportEntity.builder()
                .targetType(targetType)
                .targetId(req.getTargetId())
                .reportedBy(userId)
                .scopeType(target.scopeType())
                .scopeId(target.scopeId())
                .reason(reason)
                .description(req.getDescription())
                .targetUserId(target.targetUserId())
                .contentSnapshot(target.contentSnapshot())
                .build();
        report = reportRepository.save(report);

        log.info("コンテンツ通報作成: id={}, targetType={}, targetId={}, userId={}",
                report.getId(), req.getTargetType(), req.getTargetId(), userId);
        return withoutDerivedValues(moderationMapper.toReportResponse(report));
    }

    /**
     * 通報者向けの作成応答から、対象から導出した値（宛先スコープ・対象ユーザー・控え）を除く。
     *
     * <p>これらは運営・管理者のレビュー用に保存するもので、通報者には返さない（通報者が送ったものでもなく、
     * 画面も作成応答の本文を使っていない。{@code useMarketApi#reportMarketListing}）。管理者向けの
     * 一覧・詳細 API は同じ {@link ReportResponse} で全項目を返す。</p>
     */
    private static ReportResponse withoutDerivedValues(ReportResponse r) {
        if (r == null) {
            return null;
        }
        return new ReportResponse(r.getId(), r.getTargetType(), r.getTargetId(), r.getReportedBy(),
                null, null, null, r.getReason(), r.getDescription(), null, r.getStatus(),
                r.getReviewedBy(), r.getReviewedAt(), r.getCreatedAt(), r.getUpdatedAt());
    }

    /**
     * 対象種別ごとに、通報の宛先スコープ・対象ユーザー・控えを対象コンテンツから導出する。
     */
    private DerivedTarget deriveTarget(ReportTargetType targetType, Long targetId, Long userId) {
        return switch (targetType) {
            case TIMELINE_POST, TIMELINE_COMMENT -> {
                TimelinePostModerationService.PostReportTarget post = timelinePostModerationService
                        .findReportTarget(targetId, targetType == ReportTargetType.TIMELINE_COMMENT, userId)
                        .orElseThrow(() -> new BusinessException(ModerationErrorCode.REPORT_TARGET_NOT_FOUND));
                yield new DerivedTarget(post.scopeType(), post.scopeId(), post.authorUserId(),
                        snapshot("content", post.content()));
            }
            case RECRUITMENT_LISTING -> {
                RecruitmentListingModerationService.ListingReportTarget listing =
                        recruitmentListingModerationService.getReportTarget(targetId, userId);
                yield new DerivedTarget(listing.scopeType(), listing.scopeId(), listing.ownerUserId(),
                        snapshot("title", listing.title()));
            }
            case USER, SOCIAL_PROFILE ->
                    throw new BusinessException(ModerationErrorCode.REPORT_TARGET_TYPE_NOT_SUPPORTED);
        };
    }

    /**
     * 控え（{@code content_snapshot}・JSON 列）を 1 項目の JSON オブジェクトとして組み立てる。
     * 本文は設計書 F10.1 のサイズ制限に合わせ先頭 {@value #SNAPSHOT_TEXT_MAX_LENGTH} 文字に切り詰める。
     */
    private String snapshot(String key, String text) {
        String value = text != null && text.length() > SNAPSHOT_TEXT_MAX_LENGTH
                ? text.substring(0, SNAPSHOT_TEXT_MAX_LENGTH)
                : text;
        Map<String, String> body = new LinkedHashMap<>();
        body.put(key, value);
        try {
            return SNAPSHOT_MAPPER.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("通報の控えを JSON に変換できませんでした", e);
        }
    }

    /** 対象コンテンツから導出した通報の宛先・対象ユーザー・控え。 */
    private record DerivedTarget(String scopeType, Long scopeId, Long targetUserId, String contentSnapshot) { }

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
        if (report.getTargetType() == ReportTargetType.RECRUITMENT_LISTING) {
            recruitmentListingModerationService.hide(report.getTargetId());
        }
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
        if (report.getTargetType() == ReportTargetType.RECRUITMENT_LISTING) {
            recruitmentListingModerationService.restore(report.getTargetId());
        }
        reportRepository.save(report);
        log.info("コンテンツ非表示解除: reportId={}, targetType={}, targetId={}",
                reportId, report.getTargetType(), report.getTargetId());
    }

    /**
     * ユーザーの通報権限を制限/解除する。
     */
    @Transactional
    // TODO: moderationドメインとauthドメインをまたいでいる（UserRepositoryを直接参照）。将来はUserReportingRestrictionChangedEventで分離予定
    public void restrictReporting(Long userId, boolean restricted) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ModerationErrorCode.REPORT_NOT_FOUND));
        user.setReportingRestricted(restricted);
        userRepository.save(user);
        log.info("通報権限更新: userId={}, restricted={}", userId, restricted);
    }
}
