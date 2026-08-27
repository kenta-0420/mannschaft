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
            "com.mannschaft.app.schedule.batch.GoogleWebhookChannelRenewalBatch",

            // ── ④-B 第三陣で追加（第二陣の登録漏れ。実測で洗い出した） ──────────
            //
            // GDPR 消去（AccountPurgedEvent 購読）の本体。止めると第17条の消去期限を直接破る。
            // 初版は `**.*AnonymizationEventListener` しか登録しておらず、
            // 「命名が違うだけで役割は同じ」本群が丸ごと素通りしていた。
            // 実測: AccountPurgedEvent の購読クラスは 14。内訳は
            //   *PurgeEventListener 9 / *AnonymizationEventListener 4 / 下記 Lifecycle 1。
            "**.*PurgeEventListener",
            // 名前が役割を表していない AccountPurgedEvent 購読者（上記2パターンのどちらにも当たらない）。
            // 命名規約に頼った登録では拾えないため FQCN で個別に釘を打つ。
            "com.mannschaft.app.returnstayplan.event.ReturnStayPlanLifecycleListener",
            // 保持期間超過削除。止めると保持期限を超えた個人データが残留する
            // （DisclosureAutoDeleteBatchService と同種）。
            "com.mannschaft.app.proxy.batch.ProxyInputRecordRetentionJob",
            // 死亡・転居のライフイベントに応じた代理入力同意の失効。
            // 止めると本人が既に存在しないのに同意書が有効なまま残る。
            "com.mannschaft.app.proxy.batch.ProxyConsentLifeEventJob",
            // 封緘解除の 72h TTL 再封緘。止めると機微情報が開示されたまま残る（復旧不能な露出）。
            "com.mannschaft.app.succession.batch.AutoResealBatchService",
            // 外部（Stripe）で徴収が成立した後に発火する結果記録。
            // 止めると「外部は決済済み・自システムに記録なし」という復旧不能な金銭の乖離が残る。
            "com.mannschaft.app.recruitment.service.RecruitmentCancellationFeeResultListener",
            // 組織削除に伴う提携プロバイダ行の清掃。止めると孤児行が恒久的に残る。
            "com.mannschaft.app.pointcard.listener.PointCardOrganizationDeletedListener",

            // ── ④-B 第三陣・殿の裁定により ALWAYS 固定（迷ったら ALWAYS に倒す） ──
            //
            // 誤って ALWAYS にした損は「閉じた機能の上でバッチが無害に空回りする」だけだが、
            // 誤って SKIP/DROP にした損は「法令や金が静かに壊れ、誰も気づかない」。
            // この二つは釣り合わないため、釣り合わない賭けでは安いほうの損を選ぶ。
            //
            // 手動再実行経路が無く、止めた月が恒久的に未請求になる。
            "com.mannschaft.app.advertising.campaign.service.AdMessagingBillingBridge",
            // Stripe の与信は期限で失効する。遅延がそのまま取りはぐれになる。
            "com.mannschaft.app.recruitment.service.RecruitmentPaymentRetryBatch",
            // インシデント SLA は期限に縛られる。
            "com.mannschaft.app.incident.service.IncidentSlaBatchService",
            // 上流（event ドメイン）が非ゲートのため、落とすと代理出席と投票代理が乖離する。
            "com.mannschaft.app.proxyvote.listener.EventDelegationAcceptedListener",
            // 上流（payment）が別キー。エスカレーションの新規生成は本リスナーが唯一の経路。
            "com.mannschaft.app.succession.service.DelinquencyEscalationListener",
            // 予算消費の記録と取消は「対」であり、片方だけ止まれば予算残高が壊れる。
            // 対になっているものを別々に扱わないため、1 パターンで両方に釘を打つ。
            "com.mannschaft.app.shiftbudget.listener.ShiftBudgetConsumption*Listener",

            // ── ④-B 第三陣・Codex 検分と全数洗い出しで判明した「追いつけない」群 ──────
            //
            // 病型: SKIP_WHEN_DISABLED は「止めても再開後に追いつける」ことを暗黙の前提にする。
            // 下記はいずれも対象期間を today から導出する no-arg 入口しか持たず、
            // 停止期間を跨ぐとその期間分を二度と生成できない（恒久的な欠測が残る）。
            // いずれも外部送信を伴わない内部処理であり、閉栓中に空回りしても害は無い。
            //
            // 月次 KPI スナップショット（createSnapshot は private・常に前月固定）。
            "com.mannschaft.app.analytics.service.MonthlyKpiSnapshotBatchService",
            // 代理入力の月次サマリ PDF（BatchEndpoint の手動実行も同じ no-arg を呼ぶ）。
            "com.mannschaft.app.proxy.batch.ProxyMonthlySummaryBatchJob",
            // ポイントリセット（現在月に一致する設定しか取らない）／ランキングスナップショット／
            // バッジ評価（MONTHLY_RANK）。いずれも期間を跨ぐと取り戻せない。
            "com.mannschaft.app.gamification.service.Gamification*BatchService",
            // ページビュー日次集計。execute は常に前日固定で、対象日を指定して再実行できる
            // 【運用経路が存在しない】（aggregateForDate は public だが呼び手ゼロ。
            // AnalyticsBackfillService が面倒を見るのは DailyAggregation と MonthlyCohort だけ）。
            "com.mannschaft.app.analytics.service.PageViewDailyAggregationBatchService",
            // 資格の失効ステータス更新。止めると期限切れ資格が ACTIVE のまま残る。
            // リマインダー送信（停止可）とは判定が正反対のため別クラスへ切り出してある。
            "com.mannschaft.app.skill.service.SkillExpiryStatusUpdateBatchService",
            // プレゼンス（所在）履歴・コイントス履歴の保持期間超過削除。
            // 通知系（停止可）と同居していたため禁止域に登録できなかったので切り出した。
            "com.mannschaft.app.family.service.FamilyRetentionCleanupBatchService",

            // ── 第三の型: 上流が同じ gate_key で閉じないリスナー（Codex 検分 P1 と全数照合） ──
            //
            // DROP_WHEN_DISABLED が安全なのは【そのイベントを発火する上流が、同じ gate_key で
            // 一緒に閉じるとき】だけである。上流が閉じなければイベントは閉栓中も飛んでき、
            // そして黙って消える。SKIP は「自分が動かない」だけだが、DROP は
            // 「他人が投げたものを捨てる」。この非対称を見落とすと別ドメインが片肺のまま進む。
            // 下記はいずれも上流が別ゲートまたは CORE で閉じないため ALWAYS 以外を選べない。
            //
            // 上流 Incident は FEATURE_MODERATION_INCIDENT_ENABLED で独立に CONFIRMED へ進む。
            // 落とすと F09.13 §5.2 の incidentId 付き履歴パッケージと相互リンクが欠落する。
            "com.mannschaft.app.property.event.PropertyWorkPackageEventListener",
            // 上流の計測ビーコンは全ページ共通で、解析機能のゲートでは閉じない。
            "com.mannschaft.app.analytics.event.PageViewRecordListener",
            // 上流のタイムライン投稿とログイン（認証）は CORE であり、ゲーミフィケーションでは閉じない。
            "com.mannschaft.app.gamification.event.GamificationPointListener");

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

        /** この種別の注釈の単純名。 */
        String annotationSimpleName() {
            return annotationSimpleName;
        }
    }

    /**
     * {@code @Repeatable} なアノテーションのコンテナ（単純名）→ 中身の種別。
     *
     * <p><b>取り逃しの罠</b>: {@code @Scheduled} は {@code @Repeatable(Schedules.class)} であり、
     * 1 メソッドに 2 つ以上書くと javac は {@code @Scheduled} を直接付けず
     * {@code @Schedules({@Scheduled(...), @Scheduled(...)})} コンテナに包む。
     * 単純名で {@code Scheduled} だけを見る番人は<b>複数スケジュール指定のバッチを丸ごと取り逃す</b>。
     * 取り逃した入口は台帳を1件も動かさないため、<b>未宣言のまま完全に素通りする</b>
     * （{@code TEST_CONVENTION.md} §9「番人自体のテスト（@Repeatable の罠）」が明示的に禁じている）。</p>
     *
     * <p>実測（javap、2026-08-26）で {@code @Repeatable} なのは {@code @Scheduled} のみであり、
     * {@code @EventListener} / {@code @TransactionalEventListener} / {@code @SqsListener} は違う。
     * 将来 Spring 側で repeatable 化された場合に黙って取り逃さないよう、
     * {@link 判定ロジック自己検証#repeatable_なコンテナが全て登録されていること()} が
     * 実クラスをリフレクションで見て登録漏れを落とす。</p>
     */
    private static final Map<String, EntryKind> REPEATABLE_CONTAINERS =
            Map.of("Schedules", EntryKind.SCHEDULED);

    /** 検出した 1 入口。 */
    record Entry(String relPath, int line, String fqcn, EntryKind kind, boolean declared, String mode) {
        String where() {
            return relPath + ":" + line;
        }
    }

    /**
     * 走査結果。
     *
     * <p><b>ソース本文は保持しない。</b>本番ソースは 7000 件超あり、本文を一括で抱えると
     * 数百 MB の String がテスト JVM に載る。本テストは Spring 結合テストと同じ
     * Gradle テストワーカー上で走るため、それらのヒープを圧迫して
     * {@code OutOfMemoryError} の引き金になりうる（実際に CI の shard 5 が
     * ヒープダンプ 6.9GB を吐いて落ちた）。よって走査は<b>1ファイルずつ読んで捨てる</b>方式とし、
     * 残すのは入口一覧（数百件）と FQCN 一覧（数千件の短い文字列）だけにする。</p>
     */
    record Scan(List<Entry> entries, int sourceCount, List<String> fqcns) {
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

        List<String> fqcns = scan().fqcns();
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
        List<Entry> entries = scan().entries();
        Map<String, Integer> actualUndeclared = undeclaredCountsByKey(entries);
        Map<String, Integer> actualTotals = totalCountsByKey(entries);
        Freeze frozen = readFreeze();

        List<String> mismatches = allMismatches(actualUndeclared, actualTotals, frozen);

        assertThat(mismatches)
                .as("バックグラウンド入口の件数が凍結台帳と一致しません。\n\n"
                        + "【未宣言件数】\n"
                        + "・増加／新規クラス → 新しく足した入口に @BackgroundFeaturePolicy を付けること。\n"
                        + "  台帳へ追記して通すことは禁止（台帳は「残債」であり免罪符ではない）。\n"
                        + "・減少 → 付与が進んだ証拠。台帳の該当行を実測値へ更新すること（chip-away）。\n"
                        + "・陳腐化 → 実コードに1件も無い行。台帳から削除すること。\n\n"
                        + "【総数】入口そのものを増減させたときだけ動く。宣言を付けただけなら総数は変わらない。\n"
                        + "・増加 → 入口を新設した。その入口に宣言を付けたうえで台帳の総数を更新すること。\n"
                        + "  （この列があるおかげで「1件に宣言を付けつつ未宣言を1件足す」相殺が検出できる）\n\n"
                        + "台帳: " + FREEZE_FILE + "\n"
                        + "不一致:\n" + String.join("\n", mismatches)
                        + "\n\n--- 実測値そのままの台帳本文（是正済みならこれで置き換えてよい） ---\n"
                        + renderFreeze(actualUndeclared, actualTotals))
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
        // ファイル読み取りは 1 件ずつ読んで捨てる方式にしたうえで、予算は余裕を持たせてある。
        // 破滅的バックトラック等で超線形になれば、この余裕をもってしても落ちる。
        assertTimeout(Duration.ofMinutes(3),
                (ThrowingSupplier<Scan>) BackgroundEntryPolicyDeclarationGuardTest::freshScan,
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

    /** コンテナ単純名に対応する入口種別（未登録なら null）。自己検証テスト用。 */
    static EntryKind repeatableContainerFor(String containerSimpleName) {
        return REPEATABLE_CONTAINERS.get(containerSimpleName);
    }

    /** 台帳のキー。{@code <種別>|<FQCN>}。 */
    private static String keyOf(Entry e) {
        return e.kind().name() + "|" + e.fqcn();
    }

    /**
     * 入口の総数（宣言済み＋未宣言）を {@code <種別>|<FQCN>} 単位で数える。
     *
     * <p><b>なぜ総数まで凍結するのか</b>: 未宣言件数だけを凍結すると、
     * 同一クラス内で「既存の1件に宣言を付ける」と「新しい未宣言の入口を1件足す」を
     * 同時に行ったとき、未宣言件数が<b>相殺されて台帳と一致したまま</b>になり、
     * 新しく足した入口が宣言なしで素通りする。第二〜四陣はまさに
     * 「宣言を付けていく」作業なので、この経路は現実に踏まれる。</p>
     *
     * <p>メソッド名を台帳へ載せれば個別に追えるが、それは PR #2725 の事故
     * （本件と無関係なメソッド名変更だけで CI が赤くなる）を再来させるため採らない。
     * 代わりに総数を併せて凍結する。宣言を付けるだけなら
     * 「未宣言 −1・総数 ±0」で chip-away として通り、入口を足せば
     * 「総数 +1」となって<b>相殺されようがなく検出</b>できる。</p>
     */
    static Map<String, Integer> totalCountsByKey(List<Entry> entries) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Entry e : entries) {
            counts.merge(keyOf(e), 1, Integer::sum);
        }
        return counts;
    }

    /**
     * 未宣言入口を {@code <種別>|<FQCN>} 単位で数える。
     *
     * <p>キー集合は {@link #totalCountsByKey} と<b>必ず一致</b>させる
     * （入口が1件でもあるクラスは、全件宣言済みでも 0 件として載せる）。
     * 揃えないと、全件宣言済みになったクラスが「陳腐化」と判定され、
     * まだ意味のある総数の凍結行まで消せと迫られてしまう。</p>
     */
    static Map<String, Integer> undeclaredCountsByKey(List<Entry> entries) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Entry e : entries) {
            counts.putIfAbsent(keyOf(e), 0);
        }
        for (Entry e : entries) {
            if (!e.declared()) {
                counts.merge(keyOf(e), 1, Integer::sum);
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

    /**
     * 未宣言件数と総数の<b>両方</b>を台帳と突き合わせる（AC-3〜AC-6 の判定本体）。
     *
     * <p>総数の比較があることで、同一クラス内での「宣言を1件付ける＋未宣言を1件足す」という
     * 相殺（未宣言件数だけ見ていると台帳と一致したまま素通りする）を検出できる。
     * 実証は {@code 判定ロジック自己検証.同一クラス内の入れ替えを総数で検出する()}。</p>
     */
    static List<String> allMismatches(Map<String, Integer> actualUndeclared,
                                      Map<String, Integer> actualTotals,
                                      Freeze frozen) {
        List<String> out = new ArrayList<>();
        for (String m : mismatches(actualUndeclared, frozen.undeclared())) {
            out.add("[未宣言] " + m);
        }
        for (String m : mismatches(actualTotals, frozen.totals())) {
            out.add("[総数] " + m);
        }
        return out;
    }

    /** 実測を台帳の行形式へ整形する（失敗メッセージに載せて手作業の写経を不要にする）。 */
    static String renderFreeze(Map<String, Integer> undeclared, Map<String, Integer> totals) {
        StringBuilder sb = new StringBuilder();
        totals.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append(e.getKey())
                        .append('|').append(undeclared.getOrDefault(e.getKey(), 0))
                        .append('|').append(e.getValue())
                        .append('\n'));
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════════
    // 走査
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 走査結果のキャッシュ（本文は含まない。{@link Scan} の Javadoc 参照）。
     *
     * <p>本クラスの各テストはいずれも全ソース走査を要するため、素直に書くと
     * 7000 件超のファイル読み取りをテストの数だけ繰り返すことになる。
     * 実測（2026-08-25、Gradle ビルドが4本並走している最中）で走査 1 回あたりが 60 秒を超え、
     * AC-16 が落ちた。支配的なのは解析ではなくファイル I/O なので結果を共有する。</p>
     */
    private static Scan cachedScan;

    private static synchronized Scan scan() throws IOException {
        if (cachedScan == null) {
            cachedScan = freshScan();
        }
        return cachedScan;
    }

    /**
     * 全ソースを走査する（キャッシュを使わない実走）。
     *
     * <p>ファイルは 1 件ずつ読んで解析し、<b>本文は直ちに捨てる</b>。
     * 同時にメモリへ載るソース本文は常に 1 件分だけである。</p>
     */
    private static Scan freshScan() throws IOException {
        Path root = sourceRoot();
        List<Entry> entries = new ArrayList<>();
        List<String> fqcns = new ArrayList<>();
        int[] count = {0};

        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .forEach(p -> {
                        String relPath = p.toString().replace('\\', '/');
                        String content;
                        try {
                            content = Files.readString(p, StandardCharsets.UTF_8);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                        // Source は本ループの中だけで生き、本文は次の反復で回収される。
                        Source src = new Source(relPath, content);
                        count[0]++;
                        fqcns.add(fqcnOf(src));
                        entries.addAll(analyze(src));
                    });
        }
        return new Scan(entries, count[0], fqcns);
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
                    String name = names.get(idx);
                    int line = lineOf(content, at.get(idx));

                    EntryKind kind = EntryKind.byAnnotation(name);
                    if (kind != null) {
                        out.add(new Entry(src.relPath(), line, fqcn, kind, declared, mode));
                        continue;
                    }

                    // @Repeatable のコンテナ（@Schedules 等）は中身を展開して1件ずつ数える。
                    // ここを飛ばすと複数指定のバッチが台帳を動かさず素通りする
                    // （REPEATABLE_CONTAINERS の Javadoc 参照）。
                    EntryKind contained = REPEATABLE_CONTAINERS.get(name);
                    if (contained != null) {
                        int nested = countNestedAnnotations(masked, args.get(idx), contained.annotationSimpleName());
                        for (int c = 0; c < nested; c++) {
                            out.add(new Entry(src.relPath(), line, fqcn, contained, declared, mode));
                        }
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

    /**
     * {@code @Repeatable} コンテナの引数の中に、包まれた注釈が何個あるかを数える。
     *
     * <p>マスク済み本文を見るため、文字列リテラルの中に現れる同名トークンには反応しない。</p>
     *
     * @param masked    マスク済みソース
     * @param range     コンテナの引数範囲（{@code [開始, 終了)}。引数が無ければ {@code range[0] < 0}）
     * @param innerName 包まれた注釈の単純名（例 {@code Scheduled}）
     * @return 出現数（引数が無ければ 0）
     */
    private static int countNestedAnnotations(String masked, int[] range, String innerName) {
        if (range[0] < 0) {
            return 0;
        }
        String body = masked.substring(range[0], range[1]);
        int count = 0;
        int from = 0;
        while (true) {
            int at = body.indexOf('@', from);
            if (at < 0) {
                return count;
            }
            int ns = at + 1;
            int ne = ns;
            while (ne < body.length()
                    && (Character.isJavaIdentifierPart(body.charAt(ne)) || body.charAt(ne) == '.')) {
                ne++;
            }
            if (simpleName(body.substring(ns, ne)).equals(innerName)) {
                count++;
            }
            from = Math.max(ne, at + 1);
        }
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

    /** 凍結台帳の内容（未宣言件数と総数の2次元）。 */
    record Freeze(Map<String, Integer> undeclared, Map<String, Integer> totals) {
    }

    /**
     * 凍結台帳を読む。行形式は {@code <種別>|<FQCN>|<未宣言件数>|<総数>}。
     *
     * <p>総数を併せて凍結する理由は {@link #totalCountsByKey} の Javadoc を参照。
     * 3列だった旧形式は<b>受理しない</b>（黙って総数0として読むと、
     * 総数による相殺検出が全クラスで無効化されたまま緑になるため）。</p>
     */
    private static Freeze readFreeze() throws IOException {
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
        Map<String, Integer> undeclared = new LinkedHashMap<>();
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] parts = trimmed.split("\\|", 4);
            if (parts.length != 4 || EntryKind.byLedgerName(parts[0]) == null) {
                throw new IllegalStateException("凍結台帳の行形式が不正: " + path + " の行 \"" + trimmed
                        + "\"。期待形式: <種別>|<FQCN>|<未宣言件数>|<総数>");
            }
            String key = parts[0] + "|" + parts[1];
            int u = Integer.parseInt(parts[2]);
            int t = Integer.parseInt(parts[3]);
            if (u > t) {
                throw new IllegalStateException("凍結台帳の行が矛盾している（未宣言件数が総数を超える）: "
                        + path + " の行 \"" + trimmed + "\"");
            }
            undeclared.merge(key, u, Integer::sum);
            totals.merge(key, t, Integer::sum);
        }
        return new Freeze(undeclared, totals);
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

        /** {@link #FREE_PATH} に対応する完全修飾クラス名。 */
        private static final String FREE_FQCN = "com.mannschaft.app.sample.SampleBatchService";

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

        // ───────────────────────────────────────────────────────────────
        // ④-B 第三陣で追加した禁止域の負例（登録しただけで実証しないと空虚になる）
        // ───────────────────────────────────────────────────────────────

        /** GDPR 消去（AccountPurgedEvent 購読）の本体。命名が Anonymization と違うだけで役割は同じ。 */
        private static final String PURGE_LISTENER_PATH =
                "src/main/java/com/mannschaft/app/billing/BillingPurgeEventListener.java";

        /** 名前が役割を表していない AccountPurgedEvent 購読者。 */
        private static final String LIFECYCLE_LISTENER_PATH =
                "src/main/java/com/mannschaft/app/returnstayplan/event/ReturnStayPlanLifecycleListener.java";

        /** 停止期間を跨ぐと二度と生成できない月次スナップショット。 */
        private static final String NO_CATCHUP_PATH =
                "src/main/java/com/mannschaft/app/analytics/service/MonthlyKpiSnapshotBatchService.java";

        @Test
        @DisplayName("(AC-1) *PurgeEventListener に DROP_WHEN_DISABLED を付けると違反になる（第二陣の登録漏れの再発防止）")
        void ac1_パージリスナーのドロップ宣言を検出する() {
            List<Entry> es = entries(PURGE_LISTENER_PATH, """
                    @TransactionalEventListener
                    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.DROP_WHEN_DISABLED,
                            gateKeys = "FEATURE_BILLING_PAYMENT_ENABLED",
                            reason = "この宣言は番人に拒否されねばならない（GDPR 消去は落とせない）")
                    public void onAccountPurged(Object e) {}
                    """);

            assertThat(forbiddenStopViolations(es))
                    .as("AccountPurgedEvent を購読する *PurgeEventListener を落とすと消去期限を破る")
                    .hasSize(1);
        }

        @Test
        @DisplayName("(AC-1) 名前が役割を表さない AccountPurgedEvent 購読者も禁止域として拒否される")
        void ac1_ライフサイクルリスナーのドロップ宣言を検出する() {
            List<Entry> es = entries(LIFECYCLE_LISTENER_PATH, """
                    @TransactionalEventListener
                    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.DROP_WHEN_DISABLED,
                            reason = "この宣言は番人に拒否されねばならない（命名規約では拾えない域）")
                    public void onAccountPurged(Object e) {}
                    """);

            assertThat(forbiddenStopViolations(es))
                    .as("命名規約に当たらない購読者は FQCN 登録でしか守れない")
                    .hasSize(1);
        }

        @Test
        @DisplayName("(AC-1) 停止期間を跨ぐと追いつけないバッチに SKIP_WHEN_DISABLED を付けると違反になる")
        void ac1_追いつけないバッチのスキップ宣言を検出する() {
            List<Entry> es = entries(NO_CATCHUP_PATH, """
                    @Scheduled(cron = "0 0 4 1 * *")
                    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
                            gateKeys = "FEATURE_TRANSLATION_SEARCH_ENABLED",
                            reason = "この宣言は番人に拒否されねばならない（前月固定で追いつけない）")
                    public void execute() {}
                    """);

            assertThat(forbiddenStopViolations(es))
                    .as("対象期間を today から導出する no-arg 入口は停止期間分を取り戻せない")
                    .hasSize(1);
        }

        @Test
        @DisplayName("(AC-1) 新規登録した禁止域でも ALWAYS なら違反にならない（偽陽性が無い）")
        void ac1_新規禁止域のALWAYSは通る() {
            List<Entry> es = entries(PURGE_LISTENER_PATH, """
                    @TransactionalEventListener
                    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
                            reason = "止めると GDPR 第17条の消去期限を直接破るため必ず実行する")
                    public void onAccountPurged(Object e) {}
                    """);

            assertThat(forbiddenStopViolations(es)).isEmpty();
        }

        /** 上流が別ゲートで閉じないため DROP を選べないリスナー（第三の型）。 */
        private static final String CROSS_GATE_LISTENER_PATH =
                "src/main/java/com/mannschaft/app/property/event/PropertyWorkPackageEventListener.java";

        @Test
        @DisplayName("(AC-1) 上流が同じ gate_key で閉じないリスナーに DROP_WHEN_DISABLED を付けると違反になる")
        void ac1_上流が閉じないリスナーのドロップ宣言を検出する() {
            List<Entry> es = entries(CROSS_GATE_LISTENER_PATH, """
                    @TransactionalEventListener
                    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.DROP_WHEN_DISABLED,
                            gateKeys = "FEATURE_PROPERTY_REPAIRPLAN_ENABLED",
                            reason = "この宣言は番人に拒否されねばならない（上流の Incident は別ゲートで進む）")
                    public void onIncidentStatusChanged(Object e) {}
                    """);

            assertThat(forbiddenStopViolations(es))
                    .as("上流が閉じないなら DROP はイベントを永久に失う。選べるのは ALWAYS だけ")
                    .hasSize(1);
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

        // ── @Repeatable コンテナの取り逃し（TEST_CONVENTION.md §9） ──────────

        @Test
        @DisplayName("@Schedules コンテナに包まれた複数の @Scheduled を取り逃さない（素通り防止）")
        void repeatable_コンテナの中身を展開して数える() {
            List<Entry> es = entries(FREE_PATH, """
                    @Schedules({
                        @Scheduled(cron = "0 0 3 * * *"),
                        @Scheduled(cron = "0 0 15 * * *")
                    })
                    public void run() {}
                    """);

            assertThat(es)
                    .as("@Scheduled は @Repeatable(Schedules.class) であり、2つ以上書くと javac は "
                            + "@Schedules コンテナに包む。コンテナを展開しないと、この未宣言バッチは "
                            + "台帳を1件も動かさずに完全に素通りする")
                    .hasSize(2);
            assertThat(es).allSatisfy(e -> {
                assertThat(e.kind()).isEqualTo(EntryKind.SCHEDULED);
                assertThat(e.declared()).isFalse();
            });
        }

        @Test
        @DisplayName("@Schedules コンテナに宣言が付いていれば、展開後の全件が宣言済みになる")
        void repeatable_コンテナへの宣言は展開後の全件に効く() {
            List<Entry> es = entries(FREE_PATH, """
                    @Schedules({
                        @Scheduled(cron = "0 0 3 * * *"),
                        @Scheduled(cron = "0 0 15 * * *")
                    })
                    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
                            reason = "止めると既存データの整合性が壊れるため必ず実行する")
                    public void run() {}
                    """);

            assertThat(es).hasSize(2);
            assertThat(es).allSatisfy(e -> assertThat(e.declared()).isTrue());
        }

        @Test
        @DisplayName("(AC-1) 禁止域の @Schedules に SKIP_WHEN_DISABLED を付けると展開後の全件が違反になる")
        void repeatable_禁止域のコンテナも検出される() {
            List<Entry> es = entries(FORBIDDEN_PATH, """
                    @Schedules({
                        @Scheduled(cron = "0 0 3 * * *"),
                        @Scheduled(cron = "0 0 15 * * *")
                    })
                    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
                            reason = "この宣言は番人に拒否されねばならない")
                    public void run() {}
                    """);

            assertThat(forbiddenStopViolations(es)).hasSize(2);
        }

        @Test
        @DisplayName("コンテナの中身は外側の走査で二重計上されない")
        void repeatable_二重計上されない() {
            List<Entry> es = entries(FREE_PATH, """
                    @Schedules({@Scheduled(cron = "0 0 3 * * *")})
                    public void run() {}
                    """);

            assertThat(es).hasSize(1);
        }

        @Test
        @DisplayName("@Repeatable なコンテナが全て登録されていること（将来 Spring 側で repeatable 化されたら落ちる）")
        void repeatable_なコンテナが全て登録されていること() {
            Map<EntryKind, Class<? extends java.lang.annotation.Annotation>> annotations = Map.of(
                    EntryKind.SCHEDULED, org.springframework.scheduling.annotation.Scheduled.class,
                    EntryKind.EVENT_LISTENER, org.springframework.context.event.EventListener.class,
                    EntryKind.TRANSACTIONAL_EVENT_LISTENER,
                    org.springframework.transaction.event.TransactionalEventListener.class,
                    EntryKind.SQS_LISTENER, io.awspring.cloud.sqs.annotation.SqsListener.class);

            assertThat(annotations.keySet())
                    .as("入口種別を1つでも取りこぼすと、その種別の repeatable 化に気づけない")
                    .containsExactlyInAnyOrder(EntryKind.values());

            List<String> unregistered = new ArrayList<>();
            annotations.forEach((kind, type) -> {
                java.lang.annotation.Repeatable r =
                        type.getAnnotation(java.lang.annotation.Repeatable.class);
                if (r == null) {
                    return;
                }
                String container = r.value().getSimpleName();
                if (!kind.equals(repeatableContainerFor(container))) {
                    unregistered.add(type.getSimpleName() + " は @Repeatable(" + container
                            + ") だが REPEATABLE_CONTAINERS に " + kind + " として登録されていない");
                }
            });

            assertThat(unregistered)
                    .as("コンテナを登録しないと、その注釈を1メソッドに2つ書いたバッチが番人を素通りする")
                    .isEmpty();
        }

        // ── 同一クラス内の入れ替え（件数相殺）の検出 ────────────────────

        @Test
        @DisplayName("同一クラス内で「宣言を1件付ける＋未宣言を1件足す」入れ替えを総数で検出する")
        void 同一クラス内の入れ替えを総数で検出する() {
            // 台帳: 入口2件のうち1件が未宣言。
            Freeze frozen = new Freeze(Map.of("SCHEDULED|a.C", 1), Map.of("SCHEDULED|a.C", 2));

            // 実測: 既存の未宣言1件に宣言を付け、同時に新しい未宣言入口を1件足した。
            // 未宣言は 1 のまま（相殺）だが、入口の総数は 2 → 3 に増えている。
            Map<String, Integer> actualUndeclared = Map.of("SCHEDULED|a.C", 1);
            Map<String, Integer> actualTotals = Map.of("SCHEDULED|a.C", 3);

            // 未宣言件数だけを見ていると素通りする（この番人が塞いだ穴そのもの）。
            assertThat(mismatches(actualUndeclared, frozen.undeclared()))
                    .as("未宣言件数だけの比較では相殺して一致してしまう。これが塞ぐべき穴である")
                    .isEmpty();

            // 総数を併せて見ると検出できる。
            assertThat(allMismatches(actualUndeclared, actualTotals, frozen))
                    .anySatisfy(m -> assertThat(m).contains("[総数]").contains("増加"));
        }

        @Test
        @DisplayName("宣言を付けただけ（入口を増やしていない）なら総数は動かず、chip-away の案内だけが出る")
        void 宣言を付けただけなら総数は動かない() {
            Freeze frozen = new Freeze(Map.of("SCHEDULED|a.C", 1), Map.of("SCHEDULED|a.C", 2));

            Map<String, Integer> actualUndeclared = Map.of("SCHEDULED|a.C", 0);
            Map<String, Integer> actualTotals = Map.of("SCHEDULED|a.C", 2);

            List<String> ms = allMismatches(actualUndeclared, actualTotals, frozen);

            assertThat(ms).anySatisfy(m -> assertThat(m).contains("[未宣言]").contains("減少"));
            assertThat(ms).noneSatisfy(m -> assertThat(m).contains("[総数]"));
        }

        @Test
        @DisplayName("宣言済み・未宣言が揃って台帳と一致していれば pass（偽陽性が無い）")
        void 両次元が一致すれば通る() {
            Freeze frozen = new Freeze(Map.of("SCHEDULED|a.C", 1), Map.of("SCHEDULED|a.C", 2));

            assertThat(allMismatches(Map.of("SCHEDULED|a.C", 1), Map.of("SCHEDULED|a.C", 2), frozen))
                    .isEmpty();
        }

        @Test
        @DisplayName("全件宣言済みのクラスも未宣言0件として台帳キーに残る（陳腐化と誤判定しない）")
        void 全件宣言済みでもキーは残る() {
            List<Entry> es = entries(FREE_PATH, """
                    @Scheduled(cron = "0 0 3 * * *")
                    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
                            reason = "止めると既存データの整合性が壊れるため必ず実行する")
                    public void run() {}
                    """);

            Map<String, Integer> undeclared = undeclaredCountsByKey(es);
            Map<String, Integer> totals = totalCountsByKey(es);

            assertThat(undeclared).containsEntry("SCHEDULED|" + FREE_FQCN, 0);
            assertThat(totals).containsEntry("SCHEDULED|" + FREE_FQCN, 1);
            assertThat(undeclared.keySet()).isEqualTo(totals.keySet());
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
