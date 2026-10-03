package com.mannschaft.app.common.architecture;

import com.mannschaft.app.common.architecture.fixtures.ScheduledBatchFixtureBatch;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;

import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * バッチ規約番人 {@link ScheduledBatchGuardTest} の判定ロジックが<b>偽陰性ゼロ</b>である
 * ことを証明するメタテスト（issue #2601 Phase 1-a）。
 *
 * <h2>なぜ必要か</h2>
 * <p>本番人の 4 ルールのうち、<b>ルール 2（{@code lockAtMostFor} 明示）は発足時点で違反 0 件</b>
 * ＝ green 発足である。しかし<b>「違反 0 件」は「番人が動いている」ことの証明にはならない</b> ——
 * 判定ロジックが常に空リストを返す壊れ方をしていても、本番走査は緑のままだからである。
 * green 発足するルールこそ、意図的違反 fixture に対して<b>違反が返ること</b>を assert しなければ、
 * 静かに空虚化して「新規流入の防止」という本来の目的を果たさなくなる。</p>
 *
 * <p>そこで既存の {@link CacheableAuthzEnforcementGuardConditionTest} と同じ流儀で、
 * {@code architecture/fixtures/} に意図的な違反を置き、番人の<b>判定の単一正準</b>である
 * static ヘルパを fixture 限定で評価する（判定ロジックの二重実装を避け、
 * メタテストが実際の判定を検証していることを保証する）。</p>
 *
 * <h2>とりわけ {@code @Repeatable} の取りこぼし</h2>
 * <p>{@link Scheduled} は {@code @Repeatable(Schedules.class)} であり、1 メソッドに 2 つ以上
 * 書くと javac は {@code @Scheduled} を直接付けず {@link Schedules} コンテナだけを出力する。
 * {@code isAnnotatedWith(Scheduled.class)} だけを見る素朴な実装は、この形を丸ごと取り逃す。
 * 本メタテストは fixture の {@code repeatableScheduledMissingSchedulerLock} で
 * <b>コンテナ形式でも番人が拾うこと</b>を実証する。</p>
 */
@DisplayName("バッチ規約 番人 判定ロジックの偽陰性ゼロ証明（メタテスト）")
class ScheduledBatchGuardConditionTest {

    private static final String FIXTURES_PACKAGE =
        "com.mannschaft.app.common.architecture.fixtures";

    private static JavaClasses fixtureClasses;

    @BeforeAll
    static void importFixtures() {
        fixtureClasses = new ClassFileImporter().importPackages(FIXTURES_PACKAGE);
    }

    // ══════════════════════════════════════════════════════════════════
    // ルール 1: @Scheduled ⇒ @SchedulerLock 必須
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ルール1 違反: @SchedulerLock の無い @Scheduled は検出される")
    void ロック無しのスケジュールは違反として検出される() {
        List<String> violations =
            ScheduledBatchGuardTest.findMissingSchedulerLock(method("missingSchedulerLock"));

        assertThat(violations)
            .as("ここが空になるなら、複数 Pod での多重実行を止める番人が機能していない")
            .isNotEmpty();
        assertThat(violations.getFirst())
            .as("違反メッセージには是正の手掛かり（対象メソッドの完全名）が含まれるべき")
            .contains("missingSchedulerLock");
    }

    @Test
    @DisplayName("ルール1 @Repeatable: @Schedules コンテナに包まれた形もスケジュールとして拾える")
    void 複数スケジュール指定のバッチも番人の対象になる() {
        JavaMethod repeatable = method("repeatableScheduledMissingSchedulerLock");

        assertThat(repeatable.isAnnotatedWith(Scheduled.class))
            .as("前提の実証: @Scheduled を 2 つ書くと javac は @Scheduled を直接は付けない。"
                + "この前提が崩れたら（Java の仕様変更等）本メタテストの意味が変わるため明示的に固定する")
            .isFalse();
        assertThat(repeatable.isAnnotatedWith(Schedules.class))
            .as("代わりに @Schedules コンテナが付く").isTrue();

        assertThat(ScheduledBatchGuardTest.isScheduled(repeatable))
            .as("番人は @Schedules コンテナもスケジュールとして認識すべき").isTrue();
        assertThat(ScheduledBatchGuardTest.findMissingSchedulerLock(repeatable))
            .as("areAnnotatedWith(Scheduled.class) だけを見る実装は、複数スケジュール指定の"
                + "バッチを丸ごと取り逃す。最も見逃したくない複雑なバッチほど漏れるため致命的")
            .isNotEmpty();
    }

