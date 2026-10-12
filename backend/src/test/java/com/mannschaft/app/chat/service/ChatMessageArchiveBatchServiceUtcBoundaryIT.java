package com.mannschaft.app.chat.service;

import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-260910-0043: チャット退避の閾値は UTC 壁時計（UTC_TIMESTAMP()）基準であること。
 *
 * <p>退避境界（6か月）の1時間手前・1時間奥の行を UTC 基準で作り、JST 壁時計で閾値を作る旧実装
 * （9時間ずれ）では「境界より新しい行」まで退避・削除されてしまうことを固定する。</p>
 *
 * <p>テストプロファイルは Flyway 無効（Hibernate DDL）で {@code chat_messages_archive} が存在しないため、
 * {@code CREATE TABLE ... LIKE} で用意する。DDL は暗黙コミットを伴うので {@code @Transactional} は使わず、
 * 後始末で投入行を明示削除する。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("チャット退避の UTC 境界（CMP-260910-0043）")
class ChatMessageArchiveBatchServiceUtcBoundaryIT extends AbstractMySqlIntegrationTest {

    private static final long NEWER_ID = 910_043_001L;
    private static final long OLDER_ID = 910_043_002L;

    @Autowired
    private ChatMessageArchiveBatchService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS chat_messages_archive LIKE chat_messages");
        cleanUp();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        for (String table : new String[] {"chat_messages", "chat_messages_archive"}) {
            jdbcTemplate.update("DELETE FROM " + table + " WHERE id IN (?, ?)", NEWER_ID, OLDER_ID);
        }
    }

    @Test
    @DisplayName("UTC基準で6か月より1時間新しい削除済み行は退避せず、1時間古い行だけ退避・削除する")
    void archivesOnlyRowsOlderThanUtcThreshold() {
        // FK 検査の無効化は同一コネクション内に閉じ、プールへ戻す前に必ず元へ戻す。
        jdbcTemplate.execute((java.sql.Connection con) -> {
            try (java.sql.Statement st = con.createStatement()) {
                st.execute("SET FOREIGN_KEY_CHECKS = 0");
                try {
                    insertDeletedMessage(st, NEWER_ID, "UTC_TIMESTAMP() - INTERVAL 6 MONTH + INTERVAL 1 HOUR");
                    insertDeletedMessage(st, OLDER_ID, "UTC_TIMESTAMP() - INTERVAL 6 MONTH - INTERVAL 1 HOUR");
                } finally {
                    st.execute("SET FOREIGN_KEY_CHECKS = 1");
                }
            }
            return null;
        });

        service.archiveOldMessages();

        assertThat(count("chat_messages_archive", NEWER_ID)).as("境界より新しい行は退避しない").isZero();
        assertThat(count("chat_messages", NEWER_ID)).as("境界より新しい行は元テーブルに残る").isEqualTo(1);
        assertThat(count("chat_messages_archive", OLDER_ID)).as("境界より古い行は退避する").isEqualTo(1);
        assertThat(count("chat_messages", OLDER_ID)).as("退避済みの行は元テーブルから削除される").isZero();
    }

    private static void insertDeletedMessage(java.sql.Statement st, long id, String createdAtExpr) throws java.sql.SQLException {
        st.executeUpdate(
                "INSERT INTO chat_messages (id, channel_id, body, depth, is_edited, is_system, reply_count, reaction_count, is_pinned, "
                        + "created_at, updated_at, deleted_at) VALUES (" + id + ", 1, 'utc-boundary', 0, 0, 0, 0, 0, 0, "
                        + createdAtExpr + ", " + createdAtExpr + ", " + createdAtExpr + ")");
    }

    private int count(String table, long id) {
        Integer n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE id = ?", Integer.class, id);
        return n == null ? 0 : n;
    }
}
