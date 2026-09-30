package com.mannschaft.app.member.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.dashboard.MinRole;
import com.mannschaft.app.dashboard.ScopeType;
import com.mannschaft.app.member.MemberSubtabDefaultMinRoleMap;
import com.mannschaft.app.member.MemberSubtabKey;
import com.mannschaft.app.member.dto.MemberSubtabUpdatedByDto;
import com.mannschaft.app.member.dto.MemberSubtabVisibilityItemDto;
import com.mannschaft.app.member.dto.MemberSubtabVisibilityResponse;
import com.mannschaft.app.member.dto.UpdateMemberSubtabVisibilityRequest;
import com.mannschaft.app.member.entity.MemberSubtabRoleVisibilityEntity;
import com.mannschaft.app.member.event.MemberSubtabVisibilityUpdatedEvent;
import com.mannschaft.app.member.repository.MemberSubtabRoleVisibilityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
 * <p><b>TX 境界（PR #3387 D-3T 根治）</b>: 本クラスは TX を持たない段取り役である。権限確認
 * （{@link AccessControlService}）と表示名の解決（{@link NameResolverService}）は他ドメインの読み取りなので、
 * member の TX の外で行う。設定の書き込みだけを {@link MemberSubtabVisibilityWriter#applyUpdates} の TX に
 * 閉じ込め、監査はそこから発行するイベントで AFTER_COMMIT に記録する。クラス単位の
 * {@code @Transactional} を付けてはならない（付けると D-3T が再び赤になる）。
 * 閲覧で生じる短い隙の扱いは設計書「TX 境界とレース」節を参照。</p>
 *
 * <p>設計書: docs/features/F06.6_member_subtab_visibility.md §4, §5, §7</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberSubtabVisibilityService {

    /** 監査ログのイベント種別（{@link MemberSubtabVisibilityUpdatedEvent#AUDIT_EVENT_TYPE} と同じ値） */
    public static final String AUDIT_EVENT_TYPE = MemberSubtabVisibilityUpdatedEvent.AUDIT_EVENT_TYPE;

    /** サブタブ可視性管理パーミッション名 */
    public static final String PERMISSION_NAME = "MEMBER_SUBTAB_VISIBILITY_MANAGE";

    private final MemberSubtabRoleVisibilityRepository repository;
    private final AccessControlService accessControlService;
    private final NameResolverService nameResolverService;
    private final MemberSubtabVisibilityWriter writer;

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
        MemberSubtabVisibilitySnapshot snapshot =
                MemberSubtabVisibilitySnapshot.of(repository.findByScopeTypeAndScopeId(scopeType, scopeId));
        return buildResponse(scopeType, scopeId, snapshot, resolveUpdaterNames(snapshot, null));
    }

    // ─────────────────────────────────────────────
    // PUT: 一括更新
    // ─────────────────────────────────────────────

    /**
     * 一括更新。順序は「引数検証 → 権限確認（TX 外）→ 表示名の先読み（TX 外・書き込み前）→
     * writer の TX で書き込み → メモリ上で応答を組み立て」。
     *
     * <p>表示名は書き込みの<b>前</b>に引く。名前解決が失敗すれば何も保存していないので「5xx・変更なし」になり、
     * コミット後に失敗しうる I/O を置かない（「保存済みなのに 5xx」を起こさない）。先読みから書き込みまでの間に
     * 別の管理者が更新して新しい更新者が現れた場合、その表示名は {@code null} になる（名前を解決できない
     * 利用者と同じ扱い）。</p>
     */
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

        // 表示名の先読み（TX 外・書き込み前）: 既存の更新者＋今回の更新者（書き込み後は自分が更新者になる）
        MemberSubtabVisibilitySnapshot before =
                MemberSubtabVisibilitySnapshot.of(repository.findByScopeTypeAndScopeId(scopeType, scopeId));
        Map<Long, String> displayNames = resolveUpdaterNames(before, currentUserId);

        // 書き込み（member の TX はここだけ。途中の 422 等で丸ごと取り消される）
        MemberSubtabVisibilitySnapshot after =
                writer.applyUpdates(scopeType, scopeId, currentUserId, request.getSubtabs());

        // コミット後は応答用の DB アクセスをしない（メモリ上だけで組み立てる）
        return buildResponse(scopeType, scopeId, after, displayNames);
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

    /**
     * スナップショット中の更新者（＋ {@code extraUserId}）の表示名を1回で解決する。対象が無ければ呼ばない。
     */
    private Map<Long, String> resolveUpdaterNames(MemberSubtabVisibilitySnapshot snapshot, Long extraUserId) {
        Set<Long> updaterIds = new HashSet<>();
        for (MemberSubtabVisibilitySnapshot.Row row : snapshot.rows()) {
            if (row.updatedBy() != null) {
                updaterIds.add(row.updatedBy());
            }
        }
        if (extraUserId != null) {
            updaterIds.add(extraUserId);
        }
        return updaterIds.isEmpty() ? Map.of() : nameResolverService.resolveUserDisplayNames(updaterIds);
    }

    private static MemberSubtabVisibilityResponse buildResponse(ScopeType scopeType, Long scopeId,
                                                                MemberSubtabVisibilitySnapshot snapshot,
                                                                Map<Long, String> displayNames) {
        Map<String, MemberSubtabVisibilitySnapshot.Row> dbMap = new HashMap<>();
        for (MemberSubtabVisibilitySnapshot.Row row : snapshot.rows()) {
            dbMap.put(row.subtabKey(), row);
        }

        List<MemberSubtabVisibilityItemDto> subtabs = new ArrayList<>();
        for (Map.Entry<MemberSubtabKey, MinRole> entry : MemberSubtabDefaultMinRoleMap.getDefaults().entrySet()) {
            MemberSubtabKey key = entry.getKey();
            MemberSubtabVisibilitySnapshot.Row dbRow = dbMap.get(key.getDbValue());
            if (dbRow != null) {
                MemberSubtabUpdatedByDto updatedBy = MemberSubtabUpdatedByDto.builder()
                        .id(dbRow.updatedBy())
                        .displayName(displayNames.getOrDefault(dbRow.updatedBy(), null))
                        .build();
                subtabs.add(MemberSubtabVisibilityItemDto.builder()
                        .subtabKey(key.getDbValue())
                        .minRole(dbRow.minRole())
                        .isDefault(false)
                        .updatedBy(updatedBy)
                        .updatedAt(dbRow.updatedAt())
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
}
