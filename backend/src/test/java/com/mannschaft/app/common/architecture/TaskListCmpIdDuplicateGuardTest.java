package com.mannschaft.app.common.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 番人: {@code docs/task-list.md}（戦役横断の課題正本）に登録された CMP-ID が重複しないこと。
 *
 * <h2>背景（2026-08-14 CMP-028 消失事故）</h2>
 * <p>{@code docs/task-list.md} は複数の並行セッションが日常的に書き換える共有台帳である。
 * 旧採番方式（{@code origin/main} 上の最大 CMP 番号 +1）では、並行セッションが同時に
 * {@code origin/main} を fetch すると<b>双方が同じ最大番号を読み、同じ番号を採ってしまう</b>。
 * 挿入位置がわずかに異なると git は競合を検知できず両方を自動マージしてしまうため、
 * <b>重複したまま静かに main へ入る</b>（CMP-030 の重複が実在した）。</p>
 *
 * <h2>採番方式の移行（2026-08-19）</h2>
 * <p>衝突が例外ではなく常態化した（1行の追加に対し採番を3度やり直した実績。CMP-050 → 102 → 110)
 * ため、新規行の ID は<b>日時形式 {@code CMP-YYMMDD-HHMM}（JST）</b>へ移行した。
 * 生成は {@code date '+%y%m%d-%H%M'}。既存の連番形式 {@code CMP-NNN} の行は一切変更しないため、
 * 台帳には<b>両形式が共存</b>する。本番人は両形式を等しく走査・重複判定する。</p>
 *
 * <h2>重複が見つかったら</h2>
 * <ol>
 *   <li>どちらが後から {@code origin/main} へ merge されたか（コミット日時・PR番号）を確認する。</li>
 *   <li><b>後から入った側</b>が採番し直す — 新形式なら {@code date '+%y%m%d-%H%M'} を採り直す
 *       （同一分に別セッションと衝突した場合のみ起こりうる）。旧形式の行同士の重複なら
 *       後から入った側を新形式へ振り直す。</li>
 *   <li>行の内容（戦役名・状態・証拠）はそのまま、ID のみ振り直して commit・PR を作成する。</li>
 * </ol>
 * <p><b>本番人の検出を緩めて通す（重複IDを許容リストに追加する等）ことは禁止</b>する。
 * CMP-ID は他の記憶ファイルからの参照キーであり、重複を許すと索引が壊れる。</p>
 *
 * <h2>本テストは ArchUnit ではない</h2>
 * <p>Markdown ドキュメントの走査であり、ソースコードのバイトコード解析ではないため、
 * {@link PagingTotalCountSizeGuardTest} と同じ<b>ソース（ドキュメント）走査型</b>で書いた。
 * ArchUnit 凍結ストア（{@code src/test/resources/archunit_store}）は一切使わない。</p>
 *
 * <h2>自己検証（走査経路の健全性）【必読】</h2>
 * <p>「重複が見つからない」ことと「そもそも CMP-ID を1件も抽出できていない」ことは別事象である。
 * ファイルパスの解決ミス・正規表現の破損・table形式の変更等で抽出が0件になった場合、
 * 「重複なし」という誤った緑を返してはならない。よって本テストは重複判定とは独立に、
 * <b>厳密パターンの抽出件数が緩いパターンの計数と一致するか</b>を検証する。
 * <b>件数のしきい値は持たない</b>（行のアーカイブで件数が減っても誤検知しないため）。</p>
 *
 * <h2>状態列の件数集計（合否条件ではない）</h2>
 * <p>ID が連番でなくなった以上、「番号を見れば規模が分かる」という性質は失われる
 * （そもそも欠番・焼き捨てにより番号と件数は既に一致していなかった）。代わりに本番人は
 * 状態列の件数を<b>標準出力に出す</b>。しきい値を置くと行の増減で壊れるため、
 * 上記の自己検証と同じ思想で<b>合否条件にはしない</b>。</p>
 */
@DisplayName("番人: docs/task-list.md のCMP-IDが重複していないこと")
class TaskListCmpIdDuplicateGuardTest {

    /** {@code docs/task-list.md} のリポジトリルートからの相対パス。 */
    private static final Path TASK_LIST_RELATIVE = Paths.get("docs", "task-list.md");

