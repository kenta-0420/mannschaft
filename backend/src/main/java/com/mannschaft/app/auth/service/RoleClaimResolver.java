package com.mannschaft.app.auth.service;

import com.mannschaft.app.common.AccessControlService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * JWT アクセストークンに載せる roles 配列を、ユーザーの実ロールから組み立てるリゾルバ。
 *
 * <p>認可基盤完全根治 Phase 1（{@code docs/security/03_role_authority_model.md} §3.2）の中核。
 * トークン発行・更新の全 5 経路（ログイン / 2FA / OAuth / WebAuthn / リフレッシュ）は、
 * 従来ハードコードしていた {@code List.of("MEMBER")} を本リゾルバ経由に統一する。
 * 発行ロジックを 1 箇所に集約することで、経路ごとの実装ドリフト・SYSTEM_ADMIN 付け忘れを防ぐ。</p>
 *
 * <p>方式: <strong>roles 配列に文字列 {@code "SYSTEM_ADMIN"} を追加する</strong>（boolean claim 方式は不採用）。
 * これにより {@link com.mannschaft.app.config.JwtAuthenticationFilter} の既存変換
 * （{@code roles → "ROLE_" + role}）にそのまま乗り、{@code SecurityConfig} の
 * {@code hasRole("SYSTEM_ADMIN")} がコード変更なしで機能回復する。</p>
 *
 * <p>per-scope の team/org ADMIN・DEPUTY_ADMIN は JWT に載せない（マルチテナントで破綻するため）。
 * それらは Phase 3 で SpEL ガードによる都度判定とする。本リゾルバが扱うのは
 * グローバル権限である SYSTEM_ADMIN のみ。</p>
 *
 * <p>失効: SYSTEM_ADMIN 判定はトークン発行・リフレッシュ時のみ行う軽量な index クエリ
 * （{@code user_roles} の SYSTEM_ADMIN 行 count）。リフレッシュ時も再判定するため、
 * ロール剥奪は最長 15 分（アクセストークン寿命）で反映される。即時失効が必要な場合は
 * ロール剥奪処理側で {@code AuthTokenService#setUserInvalidationTimestamp} を併用する
 * （設計書 §6）。</p>
 */
@Component
@RequiredArgsConstructor
public class RoleClaimResolver {

    /** 全ユーザーが保持する基底ロール。 */
    static final String ROLE_MEMBER = "MEMBER";

    /** プラットフォーム管理者ロール。グローバル（テナント非依存）。 */
    static final String ROLE_SYSTEM_ADMIN = "SYSTEM_ADMIN";

    private final AccessControlService accessControlService;

    /**
     * 指定ユーザーの JWT roles claim 値を解決する。
     *
     * <p>常に {@code MEMBER} を含み、ユーザーが SYSTEM_ADMIN なら {@code SYSTEM_ADMIN} を追加する。</p>
     *
     * @param userId 対象ユーザー ID
     * @return roles 配列（例: 一般ユーザー {@code ["MEMBER"]} / SYSTEM_ADMIN {@code ["MEMBER", "SYSTEM_ADMIN"]}）
     */
    @Transactional(readOnly = true)
    public List<String> resolveRoles(Long userId) {
        List<String> roles = new ArrayList<>();
        roles.add(ROLE_MEMBER);
        if (accessControlService.isSystemAdmin(userId)) {
            roles.add(ROLE_SYSTEM_ADMIN);
        }
        return roles;
    }
}
