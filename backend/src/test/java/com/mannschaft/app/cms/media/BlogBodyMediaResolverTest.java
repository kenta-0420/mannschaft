package com.mannschaft.app.cms.media;

import com.mannschaft.app.cms.entity.BlogMediaUploadEntity;
import com.mannschaft.app.cms.repository.BlogMediaUploadRepository;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.common.storage.quota.StorageScopeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link BlogBodyMediaResolver} の純ユニットテスト（Mockito・Docker 不要）。
 *
 * <p>受け入れ条件 AC-B2 / AC-B4 と、<b>越境認可</b>・性能・空/境界の攻め口に対応する。</p>
 *
 * <p><b>越境認可がなぜ最重要か</b>: 案A（本文中の文字列を拾って presign する）は、素直に作ると
 * 「本文に他チームの r2Key を手書きするだけで他人の画像の署名 URL が発行される」穴が開く。
 * 本テストは <b>プレフィックス照合 + 台帳照合の二段</b>を実装契約として固定する。</p>
 *
 * <p>本番未稼働のため旧形式（絶対URL・先頭スラッシュ）の後方互換は対象外。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BlogBodyMediaResolver — 記事本文の r2Key を署名URLへ解決する")
class BlogBodyMediaResolverTest {

    @Mock
    private MediaUrlResolver mediaUrlResolver;
    @Mock
    private BlogMediaUploadRepository blogMediaUploadRepository;

    @InjectMocks
    private BlogBodyMediaResolver resolver;

    /** 自スコープ（TEAM/12）のキー。 */
    private static final String OWN_IMAGE_KEY = "blog/TEAM/12/aaaaaaaa-1111.png";
    private static final String OWN_VIDEO_KEY = "blog/TEAM/12/bbbbbbbb-2222.mp4";
    /** 他スコープ（TEAM/99）のキー＝本文に手書きされた越境キー。 */
    private static final String FOREIGN_KEY = "blog/TEAM/99/secret-of-another-team.png";

    private static final String SIGNED_IMAGE =
            "https://r2.example.com/bucket/blog/TEAM/12/aaaaaaaa-1111.png?X-Amz-Signature=img";
    private static final String SIGNED_VIDEO =
            "https://r2.example.com/bucket/blog/TEAM/12/bbbbbbbb-2222.mp4?X-Amz-Signature=vid";

    /**
     * 台帳（blog_media_uploads）に指定キーが実在する、という既定スタブ。
     * 問い合わせられたキーのうち、登録済みとして扱うものだけを返す。
     */
    private void stubLedgerContains(String... registeredKeys) {
        List<String> registered = List.of(registeredKeys);
        lenient().when(blogMediaUploadRepository.findByS3KeyIn(any())).thenAnswer(inv -> {
            Collection<String> asked = inv.getArgument(0);
            if (asked == null) {
                return List.of();
            }
            return asked.stream()
                    .filter(registered::contains)
                    .map(k -> BlogMediaUploadEntity.builder().s3Key(k).build())
                    .collect(Collectors.toList());
        });
    }

    /** resolveAll のモック既定動作: 渡されたキーを "signed::&lt;key&gt;" へ解決する。 */
    private void stubResolveAllEcho() {
        lenient().when(mediaUrlResolver.resolveAll(any())).thenAnswer(inv -> {
            Collection<String> keys = inv.getArgument(0);
            Map<String, String> out = new LinkedHashMap<>();
            if (keys != null) {
                for (String k : keys) {
                    out.put(k, "signed::" + k);
                }
            }
            return out;
        });
    }

