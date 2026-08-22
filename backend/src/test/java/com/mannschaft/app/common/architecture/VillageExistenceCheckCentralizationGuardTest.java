package com.mannschaft.app.common.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 番人: 村エンティティの<b>存在確認</b>（ID で村を引く操作）は
 * {@code VillageAccessGate} 以外の本番コードで行ってはならない。
 *
 * <h2>なぜ禁じるのか（存在オラクルの再発経路）</h2>
 * <p>村ドメインには「<b>非公開(UNLISTED)村の存在を秘匿する</b>」契約がある。ところが村の存在確認は
 * 各サービスが {@code private VillageEntity loadActiveVillage(UUID)} を各自複製する形で散在しており、
 * <b>どれも {@code visibility} を判定していなかった</b>。その結果、非村人が任意の村 ID を叩くと
 * 「不在なら 404 ／ UNLISTED 村として実在すれば 403」と応答が割れ、
 * 応答の違いそのものが「その村は存在する」という情報を漏らしていた（＝存在オラクル）。</p>
 *
 * <p>根治として存在確認と可視性判定は
 * {@code com.mannschaft.app.village.service.VillageAccessGate} に一元化した。だが
 * <b>一元化は「今そうなっている」というだけの状態であり、規則ではない</b>。新しいサービスが
 * 追加されるたびに各自が {@code villageRepository.findById} を書けば、同じ穴が静かに再発する。
 * 本番ソースの静的走査でその再発を機械的に止めるのが本番人である。</p>
 *
 * <h2>なぜ ArchUnit の宣言的ルールではなくソース走査なのか</h2>
 * <ol>
 *   <li>対象メソッドの過半は {@code JpaRepository}（さらに上流の {@code CrudRepository}）から
 *       <b>継承した総称メソッド</b>である。継承メソッドの呼び出しに対して ArchUnit が記録する
 *       「呼び先の所有型」は宣言型側へ解決されうるため、
 *       {@code VillageRepository} を所有型として書いたルールが<b>一致件数ゼロのまま緑になる</b>
 *       （＝番人が偽陰性で死ぬ）危険がある。番人にとって最悪の壊れ方なので採らない。</li>
 *   <li>同じ村ドメインの先行番人 {@link VillageUnlistedErrorCodeRetirementGuardTest} が
 *       ソース走査型であり、流儀を揃えられる。</li>
 *   <li>違反箇所を <b>ファイル:行番号＋当該行のソース</b> で提示できるため、
 *       落ちた人がそのまま直せる（ArchUnit の既定メッセージより具体的）。</li>
 *   <li>ArchUnit ルールを増やさないため、凍結ストア
 *       （{@code src/test/resources/archunit_store/}）を一切汚さない。</li>
 * </ol>
 *
 * <p>走査の空振り（偽陰性）を自ら晒すため、
 * (a) {@code VillageAccessGate} 自身の呼び出しを検出できることを本テスト内で確認し（カナリア）、
 * (b) 走査ロジックの陽性・陰性対照を
 * {@link VillageExistenceCheckCentralizationGuardScanningLogicTest} に置く。</p>
 */
@DisplayName("番人: 村の存在確認は VillageAccessGate に一元化する（存在オラクル再発防止）")
class VillageExistenceCheckCentralizationGuardTest {

    private static final Path MAIN_SOURCE_ROOT = Paths.get("src", "main", "java");

    /** 唯一、村の存在確認を行ってよいクラス。 */
    private static final String GATE_PATH_SUFFIX =
            "/com/mannschaft/app/village/service/VillageAccessGate.java";

    /**
     * 存在確認とみなす {@code VillageRepository} のメソッド（ID で 1 件の村を引く操作）。
     *
     * <p>一覧・検索・集計（{@code findByDeletedAtIsNull} / {@code findPilgrimageCandidateIds} など）は
     * 「その ID の村が在るか」を問う操作ではないため対象外。存在オラクルは
     * <b>クライアントが指定した ID に対する応答差</b>から生まれるので、ID 起点の取得だけを見る。</p>
     */
    private static final Set<String> EXISTENCE_LOOKUP_METHODS = new LinkedHashSet<>(List.of(
            "findById",
            "findByIdAndDeletedAtIsNullAndArchivedAtIsNull",
            "existsById",
            "getReferenceById",
            "getById"));

