package com.mannschaft.app.publicview.visibility;

import com.mannschaft.app.publicview.enums.NameDisclosureMode;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * F19.1 投稿者識別段階的開示の表示識別解決サービス。
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §7.1 / §4.6 / §4.6.4。</p>
 *
 * <p><strong>Phase 1 簡易版仕様</strong>:</p>
 * <ul>
 *   <li>未ログイン / 非メンバー → 汎用ラベル（「投稿者」）+ 汎用アバター + チーム所属非表示</li>
 *   <li>サポーター → 現在の display_name（フォールバック §4.6.4 適用）+ 実アバター + 所属表示</li>
 *   <li>メンバー / 本人 / システム管理者 → 現在の display_name + 実アバター + 所属表示</li>
 * </ul>
 *
 * <p>Phase 1 では {@code teams.supporter_name_disclosure} / {@code organizations.supporter_name_disclosure}
 * カラム自体は Foundation で追加済（V9.166）だが、本 Resolver は値の如何にかかわらず常時 DISPLAY_NAME 系で
 * 振る舞う。Phase 2 で REAL_NAME 経路を実装する際は本クラス内の {@code switch (mode)} 分岐に
 * 実装を追加する（プレースホルダ箇所を参照）。</p>
 *
 * <p><strong>display_name フォールバック規約</strong>（§4.6.4）:
 * {@code users.display_name} が NULL または空文字の場合、{@link #fallbackDisplayName(Long, String)} を
 * 経由して {@code 匿名のユーザー#${shortHash}} を返す。{@code shortHash} は SHA-256({@code users.id} の
 * 文字列表現) を Base36 エンコードして先頭 5 文字（同一ユーザーで決定論的）。</p>
 */
@Component
public class IdentityVisibilityResolver {

    /** §4.6.4 のフォールバック表示名の prefix（ja ロケール）。 */
    private static final String FALLBACK_DISPLAY_NAME_PREFIX_JA = "匿名のユーザー#";

    /** §4.6.4 の shortHash 長（4〜6 文字、設計書推奨値 5）。 */
    private static final int SHORT_HASH_LENGTH = 5;

    /**
     * 閲覧者の立場に応じた投稿者表示識別を解決する。
     *
     * <p>Phase 1 では viewer.status が ANONYMOUS / NON_MEMBER のとき汎用ラベル、
     * SUPPORTER / MEMBER / SELF / SYSTEM_ADMIN のとき display_name（フォールバック §4.6.4 適用）を返す。
     * Phase 2 で REAL_NAME モードが活性化された際は {@code scopeSettings.supporterNameDisclosure} を
     * 参照して切り替える（現在は常時 DISPLAY_NAME 系）。</p>
     *
     * @param author        投稿者情報（{@code null} 不可。退会済みは {@link PostAuthor#isAnonymizedAuthor()} で判定）
     * @param viewer        閲覧者コンテキスト（{@code null} 不可）
     * @param scope         対象スコープ参照（{@code null} 不可）
     * @param scopeSettings スコープの表示モード設定（{@code null} 不可）
     * @return 表示用識別情報
     */
    public DisplayIdentity resolveIdentityForViewer(
            PostAuthor author,
            ViewerContext viewer,
            ScopeRef scope,
            ScopeSettings scopeSettings) {
        Objects.requireNonNull(author, "author must not be null");
        Objects.requireNonNull(viewer, "viewer must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(scopeSettings, "scopeSettings must not be null");

        return switch (viewer.status()) {
            case ANONYMOUS, NON_MEMBER -> anonymousIdentity();
            case SUPPORTER -> resolveForSupporter(author, scopeSettings);
            case MEMBER, SELF, SYSTEM_ADMIN -> resolveForMemberOrAbove(author);
        };
    }

    /**
     * 未ログイン / 非メンバー向けの汎用識別を返す。
     *
     * <p>§4.6.1 開示マトリクスに従い汎用ラベル「投稿者」+ 汎用アバター + チーム所属非表示で固定。</p>
     */
    private DisplayIdentity anonymousIdentity() {
        return new DisplayIdentity(
                AnonymousLabels.POSTER,
                DisplayIdentity.ANONYMOUS_AVATAR_URL,
                false,
                true);
    }

    /**
     * サポーター向け識別を返す。
     *
     * <p>Phase 1: {@code scopeSettings.supporterNameDisclosure} の値にかかわらず DISPLAY_NAME 経路で処理する。
     * Phase 2 で REAL_NAME 経路を実装する際は本メソッド内の {@code switch} 分岐の REAL_NAME case を
     * 実装する（現在は {@link UnsupportedOperationException} を投げるプレースホルダ）。</p>
     */
    private DisplayIdentity resolveForSupporter(PostAuthor author, ScopeSettings scopeSettings) {
        NameDisclosureMode mode = scopeSettings.supporterNameDisclosure();
        return switch (mode) {
            case DISPLAY_NAME -> new DisplayIdentity(
                    resolveDisplayLabel(author),
                    resolveAvatar(author),
                    true,
                    false);
            case REAL_NAME ->
                // Phase 2 で本名スナップショット表示を実装する。
                // §4.7.1: post.realNameSnapshot != null なら本名表示、
                //          null なら display_name にフォールバック（DISPLAY_NAME と同じ挙動）。
                // Phase 1 では REAL_NAME モードが UI から設定されることはないため、
                // 防御的に DISPLAY_NAME と同じ挙動とする（fail-safe）。
                new DisplayIdentity(
                        resolveDisplayLabel(author),
                        resolveAvatar(author),
                        true,
                        false);
        };
    }

    /**
     * メンバー以上 / 本人 / システム管理者向け識別を返す。
     *
     * <p>Phase 1: 本名表示は実装しない。display_name（フォールバック §4.6.4 適用）+ 実アバター + 所属表示。
     * Phase 2 で「メンバー以上は本名固定」のマトリクス §4.6.1 が活性化したら本メソッドで
     * {@code author.realNameSnapshot} ベースの本名解決を実装する。</p>
     */
    private DisplayIdentity resolveForMemberOrAbove(PostAuthor author) {
        return new DisplayIdentity(
                resolveDisplayLabel(author),
                resolveAvatar(author),
                true,
                false);
    }

    /**
     * 投稿者の display_name を取得する。NULL/空時は §4.6.4 のフォールバックを返す。
     */
    private String resolveDisplayLabel(PostAuthor author) {
        return fallbackDisplayName(author.authorId(), author.displayName());
    }

    /**
     * 投稿者のアバター URL を取得する。{@code null} 時は汎用アバターを返す。
     */
    private String resolveAvatar(PostAuthor author) {
        String avatarUrl = author.avatarUrl();
        return (avatarUrl == null || avatarUrl.isBlank())
                ? DisplayIdentity.ANONYMOUS_AVATAR_URL
                : avatarUrl;
    }

    /**
     * §4.6.4 display_name フォールバック規約に従う表示名解決ヘルパ（static）。
     *
     * <p>{@code displayName} が NULL または空文字（whitespace のみ含む）の場合、
     * {@code 匿名のユーザー#${shortHash}} を返す。{@code authorId} が NULL の場合
     * （退会済みユーザーのセンチネル）は固定文字列 {@code 匿名のユーザー} を返す。</p>
     *
     * <p>本ヘルパは設計書 §4.6.4 の規約により Resolver 内で重複実装禁止のため、static 公開メソッドとして
     * 他箇所（DTO 組み立て箇所等）から呼び出せるよう用意する。</p>
     *
     * @param userId      フォールバック shortHash の素材（NULL 可、その場合は固定文字列を返す）
     * @param displayName 現在の display_name（NULL / 空 / blank はフォールバックを返す）
     * @return 表示用文字列
     */
    public static String fallbackDisplayName(Long userId, String displayName) {
        if (displayName != null && !displayName.isBlank()) {
            return displayName;
        }
        if (userId == null) {
            return FALLBACK_DISPLAY_NAME_PREFIX_JA.substring(0,
                    FALLBACK_DISPLAY_NAME_PREFIX_JA.length() - 1);
        }
        return FALLBACK_DISPLAY_NAME_PREFIX_JA + computeShortHash(userId);
    }

    /**
     * §4.6.4 shortHash 生成: SHA-256({@code userId} 文字列) を Base36 エンコードして先頭 N 文字を返す。
     *
     * <p>BigInteger.toString(36) は大文字を含まないため、決定論的かつ URL 安全な短文字列となる。</p>
     *
     * @param userId 投稿者 user_id（{@code null} 不可、上位で除外済み前提）
     * @return 先頭 {@value #SHORT_HASH_LENGTH} 文字の英数字
     */
    private static String computeShortHash(Long userId) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(userId.toString().getBytes(StandardCharsets.UTF_8));
            // 上位ビットが 0 になるよう正の BigInteger として扱う（先頭ゼロ抑止のため byte 配列に 0 を前置）
            byte[] positive = new byte[digest.length + 1];
            positive[0] = 0;
            System.arraycopy(digest, 0, positive, 1, digest.length);
            String base36 = new BigInteger(positive).toString(36);
            if (base36.length() >= SHORT_HASH_LENGTH) {
                return base36.substring(0, SHORT_HASH_LENGTH);
            }
            // 入力長が極端に短く Base36 表現が SHORT_HASH_LENGTH 未満になることは現実的にないが、
            // 防御的に右側を '0' で埋める。
            StringBuilder padded = new StringBuilder(base36);
            while (padded.length() < SHORT_HASH_LENGTH) {
                padded.append('0');
            }
            return padded.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 は JDK 標準で必ず存在するため、ここに到達することは無い
            throw new IllegalStateException("SHA-256 must be available", e);
        }
    }

    /**
     * 未成年判定（§11.3 暫定実装）。
     *
     * <p>軍議裁定（2026-05-18）により Phase 1 では {@code users.is_minor} カラム未追加のため、
     * 常時 false を返す暫定実装とする。Phase 2 で {@code users.is_minor} 追加後に
     * 本メソッドを差し替える。</p>
     *
     * @param authorId 投稿者 user_id
     * @return 常に false（Phase 1 暫定）
     */
    @SuppressWarnings("unused") // Phase 2 で UserRepository ベースの判定に差し替える
    public boolean isMinor(Long authorId) {
        return false;
    }
}
