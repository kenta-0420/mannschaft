package com.mannschaft.app.errorreport.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.errorreport.ErrorReportActivityType;
import com.mannschaft.app.errorreport.ErrorReportErrorCode;
import com.mannschaft.app.errorreport.ErrorReportStatus;
import com.mannschaft.app.errorreport.ErrorReportWorkflowStage;
import com.mannschaft.app.errorreport.dto.ErrorReportTimelineResponse;
import com.mannschaft.app.errorreport.entity.ErrorReportActivityEntity;
import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
import com.mannschaft.app.errorreport.entity.ErrorReportOccurrenceEntity;
import com.mannschaft.app.errorreport.event.ErrorReportAssignedEvent;
import com.mannschaft.app.errorreport.repository.ErrorReportActivityRepository;
import com.mannschaft.app.errorreport.repository.ErrorReportOccurrenceRepository;
import com.mannschaft.app.errorreport.repository.ErrorReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * エラーレポートのタイムライン・コメント・担当者・ワークフロー更新を担当するサービス。
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class ErrorReportTimelineService {

    private final ErrorReportRepository errorReportRepository;
    private final ErrorReportActivityRepository activityRepository;
    private final ErrorReportOccurrenceRepository occurrenceRepository;
    private final ErrorReportActivityService activityService;
    private final AccessControlService accessControlService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    /**
     * Issue #2990 L11 — 担当者割り当て通知は業務TX内で発火せず、ID だけを載せた業務イベントを publish する。
     * 実配送は {@link ErrorReportNotificationListener} が {@code AFTER_COMMIT} で行う。
     */
    private final ApplicationEventPublisher eventPublisher;

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
            eventPublisher.publishEvent(new ErrorReportAssignedEvent(id, assigneeId));
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
            result.put(u.getId(), u.getLastName() + " " + u.getFirstName());
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
}