    @Test
    @DisplayName("ルール1 正当形: @PodLocalScheduled で監査済み宣言された @Scheduled は違反にならない")
    void Podローカル監査済みは違反にならない() {
        assertThat(ScheduledBatchGuardTest.findMissingSchedulerLock(method("podLocalScheduled")))
            .as("ロックを掛けると敗者 Pod のバッファが永久に残る型は、"
                + "マーカーで理由を明示したうえで除外できなければ運用できない")
            .isEmpty();
    }

    @Test
    @DisplayName("ルール1 正当形: @SchedulerLock を備えた @Scheduled は違反にならない")
    void ロック済みは違反にならない() {
        assertThat(ScheduledBatchGuardTest.findMissingSchedulerLock(method("fullyCompliant")))
            .isEmpty();
    }

    @Test
    @DisplayName("ルール1 対象外: スケジュールされていないメソッドを巻き込まない")
    void 非スケジュールメソッドは対象外() {
        assertThat(ScheduledBatchGuardTest.findMissingSchedulerLock(method("notScheduledAtAll")))
            .as("@Scheduled の無いメソッドを違反にすると番人が使い物にならない")
            .isEmpty();
    }

    // ══════════════════════════════════════════════════════════════════
    // ルール 2: @SchedulerLock ⇒ lockAtMostFor 明示必須（green 発足＝要・偽陰性証明）
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ルール2 違反: lockAtMostFor を書かない @SchedulerLock は検出される")
    void lockAtMostFor未指定は違反として検出される() {
        List<String> violations = ScheduledBatchGuardTest
            .findMissingLockAtMostFor(method("schedulerLockWithoutLockAtMostFor"));

        assertThat(violations)
            .as("本ルールは本番コードに違反 0 件で発足するため、"
                + "ここが空になると『番人が動いていない』ことに誰も気づけない。"
                + "本アサーションが本ルールの生死を握っている")
            .isNotEmpty();
        assertThat(violations.getFirst())
            .as("違反メッセージにはロック名が含まれ、どの @SchedulerLock かが特定できるべき")
            .contains("fixtureLockWithoutLockAtMostFor");
    }

    @Test
    @DisplayName("ルール2 正当形: lockAtMostFor を明示した @SchedulerLock は違反にならない")
    void lockAtMostFor明示済みは違反にならない() {
        assertThat(ScheduledBatchGuardTest.findMissingLockAtMostFor(method("fullyCompliant")))
            .isEmpty();
        assertThat(ScheduledBatchGuardTest.findMissingLockAtMostFor(method("missingBatchEndpoint")))
            .isEmpty();
    }

    @Test
    @DisplayName("ルール2 対象外: @SchedulerLock の無いメソッドを巻き込まない")
    void ロック注釈の無いメソッドはルール2の対象外() {
        assertThat(ScheduledBatchGuardTest.findMissingLockAtMostFor(method("missingSchedulerLock")))
            .as("ロックが無いことはルール 1 の担当であり、ルール 2 が二重に鳴ってはならない")
            .isEmpty();
    }

    // ══════════════════════════════════════════════════════════════════
    // ルール 3: @Scheduled ⇒ @BatchEndpoint 必須
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ルール3 違反: @BatchEndpoint の無い @Scheduled は検出される")
    void BatchEndpoint欠落は違反として検出される() {
        List<String> violations =
            ScheduledBatchGuardTest.findMissingBatchEndpoint(method("missingBatchEndpoint"));

        assertThat(violations).isNotEmpty();
        assertThat(violations.getFirst()).contains("missingBatchEndpoint");
    }

