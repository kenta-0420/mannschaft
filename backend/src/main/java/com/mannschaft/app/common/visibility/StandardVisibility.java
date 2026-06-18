package com.mannschaft.app.common.visibility;

/**
 * 機能横断で扱う標準可視性レベル。
 *
 * <p>機能固有 enum (例: {@code cms.Visibility}, {@code event.entity.EventVisibility} 等) は
 * mapping パッケージ配下の Mapper で本 enum に正規化される。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §5.1 完全一致。
 *
 * <p>値追加・削除時の影響範囲は §5.1.3 を参照。値追加は本設計書の改訂を伴う作業と位置付ける。
 */
public enum StandardVisibility {

    /**
     * 誰でも閲覧可能。
     *
     * <p><strong>未認証ユーザー (userId=null) も閲覧可</strong>
     * (設計書 §17.Q1 マスター裁可済 / 2026-05-04)。
     *
     * <p>未認証時は本値かつ {@code ContentStatus.PUBLISHED} のときのみ true、
     * それ以外の StandardVisibility 値はすべて fail-closed で false 扱い。
     */
    PUBLIC,

    /**
     * スコープにおいて MEMBER ロール以上の保有者のみ閲覧可能（新ラダー・閾値方式）。
     *
     * <p>包含: ADMIN / DEPUTY_ADMIN / MEMBER（= 優先度 MEMBER 以上）。
     * <strong>SUPPORTER / GUEST は不可視</strong>（{@link #SCOPE_AFFILIATED}「直接所属軸」との差分）。
     * 未認証 (GUEST 扱い) も不可視。
     *
     * <p>判定は {@code UserScopeRoleSnapshot.hasRoleOrAbove(scope, "MEMBER")} と同等。
     * 設計書 §5.1 / §5.1.5 の新ラダー
     * （{@code PUBLIC > SUPPORTERS_AND_ABOVE > MEMBERS_AND_ABOVE > ADMINS_AND_ABOVE}）に属する。
     */
    MEMBERS_AND_ABOVE,

    /**
     * スコープへの直接所属者のみ閲覧可能（新ラダーの別軸・直接所属判定方式）。
     *
     * <p>包含: ロール保有者すべて（ADMIN / DEPUTY_ADMIN / MEMBER / SUPPORTER / GUEST）。
     * ただし JOBBER 等の並行ロール（{@code RolePriority} 未登録）は除外する（F13.1 §2.9）。
     * 未認証・非所属は不可視。
     *
     * <p>判定は {@code UserScopeRoleSnapshot.isMemberOf(scope)} と同等で、
     * 旧「所属者全員可視」軸と同一挙動。閾値ラダーとは独立した「直接所属」軸として扱う
     * （旧 MEMBERS_ONLY 相当の正準値）。設計書 §5.1 / §5.1.5 を参照。
     */
    SCOPE_AFFILIATED,

    /**
     * SUPPORTER 以上のロール保有者のみ閲覧可能。
     *
     * <p>包含: ADMIN / DEPUTY_ADMIN / MEMBER / SUPPORTER (= GUEST 以外の全認証メンバー)。
     *
     * <p>{@code AccessControlService.hasRoleOrAbove(..., "SUPPORTER")} と同等のセマンティクス。
     */
    SUPPORTERS_AND_ABOVE,

    /**
     * ADMIN ロール以上の保有者のみ閲覧可能（新ラダー・閾値方式 / 旧 ADMINS_ONLY 改名）。
     *
     * <p>包含: ADMIN / DEPUTY_ADMIN（= 優先度 ADMIN 以上）。MEMBER 以下は不可視。
     *
     * <p>判定は {@code UserScopeRoleSnapshot.hasRoleOrAbove(scope, "ADMIN")} と同等。
     * 新ラダーの最上位閾値であり、機微度も最高位として扱う。
     * 設計書 §5.1 / §5.1.5 を参照。
     */
    ADMINS_AND_ABOVE,

    /**
     * 作成者本人のみ閲覧可能。
     */
    PRIVATE,

    /**
     * SNS のフォロワー関係に基づく公開 (社会機能 F04.x 専用)。
     */
    FOLLOWERS_ONLY,

    /**
     * F01.7 カスタムテンプレートによる公開。
     *
     * <p>{@code visibility_template_id} 必須。テンプレート評価は
     * 既存の {@code VisibilityTemplateEvaluator} に委譲する。
     */
    CUSTOM_TEMPLATE,

