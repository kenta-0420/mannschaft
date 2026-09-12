package com.mannschaft.app.billing.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR5 Billing Center: Stripe Customer Portal セッション発行の <b>構造</b>受け入れ条件を先行固定する試練テスト。
 *
 * <p>正本: {@code docs/features/F20.1_entitlement_billing/05_billing_center.md} :336 / :348 / :350 / :370。
 * 対象は AC-64（configuration ID を環境変数から固定）、AC-65（起動時照合と fail-closed 503）、
 * AC-66（Portal から PLAN/ADDON を変更・解約できない）、AC-67（return_url は固定）、
 * AC-68（PORTAL_RETURN の exp は issuedAt+30分）、AC-70（同一キー body 相違は Stripe を呼ばず 409）、
 * AC-71（scope ごと 10 回/時）、AC-72（監査に URL を含めない）、AC-73（Stripe 失敗は 502）、
 * AC-74（nonce 登録 → Stripe → URL 返却の順序）。</p>
 *
 * <p><b>なぜ source 走査なのか</b>: これらは「Stripe へ何を渡したか」「起動時に何を照合したか」
 * 「どの順で外部 I/O を挟んだか」という、実 Stripe を叩かずに HTTP 応答だけからは観測できない性質である。
 * 本リポジトリには同種の番人（{@code common/architecture/*GuardTest}）の前例があり、それに倣う。
 * 出陣（実装）時に Stripe gateway のスタブを差した振る舞いテストを足しても本テストは残す
 * （スタブ付きテストは「モックにそう教えたから緑」になりうるが、こちらは実コードの形を測る）。</p>
 */
@DisplayName("PR5 Portal セッション発行の構造 AC（試練）")
class BillingPortalSessionContractTrialTest {

    private static final Path BILLING_ROOT = Paths.get("src/main/java/com/mannschaft/app/billing");

    /** Portal 発行側の実装と見なす目印。PR4 の復帰側（{@code BillingReturnController}）は含まない。 */
    private static final List<String> PORTAL_MARKERS = List.of(
            "portal-sessions",
            "STRIPE_BILLING_PORTAL_CONFIGURATION_ID",
            "com.stripe.param.billingportal",
            "com.stripe.model.billingportal",
            "BILLING_PORTAL_OPENED");

    /** ファイル名 → コメントを除去した本文。 */
    private static Map<String, String> portalSources() {
        Map<String, String> found = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(BILLING_ROOT)) {
            paths.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                String text = stripComments(read(path));
                if (PORTAL_MARKERS.stream().anyMatch(text::contains)) {
                    found.put(path.getFileName().toString(), text);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return found;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Javadoc・行コメントの記述だけで AC を満たしたことにしないため、コメントを落として本文だけを測る。 */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }

    /** Portal 実装の全本文を連結したもの。 */
    private static String allPortalCode() {
        Map<String, String> sources = portalSources();
        assertThat(sources)
                .as("Portal セッション発行の実装ソースが見つからない（%s 配下に %s のいずれかを含むファイルが無い）",
                        BILLING_ROOT, PORTAL_MARKERS)
                .isNotEmpty();
        return String.join("\n", sources.values());
    }

    @Test
    @DisplayName("AC64_configuration ID は環境変数 STRIPE_BILLING_PORTAL_CONFIGURATION_ID から固定で渡す")
    void AC64_configurationIDを環境変数から固定で渡す() {
        String code = allPortalCode();
        assertThat(code)
                .as("環境変数名 STRIPE_BILLING_PORTAL_CONFIGURATION_ID が実装に現れること")
                .contains("STRIPE_BILLING_PORTAL_CONFIGURATION_ID");
        assertThat(code)
                .as("SessionCreateParams へ configuration を渡すこと（Stripe 既定 configuration に落とさない）")
                .containsPattern("\\.configuration\\s*\\(");
    }

    @Test
    @DisplayName("AC65_起動時に configuration を 5 秒 timeout で照合し、不一致・取得不能なら Portal 開始のみ 503 で拒否する")
    void AC65_起動時照合とfailClosed503() {
        String code = allPortalCode();
        assertThat(code)
                .as("起動時 health check の入口（ApplicationReadyEvent / ApplicationRunner / InitializingBean）があること")
                .containsPattern("ApplicationReadyEvent|ApplicationRunner|InitializingBean|@PostConstruct");
        assertThat(code)
                .as("Stripe API の timeout を 5 秒で明示すること")
                .containsPattern("5_?000|Duration\\.ofSeconds\\(\\s*5\\s*\\)");
        assertThat(code)
                .as("照合失敗時は Portal 開始だけを 503 で拒否すること（アプリは起動する＝起動失敗にしない）")
                .contains("503");
        assertThat(code)
                .as("起動時照合の失敗でアプリを落としてはならない（System.exit / ExitCodeGenerator を使わない）")
                .doesNotContain("System.exit");
    }

    @Test
    @DisplayName("AC66_Portal 経由で PLAN/ADDON の変更・解約・停止ができないことを照合する")
    void AC66_subscription系機能の無効を照合する() {
        String code = allPortalCode();
        assertThat(code)
                .as("subscription_update が無効であることを起動時に照合すること")
                .containsPattern("subscription_?[uU]pdate");
        assertThat(code)
                .as("subscription_cancel が無効であることを起動時に照合すること")
                .containsPattern("subscription_?[cC]ancel");
        assertThat(code)
                .as("subscription_pause が無効であることを起動時に照合すること")
                .containsPattern("subscription_?[pP]ause");
    }

    @Test
    @DisplayName("AC67_return_url は固定の /billing/portal/return のみで、任意の return URL を受け取らない")
    void AC67_returnUrlは固定() {
        Map<String, String> sources = portalSources();
        assertThat(sources).as("Portal 実装ソースが存在すること").isNotEmpty();
        String code = String.join("\n", sources.values());
        assertThat(code)
                .as("固定の復帰先 /billing/portal/return を実装が保持すること")
                .contains("/billing/portal/return");
        assertThat(code)
                .as("リクエスト由来の return URL を読んではならない（request.returnUrl 等）")
                .doesNotContainPattern("(?i)request\\s*\\.\\s*returnUrl|returnUrl\\s*\\(\\s*request");
    }

    @Test
    @DisplayName("AC68_PORTAL_RETURN state の exp は issuedAt+30分（Checkout の Session expiry+15分とは別に持つ）")
    void AC68_PORTAL_RETURNのexpは30分() {
        String code = allPortalCode();
        assertThat(code)
                .as("PORTAL_RETURN purpose で state を発行すること")
                .contains("PORTAL_RETURN");
        assertThat(code)
                .as("exp は発行時刻 + 30 分であること")
                .containsPattern("Duration\\.ofMinutes\\(\\s*30\\s*\\)|plusSeconds\\(\\s*1_?800\\s*\\)|1_?800L?");
    }

    @Test
    @DisplayName("AC70_同一 actor・method・path・キーで body が異なる場合は Stripe を呼ぶ前に冪等判定へ入る")
    void AC70_body相違は冪等判定で打ち切る() {
        Map<String, String> sources = portalSources();
        assertThat(sources).as("Portal 実装ソースが存在すること").isNotEmpty();
        String code = String.join("\n", sources.values());
        assertThat(code)
                .as("耐久冪等性（BillingDurableIdempotencyService#begin）を通ること")
                .containsPattern("idempotencyService\\s*\\.\\s*begin|BillingDurableIdempotencyService");
        assertThat(code)
                .as("request hash を actor / method / path / body から取ること")
                .containsPattern("SHA-256|requestHash");

        String service = fileContaining(sources, "billingportal");
        assertThat(indexOfPattern(service, "begin\\s*\\(|idempoten"))
                .as("Stripe 呼び出しより前に冪等判定へ入ること（body 相違で Stripe を一度も呼ばないため）")
                .isLessThan(indexOfPattern(service, "billingportal\\.Session\\.create|Session\\.create\\s*\\("));
    }

    @Test
    @DisplayName("AC71_scope ごと Portal は 10 回/時に制限する")
    void AC71_scopeごと10回毎時() {
        String code = allPortalCode();
        assertThat(code)
                .as("scope ごとの rate limit（10 回/時）が実装に現れること")
                .containsPattern("RateLimit|rateLimiter");
        assertThat(code)
                .as("上限 10 回であること")
                .containsPattern("\\b10\\b");
        assertThat(code)
                .as("窓は 1 時間であること")
                .containsPattern("ofHours\\(\\s*1\\s*\\)|3_?600");
    }

    @Test
    @DisplayName("AC72_監査 BILLING_PORTAL_OPENED を記録し、URL を含めない")
    void AC72_監査にURLを含めない() {
        Map<String, String> sources = portalSources();
        assertThat(sources).as("Portal 実装ソースが存在すること").isNotEmpty();
        String code = String.join("\n", sources.values());
        assertThat(code)
                .as("BILLING_PORTAL_OPENED を監査すること")
                .contains("BILLING_PORTAL_OPENED");

        String auditFile = fileContaining(sources, "BILLING_PORTAL_OPENED");
        int auditAt = indexOfPattern(auditFile, "BILLING_PORTAL_OPENED");
        String auditCall = auditFile.substring(auditAt, Math.min(auditFile.length(), auditAt + 600));
        assertThat(auditCall)
                .as("監査呼び出しに Portal URL を渡してはならない（正本 §370: URL は監査から除外）")
                .doesNotContainPattern("(?i)\\burl\\b|getUrl\\(");
    }

    @Test
    @DisplayName("AC73_Stripe 側の失敗は 502 に写す")
    void AC73_Stripe失敗は502() {
        String code = allPortalCode();
        assertThat(code)
                .as("Stripe 例外を握りつぶさず 502 系の ErrorCode（STRIPE_UNAVAILABLE / CHECKOUT_SESSION_FAILED）へ写すこと")
                .containsPattern("STRIPE_UNAVAILABLE|CHECKOUT_SESSION_FAILED");
        assertThat(code)
                .as("StripeException を捕捉すること")
                .containsPattern("StripeException|catch\\s*\\(\\s*RuntimeException");
    }

    @Test
    @DisplayName("AC74_処理順序は nonce 登録 → Stripe セッション作成 → URL 返却に固定する")
    void AC74_nonce登録がStripe呼び出しより前() {
        Map<String, String> sources = portalSources();
        assertThat(sources).as("Portal 実装ソースが存在すること").isNotEmpty();
        String service = fileContaining(sources, "billingportal");

        int nonceAt = indexOfPattern(service, "returnStateService\\s*\\.\\s*issue|nonceRepository\\s*\\.\\s*register");
        int stripeAt = indexOfPattern(service, "billingportal\\.Session\\.create|Session\\.create\\s*\\(|portalGateway");
        assertThat(nonceAt)
                .as("nonce 登録（return state の issue）が Stripe セッション作成より前にあること。"
                        + "逆順だと『Stripe は成功したが DB が落ちた』窓が構造的に生まれる")
                .isLessThan(stripeAt);

        int returnAt = service.lastIndexOf("return");
        assertThat(stripeAt)
                .as("Stripe セッション作成は URL 返却より前であること")
                .isLessThan(returnAt);
    }

    /** marker を含む最初のファイル本文を返す（無ければ失敗させる）。 */
    private static String fileContaining(Map<String, String> sources, String marker) {
        return sources.entrySet().stream()
                .filter(entry -> entry.getValue().toLowerCase().contains(marker.toLowerCase()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "'" + marker + "' を含む Portal 実装ソースが見つからない: " + sources.keySet()));
    }

    /** 正規表現の最初の出現位置。見つからなければ失敗させる（-1 を返して比較を誤魔化さない）。 */
    private static int indexOfPattern(String text, String regex) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(regex).matcher(text);
        if (!matcher.find()) {
            throw new AssertionError("実装に見つからない: /" + regex + "/");
        }
        return matcher.start();
    }
}
