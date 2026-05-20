package com.mannschaft.app.publicview.visibility;

import java.util.Set;

/**
 * 公開ページ閲覧者の立場情報を集約した不変 DTO。
 *
 * <p>F19.1 §3 用語定義 / §7.6 表示時の解決ロジックで用いる「閲覧者コンテキスト」。
 * リクエスト境界で一度だけ {@link com.mannschaft.app.common.visibility.MembershipBatchQueryService}
 * 等を用いて構築し、リクエスト内の複数投稿表示で共有する（per-request スコープのキャッシュ判定）。</p>
 *
 * <p>{@code memberScopeIds} / {@code supporterScopeIds} は「閲覧者がそのロール種別で所属しているスコープ ID 集合」。
 * scope_type は問わず（TEAM と ORGANIZATION を区別せず保持）、判定時は scope_id 単位での包含チェックで対応する。
 * これにより、1 リクエスト内で複数のチーム / 組織にまたがる投稿を表示する場合でも、メンバーシップ取得は
 * 1 SQL に集約できる。</p>
 *
 * @param userId             閲覧者の user_id（{@code null} = 未ログイン）
 * @param status             閲覧者の立場（{@link ViewerStatus#ANONYMOUS} 等）
 * @param memberScopeIds     閲覧者が MEMBER として所属する scope_id 集合（不変、{@code null} 不可）
 * @param supporterScopeIds  閲覧者が SUPPORTER として所属する scope_id 集合（不変、{@code null} 不可）
 */
public record ViewerContext(
        Long userId,
        ViewerStatus status,
        Set<Long> memberScopeIds,
        Set<Long> supporterScopeIds) {

    public ViewerContext {
        if (status == null) {
            throw new NullPointerException("status must not be null");
        }
        memberScopeIds = memberScopeIds == null ? Set.of() : Set.copyOf(memberScopeIds);
        supporterScopeIds = supporterScopeIds == null ? Set.of() : Set.copyOf(supporterScopeIds);
    }

    /**
     * 未ログイン閲覧者かどうかを判定する。
     *
     * @return {@code userId == null} なら true
     */
    public boolean isAnonymous() {
        return userId == null;
    }

    /**
     * 未ログイン用の ViewerContext を構築する。
     *
     * @return 未ログイン閲覧者用の不変 ViewerContext
     */
    public static ViewerContext anonymous() {
        return new ViewerContext(null, ViewerStatus.ANONYMOUS, Set.of(), Set.of());
    }

    /**
     * ログイン済みだが対象スコープに所属していない閲覧者用の ViewerContext を構築する。
     *
     * @param userId 閲覧者のユーザー ID
     * @return 非メンバー閲覧者用の不変 ViewerContext
     */
    public static ViewerContext nonMember(Long userId) {
        return new ViewerContext(userId, ViewerStatus.NON_MEMBER, Set.of(), Set.of());
    }

    /**
     * 対象スコープの MEMBER ロール閲覧者用の ViewerContext を構築する。
     * ADMIN / DEPUTY_ADMIN も MEMBER として扱う（ViewerStatus.MEMBER）。
     *
     * @param userId       閲覧者のユーザー ID
     * @param memberScopeIds MEMBER として所属するスコープ ID 集合
     * @return MEMBER 閲覧者用の不変 ViewerContext
     */
    public static ViewerContext member(Long userId, Set<Long> memberScopeIds) {
        return new ViewerContext(userId, ViewerStatus.MEMBER, memberScopeIds, Set.of());
    }

    /**
     * 対象スコープの SUPPORTER ロール閲覧者用の ViewerContext を構築する。
     *
     * @param userId           閲覧者のユーザー ID
     * @param supporterScopeIds SUPPORTER として所属するスコープ ID 集合
     * @return SUPPORTER 閲覧者用の不変 ViewerContext
     */
    public static ViewerContext supporter(Long userId, Set<Long> supporterScopeIds) {
        return new ViewerContext(userId, ViewerStatus.SUPPORTER, Set.of(), supporterScopeIds);
    }

    /**
     * システム管理者用の ViewerContext を構築する。
     *
     * @param userId システム管理者のユーザー ID
     * @return SYSTEM_ADMIN 閲覧者用の不変 ViewerContext
     */
    public static ViewerContext systemAdmin(Long userId) {
        return new ViewerContext(userId, ViewerStatus.SYSTEM_ADMIN, Set.of(), Set.of());
    }
}
