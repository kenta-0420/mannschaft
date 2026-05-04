package com.mannschaft.app.common.visibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link ReferenceTypeIntegrityCheck} の単体テスト。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §11.2 / §15 D-12。
 *
 * <p>fail-open 起動を 4 シナリオで検証する:
 * <ol>
 *   <li>DB 値が enum と完全一致 → INFO ログ、例外なし</li>
 *   <li>DB 値の中に enum 未定義の値あり → WARN ログ、例外なし</li>
 *   <li>{@link BadSqlGrammarException} (テーブル不在) → INFO ログ、例外なし</li>
 *   <li>{@link RuntimeException} (接続失敗) → WARN ログ、例外なし</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReferenceTypeIntegrityCheck — 起動時 enum/DB 整合性ヘルスチェック")
class ReferenceTypeIntegrityCheckTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private ReferenceTypeIntegrityCheck check;
    private ListAppender<ILoggingEvent> appender;
    private Logger targetLogger;

    @BeforeEach
    void setUp() {
        check = new ReferenceTypeIntegrityCheck(jdbcTemplate);

        targetLogger = (Logger) LoggerFactory.getLogger(ReferenceTypeIntegrityCheck.class);
        appender = new ListAppender<>();
        appender.start();
        targetLogger.addAppender(appender);
        targetLogger.setLevel(Level.ALL);
    }

    @AfterEach
    void tearDown() {
        targetLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("DB 値が enum と完全一致 → INFO ログ、例外なし")
    void allDbValuesMatchEnum_emitsInfo() {
        List<String> allInEnum = Arrays.asList(
            ReferenceType.BLOG_POST.name(),
            ReferenceType.EVENT.name(),
            ReferenceType.SCHEDULE.name());
        when(jdbcTemplate.queryForList(anyString(), eq(String.class)))
            .thenReturn(allInEnum);

        assertThatCode(() -> check.verifyOnStartup()).doesNotThrowAnyException();

        assertThat(appender.list)
            .as("INFO ログが 1 件出力されること")
            .hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage())
            .contains("すべて enum 定義と一致")
            .contains("3");
    }

    @Test
    @DisplayName("DB 値に enum 未定義の値あり → WARN ログ、例外なし (fail-open)")
    void unknownValueInDb_emitsWarn() {
        List<String> withObsolete = Arrays.asList(
            ReferenceType.BLOG_POST.name(),
            "OBSOLETE_TYPE",
            ReferenceType.EVENT.name());
        when(jdbcTemplate.queryForList(anyString(), eq(String.class)))
            .thenReturn(withObsolete);

        assertThatCode(() -> check.verifyOnStartup()).doesNotThrowAnyException();

        assertThat(appender.list)
            .as("WARN ログが 1 件出力されること")
            .hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage())
            .contains("enum 未定義の reference_type")
            .contains("1")
            .contains("OBSOLETE_TYPE")
            .contains("§15 D-12");
    }

    @Test
    @DisplayName("BadSqlGrammarException (テーブル不在) → INFO ログ、例外なし (fail-open)")
    void tableMissing_emitsInfoAndDoesNotThrow() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class)))
            .thenThrow(new BadSqlGrammarException(
                "SELECT", "SELECT DISTINCT reference_type FROM corkboard_card_reference",
                new java.sql.SQLException("Table 'corkboard_card_reference' doesn't exist")));

        assertThatCode(() -> check.verifyOnStartup()).doesNotThrowAnyException();

        assertThat(appender.list)
            .as("INFO ログが 1 件出力されること")
            .hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage())
            .contains("テーブル不在")
            .contains("スキップ");
    }

    @Test
    @DisplayName("RuntimeException (DB 接続失敗等) → WARN ログ、例外なし (fail-open)")
    void connectionFailure_emitsWarnAndDoesNotThrow() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class)))
            .thenThrow(new DataAccessResourceFailureException("connection refused"));

        assertThatCode(() -> check.verifyOnStartup()).doesNotThrowAnyException();

        assertThat(appender.list)
            .as("WARN ログが 1 件出力されること")
            .hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage())
            .contains("起動時チェックに失敗")
            .contains("起動は継続")
            .contains("connection refused");
    }

    @Test
    @DisplayName("any(Class) matcher で呼び出されるクエリ文字列が想定どおり")
    void queriesExpectedSql() {
        when(jdbcTemplate.queryForList(anyString(), any(Class.class)))
            .thenReturn(List.of());

        check.verifyOnStartup();

        // 例外なく完了し、INFO (空集合は一致扱い) が出ることを確認
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.INFO);
    }
}
