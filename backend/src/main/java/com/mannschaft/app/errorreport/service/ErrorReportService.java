package com.mannschaft.app.errorreport.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.errorreport.ErrorReportActivityType;
import com.mannschaft.app.errorreport.ErrorReportErrorCode;
import com.mannschaft.app.errorreport.ErrorReportSeverity;
import com.mannschaft.app.errorreport.ErrorReportStatus;
import com.mannschaft.app.errorreport.ErrorReportWorkflowStage;
import com.mannschaft.app.errorreport.dto.ActiveIncidentResponse;
import com.mannschaft.app.errorreport.dto.ErrorReportBulkUpdateRequest;
import com.mannschaft.app.errorreport.dto.ErrorReportRequest;
import com.mannschaft.app.errorreport.dto.ErrorReportStatsResponse;
import com.mannschaft.app.errorreport.dto.ErrorReportTimelineResponse;
import com.mannschaft.app.errorreport.dto.ErrorReportUpdateRequest;
import com.mannschaft.app.errorreport.dto.KanbanResponse;
import com.mannschaft.app.errorreport.entity.ErrorReportActivityEntity;
import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
import com.mannschaft.app.errorreport.entity.ErrorReportOccurrenceEntity;
import com.mannschaft.app.errorreport.repository.ErrorReportActivityRepository;
import com.mannschaft.app.errorreport.repository.ErrorReportAiAnalysisRepository;
import com.mannschaft.app.errorreport.repository.ErrorReportOccurrenceRepository;
import com.mannschaft.app.errorreport.repository.ErrorReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * エラーレポートの作成・重複集約・検索を担当するサービス。
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class ErrorReportService {

    private final ErrorReportRepository errorReportRepository;
    private final ErrorReportNotifier errorReportNotifier;
    private final StringRedisTemplate redisTemplate;
    private final ErrorReportActivityRepository activityRepository;
    private final ErrorReportOccurrenceRepository occurrenceRepository;
    private final ErrorReportActivityService activityService;
    private final AccessControlService accessControlService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    /** F12.5 Phase 2-C — CRITICAL/HIGH 新規 / REOPEN 時に AI 即時分析をキックする。 */
    private final ErrorReportAiAnalysisService aiAnalysisService;
    /** F12.5 Phase 2-E — Kanban カードに AI 分析バッジを描画するための判定用。 */
    private final ErrorReportAiAnalysisRepository aiAnalysisRepository;

    /**
     * エラーレポートを受信し、重複集約または新規作成する。
     *
     * @param request   エラーレポートリクエスト
     * @param ipAddress 送信元IPアドレス
     * @return 作成または更新されたエラーレポートエンティティ
     */
    public ErrorReportEntity createOrAggregate(ErrorReportRequest request, String ipAddress) {
        String pagePath = extractPath(request.getPageUrl());
        String normalized = normalizeForHash(request.getErrorMessage());
        String errorHash = sha256(normalized + "|" + pagePath);

        // stack_trace を先頭2000文字に切り捨て
        String stackTrace = request.getStackTrace();
        if (stackTrace != null && stackTrace.length() > 2000) {
            stackTrace = stackTrace.substring(0, 2000);
        }

        Optional<ErrorReportEntity> existing = errorReportRepository.findByErrorHash(errorHash);

        if (existing.isPresent()) {
            ErrorReportEntity report = existing.get();

            if (report.getStatus() == ErrorReportStatus.RESOLVED) {
                // リグレッション: REOPENED に変更
                report.reopen(request.getOccurredAt());
                // F12.5 Phase 2 — リグレッション時に workflow_stage / assignee_id をリセット
                report.setWorkflowStage(null);
                report.setAssigneeId(null);
                int affectedCount = trackAffectedUser(errorHash, request.getUserId());
                if (affectedCount > 0) {
                    report.setAffectedUserCount(affectedCount);
                }
                if (request.getUserComment() != null) {
                    report.setLatestUserComment(request.getUserComment());
                }
                errorReportNotifier.notifyRegression(report);
                // F12.5 Phase 2-C — REOPEN かつ severity HIGH 以上なら AI 即時分析をキック
                if (report.getSeverity().ordinal() >= ErrorReportSeverity.HIGH.ordinal()) {
                    aiAnalysisService.analyzeAfterCommit(report.getId(), null);
                }
                log.info("エラーレポートリグレッション検知: id={}, hash={}", report.getId(), errorHash);
                return report;
            }

            if (report.getStatus() != ErrorReportStatus.IGNORED) {
                // 通常の重複集約
                ErrorReportSeverity oldSeverity = report.getSeverity();
                errorReportRepository.incrementOccurrence(errorHash, request.getOccurredAt(), request.getUserComment());

                // clearAutomatically = true により永続化コンテキストは自動クリア済み
                ErrorReportEntity updated = errorReportRepository.findByErrorHash(errorHash).orElseThrow();
                ErrorReportSeverity newSeverity = updated.getSeverity();

                // 影響ユーザー数追跡
                int affectedCount = trackAffectedUser(errorHash, request.getUserId());
                if (affectedCount > 0) {
                    updated.setAffectedUserCount(affectedCount);
                }

                // affected_user_count による severity 補正
                newSeverity = adjustSeverity(updated, affectedCount);
                if (newSeverity != updated.getSeverity()) {
                    updated.setSeverity(newSeverity);
                }

                // severity 昇格通知
                if (newSeverity.ordinal() > oldSeverity.ordinal()) {
                    errorReportNotifier.notifyEscalation(updated, oldSeverity, newSeverity);
                }

                log.info("エラーレポート重複集約: id={}, hash={}, count={}", updated.getId(), errorHash, updated.getOccurrenceCount());
                return updated;
            }
        }

        // IGNORED または該当なし → 新規作成
        ErrorReportSeverity severity = determineSeverity(request.getPageUrl(), request.getErrorMessage());
        Long organizationId = resolveOrganizationId(request.getUserId());

        ErrorReportEntity newReport = ErrorReportEntity.builder()
                .errorMessage(request.getErrorMessage())
                .stackTrace(stackTrace)
                .pageUrl(request.getPageUrl())
                .userAgent(request.getUserAgent())
                .userComment(request.getUserComment())
                .userId(request.getUserId())
                .organizationId(organizationId)
                .requestId(request.getRequestId())
                .ipAddress(ipAddress)
                .occurredAt(request.getOccurredAt())
                .status(ErrorReportStatus.NEW)
                .severity(severity)
                .errorHash(errorHash)
                .occurrenceCount(1)
                .affectedUserCount(1)
                .firstOccurredAt(request.getOccurredAt())
                .lastOccurredAt(request.getOccurredAt())
                .latestUserComment(request.getUserComment())
                .build();

        ErrorReportEntity saved = errorReportRepository.save(newReport);

        // 影響ユーザー追跡
        trackAffectedUser(errorHash, request.getUserId());

        // 新規作成時の通知
        if (severity.ordinal() >= ErrorReportSeverity.HIGH.ordinal()) {
            errorReportNotifier.notifySlack(saved);
            errorReportNotifier.notifySystemAdmins(saved);
            // F12.5 Phase 2-C — 新規 HIGH/CRITICAL は AI 即時分析をキック
            aiAnalysisService.analyzeAfterCommit(saved.getId(), null);
        }

        log.info("エラーレポート新規作成: id={}, hash={}, severity={}", saved.getId(), errorHash, severity);
        return saved;
    }

    /**
     * エラーレポートのステータスを更新する。
     *
     * @param id      エラーレポートID
     * @param request 更新リクエスト
     * @param adminId 管理者ユーザーID
     * @return 更新されたエラーレポートエンティティ
     */
    public ErrorReportEntity updateStatus(Long id, ErrorReportUpdateRequest request, Long adminId) {
        ErrorReportEntity report = errorReportRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorReportErrorCode.ERROR_REPORT_NOT_FOUND));

        ErrorReportStatus newStatus = request.getStatus() != null
                ? ErrorReportStatus.valueOf(request.getStatus()) : null;
        ErrorReportSeverity newSeverity = request.getSeverity() != null
                ? ErrorReportSeverity.valueOf(request.getSeverity()) : null;

        if (newStatus != null) {
            report.setStatus(newStatus);
        }
        if (newSeverity != null) {
            report.setSeverity(newSeverity);
        }
        if (request.getAdminNote() != null) {
            report.setAdminNote(request.getAdminNote());
        }

        if (newStatus == ErrorReportStatus.RESOLVED) {
            report.resolve(adminId);
            // 報告者通知（user_id 非NULL時）
            if (report.getUserId() != null) {
                errorReportNotifier.notifyResolution(report);
            }
        }

        log.info("エラーレポートステータス更新: id={}, status={}, adminId={}", id, newStatus, adminId);
        return report;
    }

    /**
     * エラーレポートを一括更新する。RESOLVED/IGNORED のみ許可。
     *
     * @param request 一括更新リクエスト
     * @return 更新件数
     */
    public int bulkUpdate(ErrorReportBulkUpdateRequest request) {
        ErrorReportStatus status = ErrorReportStatus.valueOf(request.getStatus());
        if (status != ErrorReportStatus.RESOLVED
                && status != ErrorReportStatus.IGNORED) {
            throw new BusinessException(ErrorReportErrorCode.ERROR_REPORT_INVALID_STATUS_TRANSITION);
        }
        if (request.getIds().size() > 100) {
            throw new BusinessException(ErrorReportErrorCode.ERROR_REPORT_BULK_LIMIT_EXCEEDED);
        }

        List<ErrorReportEntity> reports = errorReportRepository.findAllById(request.getIds());
        for (ErrorReportEntity report : reports) {
            report.setStatus(status);
            if (status == ErrorReportStatus.RESOLVED) {
                report.resolve(null);
            }
        }

        log.info("エラーレポート一括更新: count={}, status={}", reports.size(), status);
        return reports.size();
    }

    /**
     * エラーレポート統計情報を取得する。
     *
     * @return 統計レスポンス
     */
    @Transactional(readOnly = true)
    public ErrorReportStatsResponse getStats() {
        long totalNew = errorReportRepository.countByStatus(ErrorReportStatus.NEW);
        long totalInvestigating = errorReportRepository.countByStatus(ErrorReportStatus.INVESTIGATING);
        long totalReopened = errorReportRepository.countByStatus(ErrorReportStatus.REOPENED);

        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        long totalToday = errorReportRepository.countByCreatedAtAfter(todayStart);

        List<ErrorReportEntity> topErrors = errorReportRepository
                .findTop5ByStatusInOrderByOccurrenceCountDesc(
                        List.of(ErrorReportStatus.NEW, ErrorReportStatus.INVESTIGATING, ErrorReportStatus.REOPENED));

        return ErrorReportStatsResponse.builder()
                .totalNew(totalNew)
                .totalInvestigating(totalInvestigating)
                .totalReopened(totalReopened)
                .totalToday(totalToday)
                .topErrors(topErrors.stream()
                        .map(e -> ErrorReportStatsResponse.TopError.builder()
                                .errorHash(e.getErrorHash())
                                .errorMessage(e.getErrorMessage())
                                .pageUrl(e.getPageUrl())
                                .occurrenceCount(e.getOccurrenceCount())
                                .affectedUserCount(e.getAffectedUserCount())
                                .lastOccurredAt(e.getLastOccurredAt())
                                .build())
                        .toList())
                .build();
    }

    /**
     * アクティブなインシデント（CRITICAL/HIGH かつ NEW/INVESTIGATING/REOPENED）を取得する。
     *
     * @return アクティブインシデントレスポンスのリスト
     */
    @Transactional(readOnly = true)
    @Cacheable("active-incidents")
    public ActiveIncidentResponse getActiveIncidents() {
        List<ErrorReportEntity> reports = errorReportRepository
                .findBySeverityInAndStatusIn(
                        List.of(ErrorReportSeverity.CRITICAL, ErrorReportSeverity.HIGH),
                        List.of(ErrorReportStatus.NEW, ErrorReportStatus.INVESTIGATING, ErrorReportStatus.REOPENED));

        List<ActiveIncidentResponse.Incident> incidents = reports.stream()
                .map(report -> ActiveIncidentResponse.Incident.builder()
                        .pagePattern(toWildcardPattern(extractPath(report.getPageUrl())))
                        .message("一部の画面で不具合が発生しています。現在対応中です。")
                        .severity(report.getSeverity().name())
                        .since(report.getFirstOccurredAt())
                        .build())
                .toList();

        return ActiveIncidentResponse.builder()
                .incidents(incidents)
                .build();
    }

    /**
     * エラーレポートをIDで取得する。
     *
     * @param id エラーレポートID
     * @return エラーレポートエンティティ
     */
    @Transactional(readOnly = true)
    public ErrorReportEntity findById(Long id) {
        return errorReportRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorReportErrorCode.ERROR_REPORT_NOT_FOUND));
    }

    /**
     * エラーレポートを検索する（ステータス・重要度・日付範囲でフィルタ）。
     *
     * @param status   ステータス文字列（nullable）
     * @param severity 重要度文字列（nullable）
     * @param from     開始日（nullable）
     * @param to       終了日（nullable）
     * @param pageable ページング情報
     * @return ページングされたエラーレポート
     */
    @Transactional(readOnly = true)
    public Page<ErrorReportEntity> search(String status, String severity,
                                           LocalDate from, LocalDate to, Pageable pageable) {
        ErrorReportStatus statusEnum = null;
        ErrorReportSeverity severityEnum = null;
        try {
            statusEnum = status != null ? ErrorReportStatus.valueOf(status) : null;
            severityEnum = severity != null ? ErrorReportSeverity.valueOf(severity) : null;
        } catch (IllegalArgumentException e) {
            // 不正な enum 値はフィルタ無しとして扱う
            log.warn("不正なフィルタ値: status={}, severity={}", status, severity);
        }

        if (statusEnum != null && severityEnum != null) {
            return errorReportRepository.findByStatusAndSeverity(statusEnum, severityEnum, pageable);
        } else if (statusEnum != null) {
            return errorReportRepository.findByStatus(statusEnum, pageable);
        } else if (severityEnum != null) {
            return errorReportRepository.findBySeverity(severityEnum, pageable);
        } else if (from != null && to != null) {
            return errorReportRepository.findByCreatedAtBetween(
                    from.atStartOfDay(), to.plusDays(1).atStartOfDay(), pageable);
        }
        return errorReportRepository.findAll(pageable);
    }

    // ========================================
    // F12.5 Phase 2 — ワークフロー / 担当者 / コメント
    // ========================================

    /**
     * F12.5 Phase 2 — エラーレポートのワークフロー段階を更新する。
     * Kanban DnD（P2-E）からの呼び出しに対応するため、status と workflow_stage の
     * 整合性は「不正な組み合わせは拒否」かつ「自然な遷移は status を自動昇格／復帰」
     * の方針に変更する。
     *
     * <ul>
     *   <li>status=IGNORED は対象外（操作不可）</li>
     *   <li>NEW/INVESTIGATING/REOPENED → INVESTIGATION_STARTED〜FIX_IN_PROGRESS：
     *       status を INVESTIGATING に昇格</li>
     *   <li>NEW/INVESTIGATING/REOPENED → TEST_COMPLETED/RELEASED：
     *       status を RESOLVED に昇格</li>
     *   <li>RESOLVED → INVESTIGATION_STARTED〜FIX_IN_PROGRESS：
     *       status を REOPENED に復帰</li>
     *   <li>RESOLVED → TEST_COMPLETED/RELEASED：そのまま RESOLVED 維持</li>
     *   <li>newStage=NULL（未着手）：status=NEW にリセット
     *       （RESOLVED の場合は REOPENED に復帰）</li>
     * </ul>
     *
     * @param id       エラーレポートID
     * @param newStage 新しいワークフロー段階（NULL は未着手にリセット）
     * @param actorId  操作者ユーザーID
     * @return 更新後のエラーレポートエンティティ
     */
    public ErrorReportEntity updateWorkflowStage(Long id, ErrorReportWorkflowStage newStage, Long actorId) {
        accessControlService.checkSystemAdmin(actorId);
        ErrorReportEntity report = findByIdOrThrow(id);
        ErrorReportWorkflowStage oldStage = report.getWorkflowStage();
        ErrorReportStatus oldStatus = report.getStatus();

        // P2-E — IGNORED に対する Kanban / 工程更新は引き続き拒否（誤操作防止）
        if (oldStatus == ErrorReportStatus.IGNORED) {
            throw new BusinessException(ErrorReportErrorCode.ERROR_REPORT_005);
        }

        // status を新ステージに合わせて自動遷移
        ErrorReportStatus newStatus = inferStatusFromStage(oldStatus, newStage);

        if (newStatus != oldStatus) {
            report.setStatus(newStatus);
            // RESOLVED に昇格する場合は resolve() で resolvedBy/resolvedAt も更新
            if (newStatus == ErrorReportStatus.RESOLVED) {
                report.resolve(actorId);
            }
            Map<String, Object> statusMetadata = new HashMap<>();
            statusMetadata.put("from", oldStatus.name());
            statusMetadata.put("to", newStatus.name());
            activityService.record(id, actorId, ErrorReportActivityType.STATUS_CHANGED, null, statusMetadata);
        }

        report.setWorkflowStage(newStage);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("from", oldStage != null ? oldStage.name() : null);
        metadata.put("to", newStage != null ? newStage.name() : null);
        activityService.record(id, actorId, ErrorReportActivityType.WORKFLOW_CHANGED, null, metadata);

        log.info("エラーレポートワークフロー更新: id={}, from={}, to={}, status={}→{}, actorId={}",
                id, oldStage, newStage, oldStatus, newStatus, actorId);
        return report;
    }

    /**
     * 新しい workflow_stage に応じた status を推定する。
     * 詳細は {@link #updateWorkflowStage} の Javadoc を参照。
     *
     * @param current  現在の status（IGNORED は事前に弾く想定）
     * @param newStage 新しい workflow_stage（NULL は「未着手」へ戻す）
     * @return 適用すべき status
     */
    private ErrorReportStatus inferStatusFromStage(ErrorReportStatus current,
                                                    ErrorReportWorkflowStage newStage) {
        if (newStage == null) {
            // 未着手へ戻す
            if (current == ErrorReportStatus.RESOLVED) {
                return ErrorReportStatus.REOPENED;
            }
            // NEW / INVESTIGATING / REOPENED → NEW にリセット
            return ErrorReportStatus.NEW;
        }
        return switch (newStage) {
            case INVESTIGATION_STARTED, ROOT_CAUSE_IDENTIFIED, FIX_IN_PROGRESS -> {
                if (current == ErrorReportStatus.RESOLVED) {
                    yield ErrorReportStatus.REOPENED;
                }
                if (current == ErrorReportStatus.NEW) {
                    yield ErrorReportStatus.INVESTIGATING;
                }
                // INVESTIGATING / REOPENED はそのまま
                yield current;
            }
            case TEST_COMPLETED, RELEASED -> ErrorReportStatus.RESOLVED;
        };
    }

    /**
     * F12.5 Phase 2 — エラーレポートに担当者を割り当てる/解除する。
     * 担当者は SYSTEM_ADMIN 権限保有者のみ許可。
     *
     * @param id          エラーレポートID
     * @param assigneeId  担当者ユーザーID（NULL で解除）
     * @param actorId     操作者ユーザーID
     * @return 更新後のエラーレポートエンティティ
     */
    public ErrorReportEntity assign(Long id, Long assigneeId, Long actorId) {
        accessControlService.checkSystemAdmin(actorId);
        ErrorReportEntity report = findByIdOrThrow(id);
        Long oldAssigneeId = report.getAssigneeId();

        if (assigneeId != null) {
            if (!accessControlService.isSystemAdmin(assigneeId)) {
                throw new BusinessException(ErrorReportErrorCode.ERROR_REPORT_006);
            }
        }

        report.setAssigneeId(assigneeId);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("from", oldAssigneeId);
        metadata.put("to", assigneeId);
        activityService.record(id, actorId, ErrorReportActivityType.ASSIGNEE_CHANGED, null, metadata);

        if (assigneeId != null) {
            errorReportNotifier.notifyAssignment(report, assigneeId);
        }

        log.info("エラーレポート担当者更新: id={}, from={}, to={}, actorId={}",
                id, oldAssigneeId, assigneeId, actorId);
        return report;
    }

    /**
     * F12.5 Phase 2 — エラーレポートに管理者コメントを追加する。
     *
     * @param id      エラーレポートID
     * @param content コメント本文（最大2000文字）
     * @param actorId 操作者ユーザーID
     */
    public void addComment(Long id, String content, Long actorId) {
        accessControlService.checkSystemAdmin(actorId);
        // 存在チェック
        findByIdOrThrow(id);
        activityService.record(id, actorId, ErrorReportActivityType.COMMENT_ADDED, content, null);
        log.info("エラーレポートコメント追加: id={}, actorId={}, length={}",
                id, actorId, content != null ? content.length() : 0);
    }

    /**
     * F12.5 Phase 2 — タイムラインを取得する。
     * occurrences と activities をマージして {@code occurredAt} 降順で返す。
     *
     * @param id     エラーレポートID
     * @param cursor ページングカーソル（{@code "epochMillis:type:id"} 形式、NULL は先頭）
     * @param limit  取得上限件数（呼び出し元で 100 にキャップ済み想定）
     * @return タイムラインレスポンス
     */
    @Transactional(readOnly = true)
    public ErrorReportTimelineResponse fetchTimeline(Long id, String cursor, int limit) {
        // 存在チェック
        findByIdOrThrow(id);

        // 単純実装: 各 50 件取得 → メモリ上でマージ・ソート → cursor 適用
        // 件数が多い場合の最適化は P2-E（仮想スクロール導入）で実施
        int fetchSize = Math.max(limit * 2, 100);
        Pageable pageable = PageRequest.of(0, fetchSize);

        Page<ErrorReportOccurrenceEntity> occurrences =
                occurrenceRepository.findByErrorReportIdOrderByOccurredAtDesc(id, pageable);
        Page<ErrorReportActivityEntity> activities =
                activityRepository.findByErrorReportIdOrderByCreatedAtDesc(id, pageable);

        // ユーザー名解決（バルク、N+1 防止）
        Set<Long> userIds = new HashSet<>();
        occurrences.getContent().forEach(o -> {
            if (o.getUserId() != null) userIds.add(o.getUserId());
        });
        activities.getContent().forEach(a -> {
            if (a.getActorId() != null) userIds.add(a.getActorId());
        });
        Map<Long, String> nameByUserId = resolveUserNames(userIds);

        List<ErrorReportTimelineResponse.TimelineItem> items = new ArrayList<>();

        for (ErrorReportOccurrenceEntity o : occurrences.getContent()) {
            items.add(ErrorReportTimelineResponse.TimelineItem.builder()
                    .type("OCCURRENCE")
                    .occurredAt(o.getOccurredAt())
                    .pageUrl(o.getPageUrl())
                    .userId(o.getUserId())
                    .userAgent(o.getUserAgent())
                    .build());
        }

        for (ErrorReportActivityEntity a : activities.getContent()) {
            Map<String, Object> metadata = parseMetadata(a.getMetadataJson());
            boolean systemActor = a.getActorId() == null
                    && metadata != null && Boolean.TRUE.equals(metadata.get("system"));
            String actorName = a.getActorId() != null ? nameByUserId.get(a.getActorId()) : null;

            items.add(ErrorReportTimelineResponse.TimelineItem.builder()
                    .type("ACTIVITY")
                    .occurredAt(a.getCreatedAt())
                    .activityType(a.getActivityType())
                    .actorId(a.getActorId())
                    .actorName(actorName)
                    .systemActor(systemActor)
                    .content(a.getContent())
                    .metadata(metadata)
                    .build());
        }

        // occurredAt 降順、タイ時は ACTIVITY → OCCURRENCE の順
        items.sort(Comparator
                .comparing(ErrorReportTimelineResponse.TimelineItem::getOccurredAt,
                        Comparator.nullsLast(Comparator.reverseOrder())));

        // cursor 適用（"epochMillis" 形式、その時刻より前のアイテムを返す）
        if (cursor != null && !cursor.isBlank()) {
            try {
                long cursorMillis = Long.parseLong(cursor);
                LocalDateTime cursorTime = LocalDateTime.ofEpochSecond(cursorMillis / 1000,
                        (int) ((cursorMillis % 1000) * 1_000_000),
                        java.time.ZoneOffset.UTC);
                items = items.stream()
                        .filter(i -> i.getOccurredAt() != null && i.getOccurredAt().isBefore(cursorTime))
                        .toList();
            } catch (NumberFormatException e) {
                log.warn("不正な cursor 値: {}", cursor);
            }
        }

        boolean hasMore = items.size() > limit;
        List<ErrorReportTimelineResponse.TimelineItem> page = hasMore
                ? new ArrayList<>(items.subList(0, limit))
                : new ArrayList<>(items);

        String nextCursor = null;
        if (hasMore && !page.isEmpty()) {
            LocalDateTime last = page.get(page.size() - 1).getOccurredAt();
            if (last != null) {
                long millis = last.toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
                nextCursor = String.valueOf(millis);
            }
        }

        return ErrorReportTimelineResponse.builder()
                .items(page)
                .hasMore(hasMore)
                .nextCursor(nextCursor)
                .build();
    }

    // F12.5 Phase 2-E — Kanban DnD で柔軟な遷移を許すため、
    // 厳密な validateWorkflowTransition は廃止し、inferStatusFromStage で
    // status を自動遷移させる方式に置換済み。

    // ========================================
    // F12.5 Phase 2-E — Kanban ビュー
    // ========================================

    /** Kanban カラムあたりの最大カード件数。 */
    private static final int KANBAN_COLUMN_CARD_LIMIT = 50;
    /** Kanban カードのエラーメッセージ表示上限。 */
    private static final int KANBAN_MESSAGE_MAX_LENGTH = 80;
    /** Kanban カードのページURL表示上限。 */
    private static final int KANBAN_PAGE_URL_MAX_LENGTH = 80;

    /**
     * F12.5 Phase 2-E — Kanban ビュー用の 6 カラムを取得する。
     *
     * <p>カラム順:</p>
     * <ol>
     *   <li>NULL（未着手） — status IN (NEW, INVESTIGATING, REOPENED) AND workflow_stage IS NULL</li>
     *   <li>INVESTIGATION_STARTED</li>
     *   <li>ROOT_CAUSE_IDENTIFIED</li>
     *   <li>FIX_IN_PROGRESS</li>
     *   <li>TEST_COMPLETED</li>
     *   <li>RELEASED</li>
     * </ol>
     *
     * <p>各カラム最大 50 件、{@code last_occurred_at DESC}。
     * IGNORED は対象外。assignee 名と AI 分析の有無はバルク解決して N+1 を防ぐ。</p>
     */
    @Transactional(readOnly = true)
    public KanbanResponse fetchKanban() {
        // 各カラムごとに Page を取得し、key→content と totalCount を保持する
        Pageable pageable = PageRequest.of(0, KANBAN_COLUMN_CARD_LIMIT);

        // NULL（未着手）カラム
        Page<ErrorReportEntity> nullPage = errorReportRepository
                .findByStatusInAndWorkflowStageIsNullOrderByLastOccurredAtDesc(
                        List.of(ErrorReportStatus.NEW,
                                ErrorReportStatus.INVESTIGATING,
                                ErrorReportStatus.REOPENED),
                        pageable);

        // 各 workflow_stage カラム
        Map<ErrorReportWorkflowStage, Page<ErrorReportEntity>> stagePages = new HashMap<>();
        for (ErrorReportWorkflowStage stage : ErrorReportWorkflowStage.values()) {
            stagePages.put(stage, errorReportRepository
                    .findByWorkflowStageOrderByLastOccurredAtDesc(stage, pageable));
        }

        // バルク解決のため、全カードのレポートを集める
        List<ErrorReportEntity> allReports = new ArrayList<>(nullPage.getContent());
        for (ErrorReportWorkflowStage stage : ErrorReportWorkflowStage.values()) {
            allReports.addAll(stagePages.get(stage).getContent());
        }

        Set<Long> assigneeIds = new HashSet<>();
        List<Long> reportIds = new ArrayList<>(allReports.size());
        for (ErrorReportEntity r : allReports) {
            reportIds.add(r.getId());
            if (r.getAssigneeId() != null) {
                assigneeIds.add(r.getAssigneeId());
            }
        }
        Map<Long, String> assigneeNames = resolveUserNames(assigneeIds);
        Set<Long> aiAnalyzedIds = reportIds.isEmpty()
                ? Set.of()
                : new HashSet<>(aiAnalysisRepository.findIdsHavingSuccessfulAnalysis(reportIds));

        // カラム組み立て
        List<KanbanResponse.KanbanColumn> columns = new ArrayList<>();
        columns.add(buildColumn("NULL",
                nullPage.getTotalElements(),
                nullPage.getTotalElements() > KANBAN_COLUMN_CARD_LIMIT,
                nullPage.getContent(),
                assigneeNames,
                aiAnalyzedIds));
        for (ErrorReportWorkflowStage stage : ErrorReportWorkflowStage.values()) {
            Page<ErrorReportEntity> p = stagePages.get(stage);
            columns.add(buildColumn(stage.name(),
                    p.getTotalElements(),
                    p.getTotalElements() > KANBAN_COLUMN_CARD_LIMIT,
                    p.getContent(),
                    assigneeNames,
                    aiAnalyzedIds));
        }

        return KanbanResponse.builder().columns(columns).build();
    }

    /**
     * Kanban カラムを組み立てる。エンティティ → カードに変換し、
     * バルク解決済みの assignee 名と AI 分析判定を埋め込む。
     */
    private KanbanResponse.KanbanColumn buildColumn(String stageKey,
                                                     long totalCount,
                                                     boolean hasMore,
                                                     List<ErrorReportEntity> reports,
                                                     Map<Long, String> assigneeNames,
                                                     Set<Long> aiAnalyzedIds) {
        List<KanbanResponse.KanbanCard> cards = new ArrayList<>(reports.size());
        for (ErrorReportEntity r : reports) {
            cards.add(KanbanResponse.KanbanCard.builder()
                    .id(r.getId())
                    .errorMessage(truncate(r.getErrorMessage(), KANBAN_MESSAGE_MAX_LENGTH))
                    .severity(r.getSeverity().name())
                    .status(r.getStatus().name())
                    .occurrenceCount(r.getOccurrenceCount() != null ? r.getOccurrenceCount() : 0)
                    .affectedUserCount(r.getAffectedUserCount() != null ? r.getAffectedUserCount() : 0)
                    .lastOccurredAt(r.getLastOccurredAt())
                    .assigneeId(r.getAssigneeId())
                    .assigneeName(r.getAssigneeId() != null ? assigneeNames.get(r.getAssigneeId()) : null)
                    .pageUrl(truncate(r.getPageUrl(), KANBAN_PAGE_URL_MAX_LENGTH))
                    .hasGithubIssue(r.getGithubIssueUrl() != null && !r.getGithubIssueUrl().isBlank())
                    .hasAiAnalysis(aiAnalyzedIds.contains(r.getId()))
                    .build());
        }
        return KanbanResponse.KanbanColumn.builder()
                .stageKey(stageKey)
                .totalCount(totalCount)
                .hasMore(hasMore)
                .cards(cards)
                .build();
    }

    /**
     * 内部用: ID で取得し、未存在なら BusinessException を投げる。
     */
    private ErrorReportEntity findByIdOrThrow(Long id) {
        return errorReportRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorReportErrorCode.ERROR_REPORT_NOT_FOUND));
    }

    /**
     * ユーザーIDから表示名を一括解決する（N+1 防止）。
     */
    private Map<Long, String> resolveUserNames(Set<Long> userIds) {
        if (userIds.isEmpty()) return Map.of();
        Map<Long, String> result = new HashMap<>();
        List<UserEntity> users = userRepository.findByIdIn(userIds);
        for (UserEntity u : users) {
            result.put(u.getId(), u.getDisplayName());
        }
        return result;
    }

    /**
     * metadata_json をパースする。失敗時は null を返す（タイムライン表示は継続）。
     */
    private Map<String, Object> parseMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) return null;
        try {
            return objectMapper.readValue(metadataJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("metadata_json のパースに失敗: {}", metadataJson, e);
            return null;
        }
    }

    /**
     * エラーメッセージを正規化してハッシュの重複検知精度を高める。
     * 元の error_message はそのまま DB に保存し、正規化はハッシュ計算時のみ使用する。
     */
    private String normalizeForHash(String errorMessage) {
        return errorMessage
                .replaceAll("'[^']*'", "'?'")           // 'name' → '?'
                .replaceAll("\"[^\"]*\"", "\"?\"")       // "name" → "?"
                .replaceAll("\\b\\d+\\b", "N")           // 42 → N
                .replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "UUID");
    }

    /**
     * user_id から organization_id をルックアップする。
     */
    private Long resolveOrganizationId(Long userId) {
        if (userId == null) return null;
        return errorReportRepository
                .findOrganizationIdByUserId(userId)
                .orElse(null);
    }

    /**
     * 影響ユーザーを Valkey SET で追跡し、ユニークユーザー数を返す。
     */
    private int trackAffectedUser(String errorHash, Long userId) {
        if (userId == null) return -1;
        String key = "error-report:affected:" + errorHash;
        redisTemplate.opsForSet().add(key, userId.toString());
        redisTemplate.expire(key, Duration.ofDays(90));
        Long size = redisTemplate.opsForSet().size(key);
        return size != null ? size.intValue() : -1;
    }

    /**
     * 新規作成時の severity 自動判定。
     */
    private ErrorReportSeverity determineSeverity(String pageUrl, String errorMessage) {
        if (pageUrl != null && (pageUrl.contains("/checkout") || pageUrl.contains("/payment"))) {
            return ErrorReportSeverity.HIGH;
        }
        if (errorMessage != null && errorMessage.contains("ChunkLoadError")) {
            return ErrorReportSeverity.LOW;
        }
        return ErrorReportSeverity.MEDIUM;
    }

    /**
     * affected_user_count による severity 補正。
     */
    private ErrorReportSeverity adjustSeverity(ErrorReportEntity report, int affectedCount) {
        ErrorReportSeverity severity = report.getSeverity();

        // occurrence_count >= 50 かつ affected_user_count <= 1 → CRITICAL → HIGH に据え置き
        if (report.getOccurrenceCount() >= 50 && affectedCount <= 1
                && severity == ErrorReportSeverity.CRITICAL) {
            return ErrorReportSeverity.HIGH;
        }

        // affected_user_count >= 20 かつ severity が MEDIUM → HIGH に昇格
        if (affectedCount >= 20 && severity == ErrorReportSeverity.MEDIUM) {
            return ErrorReportSeverity.HIGH;
        }

        return severity;
    }

    /**
     * URL からパス部分を抽出する。
     */
    private String extractPath(String url) {
        try {
            return URI.create(url).getPath();
        } catch (Exception e) {
            // URL パースに失敗した場合はそのまま返す
            return url;
        }
    }

    /**
     * パスからワイルドカードパターンを生成する。
     * 末尾の動的セグメント（数値・UUID）を * に置換。
     */
    private String toWildcardPattern(String path) {
        if (path == null) return "*";
        return path.replaceAll("/\\d+", "/*")
                .replaceAll("/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "/*");
    }

    /**
     * SHA-256 ハッシュを計算する。
     */
    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * 文字列を指定長に切り詰める。
     */
    static String truncate(String str, int maxLength) {
        if (str == null) return null;
        return str.length() <= maxLength ? str : str.substring(0, maxLength) + "...";
    }
}
