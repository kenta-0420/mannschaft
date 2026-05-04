package com.mannschaft.app.common.visibility;

import com.mannschaft.app.common.AccessControlService;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import java.util.HashMap;
import java.util.Map;

/**
 * 1 リクエスト内のメンバーシップ判定をメモ化するキャッシュ。
 *
 * <p>同一トランザクション内で同一 {@code (userId, scopeId, scopeType)} の
 * メンバーシップを複数回引く可能性が高いため、{@link RequestScope} で結果を保持する。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §9.3 完全一致。
 *
 * <p>各 Resolver は {@link AccessControlService} を直接呼ばず本キャッシュ経由で
 * 問い合わせることが推奨される (任意)。なお本クラスは {@link RequestScope} のため、
 * リクエスト境界を跨ぐと自動的に破棄され、キャッシュは新規構築される。
 */
@Component
@RequestScope
public class MembershipQueryCache {

    private final Map<MembershipKey, Boolean> cache = new HashMap<>();

    /**
     * 指定ユーザがスコープのメンバーかどうかを返す。
     *
     * <p>同一キーの 2 回目以降は {@code delegate} を呼ばずキャッシュを返す。
     *
     * @param userId    閲覧者 userId
     * @param scopeId   スコープ ID (team_id / organization_id 等)
     * @param scopeType スコープ種別 ("TEAM" / "ORGANIZATION" 等)
     * @param delegate  実判定を行う {@link AccessControlService}
     * @return メンバーなら true
     */
    public boolean isMember(Long userId, Long scopeId, String scopeType,
                            AccessControlService delegate) {
        return cache.computeIfAbsent(
            new MembershipKey(userId, scopeId, scopeType),
            k -> delegate.isMember(k.userId(), k.scopeId(), k.scopeType()));
    }

    /**
     * メモ化キー。
     *
     * <p>{@code userId} は未認証時 {@code null} を許容するため、{@link HashMap}
     * の {@code computeIfAbsent} から見て {@code null} キー要素を含む合成キーになる。
     * {@link java.util.Objects#hash} ベースの実装で {@code null} 安全。
     */
    private record MembershipKey(Long userId, Long scopeId, String scopeType) {
    }
}
