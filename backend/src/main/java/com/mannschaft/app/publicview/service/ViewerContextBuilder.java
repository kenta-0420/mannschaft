package com.mannschaft.app.publicview.service;

import com.mannschaft.app.publicview.visibility.ViewerContext;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Set;

/**
 * HTTP リクエストの {@link Authentication} から {@link ViewerContext} を組み立てるビルダーサービス。
 *
 * <p>F19.1 §7.6 で定義される「閲覧者コンテキストの構築」を担当する。
 * Spring Security の {@link Authentication} からユーザー ID を取り出し、
 * user_roles テーブルを参照して閲覧者立場（ANONYMOUS / NON_MEMBER / SUPPORTER / MEMBER / SYSTEM_ADMIN）を決定する。</p>
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §7.6 / §4.6.2 / §12</p>
 *
 * <p><strong>クロスドメイン注意（CLAUDE.md 原則5）</strong>: publicview ドメインが
 * role ドメインの {@link UserRoleRepository} / {@link RoleRepository} を直接参照している。
 * 将来のイベント駆動化候補: ViewerContextResolvedEvent を発行し role ドメイン内で処理する方式。</p>
 *
 * <p>SELF ステータス（投稿者本人）の判定は {@link com.mannschaft.app.publicview.visibility.IdentityVisibilityResolver}
 * 側で個別投稿ごとに行うため、本サービスでは MEMBER / SUPPORTER まで解決する。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ViewerContextBuilder {

    // TODO: publicview ドメインが role ドメインの Repository を直接参照。将来はイベント駆動化を検討。
    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;

    /** SYSTEM_ADMIN ロール名（roles テーブルの name 値）。 */
    private static final String ROLE_SYSTEM_ADMIN = "SYSTEM_ADMIN";

    /** MEMBER ロール名（roles テーブルの name 値）。 */
    private static final String ROLE_MEMBER = "MEMBER";

    /** ADMIN ロール名（roles テーブルの name 値）。MEMBER 扱いとする。 */
    private static final String ROLE_ADMIN = "ADMIN";

    /** DEPUTY_ADMIN ロール名（roles テーブルの name 値）。MEMBER 扱いとする。 */
    private static final String ROLE_DEPUTY_ADMIN = "DEPUTY_ADMIN";

    /** SUPPORTER ロール名（roles テーブルの name 値）。 */
    private static final String ROLE_SUPPORTER = "SUPPORTER";

    /**
     * チーム公開ページ用の {@link ViewerContext} を構築する。
     *
     * <p>未ログイン → {@link ViewerContext#anonymous()}<br>
     * SYSTEM_ADMIN → {@link ViewerContext#systemAdmin(Long)}<br>
     * ADMIN / DEPUTY_ADMIN / MEMBER として所属 → {@link ViewerContext#member(Long, Set)}<br>
     * SUPPORTER として所属 → {@link ViewerContext#supporter(Long, Set)}<br>
     * 未所属 → {@link ViewerContext#nonMember(Long)}</p>
     *
     * @param authentication Spring Security の {@link Authentication}（未ログインなら {@code null}）
     * @param teamId         閲覧対象のチーム ID
     * @return 構築済みの {@link ViewerContext}
     */
    public ViewerContext buildForTeam(Authentication authentication, Long teamId) {
        return buildForTeamByUserId(extractUserId(authentication), teamId);
    }

    /**
     * チームスコープ用の {@link ViewerContext} を、解決済みの userId から直接構築する。
     *
     * <p>{@link Authentication} を持たないサービス層（例: F08.7 ランキング名前解決）から
     * 既に {@code SecurityUtils.getCurrentUserIdOrNull()} で取得した userId を渡して再利用するための
     * メソッド。{@code userId == null} は未ログイン扱い。
     * （{@code Authentication} 版とのオーバーロード曖昧化を避けるため別名にしている。）</p>
     *
     * @param userId 閲覧者のユーザー ID（未ログインなら {@code null}）
     * @param teamId 閲覧対象のチーム ID
     * @return 構築済みの {@link ViewerContext}
     */
    public ViewerContext buildForTeamByUserId(Long userId, Long teamId) {
        if (userId == null) {
            return ViewerContext.anonymous();
        }

        // SYSTEM_ADMIN 判定（SQL 1 回）
        if (userRoleRepository.existsSystemAdminByUserId(userId) > 0) {
            return ViewerContext.systemAdmin(userId);
        }

        // チームへの直接所属ロールを取得（SQL 1 回）
        Optional<UserRoleEntity> userRoleOpt = userRoleRepository.findByUserIdAndTeamId(userId, teamId);
        if (userRoleOpt.isEmpty()) {
            return ViewerContext.nonMember(userId);
        }

        String roleName = resolveRoleName(userRoleOpt.get().getRoleId());
        if (roleName == null) {
            log.warn("user_roles に role_id={} が存在しないか roles テーブルと不整合。userId={}, teamId={}",
                    userRoleOpt.get().getRoleId(), userId, teamId);
            return ViewerContext.nonMember(userId);
        }

        return switch (roleName) {
            case ROLE_ADMIN, ROLE_DEPUTY_ADMIN, ROLE_MEMBER -> ViewerContext.member(userId, Set.of(teamId));
            case ROLE_SUPPORTER -> ViewerContext.supporter(userId, Set.of(teamId));
            default -> ViewerContext.nonMember(userId);
        };
    }

    /**
     * 組織公開ページ用の {@link ViewerContext} を構築する。
     *
     * <p>ロジックは {@link #buildForTeam} と同様。組織 ID に対して user_roles を参照する。</p>
     *
     * @param authentication Spring Security の {@link Authentication}（未ログインなら {@code null}）
     * @param organizationId 閲覧対象の組織 ID
     * @return 構築済みの {@link ViewerContext}
     */
    public ViewerContext buildForOrganization(Authentication authentication, Long organizationId) {
        return buildForOrganizationByUserId(extractUserId(authentication), organizationId);
    }

    /**
     * 組織スコープ用の {@link ViewerContext} を、解決済みの userId から直接構築する。
     *
     * <p>{@link Authentication} を持たないサービス層（例: F08.7 ランキング名前解決）から
     * 既に {@code SecurityUtils.getCurrentUserIdOrNull()} で取得した userId を渡して再利用するための
     * メソッド。{@code userId == null} は未ログイン扱い。
     * （{@code Authentication} 版とのオーバーロード曖昧化を避けるため別名にしている。）</p>
     *
     * @param userId         閲覧者のユーザー ID（未ログインなら {@code null}）
     * @param organizationId 閲覧対象の組織 ID
     * @return 構築済みの {@link ViewerContext}
     */
    public ViewerContext buildForOrganizationByUserId(Long userId, Long organizationId) {
        if (userId == null) {
            return ViewerContext.anonymous();
        }

        // SYSTEM_ADMIN 判定（SQL 1 回）
        if (userRoleRepository.existsSystemAdminByUserId(userId) > 0) {
            return ViewerContext.systemAdmin(userId);
        }

        // 組織への直接所属ロールを取得（SQL 1 回）
        Optional<UserRoleEntity> userRoleOpt = userRoleRepository.findByUserIdAndOrganizationId(userId, organizationId);
        if (userRoleOpt.isEmpty()) {
            return ViewerContext.nonMember(userId);
        }

        String roleName = resolveRoleName(userRoleOpt.get().getRoleId());
        if (roleName == null) {
            log.warn("user_roles に role_id={} が存在しないか roles テーブルと不整合。userId={}, orgId={}",
                    userRoleOpt.get().getRoleId(), userId, organizationId);
            return ViewerContext.nonMember(userId);
        }

        return switch (roleName) {
            case ROLE_ADMIN, ROLE_DEPUTY_ADMIN, ROLE_MEMBER -> ViewerContext.member(userId, Set.of(organizationId));
            case ROLE_SUPPORTER -> ViewerContext.supporter(userId, Set.of(organizationId));
            default -> ViewerContext.nonMember(userId);
        };
    }

    /**
     * {@link Authentication} からユーザー ID を取り出す。
     *
     * <p>JwtAuthenticationFilter が設定した authentication.getName() がユーザー ID の文字列表現。
     * {@link com.mannschaft.app.common.SecurityUtils#getCurrentUserIdOrNull()} と同等のロジックを
     * Authentication を引数に取る形で実装する。</p>
     *
     * @param authentication Spring Security の Authentication（{@code null} 可）
     * @return ユーザー ID（未ログイン / 匿名 / 変換失敗の場合は {@code null}）
     */
    private Long extractUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            return null;
        }
        try {
            return Long.valueOf(authentication.getName());
        } catch (NumberFormatException e) {
            log.warn("Authentication.getName() を Long に変換できなかった: {}", authentication.getName());
            return null;
        }
    }

    /**
     * ロール ID からロール名を解決する（SQL 1 回）。
     *
     * @param roleId ロール ID
     * @return ロール名（見つからない場合は {@code null}）
     */
    private String resolveRoleName(Long roleId) {
        if (roleId == null) {
            return null;
        }
        return roleRepository.findById(roleId)
                .map(RoleEntity::getName)
                .orElse(null);
    }
}
