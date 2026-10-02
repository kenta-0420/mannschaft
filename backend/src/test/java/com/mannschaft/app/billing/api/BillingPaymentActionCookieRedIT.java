package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingContractChangeStatus;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Billing Center PR6b-1 — E3' の別名 cookie と 3DS の戻り（AC-60〜69）の受け入れテスト（試練・red）。
 *
 * <p><b>この一式が測るのは「リダイレクトを伴う 3DS」の経路だけである。</b>
 * リダイレクトを伴わない in-page の 3DS（AC-72）は<b>戻りのエンドポイントを通らない</b>ため、
 * ここで固定する cookie 機構はその経路には一切適用されない（AC-72 は
 * {@code BillingPlanChangeWebhookRedIT} が webhook だけで収束することを測る）。</p>
 *
 * <p>cookie は {@code MockMvc} の cookie マッチャではなく<b>生の {@code Set-Cookie} /
 * {@code Cookie} ヘッダ</b>で測る。名前・Path・Max-Age・HttpOnly は属性文字列そのものが
 * 契約であり、パースを挟むと「名前は同じだが Path が違う」種の欠陥が見えなくなる。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("PR6b-1 payment-action の cookie と戻り（C群 AC-60〜69・試練 red）")
class BillingPaymentActionCookieRedIT extends AbstractBillingPaymentActionApiIT {

    /** 既存 Checkout 退避 cookie の検体（AC-63 の共存検体。署名検証は必ず落ちる不透明値）。 */
    private static final String FOREIGN_CHECKOUT_TOKEN = "kid.foreign-checkout-payload.signature";

    @BeforeEach
    void setUp() {
        seedUpgradableContract("cookie");
        // E1F の耐久 lease（pointer）を1本張っておく。検体の change 用 operation は change() が切る。
        insertPlanChangeOperation();
        stubPaymentAction(Instant.now(clock).plusSeconds(900));
    }

    @AfterEach
    void tearDown() {
        cleanupScope();
    }

    /** 3DS 待ちの change を作る。{@code pendingUpdateExpiresAt} が cookie 期限の根拠（AC-61）。 */
    private UUID change(Instant pendingUpdateExpiresAt) {
        // AC-61 は期限違いの検体を2つ並べる。change と operation は uk_bcc_operation で 1:1 なので
        // 検体ごとに operation を切る（使い回すと 2件目の INSERT が一意制約で落ちる）。
        return insertChange(insertPlanChangeOperationWithoutPointer(),
                BillingContractChangeStatus.REQUIRES_ACTION, userId, pendingUpdateExpiresAt);
    }

    /** payment-action を叩いて発行された生 Set-Cookie 行を得る。 */
    private String issueCookie(UUID changeId) throws Exception {
        MvcResult result = paymentAction(userId, contractId, changeId)
                .andExpect(status().isOk()).andReturn();
        String raw = rawSetCookie(result, PAYMENT_ACTION_COOKIE);
        assertThat(raw).as("200 では payment-action 用 cookie が発行される").isNotNull();
        return raw;
    }

    // ═════════ AC-60: 別名・属性 ═════════

    @Test
    @DisplayName("AC-60: cookie 名は billing_payment_action_state で HttpOnly/Secure/SameSite=Lax/Path=/billing/payment-action/return")
    void AC60_別名cookieの属性が固定される() throws Exception {
        String raw = issueCookie(change(Instant.now(clock).plusSeconds(3_600)));

        assertThat(raw).as("E3': 既存 billing_return_state と同名にしない")
                .startsWith(PAYMENT_ACTION_COOKIE + "=");
        assertThat(raw).containsIgnoringCase("HttpOnly");
        assertThat(raw).containsIgnoringCase("Secure");
        assertThat(raw).contains("SameSite=Lax");
        assertThat(cookieAttribute(raw, "Path"))
                .as("path は戻り口そのものに限定する（/billing では広すぎる）")
                .isEqualTo(PAYMENT_ACTION_COOKIE_PATH);
    }