    /**
     * CMP-ID の形。旧・連番形式 {@code CMP-028} と新・日時形式 {@code CMP-260819-2131} の両方を表す。
     *
     * <p>日時形式を先に並べるのが要点である。{@code \d+} を先に置くと {@code CMP-260819-2131} から
     * {@code CMP-260819} だけを拾ってしまい、同日の別 ID を誤って重複と判定する。</p>
     */
    private static final String CMP_ID_FORM = "CMP-\\d{6}-\\d{4}|CMP-\\d+";

    /** 表の行頭に現れる CMP-ID（例: {@code | CMP-028 | ...}）を抽出する。 */
    private static final Pattern CMP_ID_ROW =
            Pattern.compile("(?m)^\\|\\s*(" + CMP_ID_FORM + ")\\s*\\|");

    /**
     * 走査経路の自己検証用。「CMP-ID を含む表の行」を、ID 抽出用より<b>緩い</b>条件で数える。
     *
     * <p>{@link #CMP_ID_ROW} が取りこぼしなく抽出できているかを、この緩い計数との一致で確かめる。
     * <b>件数のしきい値を持たない</b>のが要点である。当初は「30件以上あること」で自己検証していたが、
     * それは<b>現在の状態を数字で焼き付ける</b>形であり、行のアーカイブ等で件数が減れば重複が無くても
     * 誤って落ちる。件数ではなく<b>抽出の網羅性</b>を見れば、母数が何件でも成立する。</p>
     */
    private static final Pattern CMP_ID_ROW_LOOSE =
            Pattern.compile("(?m)^\\|.*?(" + CMP_ID_FORM + ")");

    /** 表の行から ID・状態列（第3列）を取り出す。 */
    private static final Pattern CMP_ROW_WITH_STATUS = Pattern.compile(
            "(?m)^\\|\\s*(" + CMP_ID_FORM + ")\\s*\\|([^|]*)\\|([^|]*)\\|");

    /** {@code docs/task-list.md} の「状態の語彙」節に定義された値。 */
    private static final List<String> STATUS_VOCABULARY =
            List.of("未着手", "設計中", "実装中", "実機検証待ち", "完了", "凍結");

    @Test
    @DisplayName("CMP-IDに重複が無く、かつ走査自体が機能していること")
    void cmpIdは重複しない() throws IOException {
        Path taskList = resolveTaskListPath();

        assertThat(Files.isRegularFile(taskList))
                .as("docs/task-list.md が見つからない: " + taskList.toAbsolutePath()
                        + "（CWD=" + Paths.get("").toAbsolutePath() + "）。"
                        + "リポジトリ構成が変わった場合は resolveTaskListPath() の候補パスを見直すこと。")
                .isTrue();

        String content = Files.readString(taskList, StandardCharsets.UTF_8);

        Map<String, Integer> occurrences = new LinkedHashMap<>();
        Matcher m = CMP_ID_ROW.matcher(content);
        while (m.find()) {
            String id = m.group(1);
            occurrences.merge(id, 1, Integer::sum);
        }

        // 走査経路が生きていることの自己検証。
        //
        // ⚠️ 「重複が見つからない」と「そもそも CMP-ID を抽出できていない」は別事象である。
        // 抽出0件で「重複なし」を返すと、ファイルパスの解決ミスや table 形式の変更で走査が
        // 壊れていても気づけない偽の緑になる。よって抽出の網羅性を重複判定とは独立に検証する。
        int totalIds = occurrences.values().stream().mapToInt(Integer::intValue).sum();

        int looseCount = 0;
        Matcher loose = CMP_ID_ROW_LOOSE.matcher(content);
        while (loose.find()) {
            looseCount++;
        }

        printStatusBreakdown(content);

        // 走査そのものが成立しているか（ファイルが空・パス誤りで 0 件になっていないか）。
        assertThat(looseCount)
                .as("docs/task-list.md から CMP-ID を含む行を 1 件も見つけられなかった"
                        + "（読んだファイル: " + taskList.toAbsolutePath() + "）。"
                        + "ファイルパスの解決ミス、または台帳の表形式そのものが変わった可能性がある。"
                        + "『重複なし』という判定自体が信用できない状態であり、重複ゼロとは別事象である。")
                .isPositive();

        // 抽出が網羅的か（厳密パターンが緩いパターンの取りこぼしを生んでいないか）。
        // 件数のしきい値を置かないため、行がアーカイブされて減っても誤検知しない。
        assertThat(totalIds)
                .as("CMP-ID を含む行は " + looseCount + " 件見つかったが、"
                        + "厳密パターンで抽出できたのは " + totalIds + " 件だった。"
                        + "CMP_ID_ROW（\"| CMP-N |\" / \"| CMP-YYMMDD-HHMM |\" 形式）が実際の表フォーマットを"
                        + "取りこぼしており、取りこぼした行の重複を見逃す。CMP_ID_ROW と docs/task-list.md の"
                        + "実際の行を突き合わせて正規表現を修正すること。"
                        + "（検出を緩めて通すのではなく、抽出を正しくすること）")
                .isEqualTo(looseCount);

        List<Map.Entry<String, Integer>> duplicates = occurrences.entrySet().stream()
                .filter(e -> e.getValue() > 1)
                .toList();

        if (duplicates.isEmpty()) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("docs/task-list.md に重複した CMP-ID が見つかりました。\n")
                .append("原因: 同一の ID が二重に登録されている。旧・連番形式では、並行セッションが")
                .append("同時に origin/main を fetch すると双方が同じ最大番号を採ってしまい、")
                .append("挿入位置の違いから git が競合検知できずに両方が自動マージされた")
                .append("（2026-08-14 CMP-028 消失事故と同根）。\n")
                .append("対処: 後から origin/main へ merge された側が採番し直すこと。")
                .append("新形式 CMP-YYMMDD-HHMM（JST・date '+%y%m%d-%H%M'）を採り直し、")
                .append("行の内容（戦役名・状態・証拠）はそのまま ID のみ振り直して commit・PR を作成すること。")
                .append("新規行は必ず表の末尾に追加すること（同じ位置に追記されていれば、"
                        + "重複は git の競合として必ず表面化する）。\n")
                .append("本番人の検出を緩めて通す（重複IDをそのまま許容する等）ことは禁止。\n")
                .append("重複ID一覧:\n");
        for (Map.Entry<String, Integer> e : duplicates) {
            sb.append("  ✗✗ ").append(e.getKey()).append(" : ").append(e.getValue()).append("回出現\n");
        }
        assertThat(duplicates).as(sb.toString()).isEmpty();
    }

