package com.mannschaft.app.common.visibility.mapping;

import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.jobmatching.enums.VisibilityScope;

import java.util.EnumSet;
import java.util.Set;

/**
 * F13.1 求人投稿 — {@link VisibilityScope} を {@link StandardVisibility} に正規化する Mapper。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §5.2 / §5.3 完全一致。
 *
 * <p>マスター裁可 C-2 (2026-05-04): TEAM_MEMBERS_SUPPORTERS は GUEST 以外の全認証メンバーを包含する
 * {@link StandardVisibility#SUPPORTERS_AND_ABOVE} に正規化する。
 */
public final class JobMatchingVisibilityMapper {

    private JobMatchingVisibilityMapper() {
        throw new AssertionError("utility class");
    }

    /**
     * 機能側 enum を {@link StandardVisibility} に変換する。
     *
     * @param v 機能側可視性 (non-null)
     * @return 正規化された {@link StandardVisibility} (non-null)
     */
    public static StandardVisibility toStandard(VisibilityScope v) {
        return switch (v) {
            // 挙動不変・名称正準化（W3）: SCOPE_AFFILIATED = isMemberOf = 旧 MEMBERS_ONLY と同一判定。
            case TEAM_MEMBERS -> StandardVisibility.SCOPE_AFFILIATED;
            case TEAM_MEMBERS_SUPPORTERS -> StandardVisibility.SUPPORTERS_AND_ABOVE;
            // §5.1.4 CUSTOM 運用規約参照、Resolver 内で個別実装
            // (JOBBER ロール限定の閲覧制御 — §5.2 備考)
            case JOBBER_INTERNAL -> StandardVisibility.CUSTOM;
            case JOBBER_PUBLIC_BOARD -> StandardVisibility.PUBLIC;
            case ORGANIZATION_SCOPE -> StandardVisibility.ORGANIZATION_WIDE;
            case CUSTOM_TEMPLATE -> StandardVisibility.CUSTOM_TEMPLATE;
        };
    }

    /**
     * CMP-028 Phase C: {@link #toStandard(VisibilityScope)} の<strong>逆写像</strong>。
     * {@code MembershipBatchQueryService#resolveVisibleLevels} が返した「行に依存せず判定できる
     * 可視 {@link StandardVisibility} ラダー集合」を、SQL の {@code visibility_scope IN (...)} に
     * 渡せる機能固有 enum 集合へ変換する。
     *
     * <p><strong>単射性の確認</strong>: {@code PUBLIC → JOBBER_PUBLIC_BOARD}・
     * {@code SCOPE_AFFILIATED → TEAM_MEMBERS}・{@code SUPPORTERS_AND_ABOVE →
     * TEAM_MEMBERS_SUPPORTERS}・{@code ORGANIZATION_WIDE → ORGANIZATION_SCOPE} は
     * {@link #toStandard} で 4 対 4 の一対一写像であり単射（injective）である。
     * よって逆写像は曖昧さなく安全に定義できる。</p>
     *
     * <p><strong>{@code JOBBER_INTERNAL}（CUSTOM）は含まれない</strong>: 「対象求人のチームで
     * viewer が JOBBER ロールを保有するか」という行依存判定のため {@code resolveVisibleLevels}
     * のラダー集合には現れない。呼び出し側は {@code user_roles × roles} への {@code EXISTS}
     * 述語を別途 OR で組み合わせること（{@code JobPostingVisibilityResolver#evaluateCustom}
     * と同一の判定を SQL へ翻訳）。</p>
     *
     * <p><strong>{@code CUSTOM_TEMPLATE} も含まれない</strong>: テンプレート評価は行ごとの
     * 動的判定が必要で SQL 述語に落とせない。現行 MVP では {@code JobPostingService
     * .MVP_ALLOWED_SCOPES} が書き込みを禁止しており到達しないため、
     * 到達するまでは fail-closed（SQL から除外）で暫定対応する（殿の判断待ち。
     * 設計書 §16.2 / 本戦役の「判断を仰ぐこと」節を参照）。</p>
     *
     * @param standardLevels {@code resolveVisibleLevels} が返した可視 {@link StandardVisibility} 集合
     * @return 対応する {@link VisibilityScope} 集合（該当なしは空集合）
     */
    public static Set<VisibilityScope> toFunctional(Set<StandardVisibility> standardLevels) {
        if (standardLevels == null || standardLevels.isEmpty()) {
            return Set.of();
        }
        EnumSet<VisibilityScope> result = EnumSet.noneOf(VisibilityScope.class);
        if (standardLevels.contains(StandardVisibility.PUBLIC)) {
            result.add(VisibilityScope.JOBBER_PUBLIC_BOARD);
        }
        if (standardLevels.contains(StandardVisibility.SCOPE_AFFILIATED)) {
            result.add(VisibilityScope.TEAM_MEMBERS);
        }
        if (standardLevels.contains(StandardVisibility.SUPPORTERS_AND_ABOVE)) {
            result.add(VisibilityScope.TEAM_MEMBERS_SUPPORTERS);
        }
        if (standardLevels.contains(StandardVisibility.ORGANIZATION_WIDE)) {
            result.add(VisibilityScope.ORGANIZATION_SCOPE);
        }
        return Set.copyOf(result);
    }
}
