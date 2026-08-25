package com.mannschaft.app.common.architecture;

import com.mannschaft.app.common.architecture.BackgroundFeaturePolicyAnnotationGuardTest.Source;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.ThrowingSupplier;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.mannschaft.app.common.architecture.DateTimeAndZoneGuardTest.classCountMismatches;
import static com.mannschaft.app.common.architecture.SelfScopedEndpointMarkerGuardTest.mask;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;

/**
 * 番人: バックグラウンド入口（{@code @Scheduled} / {@code @TransactionalEventListener} /
 * {@code @EventListener} / {@code @SqsListener}）の<b>停止時挙動の宣言漏れ</b>と、
 * <b>止めてはならぬ域を止める宣言</b>を機械的に禁じる（Gate 基盤工事④-B 第一陣 / AC-1〜AC-7・AC-16）。
 *
 * <h2>④-A の番人との境界</h2>
 * <p>{@link BackgroundFeaturePolicyAnnotationGuardTest}（④-A）は
 * <b>付与された宣言そのものが正しいか</b>（キーの実在・ALWAYS への gateKeys 併記・
 * モードと付与先の食い違い・付与位置）だけを見る。
 * 本番人はその手前、<b>そもそも宣言があるか</b>と<b>その宣言を選んでよい場所か</b>を見る。</p>
 *
 * <h2>なぜ実付与より先に番人を点火するのか</h2>
 * <p>325 箇所への付与作業は繰り返せばいずれ終わる。終わらないのは
 * 「この先、誰かが新しいバッチを足したときに宣言を忘れる」ことである。
 * 番人と凍結台帳を先に置けば、④-B が終わった後も網に穴が開かない。
 * また台帳があることで、第二〜四陣の進捗が「残り何件」として機械的に可視化される。</p>
 *
 * <h2>最大の危険 — 法令上の期限を破るバッチが静かに止まる（AC-1）</h2>
 * <p>GDPR 消去バッチ・72 時間報告義務の事前アラート・保持期間超過削除・各ドメインの匿名化リスナーは、
 * いずれも {@code FEATURE_GDPR_DISCLOSURE_ENABLED} という gate_key を持つドメインに属している。
 * 素直にドメインの gate_key を当てて {@code SKIP_WHEN_DISABLED} にすると、
 * β公開前に管理画面からそのフラグを閉じた瞬間、<b>法令上の期限を破るバッチが静かに止まる</b>。
 * 同じ構図が outbox（未送信メールの滞留と再開時の一斉送信）・fanout・
 * 監査ログのパーティション保守（挿入先パーティションが枯渇して書き込み自体が失敗する。
 * 単なる遅延では済まない）・エスクロー（DB 上は確定・決済は未実行という乖離）・
 * 退会 Saga の進行役・消込・Google webhook チャネル更新
 * （失効すると再開しても自動復旧しない）にもある。</p>
 *
 * <p><b>この判断を人手の注意力に委ねてはならない。</b>
 * {@link #FORBIDDEN_TO_STOP} に属するメソッドへ {@code SKIP_WHEN_DISABLED} /
 * {@code DROP_WHEN_DISABLED} を書くことを、本番人が機械的に拒否する。
 * <b>免除リストは設けない</b>（禁止域から逃がす仕組みを作れば、番人は初日に骨抜きになる）。</p>
 *
 * <h2>空虚 green の防止</h2>
 * <p>禁止域リストが全部タイポで誰にも当たらない状態は、実ファイル走査では緑になる。
 * よって {@link #ac2_禁止域リストが実在するクラスを指していること()} が
 * 「各パターンが実コードの 1 クラス以上に当たること」を固定する（AC-2）。
 * さらに {@link 判定ロジック自己検証} が実ファイル走査と<b>同一コア</b>
 * （{@link #analyze} / {@link #mismatches}）に合成入力を通し、負例で違反が返ることを固定する。</p>
 *
 * <h2>方式（金型）</h2>
 * <ul>
 *   <li>走査: {@link BackgroundFeaturePolicyAnnotationGuardTest} の {@code Files.walk} 型・
 *       {@link SelfScopedEndpointMarkerGuardTest#mask} によるコメント/文字列マスク</li>
 *   <li>凍結台帳: {@link DateTimeAndZoneGuardTest} の
 *       {@code <種別>|<完全修飾クラス名>|<件数>}（<b>クラス単位件数</b>。メソッド名は含めない。
 *       PR #2725 の事故を踏まえた設計）。増減判定は同クラスの
 *       {@link DateTimeAndZoneGuardTest#classCountMismatches} を<b>そのまま再利用</b>する</li>
 * </ul>
 *
 * <p><b>ArchUnit の {@code FreezingArchRule} は使わない。</b>
 * {@code ./gradlew test --tests "..."} の絞り込み実行で凍結ストアが壊れる既知の事故があり、
 * ④-A も明示的に不採用としている。本テストはファイルを<b>読み取るだけ</b>で一切の書き込みを行わない。</p>
 */
