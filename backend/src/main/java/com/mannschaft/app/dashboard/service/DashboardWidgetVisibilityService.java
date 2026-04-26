package com.mannschaft.app.dashboard.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.dashboard.MinRole;
import com.mannschaft.app.dashboard.ScopeType;
import com.mannschaft.app.dashboard.WidgetKey;
import com.mannschaft.app.dashboard.dto.UpdateWidgetVisibilityRequest;
import com.mannschaft.app.dashboard.dto.UserSummaryDto;
import com.mannschaft.app.dashboard.dto.WidgetVisibilityItemDto;
import com.mannschaft.app.dashboard.dto.WidgetVisibilityResponse;
import com.mannschaft.app.dashboard.entity.DashboardWidgetRoleVisibilityEntity;
import com.mannschaft.app.dashboard.repository.DashboardWidgetRoleVisibilityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * F02.2.1: ダッシュボードウィジェット可視性設定の管理サービス。
 *
 * <p>主な責務:</p>
 * <ul>
 *   <li>{@link #getSettings} 設定一覧の取得（GET /widget-visibility）</li>
 *   <li>{@link #updateSettings} 一括更新（PUT /widget-visibility）— 差分処理 + audit log + キャッシュ無効化</li>
 * </ul>
 *
 * <p>認可は Service 入口で多層防御として実施する:</p>
 * <ol>
 *   <li>{@link AccessControlService#checkMembership} でスコープ所属検証（GET/PUT 共通）</li>
 *   <li>更新系は ADMIN または {@code DASHBOARD_WIDGET_VISIBILITY_MANAGE} パーミッション保有を要求</li>
 * </ol>
 *
 * <p>設計書: docs/features/F02.2.1_dashboard_widget_role_visibility.md §4, §5, §7</p>
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class DashboardWidgetVisibilityService {

    /** 監査ログのイベント種別 */
    public static final String AUDIT_EVENT_TYPE = "DASHBOARD_WIDGET_VISIBILITY_UPDATED";

    /** ウィジェット可視性管理パーミッション名（V2.042 で permissions テーブルに seed 済み） */
    public static final String PERMISSION_NAME = "DASHBOARD_WIDGET_VISIBILITY_MANAGE";

    /** ウィジェット可視性キャッシュ名（{@link WidgetVisibilityResolver} と一致させる） */
    private static final String VISIBILITY_CACHE_NAME = "dashboard:widget-visibility";

    private final DashboardWidgetRoleVisibilityRepository repository;
    private final AccessControlService accessControlService;
    private final AuditLogService auditLogService;
    private final NameResolverService nameResolverService;
    private final CacheManager cacheManager;
    private final ObjectMapper objectMapper;

    // ─────────────────────────────────────────────
    // GET: 設定一覧取得
    // ─────────────────────────────────────────────

    /**
     * 指定スコープのウィジェット可視性設定一覧を取得する。
     *
     * <p>本機能の管理対象ウィジェット（ADMIN 限定除く）すべてについて、
     * DB 設定がある場合はそれを、ない場合はアプリ層デフォルト値を返す。
     * フロントの設定 UI と1対1で対応する完全な集合を返す。</p>
     */
    public WidgetVisibilityResponse getSettings(Long currentUserId, ScopeType scopeType, Long scopeId) {
        validateArgs(currentUserId, scopeType, scopeId);

        // スコープ所属検証（最低限 MEMBER 以上である必要あり）
        accessControlService.checkMembership(currentUserId, scopeId, scopeType.name());

        if (scopeType == ScopeType.PERSONAL) {
            // 個人ダッシュボードは本機能の対象外
            return WidgetVisibilityResponse.builder()
                    .scopeType(scopeType)
                    .scopeId(scopeId)
                    .widgets(List.of())
                    .build();
        }

        return buildResponse(scopeType, scopeId);
    }

    // ─────────────────────────────────────────────
    // PUT: 一括更新
    // ─────────────────────────────────────────────

    /**
     * 指定スコープのウィジェット可視性設定を一括更新する。
     */
    @Transactional
    public WidgetVisibilityResponse updateSettings(Long currentUserId,
                                                    ScopeType scopeType,
                                                    Long scopeId,
                                                    UpdateWidgetVisibilityRequest request) {
        validateArgs(currentUserId, scopeType, scopeId);
        if (request == null || request.getWidgets() == null) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }

        // 認可: スコープ所属＋更新権限
        accessControlService.checkMembership(currentUserId, scopeId, scopeType.name());
        checkUpdatePermission(currentUserId, scopeId, scopeType.name());

        if (scopeType == ScopeType.PERSONAL) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }

        // バリデーション + 差分処理 + audit log 用の変更リスト構築
        List<Map<String, Object>> changes = new ArrayList<>();
        for (UpdateWidgetVisibilityRequest.WidgetVisibilityUpdateItem update : request.getWidgets()) {
            applyOneUpdate(scopeType, scopeId, currentUserId, update, changes);
        }

        // 監査ログ（fire-and-forget）
        if (!changes.isEmpty()) {
            recordAuditLog(scopeType, scopeId, currentUserId, changes);
        }

        // キャッシュ無効化（同期的・障害時は WARN ログのみで続行）
        evictVisibilityCache(scopeType, scopeId);

        // 更新後の最新状態を返却
        return buildResponse(scopeType, scopeId);
    }

    // ─────────────────────────────────────────────
    // レスポンス組み立て
    // ─────────────────────────────────────────────

    private WidgetVisibilityResponse buildResponse(ScopeType scopeType, Long scopeId) {
        // 1. DB レコードを scopeType + scopeId でまとめて取得しキー別 Map に整理
        List<DashboardWidgetRoleVisibilityEntity> entities =
                repository.findByScopeTypeAndScopeId(scopeType, scopeId);
        Map<String, DashboardWidgetRoleVisibilityEntity> dbMap = new HashMap<>();
        Set<Long> updaterIds = new HashSet<>();
        for (DashboardWidgetRoleVisibilityEntity e : entities) {
            dbMap.put(e.getWidgetKey(), e);
            if (e.getUpdatedBy() != null) {
                updaterIds.add(e.getUpdatedBy());
            }
        }

        // 2. 更新者の表示名を一括解決
        Map<Long, String> displayNames = updaterIds.isEmpty()
                ? Map.of()
                : nameResolverService.resolveUserDisplayNames(updaterIds);

        // 3. 管理対象ウィジェット全てについて Item を生成（DB 設定 or デフォルト）
        Map<WidgetKey, MinRole> defaults = WidgetDefaultMinRoleMap.getDefaultsForScope(scopeType);
        List<WidgetVisibilityItemDto> widgets = new ArrayList<>(defaults.size());
        for (Map.Entry<WidgetKey, MinRole> entry : defaults.entrySet()) {
            WidgetKey key = entry.getKey();
            DashboardWidgetRoleVisibilityEntity dbEntity = dbMap.get(key.name());
            if (dbEntity != null) {
                UserSummaryDto updatedBy = UserSummaryDto.builder()
                        .id(dbEntity.getUpdatedBy())
                        .displayName(displayNames.getOrDefault(dbEntity.getUpdatedBy(), null))
                        .build();
                widgets.add(WidgetVisibilityItemDto.builder()
                        .widgetKey(key.name())
                        .minRole(dbEntity.getMinRole())
                        .isDefault(false)
                        .updatedBy(updatedBy)
                        .updatedAt(dbEntity.getUpdatedAt() != null ? dbEntity.getUpdatedAt().toInstant(ZoneOffset.UTC) : null)
                        .build());
            } else {
                widgets.add(WidgetVisibilityItemDto.builder()
                        .widgetKey(key.name())
                        .minRole(entry.getValue())
                        .isDefault(true)
                        .build());
            }
        }

        return WidgetVisibilityResponse.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .widgets(widgets)
                .build();
    }

    // ─────────────────────────────────────────────
    // 個別更新ロジック
    // ─────────────────────────────────────────────

    /**
     * 1件の更新リクエストを処理する。デフォルト一致なら DELETE / 不一致なら UPSERT。
     */
    private void applyOneUpdate(ScopeType scope, Long scopeId, Long currentUserId,
                                 UpdateWidgetVisibilityRequest.WidgetVisibilityUpdateItem update,
                                 List<Map<String, Object>> changes) {
        if (update == null || update.getMinRole() == null) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }

        // widget_key の妥当性検証
        WidgetKey key = parseWidgetKey(update.getWidgetKey());

        // 管理対象外（ADMIN 限定など）は 400
        if (!WidgetDefaultMinRoleMap.isConfigurable(key)) {
            log.warn("DashboardWidgetVisibilityService: 管理対象外のウィジェットキー '{}' への更新試行 "
                    + "(scopeType={}, scopeId={}, userId={})", key, scope, scopeId, currentUserId);
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }

        // スコープ整合性: TEAM スコープに ORG_* を送る等は弾く
        if (key.getScopeType() != scope) {
            log.warn("DashboardWidgetVisibilityService: スコープ不一致のウィジェットキー '{}' への更新試行 "
                    + "(scopeType={}, scopeId={}, userId={})", key, scope, scopeId, currentUserId);
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }

        MinRole defaultMinRole = WidgetDefaultMinRoleMap.getDefault(key);
        MinRole newMinRole = update.getMinRole();

        Optional<DashboardWidgetRoleVisibilityEntity> existing =
                repository.findByScopeTypeAndScopeIdAndWidgetKey(scope, scopeId, key.name());

        // before 値（audit 用）。既存があればその min_role、なければデフォルト
        MinRole beforeMinRole = existing.map(DashboardWidgetRoleVisibilityEntity::getMinRole)
                .orElse(defaultMinRole);

        if (newMinRole == defaultMinRole) {
            // デフォルト値と一致 → 既存レコードがあれば DELETE
            if (existing.isPresent()) {
                repository.deleteByScopeTypeAndScopeIdAndWidgetKey(scope, scopeId, key.name());
                addChangeIfDifferent(changes, key, beforeMinRole, newMinRole);
            }
            return;
        }

        // デフォルトと不一致 → UPSERT
        if (existing.isPresent()) {
            DashboardWidgetRoleVisibilityEntity entity = existing.get();
            if (entity.getMinRole() != newMinRole) {
                entity.changeMinRole(newMinRole, currentUserId);
                repository.save(entity);
                addChangeIfDifferent(changes, key, beforeMinRole, newMinRole);
            }
        } else {
            DashboardWidgetRoleVisibilityEntity entity = DashboardWidgetRoleVisibilityEntity.builder()
                    .scopeType(scope)
                    .scopeId(scopeId)
                    .widgetKey(key.name())
                    .minRole(newMinRole)
                    .updatedBy(currentUserId)
                    .build();
            repository.save(entity);
            addChangeIfDifferent(changes, key, beforeMinRole, newMinRole);
        }
    }

    private static void addChangeIfDifferent(List<Map<String, Object>> changes, WidgetKey key,
                                             MinRole before, MinRole after) {
        if (before == after) {
            return;
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("widget_key", key.name());
        entry.put("before", before.name());
        entry.put("after", after.name());
        changes.add(entry);
    }

    // ─────────────────────────────────────────────
    // 認可ヘルパー
    // ─────────────────────────────────────────────

    /**
     * 更新系操作の権限を検証する。ADMIN は無条件で可、DEPUTY_ADMIN は
     * {@link #PERMISSION_NAME} を保有していれば可、それ以外は 403。
     */
    private void checkUpdatePermission(Long userId, Long scopeId, String scopeType) {
        if (accessControlService.isAdmin(userId, scopeId, scopeType)) {
            return;
        }
        if (accessControlService.isSystemAdmin(userId)) {
            return;
        }
        accessControlService.checkPermission(userId, scopeId, scopeType, PERMISSION_NAME);
    }

    // ─────────────────────────────────────────────
    // バリデーションヘルパー
    // ─────────────────────────────────────────────

    private static void validateArgs(Long currentUserId, ScopeType scopeType, Long scopeId) {
        if (currentUserId == null) {
            throw new IllegalArgumentException("currentUserId must not be null");
        }
        if (scopeType == null) {
            throw new IllegalArgumentException("scopeType must not be null");
        }
        if (scopeId == null) {
            throw new IllegalArgumentException("scopeId must not be null");
        }
    }

    private static WidgetKey parseWidgetKey(String widgetKey) {
        if (widgetKey == null || widgetKey.isBlank()) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }
        try {
            return WidgetKey.valueOf(widgetKey);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }
    }

    // ─────────────────────────────────────────────
    // 監査ログ・キャッシュヘルパー
    // ─────────────────────────────────────────────

    private void recordAuditLog(ScopeType scope, Long scopeId, Long currentUserId,
                                 List<Map<String, Object>> changes) {
        Long teamId = scope == ScopeType.TEAM ? scopeId : null;
        Long organizationId = scope == ScopeType.ORGANIZATION ? scopeId : null;

        Map<String, Object> metadataMap = new LinkedHashMap<>();
        metadataMap.put("scope_type", scope.name());
        metadataMap.put("scope_id", scopeId);
        metadataMap.put("changes", changes);

        String metadataJson;
        try {
            metadataJson = objectMapper.writeValueAsString(metadataMap);
        } catch (JsonProcessingException ex) {
            log.warn("DashboardWidgetVisibilityService: 監査ログ metadata の JSON 直列化失敗 "
                    + "(userId={})", currentUserId, ex);
            metadataJson = "{}";
        }

        auditLogService.record(
                AUDIT_EVENT_TYPE,
                currentUserId,
                null,
                teamId,
                organizationId,
                null,
                null,
                null,
                metadataJson
        );
    }

    /**
     * ウィジェット可視性キャッシュを無効化する。Valkey 障害時は WARN ログのみで続行する。
     */
    private void evictVisibilityCache(ScopeType scope, Long scopeId) {
        try {
            Cache cache = cacheManager.getCache(VISIBILITY_CACHE_NAME);
            if (cache == null) {
                log.warn("DashboardWidgetVisibilityService: キャッシュ '{}' が未定義（cacheManager={}）",
                        VISIBILITY_CACHE_NAME, cacheManager.getClass().getSimpleName());
                return;
            }
            String key = scope.name() + ":" + scopeId;
            cache.evict(key);
        } catch (Exception ex) {
            log.warn("DashboardWidgetVisibilityService: キャッシュ無効化失敗 "
                    + "(cache={}, scopeType={}, scopeId={})",
                    VISIBILITY_CACHE_NAME, scope, scopeId, ex);
        }
    }
}
