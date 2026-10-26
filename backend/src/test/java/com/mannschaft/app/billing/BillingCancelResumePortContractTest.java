package com.mannschaft.app.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Billing Center PR6a — ポート署名（AC-39 / AC-47）とエンドポイント本数（D6・AC-22/AC-40 の入口）の
 * 受け入れテスト（試練・red）。
 *
 * <h2>なぜ reflection とソース走査なのか</h2>
 * <p>AC-39/AC-47 が要求するのは「{@link BillingPaymentGateway} に<b>メソッドを追加する</b>」ことである。
 * 実装前の今は存在しないメソッドを Java コードから直接呼べない（テストソースがコンパイルできず、
 * 赤の理由を観測できなくなる）。{@link Class#getMethod} で署名の存在を問えば、コンパイルは通り、
 * <b>実行されて「そのメソッドが無い」</b>という狙った赤になる。</p>
 *
 * <p>第2隊（試練A）が AC-5 の発注書として置いた {@code default} 実装は
 * {@link UnsupportedOperationException} を投げる空箱なので、署名の存在だけでは「宣言はあるが呼べば落ちる」
 * 空虚な緑になる。実体（Stripe 実装側の override）の有無を別テストで対にして測る。</p>
 *
 * <p>エンドポイント本数（D6）は Java の型に現れない「文字列としての契約」なので
 * 本体ソースを実読して照合する（{@link BillingContractOperationEntityDdlAlignmentTest} と同じ流儀）。</p>
 *
 * <p>本テストは Docker も Spring context も要らない純粋な単体テストであり、CI でもローカルでも
 * 必ず実行される（skip されて緑に見える IT だけに頼らないための足場でもある）。</p>
 */
@DisplayName("PR6a 解約/撤回のポート署名とエンドポイント本数（AC-39/47・D6・試練 red）")
class BillingCancelResumePortContractTest {

    private static final Path BILLING_SRC = Paths.get("src/main/java/com/mannschaft/app/billing");
    private static final Path GATEWAY_IMPL =
            BILLING_SRC.resolve("StripeBillingPaymentGateway.java");

    /** 解約 / 撤回の唯一のパス（D6・正本 05:334-335）。 */
    private static final String CANCEL_PATH = "/me/billing/contracts/{contractId}/cancel";

    // ============================================================
    // AC-39: cancelAtPeriodEnd に operationId を渡す形を追加する
    // ============================================================

    @Test
    @DisplayName("AC-39: BillingPaymentGatewayにcancelAtPeriodEnd(ref, operationId)が宣言されている")
    void AC39_operationId付きのcancelAtPeriodEndが宣言されている() {
        Method added = findPortMethod("cancelAtPeriodEnd", String.class, UUID.class);

        assertThat(added).as("AC-39: operationId を渡す形のポートが無い").isNotNull();
        assertThat(added.getReturnType())
                .as("期末（current_period_end）を返す点は既存と同じ").isEqualTo(Instant.class);
    }

    /**
     * <p><b>空虚な緑を排除する</b>: 第2隊（試練A）が発注書として置いた default 実装は
     * {@link UnsupportedOperationException} を投げるだけの空箱である。署名の存在だけを問うと
     * 「宣言はあるが呼べば必ず落ちる」状態で緑になるため、<b>Stripe 実装側の override</b> があることを
     * 別のテストで測る。これが緑になるのは第6隊が実体を書いたときだけである。</p>
     */
    @Test
    @DisplayName("AC-39: StripeBillingPaymentGatewayがcancelAtPeriodEnd(ref, operationId)をoverrideしている（default空箱のままにしない）")
    void AC39_gateway実装がoperationId版をoverrideしている() {
        assertThat(findDeclaredImplMethod("cancelAtPeriodEnd", String.class, UUID.class))
                .as("default 実装は UnsupportedOperationException を投げる空箱。実体が必要")
                .isNotNull();
    }

    @Test
    @DisplayName("AC-39: 既存のcancelAtPeriodEnd(ref)は残り既存呼び出し元の挙動を変えない")
    void AC39_既存の1引数版は残る() {
        assertThat(findPortMethod("cancelAtPeriodEnd", String.class))
                .as("既存呼び出し元（BillingContractService:729）の署名を壊さない").isNotNull();
        assertThat(findDeclaredImplMethod("cancelAtPeriodEnd", String.class))
                .as("既存実装もそのまま残る").isNotNull();
    }

    // ============================================================
    // AC-47: 撤回は billing ポート経由・引継専用キーを使わない
    // ============================================================

    @Test
    @DisplayName("AC-47: BillingPaymentGatewayにrevertCancelAtPeriodEnd(ref, operationId)が宣言されている")
    void AC47_撤回のポートが存在する() {
        assertThat(findPortMethod("revertCancelAtPeriodEnd", String.class, UUID.class))
                .as("AC-47: 撤回を billing ポートに置く（payment の provider を直接参照しない）")
                .isNotNull();
    }

    @Test
    @DisplayName("AC-47: StripeBillingPaymentGatewayがrevertCancelAtPeriodEnd(ref, operationId)をoverrideしている")
    void AC47_gateway実装が撤回をoverrideしている() {
        assertThat(findDeclaredImplMethod("revertCancelAtPeriodEnd", String.class, UUID.class))
                .as("宣言だけで実体が無いと撤回は呼べない").isNotNull();
    }

    @Test
    @DisplayName("AC-47: 撤回の実体は既存のrevertSubscriptionCancelAtPeriodEndを再利用する（自前実装しない）")
    void AC47_既存provider実体を再利用する() {
        assertThat(read(GATEWAY_IMPL))
                .as("payment/stripe/StripePaymentProviderImpl:1410-1429 の再利用")
                .contains("revertSubscriptionCancelAtPeriodEnd");
    }

    @Test
    @DisplayName("AC-47: 引継専用キーbilling-handover-revert-cancel-は通常の撤回に流用しない")
    void AC47_引継専用キーを流用しない() {
        String impl = read(GATEWAY_IMPL);
        int handoverKeyUses = countOccurrences(impl, "KEY_REVERT_CANCEL");

        assertThat(handoverKeyUses)
                .as("引継専用キーの使用箇所は定数定義1回＋引継メソッド1回のみ。"
                        + "通常の撤回がこれを使うと同一 subscription へのキー衝突を招く")
                .isEqualTo(2);
    }

    /**
     * <p><b>これは red ではなく番人</b>である（現時点で緑）。AC-47 の危険は「撤回 Service が手軽さから
     * payment の provider を直接掴む」ことであり、本テストはその1点だけを塞ぐ。
     * billing から {@code StripePaymentProvider} を参照すること自体は既存の webhook 経路
     * （{@code BillingSubscriptionWebhookService} / {@code invoice/StripeBillingObjectView}）で
     * 既に許されているため、参照全体を禁じると既存を理由に落ちる偽の赤になる。</p>
     */
    @Test
    @DisplayName("AC-47: revertSubscriptionCancelAtPeriodEndを呼ぶbillingクラスはgateway実装だけ（ドメイン境界の番人）")
    void AC47_provider直呼びはgateway実装に限る() {
        List<String> offenders = javaSources().stream()
                .filter(p -> !p.getFileName().toString().equals("StripeBillingPaymentGateway.java"))
                .filter(p -> read(p).contains("revertSubscriptionCancelAtPeriodEnd"))
                .map(p -> p.getFileName().toString())
                .toList();

        assertThat(offenders)
                .as("撤回の Stripe 実体はポート経由でしか触らない（AC-47）")
                .isEmpty();
    }

    // ============================================================
    // D6: エンドポイントは2本だけ（AC-22 / AC-40 の入口）
    // ============================================================

    @Test
    @DisplayName("AC-22: POST /me/billing/contracts/{contractId}/cancel が存在する")
    void AC22_解約エンドポイントが存在する() {
        assertThat(allBillingSourceCompact())
                .as("解約の入口が無い（AC-22）")
                .contains(compact("@PostMapping(\"" + CANCEL_PATH + "\")"));
    }

    @Test
    @DisplayName("AC-40: DELETE /me/billing/contracts/{contractId}/cancel が存在する")
    void AC40_撤回エンドポイントが存在する() {
        assertThat(allBillingSourceCompact())
                .as("撤回の入口が無い（AC-40）")
                .contains(compact("@DeleteMapping(\"" + CANCEL_PATH + "\")"));
    }

    @Test
    @DisplayName("D6: cancel はスコープ別に増やさない（teams/organizations 配下の cancel を作らない）")
    void D6_cancelはme配下の2本だけ() {
        String compact = allBillingSourceCompact();

        assertThat(compact)
                .as("正本 05:334-335 が定めるのは /me の2本だけ。既存 controller の3スコープは旧 API である")
                .doesNotContain(compact("/teams/{teamId}/billing/contracts/{contractId}/cancel"))
                .doesNotContain(compact("/organizations/{orgId}/billing/contracts/{contractId}/cancel"));
    }

    // ============================================================
    // ヘルパ
    // ============================================================

    private static Method findPortMethod(String name, Class<?>... params) {
        try {
            return BillingPaymentGateway.class.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /** Stripe 実装側が<b>自分で宣言している</b>（＝default 空箱を override している）か。 */
    private static Method findDeclaredImplMethod(String name, Class<?>... params) {
        try {
            return StripeBillingPaymentGateway.class.getDeclaredMethod(name, params);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("ソースが読めない: " + path, e);
        }
    }

    private static List<Path> javaSources() {
        try (Stream<Path> stream = Files.walk(BILLING_SRC)) {
            return stream.filter(p -> p.getFileName().toString().endsWith(".java")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("billing ソースツリーが読めない: " + BILLING_SRC, e);
        }
    }

    /** 空白差で判定がぶれないよう、全ソースから空白類を落として連結する。 */
    private static String allBillingSourceCompact() {
        StringBuilder sb = new StringBuilder();
        for (Path p : javaSources()) {
            sb.append(compact(read(p))).append('\n');
        }
        return sb.toString();
    }

    /** 空白・改行を除去する（走査正規表現に空白入り文字クラスを持ち込まないため単純置換で行う）。 */
    private static String compact(String source) {
        StringBuilder sb = new StringBuilder(source.length());
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (!Character.isWhitespace(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int idx = haystack.indexOf(needle, from);
            if (idx < 0) {
                return count;
            }
            count++;
            from = idx + needle.length();
        }
    }
}