    // ═════════ AC-61: 期限は min(pending_update.expires_at, now+15分) ═════════

    @Test
    @DisplayName("AC-61: cookie の Max-Age は min(pending_update.expires_at, now+15分)（PaymentIntent の期限ではない）")
    void AC61_cookie期限はminで決まる() throws Exception {
        // (1) pending_update が 5 分後 → 5 分側が採られる。
        String shortLived = issueCookie(change(Instant.now(clock).plusSeconds(300)));
        assertThat(Long.parseLong(cookieAttribute(shortLived, "Max-Age")))
                .as("pending_update が 15 分より近いときはそちらを採る")
                .isBetween(240L, 305L);

        // (2) pending_update が 2 時間後 → 15 分の上限側が採られる。
        String capped = issueCookie(change(Instant.now(clock).plusSeconds(7_200)));
        assertThat(Long.parseLong(cookieAttribute(capped, "Max-Age")))
                .as("pending_update が遠いときは now+15分 で頭打ちにする")
                .isBetween(840L, 905L);

        // PaymentIntent の期限（stub では 900 秒）をそのまま使っていないこと。
        // (1) が 900 秒なら PaymentIntent 側を見ている証拠になる。
        assertThat(Long.parseLong(cookieAttribute(shortLived, "Max-Age")))
                .as("PaymentIntent の expiry を根拠にしていない").isLessThan(600L);
    }

    // ═════════ AC-62: token 自身の exp も同値 ═════════

    @Test
    @DisplayName("AC-62: return state token 自身の exp も cookie の Max-Age と同値（token だけ長命にしない）")
    void AC62_tokenのexpもMaxAgeと同値() throws Exception {
        String raw = issueCookie(change(Instant.now(clock).plusSeconds(300)));

        long maxAge = Long.parseLong(cookieAttribute(raw, "Max-Age"));
        long exp = tokenExpEpochSecond(cookieValue(raw));
        long remaining = exp - Instant.now(clock).getEpochSecond();

        assertThat(remaining)
                .as("BillingReturnStateService.issue は最長24時間まで許す。"
                        + "cookie だけ短く token が長命という検体を作らせない")
                .isBetween(maxAge - 10, maxAge + 10);
    }

    // ═════════ AC-63: checkout の cookie が残っていても正しい方が選ばれる ═════════

    @Test
    @DisplayName("AC-63: checkout の billing_return_state が残っていても payment-action の state だけが選ばれて 303 する")
    void AC63_checkout_cookie共存でも正しいstateが選ばれる() throws Exception {
        String raw = issueCookie(change(Instant.now(clock).plusSeconds(3_600)));

        MvcResult returned = mockMvc.perform(get(RETURN_PATH)
                        .cookie(new Cookie(CHECKOUT_COOKIE, FOREIGN_CHECKOUT_TOKEN))
                        .cookie(new Cookie(PAYMENT_ACTION_COOKIE, cookieValue(raw)))
                        .with(user(String.valueOf(userId))))
                .andExpect(status().isSeeOther()).andReturn();

        assertThat(String.valueOf(returned.getResponse().getHeader("Location")))
                .as("checkout の古い state に引きずられて generic error へ落ちない")
                .doesNotContain("error=return");
        assertThat(rawSetCookie(returned, CHECKOUT_COOKIE))
                .as("checkout の cookie（別 path・別用途）には触らない").isNull();
    }

    // ═════════ AC-64: 失効は発行時と同一 path ═════════