@DisplayName("番人: バックグラウンド入口の停止時挙動が宣言されていること（Gate基盤工事④-B AC-1〜AC-7・AC-16）")
class BackgroundEntryPolicyDeclarationGuardTest {

    /** 凍結台帳。行形式: {@code <種別>|<完全修飾クラス名>|<件数>}。 */
    private static final Path FREEZE_FILE = Paths.get(
            "src", "test", "resources", "backgroundgate", "undeclared_background_entry_freeze.txt");

    /** 宣言アノテーションの単純名。 */
    private static final String POLICY = "BackgroundFeaturePolicy";

    /**
     * <b>止めてはならぬ域</b>。ここに属するメソッドは、止めた瞬間に既存データの整合性・
     * 法令上の期限・復旧不能な資源のいずれかが壊れる。よって {@code SKIP_WHEN_DISABLED} /
     * {@code DROP_WHEN_DISABLED} を選ぶこと自体を禁じる（選べるのは {@code ALWAYS} だけ）。
     *
     * <p>パターン記法: {@code **} は任意（ドットを跨ぐ）、{@code *} はドットを跨がない任意。
     * 全パターンが実在のクラスに当たることを AC-2 が機械検証する。</p>
     */
    static final List<String> FORBIDDEN_TO_STOP = List.of(
            // GDPR 消去（AccountPurgeService ほか）。止めると法令上の消去期限を破る。
            "com.mannschaft.app.gdpr.**",
            // GDPR 72時間報告義務の2時間前アラート。止めると通知が飛ばず期限を落とす。
            "com.mannschaft.app.securityincident.service.SecurityIncident70hAlertBatchService",
            // 情報開示物の保持期間超過削除。止めると保持期間を超えた個人データが残る。
            "com.mannschaft.app.disclosure.batch.DisclosureAutoDeleteBatchService",
            // 各ドメインの匿名化リスナー（約15本）。止めると退会者の PII が各ドメインに残る。
            "**.*AnonymizationEventListener",
            // Transactional Outbox。止めると未送信メールが積み上がり、再開時に一斉送信される。
            "com.mannschaft.app.mail.outbox.**",
            // 通知 fan-out。止めると配信ジョブが滞留する。
            "com.mannschaft.app.notification.fanout.**",
            // 監査ログのアーカイブ。止めると監査記録が本表に滞留する。
            "com.mannschaft.app.auth.service.AuditLogArchiveBatchService",
            // パーティション保守。止めると挿入先パーティションが枯渇し「書き込み自体が失敗」する。
            "com.mannschaft.app.auth.service.AuditLogPartitionMaintenanceBatchService",
            "com.mannschaft.app.analytics.service.PageViewPartitionMaintenanceBatchService",
            // エスクロー。止めると「DB上は確定・決済は未実行」という乖離が残る。
            "com.mannschaft.app.payment.escrow.**",
            // 論理削除の backfill 群。止めると消したはずの行が残り続ける。
            "**.*PurgeBackfillBatchService",
            // 退会 Saga の進行役。止めると Saga が中途半端な状態で凍結する。
            "com.mannschaft.app.quickmemo.service.WithdrawSagaJobBatchService",
            // 手数料の消込。止めると残高が合わなくなる。
            "com.mannschaft.app.payment.recovery.FeeReconciliationBatch",
            // Google webhook チャネル更新。失効すると再開しても自動復旧しない。
            "com.mannschaft.app.schedule.batch.GoogleWebhookChannelRenewalBatch");

    /** {@link #FORBIDDEN_TO_STOP} で禁じるモード。 */
    private static final Set<String> STOPPING_MODES = Set.of("SKIP_WHEN_DISABLED", "DROP_WHEN_DISABLED");

    /** バックグラウンド入口の種別（凍結台帳の第1列でもある）。 */
    enum EntryKind {
        SCHEDULED("Scheduled"),
        TRANSACTIONAL_EVENT_LISTENER("TransactionalEventListener"),
        EVENT_LISTENER("EventListener"),
        SQS_LISTENER("SqsListener");