    @Test
    @DisplayName("ルール3 @Repeatable: @Schedules コンテナ形式でも @BatchEndpoint 欠落を検出する")
    void 複数スケジュール指定でもBatchEndpoint欠落を検出する() {
        assertThat(ScheduledBatchGuardTest
                .findMissingBatchEndpoint(method("repeatableScheduledMissingSchedulerLock")))
            .as("ルール 3 も同じ isScheduled() を通るため、コンテナ形式で取りこぼしてはならない")
            .isNotEmpty();
    }

    @Test
    @DisplayName("ルール3 正当形: @BatchEndpointExempt で監査済み宣言された @Scheduled は違反にならない")
    void 履歴登録除外の監査済みは違反にならない() {
        assertThat(ScheduledBatchGuardTest.findMissingBatchEndpoint(method("batchEndpointExempt")))
            .as("数秒間隔の高頻度ワーカーは、理由を明示したうえで除外できなければ"
                + "履歴テーブルが無意味な記録で埋まる")
            .isEmpty();
    }

    @Test
    @DisplayName("ルール3 正当形: @BatchEndpoint を備えた @Scheduled は違反にならない")
    void BatchEndpoint済みは違反にならない() {
        assertThat(ScheduledBatchGuardTest.findMissingBatchEndpoint(method("fullyCompliant")))
            .isEmpty();
    }

    // ══════════════════════════════════════════════════════════════════
    // ルール 4: lockAtMostFor > 実行間隔（高頻度バッチ）
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ルール4 違反（最も危険な同値）: lockAtMostFor が実行間隔とぴったり同じなら検出される")
    void lockAtMostForが実行間隔と同値なら違反として検出される() {
        List<String> violations = ScheduledBatchGuardTest
            .findInsufficientLockAtMostFor(method("lockAtMostForEqualToCronInterval"));

        assertThat(violations)
            .as("同値は『処理が 1 ミリ秒でも超過した瞬間に次周回と重なる』最も危険な形であり、"
                + "ここを通すなら本ルールは存在しないに等しい")
            .isNotEmpty();
        assertThat(violations.getFirst())
            .as("違反メッセージには是正の手掛かり（対象メソッドの完全名）が含まれるべき")
            .contains("lockAtMostForEqualToCronInterval");
    }

    @Test
    @DisplayName("ルール4 違反: lockAtMostFor が cron 間隔より短いなら検出される")
    void lockAtMostForがcron間隔より短ければ違反として検出される() {
        assertThat(ScheduledBatchGuardTest
                .findInsufficientLockAtMostFor(method("lockAtMostForShorterThanCronInterval")))
            .isNotEmpty();
    }

    @Test
    @DisplayName("ルール4 違反: fixedRate（ミリ秒数値）に対する不足も検出される")
    void fixedRateに対する不足も検出される() {
        assertThat(ScheduledBatchGuardTest
                .findInsufficientLockAtMostFor(method("lockAtMostForEqualToFixedRate")))
            .as("cron だけを見る実装は fixedRate / fixedDelay のバッチを丸ごと取り逃す")
            .isNotEmpty();
    }

    @Test
    @DisplayName("ルール4 違反: fixedDelay に対する不足も検出される")
    void fixedDelayに対する不足も検出される() {
        assertThat(ScheduledBatchGuardTest
                .findInsufficientLockAtMostFor(method("lockAtMostForShorterThanFixedDelay")))
            .isNotEmpty();
    }

    @Test
    @DisplayName("ルール4 判定不能: 既定値の無い cron プレースホルダは安全側に倒して検出される")
    void 既定値の無いcronプレースホルダは違反として検出される() {
        assertThat(ScheduledBatchGuardTest
                .findInsufficientLockAtMostFor(method("undecidableCronPlaceholder")))
            .as("判定不能を通してしまうと『cron を外部プロパティへ追い出す』が"
                + "番人の抜け道になる。安全側＝落とす、が本ルールの選択である")
            .isNotEmpty();
    }

