package com.mannschaft.app.publicview.visibility;

import com.mannschaft.app.publicview.enums.NameDisclosureMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link IdentityVisibilityResolver} の Phase 2 仕様検証。
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §4.6 / §7.1 / §4.6.4 / §11.3。</p>
 *
 * <p>Phase 2 の §4.6.1 開示マトリクス + §11.3 MINOR 上書きルールを検証する:</p>
 * <ul>
 *   <li>未ログイン / 非メンバー → 汎用ラベル「投稿者」 + 汎用アバター + 所属非表示</li>
 *   <li>サポーター（DISPLAY_NAME mode）→ display_name + 実アバター + 所属表示</li>
 *   <li>サポーター（REAL_NAME mode）→ realNameSnapshot または fullName + 実アバター + 所属表示</li>
 *   <li>メンバー / 本人 / システム管理者 → realNameSnapshot または fullName + 実アバター + 所属表示</li>
 *   <li>MINOR 上書きルール → 閲覧者ステータスにかかわらず汎用ラベル「投稿者」</li>
 *   <li>退会済みユーザー → 「退会済みユーザー」ラベル</li>
 *   <li>display_name が NULL → §4.6.4 フォールバック {@code 匿名のユーザー#XXXXX}</li>
 *   <li>fallback shortHash の決定論性（同一 userId で同じ値）</li>
 * </ul>
 */
@DisplayName("IdentityVisibilityResolver Phase 2 仕様")
class IdentityVisibilityResolverTest {

    private final IdentityVisibilityResolver resolver = new IdentityVisibilityResolver();

    /** Phase 2 テスト用: displayName のみ、fullName=null、minor=false */
    private static final PostAuthor SAMPLE_AUTHOR = PostAuthor.ofPhase1(
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
    @DisplayName("サポーター（REAL_NAME mode）: realNameSnapshot が null / fullName が null → display_name フォールバック")
    void supporter_realNameMode_noSnapshotNoFullName_fallsBackToDisplayName() {
        ViewerContext viewer = new ViewerContext(
                50L, ViewerStatus.SUPPORTER, Set.of(), Set.of(100L));

        // SAMPLE_AUTHOR は realNameSnapshot=null, fullName=null なので display_name "やまだ太郎" にフォールバック
        DisplayIdentity identity = resolver.resolveIdentityForViewer(
                SAMPLE_AUTHOR, viewer, TEAM_SCOPE, REAL_NAME_MODE);

        assertThat(identity.displayLabel()).isEqualTo("やまだ太郎");
        assertThat(identity.teamAffiliationVisible()).isTrue();
    }

    @Test
    @DisplayName("サポーター（REAL_NAME mode）: realNameSnapshot がある場合はスナップショットを返す")
    void supporter_realNameMode_withSnapshot_returnsSnapshot() {
        PostAuthor authorWithSnapshot = new PostAuthor(
                42L, "やまだ太郎", "山田 太郎", "山田太郎", "/images/users/42/avatar.png", false);
        ViewerContext viewer = new ViewerContext(
                50L, ViewerStatus.SUPPORTER, Set.of(), Set.of(100L));

        DisplayIdentity identity = resolver.resolveIdentityForViewer(
                authorWithSnapshot, viewer, TEAM_SCOPE, REAL_NAME_MODE);

        assertThat(identity.displayLabel()).isEqualTo("山田 太郎");
        assertThat(identity.teamAffiliationVisible()).isTrue();
    }

    @Test
    @DisplayName("メンバー: realNameSnapshot=null, fullName=null → display_name フォールバック")
    void member_noSnapshotNoFullName_fallsBackToDisplayName() {
        ViewerContext viewer = new ViewerContext(
                51L, ViewerStatus.MEMBER, Set.of(100L), Set.of());

        // SAMPLE_AUTHOR は realNameSnapshot=null, fullName=null なので display_name にフォールバック
        DisplayIdentity identity = resolver.resolveIdentityForViewer(
                SAMPLE_AUTHOR, viewer, TEAM_SCOPE, DISPLAY_NAME_MODE);

        assertThat(identity.displayLabel()).isEqualTo("やまだ太郎");
        assertThat(identity.teamAffiliationVisible()).isTrue();
        assertThat(identity.anonymized()).isFalse();
    }

    @Test
    @DisplayName("メンバー: fullName がある場合は fullName を返す（snapshot=null）")
    void member_withFullName_returnsFullName() {
        PostAuthor authorWithFullName = new PostAuthor(
                42L, "やまだ太郎", null, "山田太郎", "/images/users/42/avatar.png", false);
        ViewerContext viewer = new ViewerContext(
                51L, ViewerStatus.MEMBER, Set.of(100L), Set.of());

        DisplayIdentity identity = resolver.resolveIdentityForViewer(
                authorWithFullName, viewer, TEAM_SCOPE, DISPLAY_NAME_MODE);

        assertThat(identity.displayLabel()).isEqualTo("山田太郎");
        assertThat(identity.teamAffiliationVisible()).isTrue();
        assertThat(identity.anonymized()).isFalse();
    }

    @Test
    @DisplayName("本人（SELF）: realNameSnapshot があればスナップショットを返す")
    void self_withSnapshot_returnsSnapshot() {
        PostAuthor authorWithSnapshot = new PostAuthor(
                42L, "やまだ太郎", "山田 太郎", "山田太郎", "/images/users/42/avatar.png", false);
        ViewerContext viewer = new ViewerContext(
                42L, ViewerStatus.SELF, Set.of(), Set.of());

        DisplayIdentity identity = resolver.resolveIdentityForViewer(
                authorWithSnapshot, viewer, TEAM_SCOPE, DISPLAY_NAME_MODE);

        assertThat(identity.displayLabel()).isEqualTo("山田 太郎");
        assertThat(identity.teamAffiliationVisible()).isTrue();
    }

    @Test
    @DisplayName("本人判定（userId 一致）: MEMBER ステータスでも author.authorId == viewer.userId なら SELF 扱い")
    void self_detectedByUserIdMatch() {
        PostAuthor authorWithFullName = new PostAuthor(
                42L, "やまだ太郎", null, "山田太郎", "/images/users/42/avatar.png", false);
        // viewer.userId == author.authorId: MEMBER ステータスでも SELF として処理
        ViewerContext viewer = new ViewerContext(
                42L, ViewerStatus.MEMBER, Set.of(100L), Set.of());

        DisplayIdentity identity = resolver.resolveIdentityForViewer(
                authorWithFullName, viewer, TEAM_SCOPE, DISPLAY_NAME_MODE);

        assertThat(identity.displayLabel()).isEqualTo("山田太郎");
        assertThat(identity.teamAffiliationVisible()).isTrue();
    }

    @Test
    @DisplayName("システム管理者: realNameSnapshot があればスナップショットを返す")
    void systemAdmin_withSnapshot_returnsSnapshot() {
        PostAuthor authorWithSnapshot = new PostAuthor(
                42L, "やまだ太郎", "山田 太郎", "山田太郎", "/images/users/42/avatar.png", false);
        ViewerContext viewer = new ViewerContext(
                1L, ViewerStatus.SYSTEM_ADMIN, Set.of(), Set.of());

        DisplayIdentity identity = resolver.resolveIdentityForViewer(
                authorWithSnapshot, viewer, TEAM_SCOPE, DISPLAY_NAME_MODE);

        assertThat(identity.displayLabel()).isEqualTo("山田 太郎");
        assertThat(identity.teamAffiliationVisible()).isTrue();
    }

    @Test
    @DisplayName("MINOR 上書きルール（§11.3）: minor=true は MEMBER でも汎用ラベル『投稿者』")
    void minor_overridesAllStatus_returnsAnonymousLabel() {
        PostAuthor minorAuthor = new PostAuthor(
                42L, "やまだ太郎", "山田太郎", "山田太郎", "/images/users/42/avatar.png", true);
        ViewerContext memberViewer = new ViewerContext(
                51L, ViewerStatus.MEMBER, Set.of(100L), Set.of());

        DisplayIdentity identity = resolver.resolveIdentityForViewer(
                minorAuthor, memberViewer, TEAM_SCOPE, DISPLAY_NAME_MODE);

        assertThat(identity.displayLabel()).isEqualTo(AnonymousLabels.POSTER);
        assertThat(identity.teamAffiliationVisible()).isFalse();
        assertThat(identity.anonymized()).isTrue();
    }

    @Test
    @DisplayName("MINOR 上書きルール（§11.3）: SYSTEM_ADMIN でも minor=true は汎用ラベル")
    void minor_overridesSystemAdmin_returnsAnonymousLabel() {
        PostAuthor minorAuthor = new PostAuthor(
                42L, "やまだ太郎", null, null, "/images/users/42/avatar.png", true);
        ViewerContext adminViewer = new ViewerContext(
                1L, ViewerStatus.SYSTEM_ADMIN, Set.of(), Set.of());

        DisplayIdentity identity = resolver.resolveIdentityForViewer(
                minorAuthor, adminViewer, TEAM_SCOPE, DISPLAY_NAME_MODE);

        assertThat(identity.displayLabel()).isEqualTo(AnonymousLabels.POSTER);
        assertThat(identity.anonymized()).isTrue();
    }

    @Test
    @DisplayName("退会済みユーザー: authorId=null → 『退会済みユーザー』ラベル")
    void anonymizedAuthor_returnsWithdrawnLabel() {
        PostAuthor withdrawnAuthor = PostAuthor.ofPhase1(null, null, null, null);
        ViewerContext memberViewer = new ViewerContext(
                51L, ViewerStatus.MEMBER, Set.of(100L), Set.of());

        DisplayIdentity identity = resolver.resolveIdentityForViewer(
                withdrawnAuthor, memberViewer, TEAM_SCOPE, DISPLAY_NAME_MODE);

        assertThat(identity.displayLabel()).isEqualTo("退会済みユーザー");
        assertThat(identity.anonymized()).isTrue();
    }

    @Test
    @DisplayName("display_name が NULL で fullName も NULL: §4.6.4 フォールバック『匿名のユーザー#XXXXX』")
    void displayNameNull_fallsBackToAnonymousHash() {
        PostAuthor author = PostAuthor.ofPhase1(42L, null, null, null);
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
    @DisplayName("サポーター DISPLAY_NAME mode: display_name が空白のみ → §4.6.4 フォールバック")
    void supporter_displayNameEmpty_fallsBackToAnonymousHash() {
        // SUPPORTER + DISPLAY_NAME mode は resolveDisplayLabel → fallbackDisplayName を呼ぶ
        PostAuthor author = PostAuthor.ofPhase1(42L, "   ", null, "/avatar.png");
        ViewerContext viewer = new ViewerContext(
                50L, ViewerStatus.SUPPORTER, Set.of(), Set.of(100L));

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

    // =========================================================
    // 追加ケース（Phase 2 テスト強化 — 32件目標）
    // =========================================================

    @Test
    @DisplayName("ANONYMOUS: アバター URL が NULL でも汎用アバターに差し替わる")
    void anonymous_nullAvatarUrl_returnsAnonymousAvatarUrl() {
        PostAuthor author = PostAuthor.ofPhase1(42L, "やまだ太郎", null, null);
        ViewerContext viewer = ViewerContext.anonymous();

        DisplayIdentity identity = resolver.resolveIdentityForViewer(
                author, viewer, TEAM_SCOPE, DISPLAY_NAME_MODE);

        assertThat(identity.avatarUrl()).isEqualTo(DisplayIdentity.ANONYMOUS_AVATAR_URL);
        assertThat(identity.anonymized()).isTrue();
    }

    @Test
    @DisplayName("NON_MEMBER: 別スコープに属するが対象スコープには属さない → 汎用ラベル")
    void nonMember_inOtherScope_returnsGenericLabel() {
        // 別スコープ 200L にはメンバーだが、対象スコープ 100L には非メンバー
        ViewerContext viewer = new ViewerContext(
                55L, ViewerStatus.NON_MEMBER, Set.of(200L), Set.of(300L));

        DisplayIdentity identity = resolver.resolveIdentityForViewer(
                SAMPLE_AUTHOR, viewer, TEAM_SCOPE, DISPLAY_NAME_MODE);

        assertThat(identity.displayLabel()).isEqualTo(AnonymousLabels.POSTER);
        assertThat(identity.teamAffiliationVisible()).isFalse();
        assertThat(identity.anonymized()).isTrue();
    }

    @Test
    @DisplayName("SUPPORTER（DISPLAY_NAME mode）: アバター URL が NULL → 汎用アバターにフォールバック")
    void supporter_displayNameMode_nullAvatarUrl_returnsAnonymousAvatarUrl() {
        PostAuthor author = PostAuthor.ofPhase1(42L, "テスト太郎", null, null);
        ViewerContext viewer = new ViewerContext(
                50L, ViewerStatus.SUPPORTER, Set.of(), Set.of(100L));

        DisplayIdentity identity = resolver.resolveIdentityForViewer(
                author, viewer, TEAM_SCOPE, DISPLAY_NAME_MODE);

        // 表示名は display_name を使うが、アバターは汎用フォールバック
        assertThat(identity.displayLabel()).isEqualTo("テスト太郎");
        assertThat(identity.avatarUrl()).isEqualTo(DisplayIdentity.ANONYMOUS_AVATAR_URL);
        assertThat(identity.teamAffiliationVisible()).isTrue();
        assertThat(identity.anonymized()).isFalse();
    }

    @Test
    @DisplayName("SUPPORTER（REAL_NAME mode）: snapshot=null, fullName あり → fullName を返す")
    void supporter_realNameMode_noSnapshot_withFullName_returnsFullName() {
        PostAuthor authorWithFullName = new PostAuthor(
                42L, "やまだ太郎", null, "山田太郎", "/images/users/42/avatar.png", false);
        ViewerContext viewer = new ViewerContext(
                50L, ViewerStatus.SUPPORTER, Set.of(), Set.of(100L));

        DisplayIdentity identity = resolver.resolveIdentityForViewer(
                authorWithFullName, viewer, TEAM_SCOPE, REAL_NAME_MODE);

        assertThat(identity.displayLabel()).isEqualTo("山田太郎");
        assertThat(identity.teamAffiliationVisible()).isTrue();
        assertThat(identity.anonymized()).isFalse();
    }

    @Test
    @DisplayName("SUPPORTER（REAL_NAME mode）: snapshot=null, fullName=null → display_name フォールバック")
    void supporter_realNameMode_noSnapshot_noFullName_fallsBackToDisplayName() {
        // SAMPLE_AUTHOR は realNameSnapshot=null, fullName=null
        ViewerContext viewer = new ViewerContext(
                50L, ViewerStatus.SUPPORTER, Set.of(), Set.of(100L));

        DisplayIdentity identity = resolver.resolveIdentityForViewer(
                SAMPLE_AUTHOR, viewer, TEAM_SCOPE, REAL_NAME_MODE);

        // fullName=null → display_name "やまだ太郎" にフォールバック
        assertThat(identity.displayLabel()).isEqualTo("やまだ太郎");
        assertThat(identity.teamAffiliationVisible()).isTrue();
    }

    @Test
    @DisplayName("MEMBER: realNameSnapshot が空白のみ → fullName にフォールバック")
    void member_blankSnapshot_fallsBackToFullName() {
        PostAuthor author = new PostAuthor(
                42L, "やまだ太郎", "   ", "山田 太郎", "/images/users/42/avatar.png", false);
        ViewerContext viewer = new ViewerContext(
                51L, ViewerStatus.MEMBER, Set.of(100L), Set.of());

        DisplayIdentity identity = resolver.resolveIdentityForViewer(
                author, viewer, TEAM_SCOPE, DISPLAY_NAME_MODE);

        // 空白のみ snapshot は null 扱い → fullName "山田 太郎"
        assertThat(identity.displayLabel()).isEqualTo("山田 太郎");
        assertThat(identity.teamAffiliationVisible()).isTrue();
        assertThat(identity.anonymized()).isFalse();
    }

    @Test
    @DisplayName("SELF（userId 一致 × SUPPORTER ステータス）: snapshot あり → snapshot を返す")
    void self_detectedWithSupporterStatus_withSnapshot_returnsSnapshot() {
        PostAuthor authorWithSnapshot = new PostAuthor(
                42L, "やまだ太郎", "山田 太郎", "山田太郎", "/images/users/42/avatar.png", false);
        // SUPPORTER ステータスで author.authorId と一致 → SELF 昇格
        ViewerContext viewer = new ViewerContext(
                42L, ViewerStatus.SUPPORTER, Set.of(), Set.of(100L));

        DisplayIdentity identity = resolver.resolveIdentityForViewer(
                authorWithSnapshot, viewer, TEAM_SCOPE, DISPLAY_NAME_MODE);

        // SELF 昇格後は REAL_NAME 処理 → snapshot "山田 太郎" が返る
        assertThat(identity.displayLabel()).isEqualTo("山田 太郎");
        assertThat(identity.teamAffiliationVisible()).isTrue();
        assertThat(identity.anonymized()).isFalse();
    }

    @Test
    @DisplayName("SELF（userId 一致 × NON_MEMBER ステータス）: snapshot=null, fullName あり → fullName を返す")
    void self_detectedWithNonMemberStatus_withFullName_returnsFullName() {
        PostAuthor authorWithFullName = new PostAuthor(
                42L, "やまだ太郎", null, "山田太郎", "/images/users/42/avatar.png", false);
        ViewerContext viewer = new ViewerContext(
                42L, ViewerStatus.NON_MEMBER, Set.of(), Set.of());

        DisplayIdentity identity = resolver.resolveIdentityForViewer(
                authorWithFullName, viewer, TEAM_SCOPE, DISPLAY_NAME_MODE);

        // SELF 昇格後は REAL_NAME 処理 → fullName "山田太郎" が返る
        assertThat(identity.displayLabel()).isEqualTo("山田太郎");
        assertThat(identity.anonymized()).isFalse();
    }

    @Test
    @DisplayName("SYSTEM_ADMIN: snapshot=null, fullName あり → fullName を返す")
    void systemAdmin_noSnapshot_withFullName_returnsFullName() {
        PostAuthor authorWithFullName = new PostAuthor(
                42L, "やまだ太郎", null, "山田太郎", "/images/users/42/avatar.png", false);
        ViewerContext viewer = new ViewerContext(
                1L, ViewerStatus.SYSTEM_ADMIN, Set.of(), Set.of());

        DisplayIdentity identity = resolver.resolveIdentityForViewer(
                authorWithFullName, viewer, TEAM_SCOPE, DISPLAY_NAME_MODE);

        assertThat(identity.displayLabel()).isEqualTo("山田太郎");
        assertThat(identity.teamAffiliationVisible()).isTrue();
        assertThat(identity.anonymized()).isFalse();
    }

    @Test
    @DisplayName("SYSTEM_ADMIN: snapshot=null, fullName=null → display_name フォールバック")
    void systemAdmin_noSnapshot_noFullName_fallsBackToDisplayName() {
        // SAMPLE_AUTHOR は fullName=null, realNameSnapshot=null
        ViewerContext viewer = new ViewerContext(
                1L, ViewerStatus.SYSTEM_ADMIN, Set.of(), Set.of());

        DisplayIdentity identity = resolver.resolveIdentityForViewer(
                SAMPLE_AUTHOR, viewer, TEAM_SCOPE, DISPLAY_NAME_MODE);

        // display_name "やまだ太郎" にフォールバック
        assertThat(identity.displayLabel()).isEqualTo("やまだ太郎");
        assertThat(identity.anonymized()).isFalse();
    }

    @Test
    @DisplayName("MINOR 上書きルール（§11.3）: SUPPORTER でも minor=true は汎用ラベル")
    void minor_overridesSupporter_returnsAnonymousLabel() {
        PostAuthor minorAuthor = new PostAuthor(
                42L, "やまだ太郎", null, null, "/images/users/42/avatar.png", true);
        ViewerContext supporterViewer = new ViewerContext(
                50L, ViewerStatus.SUPPORTER, Set.of(), Set.of(100L));

        DisplayIdentity identity = resolver.resolveIdentityForViewer(
                minorAuthor, supporterViewer, TEAM_SCOPE, REAL_NAME_MODE);

        assertThat(identity.displayLabel()).isEqualTo(AnonymousLabels.POSTER);
        assertThat(identity.teamAffiliationVisible()).isFalse();
        assertThat(identity.anonymized()).isTrue();
    }

    @Test
    @DisplayName("MINOR 上書きルール（§11.3）: SELF でも minor=true は汎用ラベル（本人すら不開示）")
    void minor_overridesSelf_returnsAnonymousLabel() {
        PostAuthor minorSelfAuthor = new PostAuthor(
                42L, "やまだ太郎", "山田太郎", "山田太郎", "/images/users/42/avatar.png", true);
        // viewer.userId == author.authorId → SELF 昇格前に MINOR チェックが走る
        ViewerContext selfViewer = new ViewerContext(
                42L, ViewerStatus.MEMBER, Set.of(100L), Set.of());

        DisplayIdentity identity = resolver.resolveIdentityForViewer(
                minorSelfAuthor, selfViewer, TEAM_SCOPE, DISPLAY_NAME_MODE);

        assertThat(identity.displayLabel()).isEqualTo(AnonymousLabels.POSTER);
        assertThat(identity.anonymized()).isTrue();
    }

    @Test
    @DisplayName("退会済みユーザー: authorId=0 はセンチネル値として「退会済みユーザー」ラベル")
    void anonymizedAuthorWithZeroId_returnsWithdrawnLabel() {
        PostAuthor withdrawnAuthor = new PostAuthor(0L, null, null, null, null, false);
        ViewerContext memberViewer = new ViewerContext(
                51L, ViewerStatus.MEMBER, Set.of(100L), Set.of());

        DisplayIdentity identity = resolver.resolveIdentityForViewer(
                withdrawnAuthor, memberViewer, TEAM_SCOPE, DISPLAY_NAME_MODE);

        assertThat(identity.displayLabel()).isEqualTo("退会済みユーザー");
        assertThat(identity.anonymized()).isTrue();
        // 退会済みユーザーのアバターは汎用アバター
        assertThat(identity.avatarUrl()).isEqualTo(DisplayIdentity.ANONYMOUS_AVATAR_URL);
    }

    @Test
    @DisplayName("退会済みユーザー: SYSTEM_ADMIN が閲覧しても「退会済みユーザー」（MINOR より優先）")
    void anonymizedAuthor_systemAdmin_returnsWithdrawnLabel() {
        PostAuthor withdrawnAuthor = PostAuthor.ofPhase1(null, null, null, null);
        ViewerContext adminViewer = new ViewerContext(
                1L, ViewerStatus.SYSTEM_ADMIN, Set.of(), Set.of());

        DisplayIdentity identity = resolver.resolveIdentityForViewer(
                withdrawnAuthor, adminViewer, TEAM_SCOPE, DISPLAY_NAME_MODE);

        assertThat(identity.displayLabel()).isEqualTo("退会済みユーザー");
        assertThat(identity.anonymized()).isTrue();
    }

    @Test
    @DisplayName("display_name が空文字 (blank=false) → §4.6.4 フォールバック『匿名のユーザー#XXXXX』")
    void displayNameEmpty_fallsBackToAnonymousHash() {
        PostAuthor author = PostAuthor.ofPhase1(42L, "", null, null);
        ViewerContext viewer = new ViewerContext(
                51L, ViewerStatus.MEMBER, Set.of(100L), Set.of());

        DisplayIdentity identity = resolver.resolveIdentityForViewer(
                author, viewer, TEAM_SCOPE, DISPLAY_NAME_MODE);

        // fullName=null, snapshot=null → display_name "" → フォールバック
        assertThat(identity.displayLabel()).startsWith("匿名のユーザー#");
        assertThat(identity.displayLabel().substring("匿名のユーザー#".length())).hasSize(5);
    }

    @Test
    @DisplayName("組織スコープ（ScopeRef.ofOrganization）でも同じ開示ルールが適用される")
    void organizationScope_sameBehaviorAsTeamScope() {
        ScopeRef orgScope = ScopeRef.ofOrganization(200L);
        ViewerContext viewer = ViewerContext.anonymous();

        DisplayIdentity identity = resolver.resolveIdentityForViewer(
                SAMPLE_AUTHOR, viewer, orgScope, DISPLAY_NAME_MODE);

        assertThat(identity.displayLabel()).isEqualTo(AnonymousLabels.POSTER);
        assertThat(identity.anonymized()).isTrue();
    }

    @Test
    @DisplayName("resolveIdentityForViewer: author=null → NullPointerException")
    void nullAuthor_throwsNullPointerException() {
        ViewerContext viewer = ViewerContext.anonymous();

        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class, () ->
                resolver.resolveIdentityForViewer(null, viewer, TEAM_SCOPE, DISPLAY_NAME_MODE));
    }

    @Test
    @DisplayName("resolveIdentityForViewer: viewer=null → NullPointerException")
    void nullViewer_throwsNullPointerException() {
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class, () ->
                resolver.resolveIdentityForViewer(SAMPLE_AUTHOR, null, TEAM_SCOPE, DISPLAY_NAME_MODE));
    }

}
