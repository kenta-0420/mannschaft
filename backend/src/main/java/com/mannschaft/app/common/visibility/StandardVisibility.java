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
     * スコープ (TEAM/ORGANIZATION) の所属メンバーのみ閲覧可能。
     *
     * <p>包含: ADMIN / DEPUTY_ADMIN / MEMBER / SUPPORTER / GUEST のうちロール保有者すべて。
     */
    MEMBERS_ONLY,

    /**
     * SUPPORTER 以上のロール保有者のみ閲覧可能。
     *
     * <p>包含: ADMIN / DEPUTY_ADMIN / MEMBER / SUPPORTER (= GUEST 以外の全認証メンバー)。
     *
     * <p>{@code AccessControlService.hasRoleOrAbove(..., "SUPPORTER")} と同等のセマンティクス。
     */
    SUPPORTERS_AND_ABOVE,

    /**
     * ADMIN ロールのみ閲覧可能。
     *
     * <p>包含: ADMIN / DEPUTY_ADMIN。
     *
     * <p>{@code AccessControlService.isAdminOrAbove(...)} と同等のセマンティクス。
     */
    ADMINS_ONLY,

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
     * スコープ全体の組織メンバーへ公開。
     *
     * <p>TEAM スコープのコンテンツでも、親 ORG の所属メンバーまで可視範囲を広げる。
     * 親スコープ解決規約は設計書 §5.1.1 を参照。
     *
     * <p>親 ORG が DELETED/SUSPENDED 等で非アクティブな場合の連鎖ルールは §11.6 を参照。
     */
    ORGANIZATION_WIDE,

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
