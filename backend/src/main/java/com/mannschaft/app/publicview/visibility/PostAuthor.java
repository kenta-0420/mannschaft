package com.mannschaft.app.publicview.visibility;

/**
 * 投稿の作者情報スナップショット（識別表示用）。
 *
 * <p>F19.1 §7.1 / §7.6 で {@link IdentityVisibilityResolver} に渡される投稿者情報を表す。
 * {@code authorId} は退会済みユーザーの場合 {@code null} （またはセンチネル）になりうる
 * （§4.6.3 本人判定）。{@code displayName} は現在の {@code users.display_name} を、
 * {@code realNameSnapshot} は §4.7 で投稿時にスナップショットされた本名を表す（Phase 2 で活性化）。</p>
 *
 * <p>{@code avatarUrl} は閲覧者が「実アバター」を見ることが許可されるときの URL。
 * 未ログイン / 非メンバー閲覧者には汎用アバターが返るため、本フィールドは内部情報として扱う。</p>
 *
 * <p>{@code fullName} は {@code users.last_name + users.first_name} の連結（本名）。
 * Phase 2 以降のメンバー以上向け本名表示で使用する。</p>
 *
 * <p>{@code minor} は {@code users.care_category == MINOR} を表す。
 * true の場合 §11.3 MINOR 上書きルールが適用され、閲覧者ステータスにかかわらず強制匿名となる。</p>
 *
 * @param authorId         投稿者 user_id（退会済みなら {@code null}）
 * @param displayName      現在の表示名（NULL/空時はフォールバック §4.6.4）
 * @param realNameSnapshot 投稿時の本名スナップショット（Phase 2 で値が入る、Phase 1 では {@code null}）
 * @param fullName         現在の本名（last_name + first_name の連結、Phase 2 で使用）
 * @param avatarUrl        実アバター URL（{@code null} 可）
 * @param minor            未成年フラグ（{@code care_category == MINOR}）。true なら §11.3 MINOR ルール適用
 */
public record PostAuthor(
        Long authorId,
        String displayName,
        String realNameSnapshot,
        String fullName,
        String avatarUrl,
        boolean minor) {

    /**
     * 後方互換ファクトリ: Phase 1 形式（fullName なし・minor=false）で PostAuthor を生成する。
     *
     * <p>既存の Phase 1 呼び出し箇所（new PostAuthor(4 引数)）の移行を簡易にするためのファクトリ。</p>
     *
     * @param authorId         投稿者 user_id
     * @param displayName      表示名
     * @param realNameSnapshot 本名スナップショット（Phase 1 では常に {@code null}）
     * @param avatarUrl        実アバター URL
     * @return Phase 1 形式の PostAuthor（fullName=null, minor=false）
     */
    public static PostAuthor ofPhase1(Long authorId, String displayName,
                                      String realNameSnapshot, String avatarUrl) {
        return new PostAuthor(authorId, displayName, realNameSnapshot, null, avatarUrl, false);
    }

    /**
     * 投稿者情報が退会済みユーザーを指すかを判定する。
     *
     * @return {@code authorId == null} または {@code 0} なら true
     */
    public boolean isAnonymizedAuthor() {
        return authorId == null || authorId == 0L;
    }
}
