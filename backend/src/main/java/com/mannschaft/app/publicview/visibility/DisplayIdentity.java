package com.mannschaft.app.publicview.visibility;

/**
 * 投稿者の表示用識別情報。
 *
 * <p>F19.1 §3 用語定義 / §7.1 で {@link IdentityVisibilityResolver} が返す不変 DTO。
 * 公開ページの各投稿カード・詳細ページで表示する「投稿者名・アバター・チーム所属表示可否」を
 * 段階開示ルール（§4.6）に従って解決した結果である。</p>
 *
 * @param displayLabel             表示する投稿者名（汎用ラベル「投稿者」/「メンバー」、または display_name 等）
 * @param avatarUrl                表示するアバター URL（汎用アバターの相対パス or 実アバター URL）
 * @param teamAffiliationVisible   「このメンバーは XX チームに所属しています」表示が許可されているか
 * @param anonymized               汎用ラベル（{@link AnonymousLabels} の固定値）にフォールバックしたか
 */
public record DisplayIdentity(
        String displayLabel,
        String avatarUrl,
        boolean teamAffiliationVisible,
        boolean anonymized) {

    /**
     * 段階開示で「未ログイン / 非メンバー向け」の汎用アバター URL（プレースホルダ画像）。
     * フロントエンド側で実体パスを当てる前提のフラグマーカー。
     */
    public static final String ANONYMOUS_AVATAR_URL = "/images/anonymous-avatar.svg";
}