    @Test
    @DisplayName("AC-64: 消費後・失敗後の失効 Set-Cookie は発行時と同一の name/path で返る")
    void AC64_失効cookieは発行時と同一pathで返る() throws Exception {
        String raw = issueCookie(change(Instant.now(clock).plusSeconds(3_600)));

        // (a) 消費成功後の失効。
        MvcResult consumed = mockMvc.perform(get(RETURN_PATH)
                        .cookie(new Cookie(PAYMENT_ACTION_COOKIE, cookieValue(raw)))
                        .with(user(String.valueOf(userId))))
                .andExpect(status().isSeeOther()).andReturn();
        String cleared = rawSetCookie(consumed, PAYMENT_ACTION_COOKIE);
        assertThat(cleared).as("消費後は必ず失効させる").isNotNull();
        assertThat(cookieAttribute(cleared, "Max-Age")).isEqualTo("0");
        assertThat(cookieAttribute(cleared, "Path"))
                .as("path が違えばブラウザは別 cookie とみなし、失効が効かない")
                .isEqualTo(PAYMENT_ACTION_COOKIE_PATH);

        // (b) 失敗（改竄 state）後の失効も同じ path。
        MvcResult failed = mockMvc.perform(get(RETURN_PATH)
                        .cookie(new Cookie(PAYMENT_ACTION_COOKIE, "kid.tampered.signature"))
                        .with(user(String.valueOf(userId))))
                .andExpect(status().isSeeOther()).andReturn();
        String clearedOnFailure = rawSetCookie(failed, PAYMENT_ACTION_COOKIE);
        assertThat(clearedOnFailure).as("失敗時も失効させる").isNotNull();
        assertThat(cookieAttribute(clearedOnFailure, "Path")).isEqualTo(PAYMENT_ACTION_COOKIE_PATH);
    }

    // ═════════ AC-65: 未認証退避も同じ名前・同じ path ═════════

    @Test
    @DisplayName("AC-65: 未認証で戻ってきたときの退避 cookie も同じ名前・同じ path（/billing へ戻さない）")
    void AC65_未認証退避cookieも同名同path() throws Exception {
        String raw = issueCookie(change(Instant.now(clock).plusSeconds(3_600)));

        MvcResult unauthenticated = mockMvc.perform(get(RETURN_PATH)
                        .cookie(new Cookie(PAYMENT_ACTION_COOKIE, cookieValue(raw))))
                .andExpect(status().isSeeOther()).andReturn();

        assertThat(String.valueOf(unauthenticated.getResponse().getHeader("Location")))
                .as("再ログイン後は callback 自身へ戻す")
                .isEqualTo("/login?next=%2Fbilling%2Fpayment-action%2Freturn");
        String stash = rawSetCookie(unauthenticated, PAYMENT_ACTION_COOKIE);
        assertThat(stash).as("退避 cookie も別名側を使う").isNotNull();
        assertThat(cookieAttribute(stash, "Path"))
                .as("既存実装の /billing へ戻ってはならない（E3'）")
                .isEqualTo(PAYMENT_ACTION_COOKIE_PATH);
        assertThat(rawSetCookie(unauthenticated, CHECKOUT_COOKIE))
                .as("checkout 側の cookie 名で退避しない").isNull();
    }

    // ═════════ AC-66: query param を読まない（既存の性質の回帰） ═════════