        private final String annotationSimpleName;

        EntryKind(String annotationSimpleName) {
            this.annotationSimpleName = annotationSimpleName;
        }

        /** 注釈の単純名から種別を引く（該当しなければ null）。 */
        static EntryKind byAnnotation(String annotationSimpleName) {
            for (EntryKind k : values()) {
                if (k.annotationSimpleName.equals(annotationSimpleName)) {
                    return k;
                }
            }
            return null;
        }

        /** 台帳の第1列（種別名）から引く（該当しなければ null）。 */
        static EntryKind byLedgerName(String name) {
            for (EntryKind k : values()) {
                if (k.name().equals(name)) {
                    return k;
                }
            }
            return null;
        }
    }

    /** 検出した 1 入口。 */
    record Entry(String relPath, int line, String fqcn, EntryKind kind, boolean declared, String mode) {
        String where() {
            return relPath + ":" + line;
        }
    }

    /** 走査結果。 */
    record Scan(List<Entry> entries, int sourceCount) {
    }

    // ═══════════════════════════════════════════════════════════════════
    // AC-1: 止めてはならぬ域を止める宣言の禁止
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("(AC-1) 止めてはならぬ域に SKIP_WHEN_DISABLED / DROP_WHEN_DISABLED が付いていないこと")
    void ac1_禁止域を止める宣言が無いこと() throws IOException {
        List<String> violations = forbiddenStopViolations(scan().entries());

        assertThat(violations)
                .as("止めてはならぬ域のバックグラウンド入口に「停止する」宣言が付いています。\n"
                        + "ここを止めると、既存データの整合性・法令上の期限・復旧不能な資源のいずれかが壊れます。\n"
                        + "選べるのは ALWAYS（gateKeys 指定禁止・reason 必須）だけです。\n"
                        + "検出を緩めて通すことは禁止（免除リストは設けない）。宣言側を直すこと。\n"
                        + "違反一覧:\n" + String.join("\n", violations))
                .isEmpty();
    }

    // ═══════════════════════════════════════════════════════════════════
    // AC-2: 禁止域リストの空虚 green 防止
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("(AC-2) 禁止域リストが空でなく、全パターンが実在するクラスを指していること")
    void ac2_禁止域リストが実在するクラスを指していること() throws IOException {
        assertThat(FORBIDDEN_TO_STOP)
                .as("禁止域リストが空では AC-1 は永久に緑になる（空虚 green）")
                .isNotEmpty();

        List<String> fqcns = sources().stream()
                .map(BackgroundEntryPolicyDeclarationGuardTest::fqcnOf)
                .toList();
        assertThat(fqcns).as("本番ソースを1件も読めていない（走査根の想定が崩れている）").isNotEmpty();

        List<String> dead = new ArrayList<>();
        for (String pattern : FORBIDDEN_TO_STOP) {
            Pattern re = toRegex(pattern);
            if (fqcns.stream().noneMatch(f -> re.matcher(f).matches())) {
                dead.add("  x " + pattern);
            }
        }

        assertThat(dead)
                .as("禁止域リストのパターンが実コードの1クラスにも当たっていません。\n"
                        + "綴り間違い・パッケージ移動・クラス削除のいずれかです。\n"
                        + "当たらないパターンを放置すると、そのパターンは番人として何も守っていないのに\n"
                        + "リストに載っているという理由で「守られている」と誤読されます。\n"
                        + "当たらないパターン:\n" + String.join("\n", dead))
                .isEmpty();
    }

    // ═══════════════════════════════════════════════════════════════════
    // AC-3〜AC-6: 未宣言入口のクラス単位凍結台帳
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("(AC-3〜AC-6) 未宣言のバックグラウンド入口が凍結台帳と件数一致していること（増加・減少・新規クラス・陳腐化を検知）")
    void ac3to6_未宣言入口が凍結台帳と一致していること() throws IOException {
        Map<String, Integer> actual = undeclaredCountsByKey(scan().entries());
        Map<String, Integer> frozen = readFreeze();

        List<String> mismatches = mismatches(actual, frozen);

        assertThat(mismatches)
                .as("未宣言のバックグラウンド入口の件数が凍結台帳と一致しません。\n\n"
                        + "・増加／新規クラス → 新しく足した入口に @BackgroundFeaturePolicy を付けること。\n"
                        + "  台帳へ追記して通すことは禁止（台帳は「残債」であり免罪符ではない）。\n"
                        + "・減少 → 付与が進んだ証拠。台帳の該当行を実測値へ更新すること（chip-away）。\n"
                        + "・陳腐化 → 実コードに1件も無い行。台帳から削除すること。\n\n"
                        + "台帳: " + FREEZE_FILE + "\n"
                        + "不一致:\n" + String.join("\n", mismatches)
                        + "\n\n--- 実測値そのままの台帳本文（是正済みならこれで置き換えてよい） ---\n"
                        + renderFreeze(actual))
                .isEmpty();
    }

