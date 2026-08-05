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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * バッチ規約番人 {@link ScheduledBatchGuardTest} の判定ロジックが<b>偽陰性ゼロ</b>である
 * ことを証明するメタテスト（issue #2601 Phase 1-a）。
 *
 * <h2>なぜ必要か</h2>
 * <p>本番人の 3 ルールのうち、<b>ルール 2（{@code lockAtMostFor} 明示）は発足時点で違反 0 件</b>
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
    // 走査の裏取り（fixture を実際に読めていることの証明）
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("裏取り: fixture を実際に読み込めている（空振りによる空虚 green の防止）")
    void fixtureを実際に読み込めている() {
        JavaClass fixture = fixtureClasses.get(ScheduledBatchFixtureBatch.class);

        assertThat(fixture.getMethods())
            .as("fixture のメソッドが読めていなければ、全アサーションが空リスト同士の比較になる")
            .hasSizeGreaterThanOrEqualTo(8);
        assertThat(fixture.getMethods().stream()
                .filter(ScheduledBatchGuardTest::isScheduled)
                .count())
            .as("fixture には 7 本のスケジュール済みメソッドを用意してある")
            .isEqualTo(7);
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