    @Test
    @DisplayName("ルール4 @Repeatable: @Schedules コンテナ内の高頻度スケジュールも検査される")
    void 複数スケジュール指定でも高頻度側の不足を検出する() {
        JavaMethod repeatable = method("repeatableScheduledWithInsufficientLock");

        assertThat(repeatable.isAnnotatedWith(Schedules.class))
            .as("前提の実証: @Scheduled を 2 つ書くと @Schedules コンテナに包まれる").isTrue();
        assertThat(ScheduledBatchGuardTest.findInsufficientLockAtMostFor(repeatable))
            .as("日次（安全）と 5 分間隔（危険）が同居する形で、コンテナを開かない実装は"
                + "危険な方を丸ごと取り逃す")
            .isNotEmpty();
    }

    @Test
    @DisplayName("ルール4 正当形: lockAtMostFor が実行間隔を上回るなら違反にならない")
    void 実行間隔を上回るlockAtMostForは違反にならない() {
        assertThat(ScheduledBatchGuardTest
                .findInsufficientLockAtMostFor(method("lockAtMostForExceedsCronInterval")))
            .isEmpty();
    }

    @Test
    @DisplayName("ルール4 適用範囲: 低頻度（日次）バッチは短い lockAtMostFor でも違反にならない")
    void 低頻度バッチは対象外() {
        assertThat(ScheduledBatchGuardTest
                .findInsufficientLockAtMostFor(method("dailyBatchWithShorterLock")))
            .as("日次バッチに 24 時間超のロックを強いると、Pod 異常終了時に"
                + "バッチが丸一日停止する。適用範囲を高頻度に限るのが本ルールの設計である")
            .isEmpty();
    }

    @Test
    @DisplayName("ルール4 正当形: 既定値付き cron プレースホルダは既定値から間隔を解決できる")
    void 既定値付きcronプレースホルダは解決できる() {
        assertThat(ScheduledBatchGuardTest
                .findInsufficientLockAtMostFor(method("resolvableCronPlaceholder")))
            .isEmpty();
    }

    @Test
    @DisplayName("ルール4 対象外: @SchedulerLock / lockAtMostFor が無いメソッドを巻き込まない")
    void ルール4はルール1と2の担当分を鳴らさない() {
        assertThat(ScheduledBatchGuardTest.findInsufficientLockAtMostFor(method("missingSchedulerLock")))
            .as("ロックが無いことはルール 1 の担当").isEmpty();
        assertThat(ScheduledBatchGuardTest
                .findInsufficientLockAtMostFor(method("schedulerLockWithoutLockAtMostFor")))
            .as("lockAtMostFor 未指定はルール 2 の担当であり、ルール 4 が二重に鳴ってはならない")
            .isEmpty();
    }

    @Test
    @DisplayName("ルール4 部品: ShedLock の期間表記（ISO-8601 / 5m / 300000）を正しく解釈する")
    void ShedLockの期間表記を正しく解釈する() {
        // ShedLock 6.2.0 の StringToDurationConverter と同じ仕様（ISO-8601 と <数値><単位>、
        // 単位省略時はミリ秒）を実装していることを固定する。ここがずれると比較そのものが嘘になる。
        assertThat(ScheduledBatchGuardTest.parseShedLockDuration("PT5M")).isEqualTo(Duration.ofMinutes(5));
        assertThat(ScheduledBatchGuardTest.parseShedLockDuration("5m")).isEqualTo(Duration.ofMinutes(5));
        assertThat(ScheduledBatchGuardTest.parseShedLockDuration("30s")).isEqualTo(Duration.ofSeconds(30));
        assertThat(ScheduledBatchGuardTest.parseShedLockDuration("2h")).isEqualTo(Duration.ofHours(2));
        assertThat(ScheduledBatchGuardTest.parseShedLockDuration("300000"))
            .as("単位を省略した数値はミリ秒（分と誤読すると 300 分と判定して違反を見逃す）")
            .isEqualTo(Duration.ofMinutes(5));
        assertThat(ScheduledBatchGuardTest.parseShedLockDuration("gomi")).isNull();
    }