    // ═══════════════════════════════════════════════════════════════════
    // AC-7 / AC-16: 走査の実在性と所要時間
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("(AC-7) 走査が空振りしていないこと（ソースも入口も1件も読めていない状態で green にならない）")
    void ac7_走査が空振りしていないこと() throws IOException {
        Scan scan = scan();

        assertThat(scan.sourceCount())
                .as("本番ソースの走査件数が少なすぎる（CWD またはソースルートの想定が崩れている）: "
                        + sourceRoot().toAbsolutePath())
                .isGreaterThan(500);
        assertThat(scan.entries())
                .as("バックグラウンド入口を1件も検出できていない。パーサが壊れていれば AC-1・AC-3 は"
                        + "そろって空虚 green になる")
                .isNotEmpty();
    }

    @Test
    @DisplayName("(AC-16) 全ソース走査が CI の1テストとして現実的な時間で終わること（走査根は src/main/java に限定）")
    void ac16_走査が現実的な時間で終わること() {
        Path root = sourceRoot();

        assertThat(root.toString().replace('\\', '/'))
                .as("走査根はリポジトリ全体ではなく src/main/java に限定する（build 生成物やテストを"
                        + "巻き込むと所要時間が跳ね、検出対象も汚れる）")
                .endsWith("src/main/java");

        // この予算が守っているのは「走査が線形であること」であって、マシンの空き具合ではない。
        // 走査は O(n) だが、本テストは開発機で他の Gradle ビルドと並走しうる。
        // 実測（2026-08-25、ビルド4本並走中）で読み取り込みの1回が約64秒かかったため、
        // ファイル読み取りは sources() で1回に畳んだうえで、予算は余裕を持たせてある。
        // 破滅的バックトラック等で超線形になれば、この余裕をもってしても落ちる。
        assertTimeout(Duration.ofMinutes(3), (ThrowingSupplier<Scan>) this::scan,
                "全ソース走査が3分以内に終わらない。線形走査が壊れている（超線形になった）疑いがある");
    }

    // ═══════════════════════════════════════════════════════════════════
    // 判定コア（純関数。合成入力で偽陰性を暴けるように切り出してある）
    // ═══════════════════════════════════════════════════════════════════

    /** 禁止域を止める宣言を列挙する（AC-1 の判定本体）。 */
    static List<String> forbiddenStopViolations(List<Entry> entries) {
        List<Pattern> patterns = FORBIDDEN_TO_STOP.stream()
                .map(BackgroundEntryPolicyDeclarationGuardTest::toRegex)
                .toList();
        List<String> violations = new ArrayList<>();
        for (Entry e : entries) {
            if (!e.declared() || !STOPPING_MODES.contains(e.mode())) {
                continue;
            }
            if (patterns.stream().anyMatch(p -> p.matcher(e.fqcn()).matches())) {
                violations.add("  x " + e.where() + " — " + e.fqcn() + " は「止めてはならぬ域」だが "
                        + e.mode() + " が宣言されている（ALWAYS 以外は選べない）");
            }
        }
        return violations;
    }

