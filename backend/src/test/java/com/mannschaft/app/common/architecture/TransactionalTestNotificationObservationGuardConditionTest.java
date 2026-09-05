package com.mannschaft.app.common.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.mannschaft.app.common.architecture.TransactionalTestNotificationObservationGuardTest.Violation;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link TransactionalTestNotificationObservationGuardTest} 自身の検体テスト（#3140 / #2990 L9）。
 *
 * <h2>なぜ必要か</h2>
 * <p>本番走査の違反が 0 件であることは、番人が動いている証明にはならない
 * （{@code TEST_CONVENTION.md} §9.6）。本テストは本番と同一の判定ロジック
 * （{@code scanSource} / {@code effectivelyTransactional}）に<b>実ファイルの検体</b>を当て、
 * さらに<b>変異</b>させて「検出できなくなること」「新たに検出できること」まで確認する。</p>
 *
 * <h2>独立オラクル</h2>
 * <p>{@link 実在した欠陥} は L8（PR #3135）で実在した {@code ScheduleKeepConvertContractIT}
 * 是正前のコードを縮約したものである。番人の検出語を検体から決めた（＝循環論法）のではなく、
 * <b>実際に起きた事故を検出できること</b>を独立に確かめる。</p>
 */
@DisplayName("番人の検体: @Transactional × 通知配送検証の検出力")
class TransactionalTestNotificationObservationGuardConditionTest {

    private static final Path FIXTURE = Path.of(
            "com/mannschaft/app/common/architecture/fixtures/notification",
            "TransactionalNotificationObservationFixture.java");

    private static String fixtureSource() {
        return TransactionalTestNotificationObservationGuardTest.read(
                TransactionalTestNotificationObservationGuardTest.testSourceRoot().resolve(FIXTURE));
    }

    private static List<Violation> scanFixture(String source) {
        boolean tx = TransactionalTestNotificationObservationGuardTest.declaresTransactional(source);
        return TransactionalTestNotificationObservationGuardTest.scanSource("Fixture", source, tx);
    }

    @Nested
    @DisplayName("負例（検出されること）")
    class 負例 {

        @Test
        @DisplayName("検体ファイルの負例4トポロジーをすべて検出する")
        void 検体の負例をすべて検出する() {
            List<Violation> violations = scanFixture(fixtureSource());

            assertThat(violations)
                    .as("検体には負例が4つある（生SQL件数 / notification_type列 / countヘルパ / verify(never)）")
                    .hasSize(4);
            assertThat(violations).extracting(Violation::statement)
                    .anyMatch(s -> s.contains("FROM notifications"))
                    .anyMatch(s -> s.contains("notification_type"))
                    .anyMatch(s -> s.contains("countNotifications"))
                    .anyMatch(s -> s.contains("verify(notificationService"));
        }
    }

    @Nested
    @DisplayName("実在した欠陥（独立オラクル・PR #3135 是正前）")
    class 実在した欠陥 {

        /**
         * L8 是正前の {@code ScheduleKeepConvertContractIT} の縮約。
         * {@code @Transactional} なクラスの中で、素の {@code DataSource} から新接続を取って
         * 通知行を数え「0 件であること」を検証していた。
         */
        private static final String PRE_L8 = """
                @Transactional
                class ScheduleKeepConvertContractIT extends AbstractMySqlIntegrationTest {
                    void 降格した作成者には変換通知が作られない() throws Exception {
                        assertThat(countConvertedNotifications(supporterId)).isZero();
                    }

                    private long countConvertedNotifications(Long userId) throws Exception {
                        try (Connection connection = dataSource.getConnection();
                             PreparedStatement statement = connection.prepareStatement(
                                     "SELECT COUNT(*) FROM notifications "
                                             + "WHERE user_id = ? AND notification_type = 'SCHEDULE_KEEP_CONVERTED'")) {
                            statement.setLong(1, userId);
                            return 0L;
                        }
                    }
                }
                """;

        @Test
        @DisplayName("是正前のコードを違反として検出する")
        void 是正前を検出する() {
            assertThat(scanFixture(PRE_L8))
                    .as("実際に CI をすり抜けた形。検出できなければ番人は無意味")
                    .isNotEmpty();
        }

