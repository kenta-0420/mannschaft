package com.mannschaft.app.notification.fanout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-11〜14（保持・アーカイブ）の<b>DDL＋索引の存在を軽く固定</b>するガードテスト（Spring context 不要）。
 *
 * <p>耐久ジョブ表と並ぶ P2 の柱である「通知の保持（アーカイブ退避＋クリーンアップ索引）」について、
 * 移送先テーブルと索引が<b>マイグレーションに宣言されている</b>ことをクラスパス上の SQL から確認する。
 * 重いバッチ挙動（移送本体・保持期間・冪等）は P2 出陣で肉付けする（{@code 申し送り} 参照）。</p>
 *
 * <p>本テストは DDL 資産の存在ガードであり、DDL を作成した本試練では green で成立する
 * （red は enqueue／ワーカー本体側で表現する）。</p>
 */
@DisplayName("AC-11〜14 保持/アーカイブ DDL 存在ガード")
class NotificationFanoutRetentionDdlTest {

    private static final String JOBS_DDL =
            "db/migration/V173.20260730033806__create_notification_fanout_jobs.sql";
    private static final String ARCHIVE_DDL =
            "db/migration/V173.20260730033807__create_notifications_archive_and_read_index.sql";

    private static String readClasspath(String path) {
        try (InputStream in = NotificationFanoutRetentionDdlTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as("マイグレーションがクラスパスに存在する: " + path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            throw new AssertionError("マイグレーション読込に失敗: " + path, e);
        }
    }

    @Test
    @DisplayName("AC-1 基盤: 耐久ジョブ表と冪等ユニーク・status/next 索引が宣言されている")
    void jobsTableAndIndexesDeclared() {
        String sql = readClasspath(JOBS_DDL);
        assertThat(sql).contains("create table notification_fanout_jobs");
        assertThat(sql).as("冪等ユニーク（AC-1）").contains("uk_fanout_idempotency");
        assertThat(sql).as("ワーカー取得索引（AC-4）").contains("idx_fanout_status_next");
        assertThat(sql).as("再開カーソル列（AC-2）").contains("cursor_subject_id");
        assertThat(sql).as("リトライ列（AC-3）").contains("retry_count");
        assertThat(sql).as("UUIDv7 主キー（原則6）").contains("binary(16)");
    }

    @Test
    @DisplayName("AC-11〜14: notifications_archive とクリーンアップ索引が宣言されている")
    void archiveTableAndCleanupIndexDeclared() {
        String sql = readClasspath(ARCHIVE_DDL);
        assertThat(sql).as("アーカイブ退避先").contains("create table notifications_archive");
        assertThat(sql).as("移送日時列").contains("archived_at");
        // per-row 状態の保持（移送後も履歴の意味を失わない）。
        assertThat(sql).contains("is_read");
        assertThat(sql).contains("read_at");
        assertThat(sql).contains("snoozed_until");
        assertThat(sql).contains("scope_type");
        assertThat(sql).contains("organization_id");
        // クリーンアップ索引（is_read, created_at 先頭）— 既存 user_id 先頭索引の乖離是正（AC-13）。
        assertThat(sql).as("クリーンアップ索引").contains("idx_notifications_read_created");
    }
}
