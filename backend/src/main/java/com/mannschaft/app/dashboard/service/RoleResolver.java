package com.mannschaft.app.dashboard.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.dashboard.ViewerRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

/**
 * F02.2.1: ダッシュボード閲覧者ロール解決サービス。
 *
 * <p>F01.2 の {@link AccessControlService#getRoleName} の戻り値を {@link ViewerRole} の6値
 * （SYSTEM_ADMIN / ADMIN / DEPUTY_ADMIN / MEMBER / SUPPORTER / PUBLIC）に正規化する。</p>
 *
 * <p>マッピングルール:</p>
 * <ul>
 *   <li>{@code AccessControlService.isSystemAdmin == true} → {@link ViewerRole#SYSTEM_ADMIN}</li>
 *   <li>{@code "ADMIN"} → {@link ViewerRole#ADMIN}</li>
 *   <li>{@code "DEPUTY_ADMIN"} → {@link ViewerRole#DEPUTY_ADMIN}</li>
 *   <li>{@code "MEMBER"} → {@link ViewerRole#MEMBER}</li>
 *   <li>{@code "SUPPORTER"} → {@link ViewerRole#SUPPORTER}</li>
 *   <li>{@code "GUEST"} または {@code null}（メンバーシップなし）→ {@link ViewerRole#PUBLIC}</li>
 * </ul>
 *
 * <p>結果は Valkey に 60秒キャッシュする。F01.2 のロール変更時は
 * {@link com.mannschaft.app.role.event.MembershipChangedEvent} 経由で
 * {@link MembershipChangedListener} がキャッシュを無効化する。</p>
 *
 * <p>設計書: docs/features/F02.2.1_dashboard_widget_role_visibility.md §5</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoleResolver {

    private final AccessControlService accessControlService;

    /**
     * 閲覧者の有効ロールを解決する。
     *
     * <p>{@link AccessControlService#getRoleName} が String を返すので、
     * 戻り値を {@link ViewerRole} に正規化する。SYSTEM_ADMIN は常に最優先で
     * バイパス判定される（スコープ内ロールが設定されていなくても SYSTEM_ADMIN）。</p>
     *
     * <p>キャッシュ: {@code dashboard:viewer-role} に
     * {@code {userId}:{scopeType}:{scopeId}} 形式のキーで 60秒保持される。</p>
     *
     * @param userId    認証済みユーザーID
     * @param scopeType スコープ種別。{@code "TEAM"} または {@code "ORGANIZATION"}
     * @param scopeId   スコープID
     * @return 閲覧者ロール（必ず非 null）
     */
    @Cacheable(
            value = "dashboard:viewer-role",
            key = "#userId + ':' + #scopeType + ':' + #scopeId"
    )
    public ViewerRole resolveViewerRole(Long userId, String scopeType, Long scopeId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
        if (scopeType == null || scopeType.isBlank()) {
            throw new IllegalArgumentException("scopeType must not be blank");
        }
        if (scopeId == null) {
            throw new IllegalArgumentException("scopeId must not be null");
        }

        // SYSTEM_ADMIN は最優先（スコープ内ロール有無を問わずバイパス）
        if (accessControlService.isSystemAdmin(userId)) {
            return ViewerRole.SYSTEM_ADMIN;
        }

        // AccessControlService.getRoleName(userId, scopeId, scopeType) の引数順に注意
        String roleName = accessControlService.getRoleName(userId, scopeId, scopeType);

        if (roleName == null) {
            // メンバーシップなし → PUBLIC
            return ViewerRole.PUBLIC;
        }

        return switch (roleName.toUpperCase()) {
            case "ADMIN" -> ViewerRole.ADMIN;
            case "DEPUTY_ADMIN" -> ViewerRole.DEPUTY_ADMIN;
            case "MEMBER" -> ViewerRole.MEMBER;
            case "SUPPORTER" -> ViewerRole.SUPPORTER;
            case "GUEST" -> ViewerRole.PUBLIC;
            default -> {
                log.warn("RoleResolver: 未知のロール名 '{}' を PUBLIC に丸める "
                        + "(userId={}, scopeType={}, scopeId={})", roleName, userId, scopeType, scopeId);
                yield ViewerRole.PUBLIC;
            }
        };
    }
}
