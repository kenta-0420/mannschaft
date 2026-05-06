package com.mannschaft.app.todo.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.todo.TodoErrorCode;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.TodoStatusBucket;
import com.mannschaft.app.todo.TodoStatusLabelScope;
import com.mannschaft.app.todo.dto.CreateTodoStatusLabelRequest;
import com.mannschaft.app.todo.dto.TodoStatusLabelResponse;
import com.mannschaft.app.todo.dto.UpdateTodoStatusLabelRequest;
import com.mannschaft.app.todo.entity.TodoStatusLabelEntity;
import com.mannschaft.app.todo.repository.TodoStatusLabelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * TODO カスタムステータスラベルサービス（F02.3.1 Phase 1a）。
 *
 * <p>SYSTEM 既定ラベル + 個人/チーム/組織スコープのラベルを管理する。
 * SYSTEM ラベルは不変、個人スコープは本人のみ、チーム・組織スコープは ADMIN/DEPUTY_ADMIN のみ
 * 編集可能。1スコープあたり最大 20 件。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TodoStatusLabelService {

    /** スコープあたりのラベル数上限（SYSTEM は除外）。 */
    public static final int MAX_LABELS_PER_SCOPE = 20;

    private final TodoStatusLabelRepository labelRepository;
    private final AccessControlService accessControlService;
    private final AuditLogService auditLogService;

    /**
     * SYSTEM 既定ラベル + 当該スコープのアクティブラベル一覧を sort_order 順で取得する。
     *
     * @param scopeType スコープ種別（SYSTEM 単独取得は不可。SYSTEM 既定は常に先頭に含まれる）
     * @param scopeId   スコープ ID（PERSONAL/TEAM/ORGANIZATION のとき必須、SYSTEM のとき NULL）
     * @param actorId   操作ユーザー ID（権限判定用）
     * @return ラベル一覧
     */
    public List<TodoStatusLabelResponse> list(TodoStatusLabelScope scopeType, Long scopeId, Long actorId) {
        validateScopeAccess(scopeType, scopeId, actorId, false);

        List<TodoStatusLabelEntity> result = new ArrayList<>(labelRepository.findAllSystemDefaults());
        if (scopeType != TodoStatusLabelScope.SYSTEM) {
            result.addAll(labelRepository.findActiveByScope(scopeType, scopeId));
        }
        return result.stream().map(this::toResponse).toList();
    }

    /**
     * カスタムラベルを新規作成する。
     *
     * @param scopeType スコープ種別（SYSTEM 不可）
     * @param scopeId   スコープ ID
     * @param request   作成リクエスト
     * @param actorId   操作ユーザー ID
     * @return 作成されたラベル
     */
    @Transactional
    public TodoStatusLabelResponse create(TodoStatusLabelScope scopeType, Long scopeId,
                                          CreateTodoStatusLabelRequest request, Long actorId) {
        if (scopeType == TodoStatusLabelScope.SYSTEM) {
            throw new BusinessException(TodoErrorCode.SYSTEM_LABEL_IMMUTABLE);
        }
        validateScopeAccess(scopeType, scopeId, actorId, true);

        // 同名重複チェック
        if (labelRepository.existsActiveByScopeAndName(scopeType, scopeId, request.getName())) {
            throw new BusinessException(TodoErrorCode.LABEL_NAME_DUPLICATE);
        }

        // 上限チェック（SYSTEM は除外）
        long count = labelRepository.countActiveByScope(scopeType, scopeId);
        if (count >= MAX_LABELS_PER_SCOPE) {
            throw new BusinessException(TodoErrorCode.LABEL_LIMIT_EXCEEDED);
        }

        TodoStatusBucket bucket = parseBucket(request.getBucket());

        TodoStatusLabelEntity entity = TodoStatusLabelEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .name(request.getName())
                .bucket(bucket)
                .color(request.getColor())
                .sortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0)
                .isSystemDefault(false)
                .createdBy(actorId)
                .build();

        entity = labelRepository.save(entity);

        log.info("TODOステータスラベル作成: id={}, scope={}:{}, name={}",
                entity.getId(), scopeType, scopeId, entity.getName());
        recordAuditLog("TODO_STATUS_LABEL_CREATED", scopeType, scopeId, actorId, entity);

        return toResponse(entity);
    }

    /**
     * カスタムラベルを更新する。
     *
     * @param labelId ラベル ID
     * @param request 更新リクエスト
     * @param actorId 操作ユーザー ID
     * @return 更新後のラベル
     */
    @Transactional
    public TodoStatusLabelResponse update(Long labelId, UpdateTodoStatusLabelRequest request, Long actorId) {
        TodoStatusLabelEntity entity = labelRepository.findActiveById(labelId)
                .orElseThrow(() -> new BusinessException(TodoErrorCode.STATUS_LABEL_NOT_FOUND));

        if (entity.isSystemDefault()) {
            throw new BusinessException(TodoErrorCode.SYSTEM_LABEL_IMMUTABLE);
        }

        validateScopeAccess(entity.getScopeType(), entity.getScopeId(), actorId, true);

        // 名前変更時は重複チェック
        if (request.getName() != null && !request.getName().equals(entity.getName())) {
            if (labelRepository.existsActiveByScopeAndNameExcludingId(
                    entity.getScopeType(), entity.getScopeId(), request.getName(), labelId)) {
                throw new BusinessException(TodoErrorCode.LABEL_NAME_DUPLICATE);
            }
            entity.rename(request.getName());
        }

        if (request.getColor() != null) {
            entity.recolor(request.getColor());
        }

        if (request.getSortOrder() != null) {
            entity.reorder(request.getSortOrder());
        }

        if (request.getBucket() != null) {
            entity.changeBucket(parseBucket(request.getBucket()));
        }

        entity = labelRepository.save(entity);

        log.info("TODOステータスラベル更新: id={}, scope={}:{}",
                entity.getId(), entity.getScopeType(), entity.getScopeId());
        recordAuditLog("TODO_STATUS_LABEL_UPDATED", entity.getScopeType(), entity.getScopeId(), actorId, entity);

        return toResponse(entity);
    }

    /**
     * カスタムラベルを論理削除する。SYSTEM 既定ラベルは削除不可、使用中ラベルも削除不可。
     *
     * @param labelId ラベル ID
     * @param actorId 操作ユーザー ID
     */
    @Transactional
    public void delete(Long labelId, Long actorId) {
        TodoStatusLabelEntity entity = labelRepository.findActiveById(labelId)
                .orElseThrow(() -> new BusinessException(TodoErrorCode.STATUS_LABEL_NOT_FOUND));

        if (entity.isSystemDefault()) {
            throw new BusinessException(TodoErrorCode.SYSTEM_LABEL_IMMUTABLE);
        }

        validateScopeAccess(entity.getScopeType(), entity.getScopeId(), actorId, true);

        long inUse = labelRepository.countTodosUsing(labelId);
        if (inUse > 0) {
            throw new BusinessException(TodoErrorCode.LABEL_IN_USE);
        }

        entity.softDelete();
        labelRepository.save(entity);

        log.info("TODOステータスラベル削除: id={}, scope={}:{}",
                entity.getId(), entity.getScopeType(), entity.getScopeId());
        recordAuditLog("TODO_STATUS_LABEL_DELETED", entity.getScopeType(), entity.getScopeId(), actorId, entity);
    }

    /**
     * ラベルが TODO のスコープで使用可能かを検証する。
     * SYSTEM ラベルは全スコープで使用可。
     *
     * @param label     対象ラベル
     * @param scopeType TODO のスコープ種別
     * @param scopeId   TODO のスコープ ID
     * @throws BusinessException スコープ不一致のとき STATUS_LABEL_SCOPE_MISMATCH
     */
    public void validateLabelForScope(TodoStatusLabelEntity label, TodoScopeType scopeType, Long scopeId) {
        if (label.getScopeType() == TodoStatusLabelScope.SYSTEM) {
            return;
        }
        TodoStatusLabelScope expectedScope = mapTodoScope(scopeType);
        if (label.getScopeType() != expectedScope || !java.util.Objects.equals(label.getScopeId(), scopeId)) {
            throw new BusinessException(TodoErrorCode.STATUS_LABEL_SCOPE_MISMATCH);
        }
    }

    /**
     * ID でアクティブラベルを取得する（TodoService から呼び出される公開メソッド）。
     */
    public TodoStatusLabelEntity findActiveById(Long id) {
        return labelRepository.findActiveById(id)
                .orElseThrow(() -> new BusinessException(TodoErrorCode.STATUS_LABEL_NOT_FOUND));
    }

    /**
     * 複数 ID のラベルを一括取得する（一覧 API の N+1 対策で TodoService から使用）。
     * 削除済みラベルは含まれない。
     */
    public List<TodoStatusLabelEntity> findActiveByIds(java.util.Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return labelRepository.findAllById(ids).stream()
                .filter(l -> l.getDeletedAt() == null)
                .toList();
    }

    // ─────────────────────────────────────────────
    // 内部ヘルパー
    // ─────────────────────────────────────────────

    /**
     * スコープへのアクセス権を検証する。
     *
     * @param scopeType   スコープ種別
     * @param scopeId     スコープ ID
     * @param actorId     操作ユーザー ID
     * @param adminOnly   true の場合は CRUD 権限（個人=本人 / チーム・組織=ADMIN/DEPUTY_ADMIN）。
     *                    false の場合は閲覧権限（個人=本人 / チーム・組織=メンバー以上は許容しない、
     *                    ここでは ADMIN チェック相当 — メンバーシップは AccessControlService で別途判定）
     */
    private void validateScopeAccess(TodoStatusLabelScope scopeType, Long scopeId, Long actorId, boolean adminOnly) {
        if (actorId == null) {
            throw new BusinessException(CommonErrorCode.COMMON_000);
        }
        switch (scopeType) {
            case SYSTEM:
                // SYSTEM は閲覧自由、書き込みはこのメソッドに到達する前に弾く
                return;
            case PERSONAL:
                if (!actorId.equals(scopeId)) {
                    throw new BusinessException(CommonErrorCode.COMMON_002);
                }
                return;
            case TEAM:
                if (adminOnly) {
                    accessControlService.checkAdminOrAbove(actorId, scopeId, "TEAM");
                }
                return;
            case ORGANIZATION:
                if (adminOnly) {
                    accessControlService.checkAdminOrAbove(actorId, scopeId, "ORGANIZATION");
                }
                return;
            default:
                throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    private TodoStatusBucket parseBucket(String value) {
        try {
            return TodoStatusBucket.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(TodoErrorCode.STATUS_LABEL_BUCKET_MISMATCH);
        }
    }

    private TodoStatusLabelScope mapTodoScope(TodoScopeType type) {
        switch (type) {
            case PERSONAL:
                return TodoStatusLabelScope.PERSONAL;
            case TEAM:
                return TodoStatusLabelScope.TEAM;
            case ORGANIZATION:
                return TodoStatusLabelScope.ORGANIZATION;
            default:
                throw new BusinessException(TodoErrorCode.STATUS_LABEL_SCOPE_MISMATCH);
        }
    }

    private TodoStatusLabelResponse toResponse(TodoStatusLabelEntity entity) {
        return new TodoStatusLabelResponse(
                entity.getId(),
                entity.getScopeType().name(),
                entity.getScopeId(),
                entity.getName(),
                entity.getBucket().name(),
                entity.getColor(),
                entity.getSortOrder(),
                entity.isSystemDefault(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private void recordAuditLog(String eventType, TodoStatusLabelScope scopeType, Long scopeId,
                                Long actorId, TodoStatusLabelEntity entity) {
        Long teamId = scopeType == TodoStatusLabelScope.TEAM ? scopeId : null;
        Long orgId = scopeType == TodoStatusLabelScope.ORGANIZATION ? scopeId : null;
        String metadata = String.format(
                "{\"labelId\":%d,\"scopeType\":\"%s\",\"scopeId\":%s,\"name\":\"%s\",\"bucket\":\"%s\"}",
                entity.getId(), entity.getScopeType().name(),
                entity.getScopeId() == null ? "null" : entity.getScopeId().toString(),
                escapeJson(entity.getName()), entity.getBucket().name()
        );
        auditLogService.record(eventType, actorId, null, teamId, orgId, null, null, null, metadata);
    }

    private String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