    /**
     * 状態列ごとの件数を標準出力に出す（<b>合否条件にはしない</b>）。
     *
     * <p>しきい値を固定すると行の増減で壊れるため、判定には使わない。ID が連番でなくなり
     * 「番号を見れば規模が分かる」性質が失われた分を、この集計で補うのが目的である。</p>
     *
     * <p>状態列には「完了（2026-08-18・PR #2749…）」のように注記が付く値が多数あるため、
     * 語彙の<b>前方一致</b>で分類し、どの語彙にも当てはまらない行は「その他」として出す。</p>
     */
    private static void printStatusBreakdown(String content) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String vocabulary : STATUS_VOCABULARY) {
            counts.put(vocabulary, 0);
        }
        counts.put("その他", 0);
        List<String> unclassified = new java.util.ArrayList<>();

        Matcher row = CMP_ROW_WITH_STATUS.matcher(content);
        int total = 0;
        while (row.find()) {
            total++;
            String id = row.group(1);
            String status = row.group(3).replace("*", "").trim();
            String matched = STATUS_VOCABULARY.stream()
                    .filter(status::startsWith)
                    .findFirst()
                    .orElseGet(() -> STATUS_VOCABULARY.stream()
                            .filter(status::contains)
                            .findFirst()
                            .orElse(null));
            if (matched == null) {
                counts.merge("その他", 1, Integer::sum);
                unclassified.add(id + " → \"" + status + "\"");
            } else {
                counts.merge(matched, 1, Integer::sum);
            }
        }

        System.out.println("=== docs/task-list.md 状態別件数（合否条件ではない・規模の把握用） ===");
        System.out.println("  総行数: " + total + " 件");
        counts.forEach((status, count) -> System.out.println("  " + status + ": " + count + " 件"));
        if (!unclassified.isEmpty()) {
            System.out.println("  ※「その他」の内訳（状態の語彙に一致しなかった行）:");
            unclassified.forEach(entry -> System.out.println("    - " + entry));
        }
        System.out.println("=== ここまで ===");
    }

    /** {@code docs/task-list.md} を backend/ 実行・リポジトリルート実行の両方に対応して解決する。 */
    private static Path resolveTaskListPath() {
        for (Path candidate : new Path[]{
                TASK_LIST_RELATIVE,
                Paths.get("..").resolve(TASK_LIST_RELATIVE),
                Paths.get("backend").resolve(TASK_LIST_RELATIVE),
        }) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return TASK_LIST_RELATIVE; // 見つからなければそのまま返し、テスト内で存在チェックに失敗させる
    }
}
