package com.mannschaft.app.member.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.dashboard.MinRole;
import com.mannschaft.app.dashboard.ScopeType;
import com.mannschaft.app.member.MemberErrorCode;
import com.mannschaft.app.member.MemberSubtabDefaultMinRoleMap;
import com.mannschaft.app.member.MemberSubtabKey;
import com.mannschaft.app.member.dto.MemberSubtabUpdatedByDto;
import com.mannschaft.app.member.dto.MemberSubtabVisibilityItemDto;
import com.mannschaft.app.member.dto.MemberSubtabVisibilityResponse;
import com.mannschaft.app.member.dto.UpdateMemberSubtabVisibilityRequest;
import com.mannschaft.app.member.entity.MemberSubtabRoleVisibilityEntity;
import com.mannschaft.app.member.repository.MemberSubtabRoleVisibilityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * CMP-260919-1140 Phase 1: メンバー統合画面サブタブ可視性設定の管理サービス。
 *
 * <p>主な責務:</p>
 * <ul>
 *   <li>{@link #getSettings} 設定一覧の取得（GET）</li>
 *   <li>{@link #updateSettings} 一括更新（PUT）— 差分処理 + audit log。一覧タブへの PUBLIC 設定は拒否（422）</li>
 *   <li>{@link #resolveMinRole} / {@link #assertViewable} — 閲覧系 API（名簿・紹介）からの「外側の門」ゲート</li>
 * </ul>
 *
 * <p>認可は {@link com.mannschaft.app.dashboard.service.DashboardWidgetVisibilityService}
 * と同じ多層防御方針:</p>
 * <ol>
 *   <li>参照系（{@code getSettings}）は非メンバーでも 403 とせず、アプリ定義のデフォルト値を返す</li>
 *   <li>更新系（{@code updateSettings}）は {@link AccessControlService#checkMembership} を必須化し、
 *       さらに ADMIN または {@code MEMBER_SUBTAB_VISIBILITY_MANAGE} パーミッション保有を要求</li>
 * </ol>
 *
 * <p>設計書: docs/features/F06.6_member_subtab_visibility.md §4, §5, §7</p>
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class MemberSubtabVisibilityService {

    /** 監査ログのイベント種別 */
    public static final String AUDIT_EVENT_TYPE = "MEMBER_SUBTAB_VISIBILITY_UPDATED";

    /** サブタブ可視性管理パーミッション名 */
    public static final String PERMISSION_NAME = "MEMBER_SUBTAB_VISIBILITY_MANAGE";

    private final MemberSubtabRoleVisibilityRepository repository;
    private final AccessControlService accessControlService;
    private final AuditLogService auditLogService;
    private final NameResolverService nameResolverService;
    private final ObjectMapper objectMapper;

    // ─────────────────────────────────────────────
    // GET: 設定一覧取得
    // ─────────────────────────────────────────────

    public MemberSubtabVisibilityResponse getSettings(Long currentUserId, ScopeType scopeType, Long scopeId) {
        validateArgs(scopeType, scopeId);

        // 検分指摘B（2巡目・P2）: isMember() は所属の有無（SUPPORTER も true）を見るだけで、
        // F06.6 §9.1 の認可表（SUPPORTER への応答は既定値、実設定は MEMBER 以上）と食い違う。
        // hasRoleOrAbove(...,"MEMBER") でロール閾値判定に揃える。
        if (currentUserId == null
                || !accessControlService.hasRoleOrAbove(currentUserId, scopeId, scopeType.name(), "MEMBER")) {
            return buildDefaultResponse(scopeType, scopeId);
        }
        return buildResponse(scopeType, scopeId);
    }

    // ─────────────────────────────────────────────
    // PUT: 一括更新
    // ─────────────────────────────────────────────

    @Transactional
    public MemberSubtabVisibilityResponse updateSettings(Long currentUserId,
                                                           ScopeType scopeType,
                                                           Long scopeId,
                                                           UpdateMemberSubtabVisibilityRequest request) {
        validateArgs(scopeType, scopeId);
        if (currentUserId == null) {
            throw new IllegalArgumentException("currentUserId must not be null");
        }
        if (request == null || request.getSubtabs() == null || request.getSubtabs().isEmpty()) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }

        // 認可: SYSTEM_ADMIN は所属の有無を問わず無条件で許可（03_role_authority_model.md §SYSTEM_ADMIN の扱い）。
        // 検分指摘（4巡目・P2）: 所属のない SYSTEM_ADMIN が checkMembership で 403 になり、
        // checkUpdatePermission 内の SYSTEM_ADMIN バイパスまで到達できていなかった。
        // 所属チェックより前段で短絡させる。
        if (!accessControlService.isSystemAdmin(currentUserId)) {
            // 認可: スコープ所属＋更新権限（Service 層入口二重防御）
            accessControlService.checkMembership(currentUserId, scopeId, scopeType.name());
            checkUpdatePermission(currentUserId, scopeId, scopeType.name());
        }

        List<Map<String, Object>> changes = new ArrayList<>();
        for (UpdateMemberSubtabVisibilityRequest.SubtabVisibilityUpdateItem update : request.getSubtabs()) {
            applyOneUpdate(scopeType, scopeId, currentUserId, update, changes);
        }

        if (!changes.isEmpty()) {
            recordAuditLog(scopeType, scopeId, currentUserId, changes);
        }

        return buildResponse(scopeType, scopeId);
    }

    // ─────────────────────────────────────────────
    // 外側の門: 閲覧系 API からのゲート
    // ─────────────────────────────────────────────

    /**
     * 指定サブタブの現在の最低必要ロール（DB 設定 or デフォルト）を解決する。
     */
    public MinRole resolveMinRole(ScopeType scopeType, Long scopeId, MemberSubtabKey subtabKey) {
        return repository.findByScopeTypeAndScopeIdAndSubtabKey(scopeType, scopeId, subtabKey.getDbValue())
                .map(MemberSubtabRoleVisibilityEntity::getMinRole)
                .orElseGet(() -> MemberSubtabDefaultMinRoleMap.getDefault(subtabKey));
    }

    /**
     * 閲覧者が指定サブタブの「外側の門」を通過できるかを検証する。通過できなければ
     * {@link BusinessException}（{@link CommonErrorCode#COMMON_002}、403）をスローする。
     *
     * <p>SYSTEM_ADMIN・スコープの ADMIN/DEPUTY_ADMIN は無条件で通過する（管理者バイパス）。</p>
     *
     * @param viewerUserId 閲覧者ユーザーID（未認証なら {@code null}）
     */
    public void assertViewable(Long viewerUserId, ScopeType scopeType, Long scopeId, MemberSubtabKey subtabKey) {
        if (viewerUserId != null) {
            if (accessControlService.isSystemAdmin(viewerUserId)) {
                return;
            }
            if (accessControlService.isAdminOrAbove(viewerUserId, scopeId, scopeType.name())) {
                return;
            }
        }

        MinRole minRole = resolveMinRole(scopeType, scopeId, subtabKey);
        MinRole viewerRole = resolveViewerMinRole(viewerUserId, scopeId, scopeType);
        if (viewerRole.getLevel() < minRole.getLevel()) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    /**
     * 閲覧者の当該スコープでの {@link MinRole} を解決する。
     * ロールなし／GUEST／未認証は {@link MinRole#PUBLIC} として扱う。
     */
    private MinRole resolveViewerMinRole(Long userId, Long scopeId, ScopeType scopeType) {
        if (userId == null) {
            return MinRole.PUBLIC;
        }
        String roleName = accessControlService.getRoleName(userId, scopeId, scopeType.name());
        if (roleName == null) {
            return MinRole.PUBLIC;
        }
        return switch (roleName) {
            case "MEMBER" -> MinRole.MEMBER;
            case "SUPPORTER" -> MinRole.SUPPORTER;
            // ADMIN/DEPUTY_ADMIN/SYSTEM_ADMIN は assertViewable 側で先にバイパス済みだが、
            // 直接 resolveViewerMinRole を呼ぶ利用者向けに安全側（最強）で扱う
            case "ADMIN", "DEPUTY_ADMIN", "SYSTEM_ADMIN" -> MinRole.MEMBER;
            default -> MinRole.PUBLIC; // GUEST 等
        };
    }

    // ─────────────────────────────────────────────
    // レスポンス組み立て
    // ─────────────────────────────────────────────

    private MemberSubtabVisibilityResponse buildDefaultResponse(ScopeType scopeType, Long scopeId) {
        List<MemberSubtabVisibilityItemDto> subtabs = new ArrayList<>();
        for (Map.Entry<MemberSubtabKey, MinRole> entry : MemberSubtabDefaultMinRoleMap.getDefaults().entrySet()) {
            subtabs.add(MemberSubtabVisibilityItemDto.builder()
                    .subtabKey(entry.getKey().getDbValue())
                    .minRole(entry.getValue())
                    .isDefault(true)
                    .build());
        }
        return MemberSubtabVisibilityResponse.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .subtabs(subtabs)
                .build();
    }

    private MemberSubtabVisibilityResponse buildResponse(ScopeType scopeType, Long scopeId) {
        List<MemberSubtabRoleVisibilityEntity> entities = repository.findByScopeTypeAndScopeId(scopeType, scopeId);
        Map<String, MemberSubtabRoleVisibilityEntity> dbMap = new HashMap<>();
        Set<Long> updaterIds = new HashSet<>();
        for (MemberSubtabRoleVisibilityEntity e : entities) {
            dbMap.put(e.getSubtabKey(), e);
            if (e.getUpdatedBy() != null) {
                updaterIds.add(e.getUpdatedBy());
            }
        }

        Map<Long, String> displayNames = updaterIds.isEmpty()
                ? Map.of()
                : nameResolverService.resolveUserDisplayNames(updaterIds);

        List<MemberSubtabVisibilityItemDto> subtabs = new ArrayList<>();
        for (Map.Entry<MemberSubtabKey, MinRole> entry : MemberSubtabDefaultMinRoleMap.getDefaults().entrySet()) {
            MemberSubtabKey key = entry.getKey();
            MemberSubtabRoleVisibilityEntity dbEntity = dbMap.get(key.getDbValue());
            if (dbEntity != null) {
                MemberSubtabUpdatedByDto updatedBy = MemberSubtabUpdatedByDto.builder()
                        .id(dbEntity.getUpdatedBy())
                        .displayName(displayNames.getOrDefault(dbEntity.getUpdatedBy(), null))
                        .build();
                subtabs.add(MemberSubtabVisibilityItemDto.builder()
                        .subtabKey(key.getDbValue())
                        .minRole(dbEntity.getMinRole())
                        .isDefault(false)
                        .updatedBy(updatedBy)
                        .updatedAt(dbEntity.getUpdatedAt())
                        .build());
            } else {
                subtabs.add(MemberSubtabVisibilityItemDto.builder()
                        .subtabKey(key.getDbValue())
                        .minRole(entry.getValue())
                        .isDefault(true)
                        .build());
            }
        }

        return MemberSubtabVisibilityResponse.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .subtabs(subtabs)
                .build();
    }

    // ─────────────────────────────────────────────
    // 個別更新ロジック
    // ─────────────────────────────────────────────

    private void applyOneUpdate(ScopeType scope, Long scopeId, Long currentUserId,
                                 UpdateMemberSubtabVisibilityRequest.SubtabVisibilityUpdateItem update,
                                 List<Map<String, Object>> changes) {
        if (update == null || update.getMinRole() == null) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }

        MemberSubtabKey key = parseSubtabKey(update.getSubtabKey());
        MinRole newMinRole = update.getMinRole();

        // 一覧タブに PUBLIC を設定しようとしたら 422 で拒否（氏名・役割等を含むため）
        if (newMinRole == MinRole.PUBLIC && !MemberSubtabDefaultMinRoleMap.isPublicAllowed(key)) {
            log.warn("MemberSubtabVisibilityService: 一覧タブへの PUBLIC 設定は拒否 "
                    + "(scopeType={}, scopeId={}, userId={})", scope, scopeId, currentUserId);
            throw new BusinessException(MemberErrorCode.MEMBER_LIST_PUBLIC_NOT_ALLOWED);
        }

        MinRole defaultMinRole = MemberSubtabDefaultMinRoleMap.getDefault(key);

        Optional<MemberSubtabRoleVisibilityEntity> existing =
                repository.findByScopeTypeAndScopeIdAndSubtabKey(scope, scopeId, key.getDbValue());

        MinRole beforeMinRole = existing.map(MemberSubtabRoleVisibilityEntity::getMinRole).orElse(defaultMinRole);

        if (newMinRole == defaultMinRole) {
            if (existing.isPresent()) {
                repository.deleteByScopeTypeAndScopeIdAndSubtabKey(scope, scopeId, key.getDbValue());
                addChangeIfDifferent(changes, key, beforeMinRole, newMinRole);
            }
            return;
        }

        if (existing.isPresent()) {
            MemberSubtabRoleVisibilityEntity entity = existing.get();
            if (entity.getMinRole() != newMinRole) {
                entity.changeMinRole(newMinRole, currentUserId);
                repository.save(entity);
                addChangeIfDifferent(changes, key, beforeMinRole, newMinRole);
            }
        } else {
            MemberSubtabRoleVisibilityEntity entity = MemberSubtabRoleVisibilityEntity.builder()
                    .scopeType(scope)
                    .scopeId(scopeId)
                    .subtabKey(key.getDbValue())
                    .minRole(newMinRole)
                    .updatedBy(currentUserId)
                    .build();
            repository.save(entity);
            addChangeIfDifferent(changes, key, beforeMinRole, newMinRole);
        }
    }

    private static void addChangeIfDifferent(List<Map<String, Object>> changes, MemberSubtabKey key,
                                               MinRole before, MinRole after) {
        if (before == after) {
            return;
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("subtab_key", key.getDbValue());
        entry.put("before", before.name());
        entry.put("after", after.name());
        changes.add(entry);
    }

    // ─────────────────────────────────────────────
    // 認可ヘルパー
    // ─────────────────────────────────────────────

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

    private static void validateArgs(ScopeType scopeType, Long scopeId) {
        if (scopeType == null) {
            throw new IllegalArgumentException("scopeType must not be null");
        }
        if (scopeId == null) {
            throw new IllegalArgumentException("scopeId must not be null");
        }
    }

    private static MemberSubtabKey parseSubtabKey(String subtabKey) {
        if (subtabKey == null || subtabKey.isBlank()) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }
        try {
            return MemberSubtabKey.fromDbValue(subtabKey);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }
    }

    // ─────────────────────────────────────────────
    // 監査ログ
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
            log.warn("MemberSubtabVisibilityService: 監査ログ metadata の JSON 直列化失敗 "
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
}