    /**
     * 除外（ファイルパス接尾辞 → <b>なぜ除外してよいのかの理由</b>）。
     *
     * <p><b>理由を書けない除外を足してはならない。</b>理由欄は飾りではなく、
     * 「中身を確認せずに行を足す」ことを構造的に不可能にするための義務である
     * （{@link #everyExclusionHasReason()} が空の理由を機械的に拒否する）。
     * 除外してよいのは<b>操作者（リクエスト実行者）が存在しない</b>経路だけであり、
     * 「ゲート経由に直すのが面倒だから」は理由にならない。</p>
     */
    private static final Map<String, String> EXCLUSIONS = new LinkedHashMap<>();

    static {
        EXCLUSIONS.put("/com/mannschaft/app/village/batch/VillagePilgrimageBatchService.java",
                "巡礼推薦バッチ。操作者（リクエスト実行者）が存在しないシステム処理であり、"
                        + "推薦候補は findPilgrimageCandidateIds が SQL 側で visibility=PUBLIC に絞った ID 集合。"
                        + "取得は絞り込み済み ID の実体化であって『クライアント指定 ID の存在確認』ではないため、"
                        + "応答差から存在が漏れる経路が無い。");
        EXCLUSIONS.put("/com/mannschaft/app/village/batch/VillageHeadmanSuccessionBatchService.java",
                "村長継承バッチ。操作者が存在しないシステム処理で、村 ID はバッチ自身が走査した対象であり"
                        + "外部入力ではない。加えて結果は HTTP 応答に出ないため存在オラクルにならない。");
    }

    // ────────────────────────────────────────────────────────────
    // 走査ロジック（自己検証テストから直接呼べるよう package-private static で公開）
    // ────────────────────────────────────────────────────────────

    /** 違反 1 件。{@code path} は '/' 区切りのソースパス、{@code line} は 1 始まり。 */
    record Violation(String path, int line, String method, String sourceLine) {
        String render() {
            return path + ":" + line + "  [" + method + "]  " + sourceLine.trim();
        }
    }

    /** {@code VillageRepository} 型のフィールド／引数／ローカル変数の名前を拾う。 */
    private static final Pattern REPOSITORY_VARIABLE =
            Pattern.compile("\\bVillageRepository\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\b");

