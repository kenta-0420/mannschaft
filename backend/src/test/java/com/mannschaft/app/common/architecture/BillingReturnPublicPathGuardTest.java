package com.mannschaft.app.common.architecture;

import com.mannschaft.app.billing.api.BillingReturnController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 番人: Stripe 復帰 callback（{@link BillingReturnController}）の <b>宣言（SecurityConfig の permitAll）</b>と
 * <b>実パス（{@code @GetMapping}）</b>が一字一句一致していることを機械的に検証する。
 *
 * <p><b>なぜこの番人が要るのか</b>: 本 controller は「未認証なら nonce を消費せず署名済み state を
 * HttpOnly Cookie へ退避して {@code /login} へ 303 する」という設計を持つが、SecurityConfig に
 * permitAll が無ければ {@code anyRequest().authenticated()} により<b>controller へ到達する前に 401</b> になり、
 * 設計が丸ごと死ぬ（PR4 の実測でこの状態だった）。しかも死に方が静かで、単体テストは
 * controller を直接叩くため緑のままである。</p>
 *
 * <p>さらに permitAll のパス文字列と {@code @GetMapping} の実パスは<b>別々に</b>書かれており、
 * 片方の綴りを変えても誰も気づかない（既知事故: permitAll パス不一致は静かに壊す）。
 * 両者を集合として突き合わせ、ズレたらビルドを赤くする。</p>
 *
 * <p><b>ワイルドカードで開けないことも併せて検証する</b>。{@code /billing/**} で広く開けると
 * 復帰用でない将来の {@code /billing} 配下の入口まで無認可で公開されるため、
 * permitAll は復帰 4 入口ちょうどでなければならない。</p>
 *
 * <p>本テストは ArchUnit ではない（素の JUnit ＋ ソース走査＋リフレクション）。
 * したがって ArchUnit 凍結ストアを一切読み書きしない。</p>
 */
@DisplayName("番人: Stripe 復帰 callback の permitAll 宣言と @GetMapping 実パスが一致すること")
class BillingReturnPublicPathGuardTest {

    private static final String BILLING_PREFIX = "/billing";

    @Test
    @DisplayName("SecurityConfig の /billing 系 permitAll は復帰 callback の実パスと完全一致する")
    void permitAllPathsExactlyMatchControllerMappings() {
        SecurityConfigRules.Rules rules = SecurityConfigRules.Rules.parse(securityConfig());

        assertThat(rules.permitAll)
                .as("SecurityConfig の permitAll 解析に失敗している（パーサーの前提が壊れた可能性）")
                .isNotEmpty();

        Set<String> declared = new LinkedHashSet<>();
        for (String pattern : rules.permitAll) {
            if (pattern.equals(BILLING_PREFIX) || pattern.startsWith(BILLING_PREFIX + "/")) {
                declared.add(pattern);
            }
        }
        Set<String> actual = controllerMappings();

        assertThat(actual)
                .as("BillingReturnController から @GetMapping を 1 件も検出できなかった（走査前提の破損）")
                .hasSize(4);
        assertThat(declared)
                .as("SecurityConfig の permitAll（/billing 系）と BillingReturnController の実パスが食い違っている。%n"
                        + "  permitAll 宣言: %s%n"
                        + "  @GetMapping 実パス: %s%n"
                        + "  ここがズレると、未認証の Stripe 復帰が controller へ到達せず 401 になり、%n"
                        + "  「cookie へ退避して /login へ 303」という設計が静かに死ぬ。", declared, actual)
                .containsExactlyInAnyOrderElementsOf(actual);
    }

    @Test
    @DisplayName("/billing 配下はワイルドカードで開けていない（復帰 4 入口ちょうど）")
    void billingIsNotOpenedByWildcard() {
        SecurityConfigRules.Rules rules = SecurityConfigRules.Rules.parse(securityConfig());

        assertThat(rules.permitAll.stream()
                .filter(pattern -> pattern.startsWith(BILLING_PREFIX))
                .filter(pattern -> pattern.contains("*"))
                .toList())
                .as("/billing 配下を * / ** で permitAll してはならない。"
                        + "復帰用でない入口まで無認可公開されるため、4 入口を個別に列挙すること。")
                .isEmpty();
    }

    private Set<String> controllerMappings() {
        RequestMapping type = AnnotatedElementUtils.findMergedAnnotation(
                BillingReturnController.class, RequestMapping.class);
        String prefix = (type == null || type.value().length == 0) ? "" : type.value()[0];

        Set<String> paths = new LinkedHashSet<>();
        for (Method method : BillingReturnController.class.getDeclaredMethods()) {
            GetMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, GetMapping.class);
            if (mapping == null) {
                continue;
            }
            for (String value : mapping.value()) {
                paths.add(prefix + value);
            }
        }
        return paths;
    }

    private Path securityConfig() {
        return SecurityConfigRules.sourceRoot()
                .resolve("com/mannschaft/app/config/SecurityConfig.java");
    }
}
