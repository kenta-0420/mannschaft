package com.mannschaft.app.common.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
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
 * <p>{@code docs/task-list.md} は直近14日で19回コミットされる、複数の並行セッションが
 * 日常的に書き換える共有台帳である。並行セッションが同時に {@code origin/main} を fetch すると、
 * <b>双方が同じ最大 CMP 番号を読み、同じ番号を採ってしまう</b>。挿入位置がわずかに異なると
 * git は競合を検知できず両方を自動マージしてしまうため、<b>重複したまま静かに main へ入る</b>
 * （過去に CMP-030 の重複が実在した）。</p>
 *
 * <p>重複そのものを事前に防ぐことはできない（同時刻に同じ main を見れば同じ答えになるのは
 * 道理である）。本テストが防ぐのは「<b>重複したまま静かに main へ入ること</b>」である。</p>
 *
 * <h2>重複が見つかったら</h2>
 * <ol>
 *   <li>どちらが後から {@code origin/main} へ merge されたか（コミット日時・PR番号）を確認する。</li>
 *   <li><b>後から入った側</b>が採番し直す — {@code git fetch origin main} してから
 *       {@code origin/main} 上の {@code docs/task-list.md} の最大 CMP 番号 +1 を新たに採る。</li>
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
 * <p>「重複が見つからない」ことと「そのそも CMP-ID を1件も抽出できていない」ことは別事象である。
 * ファイルパスの解決ミス・正規表現の破損・table形式の変更等で抽出が0件になった場合、
 * 「重複なし」という誤った緑を返してはならない。よって本テストは重複判定とは独立に、
 * <b>抽出できた CMP-ID の総数が一定数（現時点で30件以上実在）を下回っていないか</b>を検証する。
 * 逆に「常に重複がある」ことも前提にしない（重複ゼロが正常状態である）。</p>
 */
@DisplayName("番人: docs/task-list.md のCMP-IDが重複していないこと")
class TaskListCmpIdDuplicateGuardTest {

    /** {@code docs/task-list.md} のリポジトリルートからの相対パス。 */
    private static final Path TASK_LIST_RELATIVE = Paths.get("docs", "task-list.md");

    /** 表の行頭に現れる CMP-ID（例: {@code | CMP-028 | ...}）を抽出する。 */
    private static final Pattern CMP_ID_ROW = Pattern.compile("(?m)^\\|\\s*(CMP-\\d+)\\s*\\|");

    /**
     * 走査経路の自己検証用。「CMP-ID を含む表の行」を、ID 抽出用より<b>緩い</b>条件で数える。
     *
     * <p>{@link #CMP_ID_ROW} が取りこぼしなく抽出できているかを、この緩い計数との一致で確かめる。
     * <b>件数のしきい値を持たない</b>のが要点である。当初は「30件以上あること」で自己検証していたが、
     * それは<b>現在の状態を数字で焼き付ける</b>形であり、行のアーカイブ等で件数が減れば重複が無くても
     * 誤って落ちる（本日 {@code PagingTotalCountSizeGuardTest} が「違反ゼロ＝走査断絶」と誤断じて
     * 自壊した事例と同型の罠）。件数ではなく<b>抽出の網羅性</b>を見れば、母数が何件でも成立する。</p>
     */
    private static final Pattern CMP_ID_ROW_LOOSE = Pattern.compile("(?m)^\\|.*?(CMP-\\d+)");

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
        // 抽出0件（あるいは既知の実在数を大きく下回る）で「重複なし」を返すと、ファイルパスの
        // 解決ミスや table 形式の変更で走査が壊れていても気づけない偽の緑になる。よって
        // 抽出総数がしきい値を下回っていないかを、重複判定とは独立に検証する。
        int totalIds = occurrences.values().stream().mapToInt(Integer::intValue).sum();

        int looseCount = 0;
        Matcher loose = CMP_ID_ROW_LOOSE.matcher(content);
        while (loose.find()) {
            looseCount++;
        }

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
                        + "CMP_ID_ROW（\"| CMP-N |\" 形式）が実際の表フォーマットを取りこぼしており、"
                        + "取りこぼした行の重複を見逃す。CMP_ID_ROW と docs/task-list.md の"
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
                .append("原因: 並行セッションが同時に origin/main を fetch すると、双方が同じ最大 CMP 番号を")
                .append("読み同じ番号を採ってしまい、挿入位置の違いから git が競合検知できずに")
                .append("両方が自動マージされることがある（2026-08-14 CMP-028 消失事故と同根）。\n")
                .append("対処: 後から origin/main へ merge された側が採番し直すこと。")
                .append("git fetch origin main の上で、origin/main の docs/task-list.md にある")
                .append("最大 CMP 番号 +1 を新たに採り、行の内容（戦役名・状態・証拠）はそのまま")
                .append("IDのみ振り直して commit・PR を作成すること。\n")
                .append("本番人の検出を緩めて通す（重複IDをそのまま許容する等）ことは禁止。\n")
                .append("重複ID一覧:\n");
        for (Map.Entry<String, Integer> e : duplicates) {
            sb.append("  ✗✗ ").append(e.getKey()).append(" : ").append(e.getValue()).append("回出現\n");
        }
        assertThat(duplicates).as(sb.toString()).isEmpty();
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