    @Test
    @DisplayName("ルール4 部品: cron 式の最小発火間隔を算出できる")
    void cron式の最小発火間隔を算出できる() {
        assertThat(ScheduledBatchGuardTest.minimumCronInterval("0 */5 * * * *", ZoneOffset.UTC))
            .isEqualTo(Duration.ofMinutes(5));
        assertThat(ScheduledBatchGuardTest.minimumCronInterval("0 0 * * * *", ZoneOffset.UTC))
            .isEqualTo(Duration.ofHours(1));
        assertThat(ScheduledBatchGuardTest.minimumCronInterval("0 0 3 * * *", ZoneOffset.UTC))
            .isEqualTo(Duration.ofDays(1));
        assertThat(ScheduledBatchGuardTest.minimumCronInterval("0 0 9 * * MON", ZoneOffset.UTC))
            .as("週次は 7 日間隔として算出できるべき")
            .isEqualTo(Duration.ofDays(7));
        assertThat(ScheduledBatchGuardTest.minimumCronInterval("0 0,30 3 * * *", ZoneOffset.UTC))
            .as("1 日に複数回発火する形では『最も短い間隔』を採るべき（最長を採ると見逃す）")
            .isEqualTo(Duration.ofMinutes(30));
        assertThat(ScheduledBatchGuardTest.minimumCronInterval("これは cron ではない", ZoneOffset.UTC))
            .isNull();
    }

    // ══════════════════════════════════════════════════════════════════
    // 走査の裏取り（fixture を実際に読めていることの証明）
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("裏取り: fixture を実際に読み込めている（空振りによる空虚 green の防止）")
    void fixtureを実際に読み込めている() {
        JavaClass fixture = fixtureClasses.get(ScheduledBatchFixtureBatch.class);

        assertThat(fixture.getMethods())
            .as("fixture のメソッドが読めていなければ、全アサーションが空リスト同士の比較になる")
            .hasSizeGreaterThanOrEqualTo(19);
        assertThat(fixture.getMethods().stream()
                .filter(ScheduledBatchGuardTest::isScheduled)
                .count())
            .as("fixture には 18 本のスケジュール済みメソッドを用意してある")
            .isEqualTo(18);
    }

    // ══════════════════════════════════════════════════════════════════
    // ルール 5: @SchedulerLock ⇒ プリミティブ戻り値禁止
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ルール5 違反: @SchedulerLock 付きで int を返すメソッドは検出される")
    void プリミティブ戻り値は違反として検出される() {
        List<String> violations = ScheduledBatchGuardTest
            .findPrimitiveReturningSchedulerLock(method("primitiveReturningSchedulerLock"));

        assertThat(violations)
            .as("ここが空になるなら、ShedLock が実行のたびに必ず失敗する事故（issue #2724）を"
                + "番人が二度と検出できない")
            .isNotEmpty();
        assertThat(violations.getFirst())
            .as("違反メッセージには是正の手掛かり（対象メソッドの完全名）が含まれるべき")
            .contains("primitiveReturningSchedulerLock");
    }

    @Test
    @DisplayName("ルール5 正当形: void を返す @SchedulerLock は違反にならない")
    void void戻り値は違反にならない() {
        assertThat(ScheduledBatchGuardTest.findPrimitiveReturningSchedulerLock(method("fullyCompliant")))
            .isEmpty();
    }

    @Test
    @DisplayName("ルール5 正当形: 参照型（Integer）を返す @SchedulerLock は違反にならない")
    void 参照型戻り値は違反にならない() {
        assertThat(ScheduledBatchGuardTest
                .findPrimitiveReturningSchedulerLock(method("boxedReturningSchedulerLock")))
            .isEmpty();
    }

    @Test
    @DisplayName("ルール5 対象外: @SchedulerLock の無いメソッドを巻き込まない")
    void ロック注釈の無いメソッドはルール5の対象外() {
        assertThat(ScheduledBatchGuardTest.findPrimitiveReturningSchedulerLock(method("missingSchedulerLock")))
            .isEmpty();
    }

    // ── ヘルパー ──────────────────────────────────────────────────────

    /** fixture の指定メソッドを取得する。 */
    private static JavaMethod method(String methodName) {
        return fixtureClasses.get(ScheduledBatchFixtureBatch.class).getMethods().stream()
            .filter(m -> m.getName().equals(methodName))
            .findFirst()
            .orElseThrow(() -> new AssertionError("fixture メソッドが見つからない: " + methodName));
    }
}
