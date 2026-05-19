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
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §7.1 / §4.6 / §4.6.4 / §11.3。</p>
 *
 * <p><strong>Phase 2 仕様</strong>（設計書 §4.6.1 開示マトリクス）:</p>
 * <ul>
 *   <li>未ログイン / 非メンバー → 汎用ラベル「投稿者」+ 汎用アバター + チーム所属非表示（anonymized=true）</li>
 *   <li>サポーター:
 *     <ul>
 *       <li>スコープ設定 {@code DISPLAY_NAME}: display_name（フォールバック §4.6.4 適用）+ 実アバター</li>
 *       <li>スコープ設定 {@code REAL_NAME}: realNameSnapshot（なければ fullName）+ 実アバター</li>
 *     </ul>
 *   </li>
 *   <li>メンバー / 本人 / システム管理者: realNameSnapshot（なければ fullName）+ 実アバター</li>
 * </ul>
 *
 * <p><strong>MINOR 上書きルール</strong>（§11.3）:
 * {@code author.minor() == true}（{@code users.care_category == MINOR}）の場合、
 * 閲覧者ステータスにかかわらず汎用ラベル（ANONYMOUS 相当）を返す。</p>
 *
 * <p><strong>退会済みユーザー</strong>: {@code author.isAnonymizedAuthor() == true} の場合、
 * 汎用ラベル「退会済みユーザー」を返す。</p>
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

    /** §11.3 MINOR 上書きルール適用時の汎用ラベル。 */
    private static final String MINOR_ANONYMOUS_LABEL = "投稿者";

    /** 退会済みユーザー向け汎用ラベル。 */
    private static final String WITHDRAWN_USER_LABEL = "退会済みユーザー";

    /**
     * 閲覧者の立場に応じた投稿者表示識別を解決する。
     *
     * <p>Phase 2 実装: §4.6.1 開示マトリクス + §11.3 MINOR 上書きルール を完全実装する。</p>
     *
     * <p>SELF ステータス（投稿者本人）は ViewerContext 構築時に MEMBER として扱い、
     * 本メソッドは author.authorId == viewer.userId の一致チェックも行う。</p>
     *
     * @param author        投稿者情報（{@code null} 不可）
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

        // 退会済みユーザーの投稿は常に汎用ラベル
        if (author.isAnonymizedAuthor()) {
            return anonymousIdentityWithLabel(WITHDRAWN_USER_LABEL);
        }

        // §11.3 MINOR 上書きルール（最優先: 全閲覧者ステータスに優先する）
        if (author.minor()) {
            return anonymousIdentityWithLabel(MINOR_ANONYMOUS_LABEL);
        }

        // 本人判定: author.authorId == viewer.userId の場合は SELF として本名表示
        ViewerStatus effectiveStatus = viewer.status();
        if (viewer.userId() != null && viewer.userId().equals(author.authorId())) {
            effectiveStatus = ViewerStatus.SELF;
        }

        return switch (effectiveStatus) {
            case ANONYMOUS, NON_MEMBER -> anonymousIdentity();
            case SUPPORTER -> resolveForSupporter(author, scopeSettings);
            case MEMBER, SELF, SYSTEM_ADMIN -> resolveRealName(author);
        };
    }

    /**
     * 未ログイン / 非メンバー向けの汎用識別を返す。
     *
     * <p>§4.6.1 開示マトリクスに従い汎用ラベル「投稿者」+ 汎用アバター + チーム所属非表示で固定。</p>
     */
    private DisplayIdentity anonymousIdentity() {
        return anonymousIdentityWithLabel(AnonymousLabels.POSTER);
    }

    /**
     * 指定ラベルの汎用識別を返す（退会済みユーザー / MINOR 上書き用）。
     */
    private DisplayIdentity anonymousIdentityWithLabel(String label) {
        return new DisplayIdentity(
                label,
                DisplayIdentity.ANONYMOUS_AVATAR_URL,
                false,
                true);
    }

    /**
     * サポーター向け識別を返す。
     *
     * <p>Phase 2: {@code scopeSettings.supporterNameDisclosure} の値に従って DISPLAY_NAME / REAL_NAME を切り替える。</p>
     * <ul>
     *   <li>{@code DISPLAY_NAME}: display_name（フォールバック §4.6.4 適用）+ 実アバター + 所属表示</li>
     *   <li>{@code REAL_NAME}: realNameSnapshot → fullName フォールバック + 実アバター + 所属表示</li>
     * </ul>
     */
    private DisplayIdentity resolveForSupporter(PostAuthor author, ScopeSettings scopeSettings) {
        NameDisclosureMode mode = scopeSettings.supporterNameDisclosure();
        return switch (mode) {
            case DISPLAY_NAME -> new DisplayIdentity(
                    resolveDisplayLabel(author),
                    resolveAvatar(author),
                    true,
                    false);
            case REAL_NAME -> resolveRealName(author);
        };
    }

    /**
     * メンバー以上向け本名識別を返す。
     *
     * <p>§4.7.1: {@code author.realNameSnapshot()} が非 null であれば投稿時スナップショットを優先し、
     * null の場合（Phase 1 以前の投稿）は {@code author.fullName()} にフォールバックする。
     * さらに fullName も null の場合は display_name フォールバック（§4.6.4）を適用する。</p>
     */
    private DisplayIdentity resolveRealName(PostAuthor author) {
        String nameToDisplay;
        if (author.realNameSnapshot() != null && !author.realNameSnapshot().isBlank()) {
            // Phase 2 以降: 投稿時スナップショットを優先
            nameToDisplay = author.realNameSnapshot();
        } else if (author.fullName() != null && !author.fullName().isBlank()) {
            // Phase 1 以前の投稿（snapshot=null）: 現在の本名を使用
            nameToDisplay = author.fullName();
        } else {
            // 本名も取得できない場合（データ不整合）: display_name フォールバック
            nameToDisplay = resolveDisplayLabel(author);
        }
        return new DisplayIdentity(
                nameToDisplay,
                resolveAvatar(author),
                true,
                false);
    }

    /**
     * 投稿者の display_name を取得する。NULL/空時は §4.6.4 のフォールバックを返す。
     *
     * <p>SUPPORTER 向け DISPLAY_NAME モード および fallback 時に使用する。</p>
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

}
