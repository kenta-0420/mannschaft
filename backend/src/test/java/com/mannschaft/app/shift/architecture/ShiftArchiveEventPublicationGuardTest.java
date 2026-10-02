package com.mannschaft.app.shift.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-260909-1445 AC-12 — {@code ShiftScheduleEntity#archive()} を呼ぶ本番コードは
 * 必ず {@code ShiftArchivedEvent} を発行していることの番人。
 *
 * <h2>なぜ番人が要るか</h2>
 * <p>本欠陥の正体は「アーカイブする経路が 2 つあり、片方だけがイベントを出していた」ことである。
 * バッチだけが発行元で、UI/API 経由の {@code ShiftScheduleService#transitionStatus(ARCHIVED)} は
 * {@code entity.archive()} を呼ぶだけだった。結果、予算消化の取消も Todo の自動 CANCELLED 化も
 * 手動アーカイブでは一切走らず、割当が {@code SHIFT_BUDGET_012} で恒久的に削除不能になった。</p>
 *
 * <p>この形の欠陥は「3 つ目の経路」が生えた瞬間に同じ顔で再発する。IT は既存 2 経路しか守れないため、
 * <b>アーカイブ経路の追加そのもの</b>を機械的に検知する番人を置く。</p>
 *
 * <h2>実装方針（走査の安全性）</h2>
 * <ul>
 *   <li><b>正規表現を使わない。</b> 可変長の前方一致は破滅的バックトラックの温床であり、過去に
 *       走査番人が JVM ごと落ちた前例がある。固定文字列の {@code indexOf} による線形判定のみで組む。</li>
 *   <li><b>台帳（freeze store）を持たない読み取り専用の番人である。</b> ファイルへ書き戻さないため、
 *       {@code --tests} で絞って実行しても台帳が壊れる事故が起きない。</li>
 *   <li>走査対象は {@code shift} ドメインに限定する。他ドメインにも {@code .archive()} を持つ
 *       エンティティは多数あるが（bulletin / chat / team ほか）、それらは別のイベント契約であり
 *       本番人の射程ではない。</li>
 * </ul>
 */
@DisplayName("CMP-260909-1445 AC-12 シフトのアーカイブ経路はイベント発行を伴う（番人）")
class ShiftArchiveEventPublicationGuardTest {

    /**
     * {@code ShiftScheduleEntity#archive()} の呼び出しを表す固定文字列。
     *
     * <p>宣言行 {@code public void archive()} は先頭のドットを伴わないため一致しない。
     * {@code unarchive()} も {@code .unarchive()} という別文字列になるため一致しない
     * （{@code .archive()} は部分文字列として現れない）。</p>
     */
    private static final String ARCHIVE_CALL = ".archive()";

    /** {@code archive()} の宣言側（呼び出しではない）。 */
    private static final String ARCHIVE_DECLARATION = "public void archive()";

    /** 発行されていなければならないイベント型名。 */
    private static final String REQUIRED_EVENT = "ShiftArchivedEvent";

    /**
     * 走査から除外するファイル。
     *
     * <p>{@code ShiftScheduleEntity} は {@code archive()} の<b>宣言</b>を持つだけで、
     * イベント発行の責務はエンティティにない（ドメインイベントは Service / Batch が publish する）。</p>
     */
    private static final Set<String> EXEMPT_FILE_NAMES = Set.of("ShiftScheduleEntity.java");

    private static final Path SHIFT_MAIN_DIR =
            Paths.get("src", "main", "java", "com", "mannschaft", "app", "shift");

    @Test
    @DisplayName("shift ドメインで archive() を呼ぶ本番クラスは ShiftArchivedEvent を発行している")
    void アーカイブ経路は必ずイベントを発行する() throws IOException {
        assertThat(SHIFT_MAIN_DIR)
                .as("走査対象ディレクトリが見つからない。番人が 0 件走査で無言の緑になっていないか確認すること")
                .exists();

        List<String> violations = new ArrayList<>();
        List<String> scannedCallers = new ArrayList<>();

        try (Stream<Path> files = Files.walk(SHIFT_MAIN_DIR)) {
            List<Path> javaFiles = files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .toList();

            for (Path file : javaFiles) {
                String fileName = file.getFileName().toString();
                if (EXEMPT_FILE_NAMES.contains(fileName)) {
                    continue;
                }
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (source.indexOf(ARCHIVE_CALL) < 0) {
                    continue;
                }
                scannedCallers.add(fileName);
                if (!source.contains(REQUIRED_EVENT)) {
                    violations.add(fileName);
                }
            }
        }

        assertThat(scannedCallers)
                .as("""
                        archive() の呼び出しが 1 件も見つからない。実装が移動したか走査条件が壊れており、
                        番人が「違反ゼロ」ではなく「何も見ていない」状態になっている。""")
                .isNotEmpty();

        assertThat(violations)
                .as("""
                        シフトを ARCHIVED にしながら ShiftArchivedEvent を発行していない本番クラスがある。
                        イベントを出さないと予算消化の取消（ShiftBudgetConsumptionCancelListener）も
                        Todo の自動 CANCELLED 化（ShiftArchivedToTodoCancelListener）も走らず、
                        該当 allocation が SHIFT_BUDGET_012 で恒久的に削除不能になる。
                        違反クラス: %s / 走査した呼び出し元: %s""".formatted(violations, scannedCallers))
                .isEmpty();
    }

    @Test
    @DisplayName("番人の前提: ShiftScheduleEntity は archive() の宣言を持ち、除外対象である")
    void 除外対象は宣言側だけであること() throws IOException {
        Path entity = SHIFT_MAIN_DIR.resolve(Paths.get("entity", "ShiftScheduleEntity.java"));
        assertThat(entity).exists();
        assertThat(Files.readString(entity, StandardCharsets.UTF_8))
                .as("除外理由（宣言を持つだけ）が失効していないこと。宣言が消えたら除外も見直す")
                .contains(ARCHIVE_DECLARATION);
    }
}