        @Test
        @DisplayName("是正後の @RecordApplicationEvents 形は違反にならない")
        void 是正後は違反にならない() {
            String post = """
                    @RecordApplicationEvents
                    @Transactional
                    class ScheduleKeepConvertContractIT extends AbstractMySqlIntegrationTest {
                        void AC15b_配送イベントがpublishされる() throws Exception {
                            assertThat(applicationEvents.stream(ScheduleKeepConvertedEvent.class))
                                    .anySatisfy(event -> assertThat(event.keepId()).isEqualTo(keep.getId()));
                        }
                    }
                    """;
            assertThat(scanFixture(post)).isEmpty();
        }

        @Test
        @DisplayName("正規形（@Transactional を外し TransactionTemplate でコミット）は違反にならない")
        void 正規形は違反にならない() {
            String canonical = """
                    class ScheduleCommentNotificationPartialFailureIT extends AbstractMySqlIntegrationTest {
                        void 通知が届く() {
                            transactionTemplate.executeWithoutResult(tx -> insertFixtures());
                            assertThat(countNotifications(recipientOkId)).isEqualTo(1L);
                        }
                    }
                    """;
            assertThat(scanFixture(canonical))
                    .as("クラスに @Transactional が無いので、通知件数を測ってよい")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("変異（検出力の実測）")
    class 変異 {

        @Test
        @DisplayName("@Transactional を外すと検出しなくなる（トランザクション文脈が判定に効いている）")
        void トランザクション注釈を外すと検出しない() {
            String source = fixtureSource();
            assertThat(scanFixture(source)).hasSize(4);

            String mutated = source.replace("\n@Transactional\npublic class", "\npublic class");
            assertThat(mutated)
                    .as("変異が空振りしていない（CRLF・整形の差で置換が効かないと偽の緑になる）")
                    .isNotEqualTo(source);
            assertThat(scanFixture(mutated))
                    .as("@Transactional が無ければコミットされるので通知件数を測ってよい")
                    .isEmpty();
        }

        @Test
        @DisplayName("検証表現を外すと、その1件だけ検出しなくなる")
        void 検証表現を外すと1件減る() {
            String source = fixtureSource();
            String mutated = source.replace(
                    "assertThat(countNotifications(1L)).isZero();",
                    "long ignored = countNotifications(1L);");
            assertThat(mutated).isNotEqualTo(source);
            assertThat(scanFixture(mutated))
                    .as("観測しているだけで表明していない文は違反ではない")
                    .hasSize(3);
        }

        @Test
        @DisplayName("違反を1つ足すと検出件数が1増える（検出対象を増やせることの実測）")
        void 違反を足すと検出が増える() {
            String source = fixtureSource();
            String added = "    public void mutantAddedViolation() {\n"
                    + "        assertThat(query(\"SELECT COUNT(*) FROM notifications\")).isZero();\n"
                    + "    }\n\n"
                    + "    /** 正例1:";
            String mutated = source.replace("    /** 正例1:", added);
            assertThat(mutated).isNotEqualTo(source);
            assertThat(scanFixture(mutated)).hasSize(5);
        }

        @Test
        @DisplayName("日本語のテストメソッド名でも検出する（ASCII 限定の \\w だと全域が見えなくなる）")
        void 日本語メソッド名でも検出する() {
            String source = """
                    @Transactional
                    class SomeIT extends AbstractMySqlIntegrationTest {
                        void 降格した作成者には変換通知が作られない() throws Exception {
                            assertThat(countNotifications(supporterId)).isZero();
                        }
                    }
                    """;
            assertThat(scanFixture(source))
                    .as("このリポジトリのテストメソッド名はほぼ日本語。ここを取り逃すと番人は事実上ゼロ検出")
                    .hasSize(1);
        }

        @Test
        @DisplayName("観測ヘルパの名前が通知語彙でなくても検出する（2パス判定が効いている）")
        void 名前が通知語彙でないヘルパ経由でも検出する() {
            String source = """
                    @Transactional
                    class SomeIT extends AbstractMySqlIntegrationTest {
                        void 通知は作られない() {
                            assertThat(rows(userId)).isZero();
                        }

                        private long rows(Long userId) {
                            return jdbcTemplate.queryForObject(
                                    "SELECT COUNT(*) FROM notifications WHERE user_id = ?", Long.class, userId);
                        }
                    }
                    """;
            assertThat(scanFixture(source))
                    .as("ヘルパ名を語彙で当てにいくと取り逃す。本体に生の観測を持つメソッドを先に集めること")
                    .hasSize(1);
        }

        @Test
        @DisplayName("観測と表明が別の文に分かれていても検出する（ローカル変数を挟んだすり抜けを塞ぐ）")
        void 観測と表明が別の文でも検出する() {
            String source = """
                    @Transactional
                    class SomeIT extends AbstractMySqlIntegrationTest {
                        void 通知は作られない() {
                            long count = jdbcTemplate.queryForObject(
                                    "SELECT COUNT(*) FROM notifications WHERE user_id = ?", Long.class, userId);
                            assertThat(count).isZero();
                        }
                    }
                    """;
            assertThat(scanFixture(source)).hasSize(1);
        }

        @Test
        @DisplayName("Javadoc 中の @Transactional への言及ではトランザクショナル判定しない")
        void コメント中の言及では判定しない() {
            String source = """
                    /**
                     * <h2>クラスに {@code @Transactional} を付けない理由</h2>
                     * 通知は afterCommit で発火するため、トランザクションで包むと偽の緑になる。
                     */
                    class SomeIT extends AbstractMySqlIntegrationTest {
                        void 通知が届く() {
                            assertThat(countNotifications(userId)).isEqualTo(1L);
                        }
                    }
                    """;
            assertThat(TransactionalTestNotificationObservationGuardTest.declaresTransactional(source))
                    .as("Javadoc の言及を注釈と誤認すると、正しく直したテストを違反にしてしまう")
                    .isFalse();
            assertThat(scanFixture(source)).isEmpty();
        }
    }

    @Nested
    @DisplayName("適法例外の台帳")
    class 適法例外 {

        @Test
        @DisplayName("ALLOWED の各エントリは実際に判定へ引っかかる（効いていない例外を残さない）")
        void 例外はすべて効いている() {
            for (String fqcn : TransactionalTestNotificationObservationGuardTest.ALLOWED) {
                Path file = TransactionalTestNotificationObservationGuardTest.testSourceRoot()
                        .resolve(fqcn.replace('.', '/') + ".java");
                String source = TransactionalTestNotificationObservationGuardTest.read(file);
                boolean tx = TransactionalTestNotificationObservationGuardTest
                        .declaresTransactional(source);

                assertThat(TransactionalTestNotificationObservationGuardTest
                        .scanSource(fqcn, source, tx))
                        .as("%s は除外しなくても違反にならない＝台帳から外すべき", fqcn)
                        .isNotEmpty();
            }
        }
    }

    @Nested
    @DisplayName("継承の追跡")
    class 継承 {

        @Test
        @DisplayName("親クラスの @Transactional を推移的に引き継ぐ")
        void 親の注釈を引き継ぐ() {
            Map<String, String> tree = Map.of(
                    "Base", "@Transactional\nabstract class Base {}\n",
                    "Middle", "abstract class Middle extends Base {}\n",
                    "Leaf", "class Leaf extends Middle {}\n",
                    "Free", "class Free extends Object {}\n");

            assertThat(TransactionalTestNotificationObservationGuardTest
                    .effectivelyTransactional("Leaf", tree)).isTrue();
            assertThat(TransactionalTestNotificationObservationGuardTest
                    .effectivelyTransactional("Free", tree)).isFalse();
        }

        @Test
        @DisplayName("循環継承（壊れた入力）でも無限ループしない")
        void 循環しても止まる() {
            Map<String, String> tree = Map.of(
                    "A", "class A extends B {}\n",
                    "B", "class B extends A {}\n");
            assertThat(TransactionalTestNotificationObservationGuardTest
                    .effectivelyTransactional("A", tree)).isFalse();
        }
    }
}
