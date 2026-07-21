package com.mannschaft.app.common.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * stripe-java 依存バージョンの番人テスト。
 *
 * <h2>背景（F08.9 P5 継続課金・{@code StripeConfig} 必読コメント）</h2>
 * <p>本プロジェクトは {@code build.gradle.kts} で {@code stripe-java 28.x} を固定採用している。
 * {@link com.mannschaft.app.config.StripeConfig} のクラス Javadoc に記載の通り、29.x（basil 系。
 * Stripe API バージョン {@code 2025-03-31} 以降）へ上げると {@code invoice.application_fee_amount} /
 * {@code transfer_data} / {@code charge} 等が新 Invoice Payments 構造へ移行して invoice オブジェクトから
 * 消え、P5 継続課金の「{@code invoice.created} の draft 窓で {@code application_fee_amount} を固定上書き
 * する手数料機構」が<b>黙殺で壊れる</b>（HTTP 200 のまま無視される。例外もログも出ない）。</p>
 *
 * <p>Dependabot 等による依存の一括更新でこの事故を機械的に防ぐため、{@code build.gradle.kts} の
 * テキストから stripe-java のバージョン宣言を読み取り、メジャーバージョンが 28 以外になったら
 * ビルドを fail させる。Spring context は起動しない軽量な純粋単体テスト。</p>
 */
class StripeJavaVersionGuardTest {

    /** 固定必須のメジャーバージョン。上げる場合は本テストの更新前に下記の検証が必須。 */
    private static final String REQUIRED_MAJOR_VERSION = "28";

    /**
     * {@code build.gradle.kts} 内の stripe-java 依存宣言を抽出する正規表現。
     * 例: {@code implementation("com.stripe:stripe-java:28.2.0")}
     */
    private static final Pattern STRIPE_DEPENDENCY_PATTERN =
        Pattern.compile("com\\.stripe:stripe-java:(\\d+)\\.\\d+\\.\\d+");

    /**
     * {@code backend/} からのテスト実行（通常想定）と、リポジトリルートからの実行の
     * 両方に対応するための候補パス。存在する最初のものを採用する。
     */
    private static final List<Path> BUILD_GRADLE_CANDIDATES = List.of(
        Paths.get("build.gradle.kts"),              // CWD = backend/ （通常想定）
        Paths.get("backend", "build.gradle.kts")     // CWD = リポジトリルート
    );

    @Test
    @DisplayName("stripe-java 依存はメジャーバージョン28に固定されている（29.x basil 移行で手数料上書きが黙殺で壊れるため）")
    void stripeJavaのメジャーバージョンは28に固定されている() {
        Path buildGradleKts = resolveBuildGradleKts();
        String content = readFile(buildGradleKts);

        Matcher matcher = STRIPE_DEPENDENCY_PATTERN.matcher(content);
        if (!matcher.find()) {
            fail(
                "build.gradle.kts から stripe-java の依存宣言（com.stripe:stripe-java:x.y.z）が"
                    + "見つかりませんでした。依存の記述形式が変更された可能性があります。\n"
                    + "本番手数料機構（F08.9 P5）に影響するため、バージョン固定の意図が保たれているか"
                    + "手動で確認し、本テストの正規表現を追従修正してください。\n"
                    + "確認ファイル: " + buildGradleKts.toAbsolutePath());
            return; // unreachable（fail は例外を投げるが静的解析用）
        }

        String actualMajorVersion = matcher.group(1);
        if (!REQUIRED_MAJOR_VERSION.equals(actualMajorVersion)) {
            fail(
                "stripe-java の依存バージョンがメジャー " + REQUIRED_MAJOR_VERSION
                    + ".x から外れています（検出値: メジャー " + actualMajorVersion + "）。\n\n"
                    + "【なぜ 28.x に固定しているか】\n"
                    + "com.mannschaft.app.config.StripeConfig の Javadoc（backend/src/main/java/"
                    + "com/mannschaft/app/config/StripeConfig.java）参照。29.x（basil 系。Stripe API"
                    + " バージョン 2025-03-31 以降）では invoice.application_fee_amount /"
                    + " transfer_data / charge 等が新 Invoice Payments 構造へ移行して invoice"
                    + " オブジェクトから消える。\n\n"
                    + "【上げると何が黙って壊れるか】\n"
                    + "F08.9 P5 継続課金の「invoice.created の draft 窓で application_fee_amount を"
                    + "固定上書きする手数料機構」が、例外もエラーログも出ないまま無効化される"
                    + "（Stripe API は HTTP 200 のまま新フィールドを無視して黙殺する）。CI・自動テストも"
                    + "無言で緑のまま通過するため、本番で手数料徴収額がズレるまで誰も気づけない。\n\n"
                    + "【本当に上げたい場合に検証すべきこと】\n"
                    + "1. docs/features/F08.9_membership_billing_paywall/README §11-3 と"
                    + " scripts/poc/README_f089_p5_poc.md §0 を読み、basil 移行後の新 Invoice"
                    + " Payments 構造で P5 手数料上書き機構を再設計する\n"
                    + "2. 新構造での上書き機構を実装し、実際に Stripe テスト環境で"
                    + " application_fee_amount が反映されることを確認する（PoC相当の実証が必須）\n"
                    + "3. 上記が完了して初めて、本テストの REQUIRED_MAJOR_VERSION を更新すること。"
                    + "単に期待値だけを書き換えて通すのは対処療法であり禁止（CLAUDE.md 障害対応の原則）。\n\n"
                    + "確認ファイル: " + buildGradleKts.toAbsolutePath());
        }
    }

    private static Path resolveBuildGradleKts() {
        for (Path candidate : BUILD_GRADLE_CANDIDATES) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Path candidate : BUILD_GRADLE_CANDIDATES) {
            sb.append("  ✗ ").append(candidate.toAbsolutePath()).append('\n');
        }
        fail("build.gradle.kts が見つかりません（CWD=" + Paths.get("").toAbsolutePath()
            + "）。試行したパス:\n" + sb);
        throw new IllegalStateException("unreachable");
    }

    private static String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("ファイル読み込みに失敗: " + path.toAbsolutePath(), e);
        }
    }
}