    /**
     * スコープ全体の組織メンバーへ公開（<strong>上向き 1 段</strong>・所属拡大軸）。
     *
     * <p>TEAM スコープのコンテンツでも、親 ORG の所属メンバーまで可視範囲を広げる。
     * 親スコープ解決規約は設計書 §5.1.1 を参照。判定は
     * {@code UserScopeRoleSnapshot.isMemberOfParentOrg(scope)}
     * （= TEAM → 親 ORG 1 段を上向きに辿り、その ORG の直接所属を確認）。
     *
     * <p>方向性の対比: 本値は「子コンテンツ → 親 ORG メンバー」の<strong>上向き</strong>展開であり、
     * {@link #ORGANIZATION_AND_DESCENDANTS}（親 ORG コンテンツ → 全子孫組織 + 配下チームの
     * メンバーへの<strong>下向き</strong>再帰展開）とは展開方向が真逆である。両者は閾値ラダー
     * （{@code PUBLIC > SUPPORTERS_AND_ABOVE > MEMBERS_AND_ABOVE > ADMINS_AND_ABOVE}）ではなく、
     * {@link #SCOPE_AFFILIATED} と並ぶ「所属拡大軸」に属する。
     *
     * <p>親 ORG が DELETED/SUSPENDED 等で非アクティブな場合の連鎖ルールは §11.6 を参照。
     */
    ORGANIZATION_WIDE,

    /**
     * 組織スコープのコンテンツを、<strong>組織 + 全子孫組織 + 配下 ACTIVE チームのメンバー</strong>
     * （再帰）へ公開する（<strong>下向き再帰</strong>・所属拡大軸 / フェーズ M2）。
     *
     * <p>{@link #ORGANIZATION_WIDE}（上向き 1 段）の<strong>鏡像</strong>として導入された値である。
     * 組織はネスト（{@code organizations.parent_organization_id} 隣接リスト）するため、
     * root 組織が配信したコンテンツを末端の参加チームのみに所属するユーザーまで届ける必要がある。
     * 従来の {@link #SCOPE_AFFILIATED}（当該 ORG への直接所属のみ）や {@link #ORGANIZATION_WIDE}
     * （親 ORG 1 段上向き）では、孫組織配下チームのみ所属者を deny してしまう（欠陥 Z）。</p>
     *
     * <p>判定は {@code UserScopeRoleSnapshot.isDescendantMemberOf(scope)}。当該 ORG を根とした
     * 再帰的配下ツリー（全子孫組織の直属 ∪ それら組織の ACTIVE 参加チームメンバー）に
     * viewer が含まれるかを下向きに評価する。配信 universe（M1 で再帰化済）と評価範囲が一致する。</p>
     *
     * <p><strong>所属軸であり SUPPORTER を含む</strong>（G7）。閾値ラダーとは独立した
     * 「所属拡大軸」であり、閲覧可否は「当該組織コンテンツを見てよい所属圏にいるか」で決まる。
     * SUPPORTER 除外は行わない。</p>
     *
     * <p><strong>組織メンバー定義は不変</strong>（G3）。本値は可視範囲の拡大に用いるだけで、
     * 配下チーム所属を ORG 直接所属に「昇格」させるものではない。snapshot 上も
     * {@code orgMemberOf}（直接所属）とは別フィールド {@code descendantMemberOfOrgIds} で保持し、
     * 既存の {@link #ORGANIZATION_WIDE} / {@link #SCOPE_AFFILIATED} の判定には一切影響しない。</p>
     *
     * <p>当該 ORG 自身が DELETED/SUSPENDED 等で非アクティブな場合は fail-closed
     * （{@code UserScopeRoleSnapshot.isOrgInactive(scope)}・§11.6 の鏡像）。</p>
     */
    ORGANIZATION_AND_DESCENDANTS,

    /**
     * 上記いずれにも該当しない、機能独自のセマンティクス。
     *
     * <p>Resolver 内で個別ハンドリング ({@code evaluateCustom}) が必要。
     * 例: Survey の AFTER_RESPONSE (時間軸条件)、Committee の NAME_ONLY (部分公開)。
     *
     * <p>運用規約は設計書 §5.1.4 を参照。
     * 全機能 enum 値のうち CUSTOM に流れる比率が 30% を超えた場合、
     * StandardVisibility 値追加の議題化が必須となる。
     * 新規機能設計時に初手から CUSTOM を選ぶことは禁止。
     */
    CUSTOM
}