    /** 未宣言入口を {@code <種別>|<FQCN>} 単位で数える。 */
    static Map<String, Integer> undeclaredCountsByKey(List<Entry> entries) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Entry e : entries) {
            if (!e.declared()) {
                counts.merge(e.kind().name() + "|" + e.fqcn(), 1, Integer::sum);
            }
        }
        return counts;
    }

    /**
     * 実測と台帳の差分を列挙する（AC-3〜AC-6）。
     *
     * <p>判定そのものは実績のある {@link DateTimeAndZoneGuardTest#classCountMismatches} を再利用する
     * （増加=fail / 減少=台帳更新を促して fail / 台帳未登録=fail / 陳腐化=fail、
     * 一致のみ pass という 4 方向の性質がそこで既に固定されている）。</p>
     */
    static List<String> mismatches(Map<String, Integer> actual, Map<String, Integer> frozen) {
        return classCountMismatches(actual, frozen);
    }

    /** 実測を台帳の行形式へ整形する（失敗メッセージに載せて手作業の写経を不要にする）。 */
    static String renderFreeze(Map<String, Integer> counts) {
        StringBuilder sb = new StringBuilder();
        counts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append(e.getKey()).append('|').append(e.getValue()).append('\n'));
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════════
    // 走査
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 本番ソースのキャッシュ。
     *
     * <p>本クラスの各テストはいずれも全ソース走査を要するため、素直に書くと
     * 7000 件超のファイル読み取りをテストの数だけ繰り返すことになる。
     * 実測（2026-08-25、Gradle ビルドが4本並走している最中）で、
     * 走査 1 回あたりの所要が 60 秒を超え AC-16 が落ちた。
     * 支配的なのは解析ではなくファイル I/O なので、読み取りは 1 回に畳んで共有する。</p>
     */
    private static List<Source> cachedSources;

    private static synchronized List<Source> sources() throws IOException {
        if (cachedSources == null) {
            cachedSources = loadSources();
        }
        return cachedSources;
    }

    private Scan scan() throws IOException {
        List<Source> sources = sources();
        List<Entry> entries = new ArrayList<>();
        for (Source s : sources) {
            entries.addAll(analyze(s));
        }
        return new Scan(entries, sources.size());
    }

    /**
     * 1 ソースからバックグラウンド入口を抽出する（判定コア。合成入力でも同じ経路を通る）。
     *
     * <p>注釈クラスタ（メソッド直前に連続して並ぶ注釈の並び）を単位に読む。
     * {@code @TransactionalEventListener} は {@code EventListener} を部分文字列に含むため、
     * 部分一致ではなく<b>注釈トークンの単純名</b>で突き合わせる。</p>
     */
    static List<Entry> analyze(Source src) {
        List<Entry> out = new ArrayList<>();
        String content = src.content();
        String masked = mask(content);
        String fqcn = fqcnOf(src);
        int n = masked.length();
        int i = 0;

        while (i < n) {
            if (masked.charAt(i) != '@') {
                i++;
                continue;
            }

            // ── 注釈クラスタを読む ────────────────────────────────
            List<String> names = new ArrayList<>();
            List<Integer> at = new ArrayList<>();
            List<int[]> args = new ArrayList<>();
            int j = i;
            boolean broken = false;

            while (j < n && masked.charAt(j) == '@') {
                int ns = j + 1;
                int ne = ns;
                while (ne < n && (Character.isJavaIdentifierPart(masked.charAt(ne)) || masked.charAt(ne) == '.')) {
                    ne++;
                }
                String raw = masked.substring(ns, ne);
                if (raw.isEmpty() || "interface".equals(raw)) {
                    // 「@interface」は注釈型宣言であって注釈ではない。
                    broken = true;
                    j = ne;
                    break;
                }
                int k = skipWs(masked, ne);
                int as = -1;
                int ae = -1;
                if (k < n && masked.charAt(k) == '(') {
                    int close = matchParen(masked, k);
                    if (close < 0) {
                        // 括弧が閉じていない（コンパイル不能）ソースは対象外。
                        broken = true;
                        break;
                    }
                    as = k + 1;
                    ae = close;
                    k = skipWs(masked, close + 1);
                }
                names.add(simpleName(raw));
                at.add(j);
                args.add(new int[]{as, ae});
                j = k;
            }

            if (broken || names.isEmpty()) {
                i = Math.max(j, i + 1);
                continue;
            }

            // ── 宣言がメソッドかを見る ────────────────────────────
            if (isMethodDeclaration(masked, j)) {
                int policyIdx = names.indexOf(POLICY);
                boolean declared = policyIdx >= 0;
                String mode = declared ? modeOf(masked, args.get(policyIdx)) : null;

                for (int idx = 0; idx < names.size(); idx++) {
                    EntryKind kind = EntryKind.byAnnotation(names.get(idx));
                    if (kind != null) {
                        out.add(new Entry(src.relPath(), lineOf(content, at.get(idx)), fqcn, kind, declared, mode));
                    }
                }
            }

            i = Math.max(j, i + 1);
        }
        return out;
    }

    /**
     * {@code @BackgroundFeaturePolicy} の引数からモード名を読む。
     *
     * <p>マスク済み本文を見るため、{@code reason} の文中に書かれたモード名には惑わされない
     * （文字列リテラルの中身はマスクで空白化されている）。</p>
     */
    private static String modeOf(String masked, int[] range) {
        if (range[0] < 0) {
            return null;
        }
        String body = masked.substring(range[0], range[1]);
        for (String candidate : List.of("SKIP_WHEN_DISABLED", "DROP_WHEN_DISABLED", "ALWAYS")) {
            if (containsWord(body, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** {@code cursor} 以降の宣言がメソッドか（{@code (} に先に到達するか）。 */
    private static boolean isMethodDeclaration(String masked, int cursor) {
        StringBuilder word = new StringBuilder();
        for (int i = cursor; i < masked.length(); i++) {
            char c = masked.charAt(i);
            if (c == '(') {
                return true;
            }
            if (c == '=' || c == ';' || c == '{' || c == '}') {
                return false;
            }
            if (Character.isJavaIdentifierPart(c)) {
                word.append(c);
            } else {
                String w = word.toString();
                if ("class".equals(w) || "interface".equals(w) || "enum".equals(w) || "record".equals(w)) {
                    return false;
                }
                word.setLength(0);
            }
        }
        return false;
    }

    // ── 小道具 ────────────────────────────────────────────────────────

    private static boolean containsWord(String s, String word) {
        int from = 0;
        while (true) {
            int i = s.indexOf(word, from);
            if (i < 0) {
                return false;
            }
            boolean leftOk = i == 0 || !Character.isJavaIdentifierPart(s.charAt(i - 1));
            int after = i + word.length();
            boolean rightOk = after >= s.length() || !Character.isJavaIdentifierPart(s.charAt(after));
            if (leftOk && rightOk) {
                return true;
            }
            from = after;
        }
    }

    private static int skipWs(String s, int i) {
        int k = i;
        while (k < s.length() && Character.isWhitespace(s.charAt(k))) {
            k++;
        }
        return k;
    }

    private static int matchParen(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String simpleName(String qualified) {
        int dot = qualified.lastIndexOf('.');
        return dot < 0 ? qualified : qualified.substring(dot + 1);
    }

    private static int lineOf(String content, int offset) {
        int line = 1;
        int limit = Math.min(offset, content.length());
        for (int i = 0; i < limit; i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** ファイルパスから完全修飾クラス名を復元する（package 宣言の解析より堅い）。 */
    static String fqcnOf(Source src) {
        String p = src.relPath().replace('\\', '/');
        int at = p.indexOf("src/main/java/");
        String rel = at < 0 ? p : p.substring(at + "src/main/java/".length());
        if (rel.endsWith(".java")) {
            rel = rel.substring(0, rel.length() - ".java".length());
        }
        return rel.replace('/', '.');
    }

    /** 禁止域パターンを正規表現へ変換する（{@code **}=ドットを跨ぐ任意 / {@code *}=跨がない任意）。 */
    static Pattern toRegex(String pattern) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c == '*') {
                if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
                    sb.append(".*");
                    i += 2;
                } else {
                    sb.append("[^.]*");
                    i++;
                }
            } else {
                sb.append(Pattern.quote(String.valueOf(c)));
                i++;
            }
        }
        return Pattern.compile(sb.toString());
    }

    // ═══════════════════════════════════════════════════════════════════
    // ファイル入出力（読み取りのみ。台帳へは一切書き込まない）
    // ═══════════════════════════════════════════════════════════════════

    private static Path sourceRoot() {
        for (String candidate : new String[]{"src/main/java", "backend/src/main/java"}) {
            Path p = Paths.get(candidate);
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        throw new IllegalStateException("src/main/java が見つからない（cwd=" + Paths.get("").toAbsolutePath() + "）");
    }

    private static List<Source> loadSources() throws IOException {
        Path root = sourceRoot();
        List<Source> out = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .forEach(p -> {
                        try {
                            out.add(new Source(p.toString().replace('\\', '/'),
                                    Files.readString(p, StandardCharsets.UTF_8)));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        }
        return out;
    }

    private static Map<String, Integer> readFreeze() throws IOException {
        Path path = null;
        for (Path candidate : new Path[]{FREEZE_FILE, Paths.get("backend").resolve(FREEZE_FILE)}) {
            if (Files.isRegularFile(candidate)) {
                path = candidate;
                break;
            }
        }
        if (path == null) {
            throw new IllegalStateException(
                    "凍結台帳が見つからない: " + FREEZE_FILE + "（cwd=" + Paths.get("").toAbsolutePath() + "）");
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] parts = trimmed.split("\\|", 3);
            if (parts.length != 3 || EntryKind.byLedgerName(parts[0]) == null) {
                throw new IllegalStateException("凍結台帳の行形式が不正: " + path + " の行 \"" + trimmed
                        + "\"。期待形式: <種別>|<FQCN>|<件数>");
            }
            counts.merge(parts[0] + "|" + parts[1], Integer.parseInt(parts[2]), Integer::sum);
        }
        return counts;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 判定ロジック自己検証（負例による陽性対照。実ファイル走査と同一コアを通す）
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("判定ロジック自己検証（合成入力の負例で違反が返ること）")
    class 判定ロジック自己検証 {

        /** 禁止域に実在する（AC-2 が「当たること」を保証している）クラスのパス。 */
        private static final String FORBIDDEN_PATH =
                "src/main/java/com/mannschaft/app/gdpr/service/AccountPurgeService.java";

        /** 禁止域に属さないクラスのパス。 */
        private static final String FREE_PATH =
                "src/main/java/com/mannschaft/app/sample/SampleBatchService.java";

        private List<Entry> entries(String path, String body) {
            return analyze(new Source(path, "class Synthetic {\n" + body + "\n}\n"));
        }

        @Test
        @DisplayName("(AC-1) 禁止域の @Scheduled に SKIP_WHEN_DISABLED を付けると違反になる")
        void ac1_禁止域のスキップ宣言を検出する() {
            List<Entry> es = entries(FORBIDDEN_PATH, """
                    @Scheduled(cron = "0 0 * * * *")
                    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
                            reason = "この宣言は番人に拒否されねばならない")
                    public void purge() {}
                    """);

            assertThat(forbiddenStopViolations(es)).hasSize(1);
        }

        @Test
        @DisplayName("(AC-1) 禁止域のリスナーに DROP_WHEN_DISABLED を付けると違反になる")
        void ac1_禁止域のドロップ宣言を検出する() {
            List<Entry> es = entries(FORBIDDEN_PATH, """
                    @TransactionalEventListener
                    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.DROP_WHEN_DISABLED,
                            reason = "この宣言は番人に拒否されねばならない")
                    public void onEvent(Object e) {}
                    """);

            assertThat(forbiddenStopViolations(es)).hasSize(1);
        }

        @Test
        @DisplayName("(AC-1) 禁止域でも ALWAYS なら違反にならない")
        void ac1_禁止域のALWAYSは通る() {
            List<Entry> es = entries(FORBIDDEN_PATH, """
                    @Scheduled(cron = "0 0 * * * *")
                    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
                            reason = "止めると法令上の消去期限を破るため必ず実行する")
                    public void purge() {}
                    """);

            assertThat(forbiddenStopViolations(es)).isEmpty();
        }

        @Test
        @DisplayName("(AC-1) 禁止域でないクラスの SKIP_WHEN_DISABLED は違反にならない（偽陽性が無い）")
        void ac1_禁止域外のスキップは通る() {
            List<Entry> es = entries(FREE_PATH, """
                    @Scheduled(cron = "0 0 * * * *")
                    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
                            reason = "未公開機能であり停止しても既存データの整合性は壊れない")
                    public void run() {}
                    """);

            assertThat(forbiddenStopViolations(es)).isEmpty();
        }

        @Test
        @DisplayName("(AC-1) reason 本文に SKIP_WHEN_DISABLED と書いてあってもモードは ALWAYS と読む")
        void ac1_理由文中のモード名に惑わされない() {
            List<Entry> es = entries(FORBIDDEN_PATH, """
                    @Scheduled(cron = "0 0 * * * *")
                    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
                            reason = "SKIP_WHEN_DISABLED は選べないため ALWAYS とする")
                    public void purge() {}
                    """);

            assertThat(forbiddenStopViolations(es)).isEmpty();
        }

        @Test
        @DisplayName("@TransactionalEventListener が EventListener として二重計上されない")
        void 種別は注釈トークンの単純名で判定される() {
            List<Entry> es = entries(FREE_PATH, """
                    @TransactionalEventListener
                    public void onEvent(Object e) {}
                    """);

            assertThat(es).hasSize(1);
            assertThat(es.get(0).kind()).isEqualTo(EntryKind.TRANSACTIONAL_EVENT_LISTENER);
        }

        @Test
        @DisplayName("完全修飾で書かれた注釈も単純名に落として認識する")
        void 完全修飾の注釈も認識する() {
            List<Entry> es = entries(FREE_PATH, """
                    @org.springframework.scheduling.annotation.Scheduled(cron = "0 0 * * * *")
                    public void run() {}
                    """);

            assertThat(es).singleElement().extracting(Entry::kind).isEqualTo(EntryKind.SCHEDULED);
        }

        @Test
        @DisplayName("宣言済み／未宣言が正しく分かれる")
        void 宣言の有無を判別する() {
            List<Entry> undeclared = entries(FREE_PATH, """
                    @Scheduled(cron = "0 0 * * * *")
                    public void run() {}
                    """);
            List<Entry> declared = entries(FREE_PATH, """
                    @Scheduled(cron = "0 0 * * * *")
                    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
                            reason = "止めると既存データの整合性が壊れるため必ず実行する")
                    public void run() {}
                    """);

            assertThat(undeclared).singleElement().extracting(Entry::declared).isEqualTo(false);
            assertThat(declared).singleElement().extracting(Entry::declared).isEqualTo(true);
        }

        @Test
        @DisplayName("メソッド以外（型・フィールド）に付いた同名注釈は入口として数えない")
        void メソッド宣言だけを入口とみなす() {
            assertThat(analyze(new Source(FREE_PATH, """
                    @Scheduled
                    class Synthetic {
                        @Scheduled
                        private String field = "x";
                    }
                    """))).isEmpty();
        }

        @Test
        @DisplayName("コメントアウトされた入口は数えない")
        void コメント内の注釈は無視される() {
            assertThat(analyze(new Source(FREE_PATH, """
                    class Synthetic {
                        // @Scheduled(cron = "0 0 * * * *")
                        // public void run() {}
                    }
                    """))).isEmpty();
        }

        @Test
        @DisplayName("(AC-3) 実測 > 台帳 なら fail（新規に足した入口の宣言忘れ）")
        void ac3_増加を検出する() {
            assertThat(mismatches(Map.of("SCHEDULED|a.B", 3), Map.of("SCHEDULED|a.B", 2)))
                    .anySatisfy(m -> assertThat(m).contains("増加"));
        }

        @Test
        @DisplayName("(AC-4) 実測 < 台帳 なら台帳更新を促して fail（chip-away）")
        void ac4_減少を検出する() {
            assertThat(mismatches(Map.of("SCHEDULED|a.B", 1), Map.of("SCHEDULED|a.B", 2)))
                    .anySatisfy(m -> assertThat(m).contains("減少"));
        }

        @Test
        @DisplayName("(AC-4) 台帳に無いクラスで実測があれば fail")
        void ac4_台帳未登録クラスを検出する() {
            assertThat(mismatches(Map.of("SCHEDULED|a.New", 1), Map.of()))
                    .anySatisfy(m -> assertThat(m).contains("新規クラス"));
        }

        @Test
        @DisplayName("(AC-5) 実測 = 台帳 なら pass（偽陽性が無い）")
        void ac5_一致なら通る() {
            assertThat(mismatches(
                    Map.of("SCHEDULED|a.B", 2, "EVENT_LISTENER|a.C", 1),
                    Map.of("SCHEDULED|a.B", 2, "EVENT_LISTENER|a.C", 1)))
                    .isEmpty();
        }

        @Test
        @DisplayName("(AC-6) 実測 0 なのに台帳に行が残っていれば陳腐化として fail")
        void ac6_陳腐化を検出する() {
            assertThat(mismatches(Map.of(), Map.of("SCHEDULED|a.Gone", 2)))
                    .anySatisfy(m -> assertThat(m).contains("陳腐化"));
        }

        @Test
        @DisplayName("同一 FQCN でも種別が違えば別の台帳キーになる")
        void 種別はキーの一部である() {
            assertThat(mismatches(Map.of("SCHEDULED|a.B", 1), Map.of("EVENT_LISTENER|a.B", 1)))
                    .hasSize(2);
        }

        @Test
        @DisplayName("(AC-2) 禁止域パターンの照合が実際に効いている（当たる／当たらないの両方）")
        void ac2_パターン照合が機能している() {
            assertThat(toRegex("com.mannschaft.app.gdpr.**")
                    .matcher("com.mannschaft.app.gdpr.service.AccountPurgeService").matches()).isTrue();
            assertThat(toRegex("**.*AnonymizationEventListener")
                    .matcher("com.mannschaft.app.auth.event.AuthAnonymizationEventListener").matches()).isTrue();
            // ドットは正規表現の「任意の1文字」ではなくリテラルとして扱われること。
            assertThat(toRegex("com.mannschaft.app.gdpr.**")
                    .matcher("com.mannschaft.app.gdprx.service.Foo").matches()).isFalse();
            assertThat(toRegex("com.mannschaft.app.payment.escrow.**")
                    .matcher("com.mannschaft.app.payment.recovery.FeeReconciliationBatch").matches()).isFalse();
        }
    }
}