    @Test
    @DisplayName("AC-66: GET /billing/payment-action/return は query param の state を一切読まない")
    void AC66_queryParamを読まない() throws Exception {
        String raw = issueCookie(change(Instant.now(clock).plusSeconds(3_600)));
        String token = cookieValue(raw);

        // cookie 無し・query だけ → 消費されず generic error へ。
        mockMvc.perform(get(RETURN_PATH).param("state", token)
                        .with(user(String.valueOf(userId))))
                .andExpect(status().isSeeOther())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Location", "/billing?scopeKind=USER&tab=plan&error=return"));

        // 陽性対照: 同じ token を cookie で渡せば消費できる（token が無効だから落ちたのではない）。
        mockMvc.perform(get(RETURN_PATH)
                        .cookie(new Cookie(PAYMENT_ACTION_COOKIE, token))
                        .with(user(String.valueOf(userId))))
                .andExpect(status().isSeeOther())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Location",
                                org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("error=return"))));
    }

    // ═════════ AC-67: 検証順序（purpose / actor が nonce CAS より先） ═════════

    @Test
    @DisplayName("AC-67: purpose 不一致・actor 不一致では nonce を消費しない（CAS は最後に一度だけ）")
    void AC67_検証順序はnonceCASが最後() throws Exception {
        String raw = issueCookie(change(Instant.now(clock).plusSeconds(3_600)));
        String token = cookieValue(raw);
        Long strangerId = insertUser("cookie-stranger");

        // (1) actor 不一致（別人が cookie を持ち込んだ）→ 失敗するが nonce は消費されない。
        mockMvc.perform(get(RETURN_PATH)
                        .cookie(new Cookie(PAYMENT_ACTION_COOKIE, token))
                        .with(user(String.valueOf(strangerId))))
                .andExpect(status().isSeeOther())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Location", "/billing?scopeKind=USER&tab=plan&error=return"));

        // (2) purpose 不一致（checkout の入口へ持ち込んだ）→ 失敗するが nonce は消費されない。
        mockMvc.perform(get("/billing/checkout/success")
                        .cookie(new Cookie(CHECKOUT_COOKIE, token))
                        .with(user(String.valueOf(userId))))
                .andExpect(status().isSeeOther())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Location", "/billing?scopeKind=USER&tab=plan&error=return"));

        // (3) 正しい actor・正しい入口なら、まだ消費できる。
        //     ここが赤ければ、(1)(2) のどちらかが nonce を先に焼いている。
        mockMvc.perform(get(RETURN_PATH)
                        .cookie(new Cookie(PAYMENT_ACTION_COOKIE, token))
                        .with(user(String.valueOf(userId))))
                .andExpect(status().isSeeOther())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Location",
                                org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("error=return"))));

    }

    // ═════════ AC-68 / AC-69: 二度目は弾く・clean 303 ═════════

    @Test
    @DisplayName("AC-68: 二度目の戻りは nonce 消費済みで弾かれる")
    void AC68_二度目の戻りは弾かれる() throws Exception {
        String raw = issueCookie(change(Instant.now(clock).plusSeconds(3_600)));
        String token = cookieValue(raw);

        mockMvc.perform(get(RETURN_PATH)
                        .cookie(new Cookie(PAYMENT_ACTION_COOKIE, token))
                        .with(user(String.valueOf(userId))))
                .andExpect(status().isSeeOther());

        mockMvc.perform(get(RETURN_PATH)
                        .cookie(new Cookie(PAYMENT_ACTION_COOKIE, token))
                        .with(user(String.valueOf(userId))))
                .andExpect(status().isSeeOther())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Location", "/billing?scopeKind=USER&tab=plan&error=return"));
    }

    @Test
    @DisplayName("AC-69: 消費後は clean 303 で /billing へ戻り、query に秘密を載せない")
    void AC69_消費後はclean303() throws Exception {
        String raw = issueCookie(change(Instant.now(clock).plusSeconds(3_600)));

        MvcResult returned = mockMvc.perform(get(RETURN_PATH)
                        .cookie(new Cookie(PAYMENT_ACTION_COOKIE, cookieValue(raw)))
                        .with(user(String.valueOf(userId))))
                .andExpect(status().isSeeOther()).andReturn();

        String location = String.valueOf(returned.getResponse().getHeader("Location"));
        assertThat(location).as("/billing へ戻る").startsWith("/billing?");
        assertThat(location).as("state を query へ出さない").doesNotContain("state=");
        assertThat(location).as("clientSecret を query へ出さない").doesNotContain(CLIENT_SECRET);
        assertThat(returned.getResponse().getContentAsString())
                .as("本文にも秘密を出さない").doesNotContain(CLIENT_SECRET);
    }
}