    /** presign 対象として実際に渡されたキー集合を捕捉する。 */
    private Collection<String> capturePresignedKeys() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(mediaUrlResolver).resolveAll(captor.capture());
        return captor.getValue();
    }

    // ========================================
    // AC-B2: 取得時に署名URLへ解決される
    // ========================================

    @Nested
    @DisplayName("AC-B2: 本文中の r2Key が署名URLへ解決される")
    class ResolveBody {

        @Test
        @DisplayName("AC-B2-1: 画像記法 ![alt](blog/...) が署名URLへ置換される")
        void 画像のr2Keyが署名URLへ置換される() {
            stubLedgerContains(OWN_IMAGE_KEY);
            given(mediaUrlResolver.resolveAll(any()))
                    .willReturn(Map.of(OWN_IMAGE_KEY, SIGNED_IMAGE));

            String body = "冒頭の文章\n\n![写真](" + OWN_IMAGE_KEY + ")\n\n末尾の文章";

            String result = resolver.resolveBody(body, StorageScopeType.TEAM, 12L);

            assertThat(result)
                    .as("生キーは署名URLへ置換されていること")
                    .contains(SIGNED_IMAGE)
                    .doesNotContain("(" + OWN_IMAGE_KEY + ")");
            assertThat(result)
                    .as("画像以外の本文は保持されること")
                    .contains("冒頭の文章")
                    .contains("末尾の文章");
        }

        @Test
        @DisplayName("AC-B2-2: 動画記法 <video src=\"blog/...\"> が署名URLへ置換される")
        void 動画のr2Keyが署名URLへ置換される() {
            stubLedgerContains(OWN_VIDEO_KEY);
            given(mediaUrlResolver.resolveAll(any()))
                    .willReturn(Map.of(OWN_VIDEO_KEY, SIGNED_VIDEO));

            String body = "<video src=\"" + OWN_VIDEO_KEY + "\" controls></video>";

            String result = resolver.resolveBody(body, StorageScopeType.TEAM, 12L);

            assertThat(result)
                    .as("動画の生キーも署名URLへ置換されていること")
                    .contains(SIGNED_VIDEO)
                    .doesNotContain("\"" + OWN_VIDEO_KEY + "\"");
        }

        @Test
        @DisplayName("AC-B2-3: 画像と動画が混在する本文で両方とも解決される")
        void 画像と動画が混在しても両方解決される() {
            stubLedgerContains(OWN_IMAGE_KEY, OWN_VIDEO_KEY);
            given(mediaUrlResolver.resolveAll(any()))
                    .willReturn(Map.of(OWN_IMAGE_KEY, SIGNED_IMAGE, OWN_VIDEO_KEY, SIGNED_VIDEO));

            String body = "![写真](" + OWN_IMAGE_KEY + ")\n"
                    + "<video src=\"" + OWN_VIDEO_KEY + "\" controls></video>";

            String result = resolver.resolveBody(body, StorageScopeType.TEAM, 12L);

            assertThat(result).contains(SIGNED_IMAGE).contains(SIGNED_VIDEO);
        }

        @Test
        @DisplayName("AC-B2-4: ORGANIZATION スコープの投稿でも解決される")
        void 組織スコープでも解決される() {
            String orgKey = "blog/ORGANIZATION/7/cccccccc-3333.png";
            stubLedgerContains(orgKey);
            stubResolveAllEcho();

            String result = resolver.resolveBody(
                    "![図](" + orgKey + ")", StorageScopeType.ORGANIZATION, 7L);

            assertThat(result).contains("signed::" + orgKey);
        }

        @Test
        @DisplayName("AC-B2-5: PERSONAL スコープ（個人ブログ）の投稿でも解決される")
        void 個人スコープでも解決される() {
            String personalKey = "blog/PERSONAL/42/dddddddd-4444.png";
            stubLedgerContains(personalKey);
            stubResolveAllEcho();

            String result = resolver.resolveBody(
                    "![図](" + personalKey + ")", StorageScopeType.PERSONAL, 42L);

            assertThat(result).contains("signed::" + personalKey);
        }

        @Test
        @DisplayName("AC-B2-6: 抽出は出現順・重複排除されたキー一覧を返す")
        void extractR2Keysは出現順で重複排除される() {
            String body = "![a](" + OWN_IMAGE_KEY + ")\n"
                    + "![a再掲](" + OWN_IMAGE_KEY + ")\n"
                    + "<video src=\"" + OWN_VIDEO_KEY + "\"></video>";

            assertThat(resolver.extractR2Keys(body))
                    .as("重複を排除し出現順で返すこと")
                    .containsExactly(OWN_IMAGE_KEY, OWN_VIDEO_KEY);
        }
    }

    // ========================================
    // 越境認可 関門1: プレフィックス照合
    // ========================================

    @Nested
    @DisplayName("越境認可 関門1: 投稿スコープ外の r2Key は presign しない")
    class ScopePrefixGate {

        @Test
        @DisplayName("AUTHZ-1: 他チームのキーは presign 対象へ渡さず、自スコープのキーのみ解決する")
        void 他スコープのキーは署名URLを発行しない() {
            stubLedgerContains(OWN_IMAGE_KEY, FOREIGN_KEY); // 台帳には両方実在させる（関門1単独の検証）
            stubResolveAllEcho();

            // 攻撃者が自分の記事（TEAM/12）の本文に、他チーム（TEAM/99）のキーを手書きした状況
            String body = "![自分の画像](" + OWN_IMAGE_KEY + ")\n"
                    + "![盗み見狙い](" + FOREIGN_KEY + ")";

            String result = resolver.resolveBody(body, StorageScopeType.TEAM, 12L);

            assertThat(capturePresignedKeys())
                    .as("他スコープのキーを presign 対象に含めてはならない（情報漏洩）")
                    .doesNotContain(FOREIGN_KEY)
                    .as("自スコープのキーは presign 対象に含めること")
                    .contains(OWN_IMAGE_KEY);

            assertThat(result)
                    .as("他スコープのキーに対する署名URLが本文へ現れてはならない")
                    .doesNotContain("signed::" + FOREIGN_KEY);
            assertThat(result)
                    .as("自スコープの画像は解決されること")
                    .contains("signed::" + OWN_IMAGE_KEY);
        }

        @Test
        @DisplayName("AUTHZ-2: スコープ種別違い（TEAM/12 の投稿に ORGANIZATION/12 のキー）も拒否する")
        void スコープ種別が違うキーは拒否される() {
            String orgKey = "blog/ORGANIZATION/12/other-domain.png";
            stubLedgerContains(OWN_IMAGE_KEY, orgKey);
            stubResolveAllEcho();

            String body = "![自分](" + OWN_IMAGE_KEY + ")\n![越境](" + orgKey + ")";

            String result = resolver.resolveBody(body, StorageScopeType.TEAM, 12L);

            assertThat(capturePresignedKeys())
                    .as("scopeId が同じでも scopeType が違えば別スコープ")
                    .doesNotContain(orgKey)
                    .contains(OWN_IMAGE_KEY);
            assertThat(result).doesNotContain("signed::" + orgKey);
        }

        @Test
        @DisplayName("AUTHZ-3: パストラバーサル風のキー（blog/TEAM/12/../99/x.png）を拒否する")
        void パストラバーサル風のキーは拒否される() {
            String traversalKey = "blog/TEAM/12/../99/x.png";
            stubLedgerContains(OWN_IMAGE_KEY, traversalKey);
            stubResolveAllEcho();

            String body = "![自分](" + OWN_IMAGE_KEY + ")\n![traversal](" + traversalKey + ")";

            String result = resolver.resolveBody(body, StorageScopeType.TEAM, 12L);

            assertThat(capturePresignedKeys())
                    .as("プレフィックス一致だけで通すと ../ で越境できる。正規化して拒否すること")
                    .doesNotContain(traversalKey)
                    .contains(OWN_IMAGE_KEY);
            assertThat(result).doesNotContain("signed::" + traversalKey);
        }

        @Test
        @DisplayName("AUTHZ-4: scopeId の前方一致誤判定（TEAM/12 の投稿に TEAM/123 のキー）を拒否する")
        void scopeIdの前方一致では通さない() {
            String siblingKey = "blog/TEAM/123/neighbour.png";
            stubLedgerContains(OWN_IMAGE_KEY, siblingKey);
            stubResolveAllEcho();

            String body = "![自分](" + OWN_IMAGE_KEY + ")\n![隣](" + siblingKey + ")";

            String result = resolver.resolveBody(body, StorageScopeType.TEAM, 12L);

            assertThat(capturePresignedKeys())
                    .as("\"blog/TEAM/12\" の前方一致で判定すると TEAM/123 が通ってしまう")
                    .doesNotContain(siblingKey)
                    .contains(OWN_IMAGE_KEY);
            assertThat(result).doesNotContain("signed::" + siblingKey);
        }

        @Test
        @DisplayName("AUTHZ-4b: パーセントエンコードされたトラバーサル（%2e%2e）を拒否する")
        void パーセントエンコードのトラバーサルは拒否される() {
            // ".." の文字列完全一致チェックだけでは "%2e%2e" はデコードされず素通りしてしまう。
            String percentEncodedTraversalKey = "blog/TEAM/12/%2e%2e/%2e%2e/TEAM/99/x.png";
            stubLedgerContains(OWN_IMAGE_KEY, percentEncodedTraversalKey);
            stubResolveAllEcho();

            String body = "![自分](" + OWN_IMAGE_KEY + ")\n![encoded traversal](" + percentEncodedTraversalKey + ")";

            String result = resolver.resolveBody(body, StorageScopeType.TEAM, 12L);

            assertThat(capturePresignedKeys())
                    .as("%2e%2e はデコードせず、正規形でない（% を含む）キーとして一律拒否すること")
                    .doesNotContain(percentEncodedTraversalKey)
                    .contains(OWN_IMAGE_KEY);
            assertThat(result).doesNotContain("signed::" + percentEncodedTraversalKey);
        }

        @Test
        @DisplayName("AUTHZ-5: blog/ 以外のプレフィックス（他機能のキー）を拒否する")
        void 他機能のキーは拒否される() {
            // 掲示板添付やチームアイコンなど、blog 以外のストレージ領域を狙う手書き
            String otherFeatureKey = "team/12/icon/secret.png";
            stubLedgerContains(OWN_IMAGE_KEY);
            stubResolveAllEcho();

            String body = "![自分](" + OWN_IMAGE_KEY + ")\n![他機能](" + otherFeatureKey + ")";

            String result = resolver.resolveBody(body, StorageScopeType.TEAM, 12L);

            assertThat(capturePresignedKeys())
                    .as("blog 配下以外のキーは本部品の責務外。presign してはならない")
                    .doesNotContain(otherFeatureKey)
                    .contains(OWN_IMAGE_KEY);
            assertThat(result).doesNotContain("signed::" + otherFeatureKey);
        }
    }

    // ========================================
    // 越境認可 関門2: 台帳（blog_media_uploads）照合
    // ========================================

    @Nested
    @DisplayName("越境認可 関門2: blog_media_uploads に実在しない r2Key は presign しない")
    class LedgerGate {

        @Test
        @DisplayName("AUTHZ-6: 自スコープのプレフィックスでも台帳に無いキーは presign しない")
        void 台帳に無いキーは署名URLを発行しない() {
            // プレフィックスは自スコープに一致するが、アップロード経路を通っていない捏造キー
            String fabricatedKey = "blog/TEAM/12/guessed-by-attacker.png";
            stubLedgerContains(OWN_IMAGE_KEY); // fabricatedKey は台帳に無い
            stubResolveAllEcho();

            String body = "![自分](" + OWN_IMAGE_KEY + ")\n![捏造](" + fabricatedKey + ")";

            String result = resolver.resolveBody(body, StorageScopeType.TEAM, 12L);

            assertThat(capturePresignedKeys())
                    .as("台帳に実在しないキーは presign 対象へ含めてはならない")
                    .doesNotContain(fabricatedKey)
                    .contains(OWN_IMAGE_KEY);
            assertThat(result).doesNotContain("signed::" + fabricatedKey);
        }

        @Test
        @DisplayName("AUTHZ-7: 台帳照合は本文1件につき1クエリ（キーごとのループ照会をしない）")
        void 台帳照合は一括クエリで行う() {
            stubLedgerContains(OWN_IMAGE_KEY, OWN_VIDEO_KEY);
            stubResolveAllEcho();

            String body = IntStream.range(0, 30)
                    .mapToObj(i -> "![img" + i + "](blog/TEAM/12/img-" + i + ".png)")
                    .collect(Collectors.joining("\n"));

            resolver.resolveBody(body, StorageScopeType.TEAM, 12L);

            verify(blogMediaUploadRepository, times(1)).findByS3KeyIn(any());
            verify(blogMediaUploadRepository, never()).findByS3Key(anyString());
        }

        @Test
        @DisplayName("AUTHZ-8: 台帳照会にはスコープ関門を通過したキーのみを渡す（越境キーを問い合わせない）")
        void 台帳照会には自スコープのキーのみ渡す() {
            stubLedgerContains(OWN_IMAGE_KEY);
            stubResolveAllEcho();

            String body = "![自分](" + OWN_IMAGE_KEY + ")\n![越境](" + FOREIGN_KEY + ")";

            resolver.resolveBody(body, StorageScopeType.TEAM, 12L);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass(Collection.class);
            verify(blogMediaUploadRepository).findByS3KeyIn(captor.capture());

            assertThat(captor.getValue())
                    .as("スコープ関門で落ちたキーは台帳照会にも載せない（安価な関門を先に通す）")
                    .doesNotContain(FOREIGN_KEY)
                    .contains(OWN_IMAGE_KEY);
        }

        @Test
        @DisplayName("AUTHZ-9: 台帳照会が例外を投げた場合は fail-closed（presign しない）")
        void 台帳照会失敗時はfailClosedで解決しない() {
            given(blogMediaUploadRepository.findByS3KeyIn(any()))
                    .willThrow(new RuntimeException("DB 障害"));

            String body = "![自分](" + OWN_IMAGE_KEY + ")";

            String[] result = new String[1];
            assertThatCode(() -> result[0] = resolver.resolveBody(body, StorageScopeType.TEAM, 12L))
                    .as("台帳照会の失敗で記事取得 API を 500 にしてはならない")
                    .doesNotThrowAnyException();

            verify(mediaUrlResolver, never()).resolveAll(any());
            assertThat(result[0])
                    .as("検証できない以上 presign しない（fail-closed）。本文は失わない")
                    .contains(OWN_IMAGE_KEY);
        }
    }

    // ========================================
    // AC-B4: 解決失敗しても 500 にしない（null 縮退）
    // ========================================

    @Nested
    @DisplayName("AC-B4: 解決失敗しても記事取得は 500 にならない")
    class FailureDegradation {

        @Test
        @DisplayName("AC-B4-1: 一部の画像が解決できなくても例外を投げず、解決できた分は置換する")
        void 一部解決失敗でも例外を投げない() {
            String brokenKey = "blog/TEAM/12/dddddddd-4444.png";
            stubLedgerContains(OWN_IMAGE_KEY, brokenKey);
            // resolveAll は解決できたものだけを返す（MediaUrlResolver の契約）
            given(mediaUrlResolver.resolveAll(any()))
                    .willReturn(Map.of(OWN_IMAGE_KEY, SIGNED_IMAGE));

            String body = "![ok](" + OWN_IMAGE_KEY + ")\n![壊れ](" + brokenKey + ")";

            String[] result = new String[1];
            assertThatCode(() -> result[0] = resolver.resolveBody(body, StorageScopeType.TEAM, 12L))
                    .as("画像1枚の解決失敗で API 全体を落としてはならない")
                    .doesNotThrowAnyException();

            assertThat(result[0])
                    .as("解決できた画像は署名URLへ置換されること")
                    .contains(SIGNED_IMAGE);
            assertThat(result[0])
                    .as("解決できなかったキーは本文中にそのまま残すこと（黙って消さない）")
                    .contains(brokenKey);
        }

        @Test
        @DisplayName("AC-B4-2: resolveAll が例外を投げても伝播させず、本文を素通しして返す")
        void resolveAllが例外でも伝播させない() {
            stubLedgerContains(OWN_IMAGE_KEY);
            given(mediaUrlResolver.resolveAll(any()))
                    .willThrow(new RuntimeException("R2 presign 全滅"));

            String body = "![ok](" + OWN_IMAGE_KEY + ")";

            String[] result = new String[1];
            assertThatCode(() -> result[0] = resolver.resolveBody(body, StorageScopeType.TEAM, 12L))
                    .as("presign 基盤の障害で記事取得 API を 500 にしてはならない")
                    .doesNotThrowAnyException();

            assertThat(result[0])
                    .as("縮退時も本文自体は失わないこと")
                    .contains(OWN_IMAGE_KEY);
        }
    }

    // ========================================
    // 空 / null / 境界値
    // ========================================

    @Nested
    @DisplayName("空・null・境界値")
    class EdgeCases {

        @Test
        @DisplayName("EDGE-1: 本文が null なら null を返し presign を呼ばない")
        void 本文がnullならnullを返す() {
            assertThat(resolver.resolveBody(null, StorageScopeType.TEAM, 12L)).isNull();
            verify(mediaUrlResolver, never()).resolveAll(any());
            verify(blogMediaUploadRepository, never()).findByS3KeyIn(any());
        }

        @Test
        @DisplayName("EDGE-2: 本文が空文字ならそのまま返し presign を呼ばない")
        void 本文が空文字ならpresignを呼ばない() {
            assertThat(resolver.resolveBody("", StorageScopeType.TEAM, 12L)).isEmpty();
            verify(mediaUrlResolver, never()).resolveAll(any());
            verify(blogMediaUploadRepository, never()).findByS3KeyIn(any());
        }

        @Test
        @DisplayName("EDGE-3: 画像が1枚も無い本文では台帳照会も presign も一切呼ばない")
        void 画像が無ければpresignを呼ばない() {
            String body = "# 見出し\n\nただのテキストです。外部リンク http://example.com もある。";

            String result = resolver.resolveBody(body, StorageScopeType.TEAM, 12L);

            assertThat(result).isEqualTo(body);
            verify(mediaUrlResolver, never()).resolveAll(any());
            verify(blogMediaUploadRepository, never()).findByS3KeyIn(any());
        }

        @Test
        @DisplayName("EDGE-4: 外部URL画像（https://...）は R2 キーではないので presign 対象外")
        void 外部URL画像はpresign対象外() {
            String body = "![外部](https://example.com/photo.png)";

            String result = resolver.resolveBody(body, StorageScopeType.TEAM, 12L);

            assertThat(result).isEqualTo(body);
            verify(mediaUrlResolver, never()).resolveAll(any());
        }

        @Test
        @DisplayName("EDGE-5: extractR2Keys(null/空) は空リストを返す")
        void extractR2Keysのnull安全() {
            assertThat(resolver.extractR2Keys(null)).isEmpty();
            assertThat(resolver.extractR2Keys("")).isEmpty();
        }

        @Test
        @DisplayName("EDGE-6: 画像30枚ちょうど（1記事あたりの上限）でも全て解決される")
        void 画像30枚ちょうどでも全て解決される() {
            String[] keys = IntStream.range(0, 30)
                    .mapToObj(i -> "blog/TEAM/12/img-" + i + ".png")
                    .toArray(String[]::new);
            stubLedgerContains(keys);
            stubResolveAllEcho();

            String body = IntStream.range(0, 30)
                    .mapToObj(i -> "![img" + i + "](blog/TEAM/12/img-" + i + ".png)")
                    .collect(Collectors.joining("\n"));

            String result = resolver.resolveBody(body, StorageScopeType.TEAM, 12L);

            for (int i = 0; i < 30; i++) {
                assertThat(result)
                        .as("30枚目まで漏れなく解決されること: index=" + i)
                        .contains("signed::blog/TEAM/12/img-" + i + ".png");
            }
        }
    }

    // ========================================
    // 性能: presign の N+1 を起こさない
    // ========================================

    @Nested
    @DisplayName("性能: presign をループ呼びしない")
    class Performance {

        @Test
        @DisplayName("PERF-1: 30枚の画像を含む本文でも resolveAll は1回だけ・resolve のループ呼びをしない")
        void 三十枚でもresolveAllは一回だけ() {
            String[] keys = IntStream.range(0, 30)
                    .mapToObj(i -> "blog/TEAM/12/img-" + i + ".png")
                    .toArray(String[]::new);
            stubLedgerContains(keys);
            stubResolveAllEcho();

            String body = IntStream.range(0, 30)
                    .mapToObj(i -> "![img" + i + "](blog/TEAM/12/img-" + i + ".png)")
                    .collect(Collectors.joining("\n"));

            resolver.resolveBody(body, StorageScopeType.TEAM, 12L);

            verify(mediaUrlResolver, times(1)).resolveAll(any());
            verify(mediaUrlResolver, never()).resolve(anyString());
        }

        @Test
        @DisplayName("PERF-2: 同一キーが本文に複数回出ても presign 対象は1件に重複排除される")
        void 同一キーの重複はpresign対象で排除される() {
            stubLedgerContains(OWN_IMAGE_KEY);
            stubResolveAllEcho();

            String body = IntStream.range(0, 10)
                    .mapToObj(i -> "![同じ画像](" + OWN_IMAGE_KEY + ")")
                    .collect(Collectors.joining("\n"));

            String result = resolver.resolveBody(body, StorageScopeType.TEAM, 12L);

            assertThat(capturePresignedKeys())
                    .as("同一キーは1件へ重複排除して渡すこと")
                    .containsExactly(OWN_IMAGE_KEY);
            assertThat(result)
                    .as("出現箇所は全て置換されること")
                    .doesNotContain("(" + OWN_IMAGE_KEY + ")");
        }
    }
}
