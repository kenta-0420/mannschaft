package com.mannschaft.app.publicview.visibility;

import com.mannschaft.app.publicview.enums.NameDisclosureMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link IdentityVisibilityResolver} の Phase 1 仕様検証。
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §4.6 / §7.1 / §4.6.4。</p>
 *
 * <p>Phase 1 では「常時 DISPLAY_NAME 系で振る舞う」ことが要件であり、立場別に以下を検証する:</p>
 * <ul>
 *   <li>未ログイン / 非メンバー → 汎用ラベル「投稿者」 + 汎用アバター + 所属非表示</li>
 *   <li>サポーター（DISPLAY_NAME mode）→ display_name + 実アバター + 所属表示</li>
 *   <li>サポーター（REAL_NAME mode の Phase 1 暫定）→ display_name と同じ挙動（fail-safe）</li>
 *   <li>メンバー / 本人 / システム管理者 → display_name + 実アバター + 所属表示</li>
 *   <li>display_name が NULL → §4.6.4 フォールバック {@code 匿名のユーザー#XXXXX}</li>
 *   <li>display_name が空文字 → §4.6.4 フォールバック</li>
 *   <li>fallback shortHash の決定論性（同一 userId で同じ値）</li>
 * </ul>
 */
@DisplayName("IdentityVisibilityResolver Phase 1 仕様")
class IdentityVisibilityResolverTest {

    private final IdentityVisibilityResolver resolver = new IdentityVisibilityResolver();

    private static final PostAuthor SAMPLE_AUTHOR = new PostAuthor(
            42L,
            "やまだ太郎",
            null,
            "/images/users/42/avatar.png");
    private static final ScopeRef TEAM_SCOPE = ScopeRef.ofTeam(100L);
    private static final ScopeSettings DISPLAY_NAME_MODE = ScopeSettings.defaultDisplayName();
    private static final ScopeSettings REAL_NAME_MODE = new ScopeSettings(NameDisclosureMode.REAL_NAME);

    @Test
    @DisplayName("未ログイン: 汎用ラベル『投稿者』+ 汎用アバター + 所属非表示 + anonymized=true")
    void anonymous_returnsGenericLabel() {
        ViewerContext viewer = ViewerContext.anonymous();

        DisplayIdentity identity = resolver.resolveIdentityForViewer(
                SAMPLE_AUTHOR, viewer, TEAM_SCOPE, DISPLAY_NAME_MODE);

        assertThat(identity.displayLabel()).isEqualTo(AnonymousLabels.POSTER);
        assertThat(identity.avatarUrl()).isEqualTo(DisplayIdentity.ANONYMOUS_AVATAR_URL);
        assertThat(identity.teamAffiliationVisible()).isFalse();
        assertThat(identity.anonymized()).isTrue();
    }

    @Test
    @DisplayName("非メンバー: 未ログインと同じ汎用ラベル")
    void nonMember_returnsGenericLabel() {
        ViewerContext viewer = new ViewerContext(
                99L, ViewerStatus.NON_MEMBER, Set.of(), Set.of());

        DisplayIdentity identity = resolver.resolveIdentityForViewer(
                SAMPLE_AUTHOR, viewer, TEAM_SCOPE, DISPLAY_NAME_MODE);

        assertThat(identity.displayLabel()).isEqualTo(AnonymousLabels.POSTER);
        assertThat(identity.teamAffiliationVisible()).isFalse();
        assertThat(identity.anonymized()).isTrue();
    }

    @Test
    @DisplayName("サポーター（DISPLAY_NAME mode）: display_name + 実アバター + 所属表示")
    void supporter_displayNameMode_returnsDisplayName() {
        ViewerContext viewer = new ViewerContext(
                50L, ViewerStatus.SUPPORTER, Set.of(), Set.of(100L));

        DisplayIdentity identity = resolver.resolveIdentityForViewer(
                SAMPLE_AUTHOR, viewer, TEAM_SCOPE, DISPLAY_NAME_MODE);

        assertThat(identity.displayLabel()).isEqualTo("やまだ太郎");
        assertThat(identity.avatarUrl()).isEqualTo("/images/users/42/avatar.png");
        assertThat(identity.teamAffiliationVisible()).isTrue();
        assertThat(identity.anonymized()).isFalse();
    }

    @Test
    @DisplayName("サポーター（REAL_NAME mode）: Phase 1 暫定で DISPLAY_NAME と同じ挙動")
    void supporter_realNameMode_phase1FallsBackToDisplayName() {
        ViewerContext viewer = new ViewerContext(
                50L, ViewerStatus.SUPPORTER, Set.of(), Set.of(100L));

        DisplayIdentity identity = resolver.resolveIdentityForViewer(
                SAMPLE_AUTHOR, viewer, TEAM_SCOPE, REAL_NAME_MODE);

        // Phase 1: REAL_NAME モードでも display_name を返す（snapshot 機能は Phase 2）
        assertThat(identity.displayLabel()).isEqualTo("やまだ太郎");
        assertThat(identity.teamAffiliationVisible()).isTrue();
    }

    @Test
    @DisplayName("メンバー: display_name + 実アバター + 所属表示")
    void member_returnsDisplayName() {
        ViewerContext viewer = new ViewerContext(
                51L, ViewerStatus.MEMBER, Set.of(100L), Set.of());

        DisplayIdentity identity = resolver.resolveIdentityForViewer(
                SAMPLE_AUTHOR, viewer, TEAM_SCOPE, DISPLAY_NAME_MODE);

        assertThat(identity.displayLabel()).isEqualTo("やまだ太郎");
        assertThat(identity.teamAffiliationVisible()).isTrue();
        assertThat(identity.anonymized()).isFalse();
    }

    @Test
    @DisplayName("本人: display_name + 実アバター + 所属表示")
    void self_returnsDisplayName() {
        ViewerContext viewer = new ViewerContext(
                42L, ViewerStatus.SELF, Set.of(), Set.of());

        DisplayIdentity identity = resolver.resolveIdentityForViewer(
                SAMPLE_AUTHOR, viewer, TEAM_SCOPE, DISPLAY_NAME_MODE);

        assertThat(identity.displayLabel()).isEqualTo("やまだ太郎");
        assertThat(identity.teamAffiliationVisible()).isTrue();
    }

    @Test
    @DisplayName("システム管理者: display_name + 所属表示")
    void systemAdmin_returnsDisplayName() {
        ViewerContext viewer = new ViewerContext(
                1L, ViewerStatus.SYSTEM_ADMIN, Set.of(), Set.of());

        DisplayIdentity identity = resolver.resolveIdentityForViewer(
                SAMPLE_AUTHOR, viewer, TEAM_SCOPE, DISPLAY_NAME_MODE);

        assertThat(identity.displayLabel()).isEqualTo("やまだ太郎");
        assertThat(identity.teamAffiliationVisible()).isTrue();
    }

    @Test
    @DisplayName("display_name が NULL: §4.6.4 フォールバック『匿名のユーザー#XXXXX』")
    void displayNameNull_fallsBackToAnonymousHash() {
        PostAuthor author = new PostAuthor(42L, null, null, null);
        ViewerContext viewer = new ViewerContext(
                51L, ViewerStatus.MEMBER, Set.of(100L), Set.of());

        DisplayIdentity identity = resolver.resolveIdentityForViewer(
                author, viewer, TEAM_SCOPE, DISPLAY_NAME_MODE);

        assertThat(identity.displayLabel()).startsWith("匿名のユーザー#");
        // shortHash は 5 文字
        assertThat(identity.displayLabel().substring("匿名のユーザー#".length()).length()).isEqualTo(5);
        // アバター URL が NULL なら汎用アバターを返す
        assertThat(identity.avatarUrl()).isEqualTo(DisplayIdentity.ANONYMOUS_AVATAR_URL);
    }

    @Test
    @DisplayName("display_name が空文字: §4.6.4 フォールバック")
    void displayNameEmpty_fallsBackToAnonymousHash() {
        PostAuthor author = new PostAuthor(42L, "   ", "ignored", "/avatar.png");
        ViewerContext viewer = new ViewerContext(
                51L, ViewerStatus.MEMBER, Set.of(100L), Set.of());

        DisplayIdentity identity = resolver.resolveIdentityForViewer(
                author, viewer, TEAM_SCOPE, DISPLAY_NAME_MODE);

        assertThat(identity.displayLabel()).startsWith("匿名のユーザー#");
    }

    @Test
    @DisplayName("fallbackDisplayName ヘルパ: 同一 userId は決定論的に同じ shortHash を返す")
    void fallbackHelper_isDeterministic() {
        String a = IdentityVisibilityResolver.fallbackDisplayName(42L, null);
        String b = IdentityVisibilityResolver.fallbackDisplayName(42L, "");
        String c = IdentityVisibilityResolver.fallbackDisplayName(42L, "   ");

        assertThat(a).isEqualTo(b).isEqualTo(c);
        assertThat(a).startsWith("匿名のユーザー#");
        assertThat(a.length()).isEqualTo("匿名のユーザー#".length() + 5);
    }

    @Test
    @DisplayName("fallbackDisplayName ヘルパ: 異なる userId は異なる shortHash を返す")
    void fallbackHelper_distinctIds() {
        String a = IdentityVisibilityResolver.fallbackDisplayName(42L, null);
        String b = IdentityVisibilityResolver.fallbackDisplayName(43L, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("fallbackDisplayName ヘルパ: userId == null は固定文字列『匿名のユーザー』を返す")
    void fallbackHelper_nullUserId() {
        String result = IdentityVisibilityResolver.fallbackDisplayName(null, null);

        assertThat(result).isEqualTo("匿名のユーザー");
    }

    @Test
    @DisplayName("fallbackDisplayName ヘルパ: display_name が設定済みなら shortHash は使われない")
    void fallbackHelper_existingDisplayName() {
        String result = IdentityVisibilityResolver.fallbackDisplayName(42L, "山田花子");

        assertThat(result).isEqualTo("山田花子");
    }

    @Test
    @DisplayName("isMinor: Phase 1 暫定実装は常に false を返す")
    void isMinor_phase1_alwaysFalse() {
        assertThat(resolver.isMinor(1L)).isFalse();
        assertThat(resolver.isMinor(99999L)).isFalse();
    }
}
