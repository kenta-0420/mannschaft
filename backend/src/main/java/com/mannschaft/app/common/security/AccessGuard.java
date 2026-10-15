package com.mannschaft.app.common.security;

import com.mannschaft.app.common.AccessControlService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * {@code @PreAuthorize} の SpEL からパス変数（scopeId）を参照して per-scope 認可を行うガード。
 *
 * <p>判定本体は {@link AccessControlService} に委譲し、ロジックを一元化する。
 * 既存 {@code AdminRoleChecker} / {@code QuickMemoAccessGuard} の定石に倣い、
 * {@code Authentication} の principal name からユーザー ID をパースする。</p>
 *
 * <p>使用例:
 * <pre>{@code @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")}</pre>
 * </p>
 *
 * <p><strong>設計上の取り決め（設計書 §3.3.1）:</strong></p>
 * <ol>
 *   <li><strong>SYSTEM_ADMIN は常に通す</strong> — 各メソッドは先に
 *       {@link AccessControlService#isSystemAdmin(Long)} を確認し、true なら無条件許可する。</li>
 *   <li><strong>null / 非認証 / パース失敗は false</strong> — DB を参照せず早期に false を返す。</li>
 *   <li><strong>scopeType は文字列リテラル</strong> — SpEL から {@code 'TEAM'} / {@code 'ORGANIZATION'} を渡す。</li>
 *   <li><strong>委譲先の再利用</strong> — 新規 SQL は追加しない。</li>
 *   <li><strong>boolean を返す（例外を投げない）</strong> — SpEL は boolean を期待するため
 *       {@code checkXxx}（void・例外送出）ではなく {@code isXxx}（boolean）系に委譲する。</li>
 * </ol>
 *
 * <p>設計書: docs/security/03_role_authority_model.md §3.3</p>
 */
@Component("accessGuard")
@RequiredArgsConstructor
public class AccessGuard {

    private final AccessControlService accessControlService;

    /**
     * SYSTEM_ADMIN もしくは当該スコープの ADMIN/DEPUTY_ADMIN なら true。
     *
     * @param authentication 現在の認証情報
     * @param scopeId        スコープ ID（チーム ID または組織 ID）
     * @param scopeType      スコープ種別（"TEAM" / "ORGANIZATION"）
     * @return 許可なら true
     */
    public boolean isScopeAdmin(Authentication authentication, Long scopeId, String scopeType) {
        Long userId = resolveUserId(authentication);
        if (userId == null) {
            return false;
        }
        if (accessControlService.isSystemAdmin(userId)) {
            return true;
        }
        return accessControlService.isAdminOrAbove(userId, scopeId, scopeType);
    }

    /**
     * SYSTEM_ADMIN もしくは当該スコープの ADMIN（DEPUTY_ADMIN を除く）なら true。
     *
     * @param authentication 現在の認証情報
     * @param scopeId        スコープ ID
     * @param scopeType      スコープ種別
     * @return 許可なら true
     */
    public boolean isScopeStrictAdmin(Authentication authentication, Long scopeId, String scopeType) {
        Long userId = resolveUserId(authentication);
        if (userId == null) {
            return false;
        }
        if (accessControlService.isSystemAdmin(userId)) {
            return true;
        }
        return accessControlService.isAdmin(userId, scopeId, scopeType);
    }

    /**
     * SYSTEM_ADMIN もしくは当該スコープのメンバー（MEMBER 以上）なら true。
     *
     * @param authentication 現在の認証情報
     * @param scopeId        スコープ ID
     * @param scopeType      スコープ種別
     * @return 許可なら true
     */
    public boolean isScopeMember(Authentication authentication, Long scopeId, String scopeType) {
        Long userId = resolveUserId(authentication);
        if (userId == null) {
            return false;
        }
        if (accessControlService.isSystemAdmin(userId)) {
            return true;
        }
        return accessControlService.isMember(userId, scopeId, scopeType);
    }

    /**
     * SYSTEM_ADMIN もしくは当該スコープで指定 permission を保有するなら true
     *（DEPUTY_ADMIN の細粒度権限用）。
     *
     * @param authentication 現在の認証情報
     * @param scopeId        スコープ ID
     * @param scopeType      スコープ種別
     * @param permission     必要な Permission 名
     * @return 許可なら true
     */
    public boolean hasScopePermission(Authentication authentication, Long scopeId,
                                      String scopeType, String permission) {
        Long userId = resolveUserId(authentication);
        if (userId == null) {
            return false;
        }
        if (accessControlService.isSystemAdmin(userId)) {
            return true;
        }
        return accessControlService.hasPermission(userId, scopeId, scopeType, permission);
    }

    /**
     * Authentication からユーザー ID を解決する。
     * null / 非認証 / 数値パース失敗の場合は {@code null} を返す（呼出側で false 扱い）。
     *
     * @param authentication 認証情報
     * @return ユーザー ID。解決不能なら {@code null}
     */
    private Long resolveUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        try {
            return Long.parseLong(authentication.getName());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
