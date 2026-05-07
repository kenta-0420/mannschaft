package com.mannschaft.app.todo.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.todo.TodoErrorCode;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.TodoStatus;
import com.mannschaft.app.todo.TodoStatusBucket;
import com.mannschaft.app.todo.dto.TodoHandoffRequest;
import com.mannschaft.app.todo.dto.TodoHandoffResponse;
import com.mannschaft.app.todo.entity.TodoAssigneeEntity;
import com.mannschaft.app.todo.entity.TodoEntity;
import com.mannschaft.app.todo.entity.TodoHandoffEntity;
import com.mannschaft.app.todo.entity.TodoStatusLabelEntity;
import com.mannschaft.app.todo.event.TodoHandoffEvent;
import com.mannschaft.app.todo.event.TodoStatusChangedEvent;
import com.mannschaft.app.todo.repository.TodoAssigneeRepository;
import com.mannschaft.app.todo.repository.TodoHandoffRepository;
import com.mannschaft.app.todo.repository.TodoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TODO キャッチボール（引き渡し）サービス（F02.3.1 Phase 2）。
 *
 * <p>1回の {@link #handoff} で以下を同一トランザクション内に行う:
 * <ol>
 *   <li>個人スコープ拒否 / TODO スコープ整合 / 操作者メンバー判定 / 宛先メンバー判定 / ラベル整合</li>
 *   <li>操作前 snapshot の取得</li>
 *   <li>既存 {@code todo_assignees} の全削除 → 新 assignees の一括挿入</li>
 *   <li>{@link TodoEntity#changeStatusWithLabel} 呼び出し</li>
 *   <li>{@link TodoHandoffEntity} の保存（ラベル名スナップショット込み）</li>
 *   <li>{@link TodoHandoffEvent} 発行（通知ディスパッチ用）</li>
 *   <li>{@link TodoStatusChangedEvent}({@code fromHandoff=true}) 発行（status 通知抑制用）</li>
 *   <li>{@code TODO_HANDED_OFF} 監査ログ記録</li>
 * </ol>
 *
 * <p>自己 handoff（操作者だけが toUserIds に含まれる）は許容するが、通知は飛ばさない。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TodoHandoffService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final TodoRepository todoRepository;
    private final TodoAssigneeRepository assigneeRepository;
    private final TodoHandoffRepository handoffRepository;
    private final TodoStatusLabelService labelService;
    private final TodoService todoService;
    private final AccessControlService accessControlService;
    private final NameResolverService nameResolverService;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * TODO をキャッチボールする。
     *
     * @param scopeType TODO スコープ種別（PERSONAL は 400）
     * @param scopeId   TODO スコープ ID
     * @param todoId    対象 TODO ID
     * @param request   引き渡しリクエスト
     * @param actorId   操作ユーザー ID
     * @return 履歴レスポンス
     */
    @Transactional
    public ApiResponse<TodoHandoffResponse> handoff(TodoScopeType scopeType, Long scopeId,
                                                     Long todoId, TodoHandoffRequest request, Long actorId) {
        // (1) 個人スコープ拒否
        if (scopeType == TodoScopeType.PERSONAL) {
            throw new BusinessException(TodoErrorCode.HANDOFF_NOT_ALLOWED_FOR_PERSONAL);
        }

        // (2) TODO 取得 + スコープ整合（IDOR 対策のため TODO_NOT_FOUND で統一）
        TodoEntity todo = todoRepository.findByIdAndDeletedAtIsNull(todoId)
                .filter(t -> t.getScopeType() == scopeType && java.util.Objects.equals(t.getScopeId(), scopeId))
                .orElseThrow(() -> new BusinessException(TodoErrorCode.TODO_NOT_FOUND));

        // (2.5) マイルストーンロック中 TODO への handoff は拒否（F02.7）
        // Handoff はステータス・担当者変更を伴うため、ロック中の TODO は 423 Locked で拒否する。
        todoService.assertNotMilestoneLocked(todo);

        // (3) 操作者がスコープのメンバーか
        String scopeKey = mapScopeTypeKey(scopeType);
        if (!accessControlService.isMember(actorId, scopeId, scopeKey)) {
            throw new BusinessException(TodoErrorCode.TODO_NOT_FOUND);
        }

        // (4) 全宛先ユーザーがスコープのメンバーか（自己 handoff も許可）
        List<Long> toUserIds = new ArrayList<>(new java.util.LinkedHashSet<>(request.getToUserIds()));
        for (Long uid : toUserIds) {
            if (!accessControlService.isMember(uid, scopeId, scopeKey)) {
                throw new BusinessException(TodoErrorCode.HANDOFF_RECIPIENT_NOT_MEMBER);
            }
        }

        // (5) ラベル検証（存在 + スコープ一致 / SYSTEM 可）
        TodoStatusLabelEntity label = labelService.findActiveById(request.getStatusLabelId());
        labelService.validateLabelForScope(label, scopeType, scopeId);

        // (6) 操作前 snapshot
        List<TodoAssigneeEntity> previousAssignees = assigneeRepository.findByTodoId(todoId);
        List<Long> previousUserIds = previousAssignees.stream().map(TodoAssigneeEntity::getUserId).toList();
        TodoStatus previousStatus = todo.getStatus();
        Long previousLabelId = todo.getStatusLabelId();
        TodoStatusLabelEntity previousLabel = previousLabelId != null
                ? labelService.findActiveByIds(List.of(previousLabelId)).stream().findFirst().orElse(null)
                : null;
        String previousLabelName = previousLabel != null ? previousLabel.getName() : null;

        // (7) 同一トランザクション内: assignees 置換 + status/label 更新 + handoff 行挿入
        // (7a) 既存 assignees 全削除
        if (!previousAssignees.isEmpty()) {
            assigneeRepository.deleteAll(previousAssignees);
            assigneeRepository.flush();
        }
        // (7b) 新 assignees 一括挿入
        for (Long uid : toUserIds) {
            assigneeRepository.save(TodoAssigneeEntity.builder()
                    .todoId(todoId)
                    .userId(uid)
                    .assignedBy(actorId)
                    .build());
        }
        // (7c) status + statusLabelId 更新
        TodoStatus newStatus = bucketToStatus(label.getBucket());
        todo.changeStatusWithLabel(newStatus, label.getId(), actorId);
        todo = todoRepository.save(todo);

        // (7d) 履歴行挿入
        TodoHandoffEntity handoff = TodoHandoffEntity.builder()
                .todoId(todoId)
                .fromUserId(actorId)
                .fromAssigneeUserIds(toJsonArray(previousUserIds))
                .toAssigneeUserIds(toJsonArray(toUserIds))
                .previousStatus(previousStatus.name())
                .previousStatusLabelId(previousLabelId)
                .previousStatusLabelName(previousLabelName)
                .newStatus(newStatus.name())
                .newStatusLabelId(label.getId())
                .newStatusLabelName(label.getName())
                .message(blankToNull(request.getMessage()))
                .build();
        handoff = handoffRepository.save(handoff);

        // (8) Handoff イベント発行
        eventPublisher.publishEvent(new TodoHandoffEvent(
                todoId,
                actorId,
                toUserIds,
                label.getName(),
                handoff.getMessage(),
                todo.getTitle(),
                scopeType,
                scopeId
        ));

        // (9) ステータス変更イベントを fromHandoff=true で発行（status 通知抑制用）
        eventPublisher.publishEvent(new TodoStatusChangedEvent(
                todoId, todo.getProjectId(), previousStatus, newStatus, actorId, true));

        // (10) 監査ログ
        Long teamId = scopeType == TodoScopeType.TEAM ? scopeId : null;
        Long orgId = scopeType == TodoScopeType.ORGANIZATION ? scopeId : null;
        auditLogService.record(AuditEventType.TODO_HANDED_OFF.name(), actorId, null, teamId, orgId, null, null, null,
                buildAuditMetadata(todoId, previousUserIds, toUserIds, previousStatus, newStatus, label));

        log.info("TODO キャッチボール: todoId={}, from={}, to={}, label={}",
                todoId, actorId, toUserIds, label.getName());

        return ApiResponse.of(toResponse(handoff, label, previousLabel));
    }

    /**
     * TODO のキャッチボール履歴を新しい順で取得する。
     *
     * @param scopeType TODO スコープ種別
     * @param scopeId   TODO スコープ ID
     * @param todoId    対象 TODO ID
     * @param actorId   操作ユーザー ID（メンバーシップ確認用）
     * @return 履歴一覧
     */
    public ApiResponse<List<TodoHandoffResponse>> listHistory(TodoScopeType scopeType, Long scopeId,
                                                               Long todoId, Long actorId) {
        // 個人スコープには履歴 UI を出さない（呼ばれても 404 で返す）
        if (scopeType == TodoScopeType.PERSONAL) {
            throw new BusinessException(TodoErrorCode.TODO_NOT_FOUND);
        }

        TodoEntity todo = todoRepository.findByIdAndDeletedAtIsNull(todoId)
                .filter(t -> t.getScopeType() == scopeType && java.util.Objects.equals(t.getScopeId(), scopeId))
                .orElseThrow(() -> new BusinessException(TodoErrorCode.TODO_NOT_FOUND));

        // メンバーシップ確認
        String scopeKey = mapScopeTypeKey(scopeType);
        if (!accessControlService.isMember(actorId, scopeId, scopeKey)) {
            throw new BusinessException(TodoErrorCode.TODO_NOT_FOUND);
        }

        List<TodoHandoffEntity> rows = handoffRepository.findByTodoIdOrderByCreatedAtDesc(todo.getId());

        // ラベルを一括取得（N+1 対策）
        Set<Long> labelIds = new HashSet<>();
        for (TodoHandoffEntity h : rows) {
            if (h.getPreviousStatusLabelId() != null) labelIds.add(h.getPreviousStatusLabelId());
            if (h.getNewStatusLabelId() != null) labelIds.add(h.getNewStatusLabelId());
        }
        Map<Long, TodoStatusLabelEntity> labelMap = new java.util.HashMap<>();
        if (!labelIds.isEmpty()) {
            for (TodoStatusLabelEntity l : labelService.findActiveByIds(labelIds)) {
                labelMap.put(l.getId(), l);
            }
        }

        List<TodoHandoffResponse> responses = rows.stream()
                .map(h -> toResponse(h,
                        h.getNewStatusLabelId() != null ? labelMap.get(h.getNewStatusLabelId()) : null,
                        h.getPreviousStatusLabelId() != null ? labelMap.get(h.getPreviousStatusLabelId()) : null))
                .toList();
        return ApiResponse.of(responses);
    }

    // ─────────────────────────────────────────────
    // ヘルパー
    // ─────────────────────────────────────────────

    private TodoHandoffResponse toResponse(TodoHandoffEntity entity,
                                            TodoStatusLabelEntity newLabel,
                                            TodoStatusLabelEntity previousLabel) {
        List<Long> fromUserIds = parseJsonArray(entity.getFromAssigneeUserIds());
        List<Long> toUserIds = parseJsonArray(entity.getToAssigneeUserIds());

        Set<Long> needNames = new HashSet<>();
        needNames.add(entity.getFromUserId());
        needNames.addAll(fromUserIds);
        needNames.addAll(toUserIds);
        Map<Long, String> nameMap = nameResolverService.resolveUserDisplayNames(needNames);

        TodoHandoffResponse.UserSummary fromUser = new TodoHandoffResponse.UserSummary(
                entity.getFromUserId(), nameMap.getOrDefault(entity.getFromUserId(), ""));
        List<TodoHandoffResponse.UserSummary> fromAssignees = fromUserIds.stream()
                .map(uid -> new TodoHandoffResponse.UserSummary(uid, nameMap.getOrDefault(uid, "")))
                .toList();
        List<TodoHandoffResponse.UserSummary> toAssignees = toUserIds.stream()
                .map(uid -> new TodoHandoffResponse.UserSummary(uid, nameMap.getOrDefault(uid, "")))
                .toList();

        TodoHandoffResponse.LabelInfo previousInfo = buildLabelInfo(
                entity.getPreviousStatusLabelId(), entity.getPreviousStatusLabelName(), previousLabel);
        TodoHandoffResponse.LabelInfo newInfo = buildLabelInfo(
                entity.getNewStatusLabelId(), entity.getNewStatusLabelName(), newLabel);

        return new TodoHandoffResponse(
                entity.getId(),
                fromUser,
                fromAssignees,
                toAssignees,
                entity.getPreviousStatus(),
                previousInfo,
                entity.getNewStatus(),
                newInfo,
                entity.getMessage(),
                entity.getCreatedAt()
        );
    }

    /**
     * ラベル情報を組み立てる。スナップショット名のみで現存ラベルが消えている場合は deleted=true。
     */
    private TodoHandoffResponse.LabelInfo buildLabelInfo(Long labelId, String snapshotName,
                                                          TodoStatusLabelEntity current) {
        if (labelId == null && snapshotName == null) {
            return null;
        }
        if (current != null) {
            return new TodoHandoffResponse.LabelInfo(
                    current.getId(), current.getName(), current.getBucket().name(),
                    current.getColor(), false);
        }
        // 現存しない（削除済み）→ スナップショットで返す
        return new TodoHandoffResponse.LabelInfo(labelId, snapshotName, null, null, true);
    }

    private TodoStatus bucketToStatus(TodoStatusBucket bucket) {
        if (bucket == null) {
            // F02.3.1 後続 C-2: 想定外の値（NULL 含む）は fail-fast で気付ける形にする
            throw new IllegalStateException("bucket は必須です");
        }
        return switch (bucket) {
            case OPEN -> TodoStatus.OPEN;
            case IN_PROGRESS -> TodoStatus.IN_PROGRESS;
            case COMPLETED -> TodoStatus.COMPLETED;
            // F02.3.1 後続 C-2: 静かな OPEN フォールバックを排除し、enum 拡張時に必ず気付く
            // ようにコンパイラの網羅性チェックに頼る（switch expression なので default 不要）。
        };
    }

    private String mapScopeTypeKey(TodoScopeType scopeType) {
        return switch (scopeType) {
            case TEAM -> "TEAM";
            case ORGANIZATION -> "ORGANIZATION";
            default -> "PERSONAL"; // ここには来ない（PERSONAL は事前に弾く）
        };
    }

    /**
     * Long のリストを JSON 配列文字列に変換する。
     *
     * <p>F02.3.1 後続 C-1: 旧実装は {@code catch (Exception)} で空配列にフォールバックしていたが、
     * {@code List<Long>} の serialize は通常失敗しないため、失敗したら本当の異常事態。
     * {@link JsonProcessingException} のみを catch し、{@link IllegalStateException} で fail-fast する。</p>
     */
    private String toJsonArray(List<Long> ids) {
        try {
            return JSON.writeValueAsString(ids == null ? List.of() : ids);
        } catch (JsonProcessingException e) {
            log.error("List<Long> の JSON serialize に失敗: ids={}", ids, e);
            throw new IllegalStateException("List<Long> の JSON serialize に失敗しました", e);
        }
    }

    /**
     * JSON 配列文字列を Long のリストにパースする。
     *
     * <p>履歴行から旧 assignees / 新 assignees を復元する際に使用。スナップショットなので
     * 不正な JSON が保存されている可能性は本来ゼロだが、万一壊れていても履歴表示が落ちないよう
     * 空リストにフォールバックし WARN ログを残す（読み取り側はベストエフォート）。</p>
     */
    private List<Long> parseJsonArray(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return JSON.readValue(json, new TypeReference<List<Long>>() {});
        } catch (JsonProcessingException e) {
            log.warn("handoff スナップショット JSON のパースに失敗: json={}, cause={}", json, e.toString());
            return List.of();
        }
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * 監査ログ用 metadata 文字列を生成する。
     *
     * <p>F02.3.1 後続 C-1: 旧実装は {@code catch (Exception)} で {@code "{}"} を返していたが、
     * 監査ログが空オブジェクトで記録されると後追い不能になり監査の意義が失われる。
     * {@link JsonProcessingException} のみを catch し、{@link IllegalStateException} で fail-fast する。</p>
     */
    private String buildAuditMetadata(Long todoId, Collection<Long> fromIds, Collection<Long> toIds,
                                       TodoStatus previousStatus, TodoStatus newStatus,
                                       TodoStatusLabelEntity label) {
        try {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("todoId", todoId);
            map.put("fromAssignees", fromIds);
            map.put("toAssignees", toIds);
            map.put("previousStatus", previousStatus.name());
            map.put("newStatus", newStatus.name());
            map.put("statusLabelId", label.getId());
            map.put("statusLabelName", label.getName());
            return JSON.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            log.error("監査メタデータの JSON serialize に失敗: todoId={}", todoId, e);
            throw new IllegalStateException("監査メタデータの JSON serialize に失敗しました", e);
        }
    }
}