    /**
     * 与えられたソース群から違反を抽出する。
     *
     * <p>変数名を {@code villageRepository} に決め打ちせず、<b>ファイルごとに
     * {@code VillageRepository} 型の変数名を実際に読み取ってから</b>その変数への呼び出しを探す。
     * フィールド名を変えただけで番人をすり抜けられては意味がないためである。</p>
     *
     * <p>コメント・文字列リテラルは {@link JavaSourceScanningUtils#maskCommentsAndLiterals} で
     * 潰してから走査する（Javadoc の説明文を違反と誤検知しないため）。ただし失敗メッセージには
     * 読みやすさのため<b>原文の行</b>を載せる。</p>
     *
     * @param sources キー = '/' 区切りのソースパス、値 = ソース本文
     */
    static List<Violation> scan(Map<String, String> sources) {
        List<Violation> violations = new ArrayList<>();
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            String path = entry.getKey();
            String original = entry.getValue();
            String masked = JavaSourceScanningUtils.maskCommentsAndLiterals(original);

            Set<String> variables = new LinkedHashSet<>();
            Matcher varMatcher = REPOSITORY_VARIABLE.matcher(masked);
            while (varMatcher.find()) {
                variables.add(varMatcher.group(1));
            }
            if (variables.isEmpty()) {
                continue;
            }

            String[] maskedLines = masked.split("\n", -1);
            String[] originalLines = original.split("\n", -1);
            for (int i = 0; i < maskedLines.length; i++) {
                for (String variable : variables) {
                    for (String method : EXISTENCE_LOOKUP_METHODS) {
                        Pattern call = Pattern.compile(
                                "\\b" + Pattern.quote(variable) + "\\s*\\.\\s*"
                                        + Pattern.quote(method) + "\\s*\\(");
                        if (call.matcher(maskedLines[i]).find()) {
                            violations.add(new Violation(path, i + 1, method,
                                    i < originalLines.length ? originalLines[i] : ""));
                        }
                    }
                }
            }
        }
        return violations;
    }

    // ────────────────────────────────────────────────────────────
    // 本番ソースに対する適用
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("VillageAccessGate 以外は村の存在確認（ID 取得）を行わない")
    void villageExistenceCheckOnlyInAccessGate() throws IOException {
        assertTrue(Files.isDirectory(MAIN_SOURCE_ROOT),
                "本番ソースルートが見つかりません: " + MAIN_SOURCE_ROOT.toAbsolutePath()
                        + "（CWD=" + Paths.get("").toAbsolutePath() + "）");

        Map<String, String> sources = readMainSources();

        // カナリア: 走査そのものが空振りしていないことを、ゲート自身の呼び出しで毎回実証する。
        // （検出器は自分の偽陰性を最初に晒すこと。ここが 0 件なら「違反ゼロ」の緑は嘘である）
        String gateSource = sources.entrySet().stream()
                .filter(e -> e.getKey().endsWith(GATE_PATH_SUFFIX))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "VillageAccessGate が見つかりません（移動・改名した場合は GATE_PATH_SUFFIX を追随させること）: "
                                + GATE_PATH_SUFFIX));
        assertFalse(scan(Map.of(GATE_PATH_SUFFIX, gateSource)).isEmpty(),
                "走査が空振りしている: VillageAccessGate 自身の存在確認呼び出しすら検出できていない。"
                        + "リポジトリのメソッド名が変わった可能性がある（EXISTENCE_LOOKUP_METHODS を見直すこと）。");

        List<Violation> violations = scan(sources).stream()
                .filter(v -> !v.path().endsWith(GATE_PATH_SUFFIX))
                .filter(v -> EXCLUSIONS.keySet().stream().noneMatch(v.path()::endsWith))
                .toList();

        if (!violations.isEmpty()) {
            fail("""
                    村の存在確認（ID による村の取得）が VillageAccessGate の外で行われている。

                    非公開(UNLISTED)村は「不在」と区別がつかない応答でなければならず、
                    存在確認と可視性判定は VillageAccessGate に一元化されている。
                    各サービスが自前で村を引くと visibility の判定が抜け、
                    「不在なら 404 ／実在すれば 403」の応答差から村の存在が漏れる（存在オラクル）。

                    直し方: villageRepository から直接引かず、VillageAccessGate を注入して
                      - 書き込み・メンバー限定操作: gate.loadActiveVillage(villageId, actorUserId)
                      - 読み取り公開:               gate.loadReadableVillage(villageId, actorUserId)
                      - 可視かどうかの判定だけ:     gate.isVisibleTo(village, actorUserId)
                    を呼ぶこと。

                    正当な例外（操作者が存在しないバッチ等）であれば、
                    本テストの EXCLUSIONS に「なぜ存在オラクルにならないのか」の理由付きで登録すること
                    （理由の無い除外は everyExclusionHasReason() が拒否する）。

                    違反箇所（%d 件）:
                      %s""".formatted(violations.size(),
                    String.join("\n  ", violations.stream().map(Violation::render).toList())));
        }
    }

    @Test
    @DisplayName("除外エントリには必ず日本語の理由が書かれている")
    void everyExclusionHasReason() {
        List<String> missing = EXCLUSIONS.entrySet().stream()
                .filter(e -> e.getValue() == null || e.getValue().isBlank())
                .map(Map.Entry::getKey)
                .toList();
        assertTrue(missing.isEmpty(),
                "除外理由が空のエントリがある（なぜ存在オラクルにならないのかを書くこと）: " + missing);
    }

    private static Map<String, String> readMainSources() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        try (Stream<Path> stream = Files.walk(MAIN_SOURCE_ROOT)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .forEach(p -> {
                        String path = p.toString().replace(java.io.File.separatorChar, '/');
                        try {
                            sources.put(path, Files.readString(p, StandardCharsets.UTF_8));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        }
        return sources;
    }
}
