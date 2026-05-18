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
 * <p>Phase 1 では {@code realNameSnapshot} は常時 {@code null}（Phase 2 で表示モード活性化時に値が入る）。</p>
 *
 * @param authorId         投稿者 user_id（退会済みなら {@code null}）
 * @param displayName      現在の表示名（NULL/空時はフォールバック §4.6.4）
 * @param realNameSnapshot 投稿時の本名スナップショット（Phase 2 で値が入る、Phase 1 では常に {@code null}）
 * @param avatarUrl        実アバター URL（{@code null} 可）
 */
public record PostAuthor(
        Long authorId,
        String displayName,
        String realNameSnapshot,
        String avatarUrl) {

    /**
     * 投稿者情報が退会済みユーザーを指すかを判定する。
     *
     * @return {@code authorId == null} または {@code 0} なら true
     */
    public boolean isAnonymizedAuthor() {
        return authorId == null || authorId == 0L;
    }
}
