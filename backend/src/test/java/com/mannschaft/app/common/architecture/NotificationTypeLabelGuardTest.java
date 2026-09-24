package com.mannschaft.app.common.architecture;

import com.mannschaft.app.notification.NotificationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link NotificationType} の全 enum 値に対し、ラベル定義（{@code notification.type.<NAME>.label}）が
 * {@code messages.properties} 系リソースに存在することを機械的に強制する番人（CMP-260919-1446）。
 *
 * <h2>なぜこの番人が要るのか</h2>
 * <p>{@link NotificationType#getLabelKey()} はキーを機械的に組み立てるだけで、対応する
 * {@code messages.properties} の値が存在するかは呼び出し側では検証されない。定義が無いキーを
 * {@code MessageSource} に渡すと、Spring はコード（例: {@code notification.type.JOIN_REQUEST_RECEIVED.label}）
 * をそのまま返す。通知設定画面はこれをそのまま {@code {{ tp.label }}} で描画するため、
 * <b>英語の列挙定数がユーザーにそのまま見える</b>という UI バグになる
 * （実際に CMP-260919-1446 で 9 件がこの経路で欠落していた）。</p>
 *
 * <p>この欠落は単体テストでは検出できない（{@code getLabelKey()} 自体は文字列を返すだけで常に緑）。
 * また実機を毎回全種別分踏むのは非現実的なため、enum とプロパティファイルを突き合わせる
 * 静的な番人で機械的に担保する。</p>
 *
 * <h2>対象言語ファイルの選定理由</h2>
 * <p>{@code messages.properties}（既定・フォールバック先）と {@code messages_ja.properties} /
 * {@code messages_en.properties} を<b>必須</b>とする。実測の結果、{@code messages_de/es/ko/zh.properties}
 * には {@code notification.type.*} キーが<b>1件も定義されていない</b>（部分的な欠落ではなく、
 * この区分をそもそも持たない設計）。これらは Spring の {@code MessageSource} 親子解決により
 * 既定 {@code messages.properties}（日本語）へフォールバックする前提であり、CMP-260919-1446 の
 * 欠落パターン（3ファイルのうち9件だけが漏れている）とは性質が異なる。よって
 * de/es/ko/zh を必須対象に含めると「そもそも維持していない設計」を無理に赤くしてしまうため、
 * ja/en/既定の3ファイルのみを必須とする。</p>
 *
 * <h2>空虚 green の防止</h2>
 * <p>{@link NotificationType#values()} が万一 0 件になっても「違反 0 件＝緑」で通ってしまうため、
 * enum の件数そのものを下限アサーションで固定する（{@link #enum件数が想定を下回っていないこと()}）。</p>
 */
class NotificationTypeLabelGuardTest {

    /** 走査対象のリソースルート（{@code backend/} を CWD とする Gradle テスト実行に合わせた相対パス）。 */
    private static final Path RESOURCE_ROOT = Paths.get("src", "main", "resources");

    /**
     * ラベル定義を必須とするリソースファイル名一覧。
     * de/es/ko/zh を含めない理由は本クラス Javadoc を参照。
     */
    private static final List<String> REQUIRED_BUNDLES = List.of(
        "messages.properties",
        "messages_ja.properties",
        "messages_en.properties");

    @Test
    @DisplayName("NotificationType の全 enum 値に notification.type.<NAME>.label 定義が存在すること")
    void 全種別にラベル定義が存在すること() throws IOException {
        List<String> violations = analyze(NotificationType.values(), REQUIRED_BUNDLES, RESOURCE_ROOT);
        if (violations.isEmpty()) {
            return;
        }
        fail(buildMessage(violations));
    }

    @Test
    @DisplayName("空虚 green 防止: NotificationType の件数が想定を下回っていないこと")
    void enum件数が想定を下回っていないこと() {
        assertTrue(NotificationType.values().length >= 38,
            "NotificationType の enum 件数が想定より少ない（"
                + NotificationType.values().length + " 件）。"
                + "reflection が壊れて 0 件相当になると本番人は違反 0 件のまま緑になるため、"
                + "件数そのものを固定する。種別を意図的に減らした場合は本アサーションも更新すること。");
    }

    @Test
    @DisplayName("裏取り: 判定コアが実際にプロパティを読み込めている（パーサ破損の検知）")
    void プロパティを実際に読み込めていること() throws IOException {
        Properties props = loadBundle(RESOURCE_ROOT.resolve("messages.properties"));
        assertTrue(props.size() > 100,
            "messages.properties の読み込み件数が少なすぎます（" + props.size() + " 件）。"
                + "リソースルートの想定が崩れている可能性があります: "
                + RESOURCE_ROOT.toAbsolutePath());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 判定コア（実ファイル走査・自己検証で共通利用する単一コア）
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * enum の全値について、必須バンドルそれぞれにラベル定義が存在するか検証する。
     *
     * @param types          検証対象の enum 値
     * @param requiredBundle 必須バンドルのファイル名一覧（{@code resourceRoot} 直下）
     * @param resourceRoot   プロパティファイルを探すディレクトリ
     * @return 違反メッセージ一覧（空なら合格）
     */
    static List<String> analyze(NotificationType[] types, List<String> requiredBundle, Path resourceRoot)
            throws IOException {
        List<String> violations = new ArrayList<>();
        for (String bundle : requiredBundle) {
            Path path = resourceRoot.resolve(bundle);
            assertTrue(Files.isRegularFile(path),
                "必須バンドルが見つかりません: " + path.toAbsolutePath());
            Properties props = loadBundle(path);
            for (NotificationType type : types) {
                String key = type.getLabelKey();
                String value = props.getProperty(key);
                if (value == null || value.isBlank()) {
                    violations.add(bundle + ": " + key + " が未定義（または空）です。");
                }
            }
        }
        return violations;
    }

    private static Properties loadBundle(Path path) throws IOException {
        Properties props = new Properties();
        try (Reader reader = new InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return props;
    }

    private static String buildMessage(List<String> violations) {
        StringBuilder sb = new StringBuilder();
        sb.append("NotificationType のラベル定義が欠落しています（")
            .append(violations.size()).append(" 件）。\n")
            .append("定義の無いキーは MessageSource からコードがそのまま返り、通知設定画面に"
                + "英語の列挙定数がそのまま表示されます（CMP-260919-1446 参照）。\n\n");
        for (String v : violations) {
            sb.append("  ✗ ").append(v).append('\n');
        }
        sb.append("\n対処: backend/src/main/resources/messages.properties, messages_ja.properties, "
            + "messages_en.properties のそれぞれに notification.type.<NAME>.label を追加してください。");
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 判定コアの自己検証（正例・負例）
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("自己検証・正例: 全キーが揃ったバンドルは違反 0 件")
    void 自己検証_正例_全キーが揃っていれば違反なし(@org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
        NotificationType only = NotificationType.SCHEDULE_CREATED;
        writeBundle(tempDir.resolve("messages.properties"), only.getLabelKey() + "=テスト");
        writeBundle(tempDir.resolve("messages_ja.properties"), only.getLabelKey() + "=テスト");
        writeBundle(tempDir.resolve("messages_en.properties"), only.getLabelKey() + "=Test");

        List<String> violations = analyze(new NotificationType[] {only},
            List.of("messages.properties", "messages_ja.properties", "messages_en.properties"), tempDir);
        assertTrue(violations.isEmpty(), "全キーが揃っていれば違反 0 件であるべき: " + violations);
    }

    @Test
    @DisplayName("自己検証・負例: 1バンドルでキー欠落 → その1件が違反として検出される")
    void 自己検証_負例_キー欠落が検出される(@org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
        NotificationType only = NotificationType.NEW_DEVICE_LOGIN;
        writeBundle(tempDir.resolve("messages.properties"), only.getLabelKey() + "=テスト");
        writeBundle(tempDir.resolve("messages_ja.properties"), only.getLabelKey() + "=テスト");
        // messages_en.properties だけキーを書かない（欠落を再現）
        writeBundle(tempDir.resolve("messages_en.properties"), "# no label here");

        List<String> violations = analyze(new NotificationType[] {only},
            List.of("messages.properties", "messages_ja.properties", "messages_en.properties"), tempDir);
        assertEquals(1, violations.size(), "欠落は 1 件だけ検出されるべき: " + violations);
        assertTrue(violations.get(0).contains("messages_en.properties"),
            "欠落したバンドル名を含むべき: " + violations.get(0));
        assertTrue(violations.get(0).contains(only.getLabelKey()),
            "欠落したキーを含むべき: " + violations.get(0));
    }

    @Test
    @DisplayName("自己検証・負例: 値が空文字のキーも欠落として扱われる")
    void 自己検証_負例_空文字の値も欠落扱い(@org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
        NotificationType only = NotificationType.JOIN_REQUEST_RECEIVED;
        writeBundle(tempDir.resolve("messages.properties"), only.getLabelKey() + "=");
        writeBundle(tempDir.resolve("messages_ja.properties"), only.getLabelKey() + "=テスト");
        writeBundle(tempDir.resolve("messages_en.properties"), only.getLabelKey() + "=Test");

        List<String> violations = analyze(new NotificationType[] {only},
            List.of("messages.properties", "messages_ja.properties", "messages_en.properties"), tempDir);
        assertEquals(1, violations.size(), "空文字の値は欠落として扱われるべき: " + violations);
        assertTrue(violations.get(0).contains("messages.properties"));
    }

    private static void writeBundle(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
