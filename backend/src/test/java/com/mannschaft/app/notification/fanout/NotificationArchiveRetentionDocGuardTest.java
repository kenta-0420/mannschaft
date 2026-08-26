package com.mannschaft.app.notification.fanout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave2-A 保持バッチ本体の <b>DDL 索引・ドキュメント・ShedLock 登録</b>を軽く固定するガードテスト
 * （Spring context 不要）。
 *
 * <p>金型は同パッケージの {@code NotificationFanoutRetentionDdlTest}。
 * 移送先表と索引は Wave1（V173）で敷設済みのため AC-13/14 は回帰固定（索引の存在保証）。
 * AC-12（ドキュメントの索引出自・移送型記述）と AC-15（ShedLock へ保持バッチ名を登録）は
 * Wave2-A で是正した項目を固定する。</p>
 */
@DisplayName("Wave2-A AC-12〜15 保持バッチ DDL/doc/ShedLock ガード")
class NotificationArchiveRetentionDocGuardTest {

    private static final String ARCHIVE_DDL =
            "db/migration/V173.20260730033807__create_notifications_archive_and_read_index.sql";
    /** worktree/CI いずれからも解決できるよう backend からの相対でリポジトリルートを辿る。 */
    private static final Path DB_SCALABILITY_DOC =
            Path.of("..", "docs", "architecture", "db_scalability.md");
    private static final Path SHEDLOCK_CONFIG =
            Path.of("src", "main", "java", "com", "mannschaft", "app", "config", "ShedLockConfig.java");

    private static String readClasspath(String path) {
        try (InputStream in = NotificationArchiveRetentionDocGuardTest.class
                .getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as("マイグレーションがクラスパスに存在する: " + path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            throw new AssertionError("マイグレーション読込に失敗: " + path, e);
        }
    }

    private static String readRepoFile(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError("リポジトリ内ファイル読込に失敗: " + path.toAbsolutePath(), e);
        }
    }

    // ---- AC-13 移送 WHERE(is_read, created_at) 索引（Wave1 で敷設済み・回帰固定 green） ----
    @Test
    @DisplayName("AC-13 移送WHERE用 idx_notifications_read_created が V173 に存在（フルスキャン回避）")
    void ac13_readCreatedIndexDeclared() {
        String sql = readClasspath(ARCHIVE_DDL);
        assertThat(sql)
                .as("全ユーザー横断で is_read + created_at を掃く移送索引")
                .contains("idx_notifications_read_created");
        assertThat(sql).contains("create index idx_notifications_read_created on notifications (is_read, created_at)");
    }

    // ---- AC-14 archive user_id 削除索引（Wave1 で敷設済み・回帰固定 green） ----
    @Test
    @DisplayName("AC-14 退会削除用 idx_notif_arch_user_created が V173 に存在")
    void ac14_archiveUserCreatedIndexDeclared() {
        String sql = readClasspath(ARCHIVE_DDL);
        assertThat(sql)
                .as("archive の user_id 単位削除を支える索引")
                .contains("idx_notif_arch_user_created");
    }

    // ---- AC-12 ドキュメント §3-C の索引出自是正（V64→V173）＋移送型記述（現状 red） ----
    @Test
    @DisplayName("AC-12 db_scalability.md §3-C の索引出自を V173 とし移送型を反映する")
    void ac12_docReflectsV173AndArchiveMove() {
        String doc = readRepoFile(DB_SCALABILITY_DOC);
        int start = doc.indexOf("3-C.");
        assertThat(start).as("§3-C 見出しが存在する").isGreaterThanOrEqualTo(0);
        // 次のセクション区切りまでを §3-C として切り出す。
        int nextSection = doc.indexOf("####", start + 4);
        int hr = doc.indexOf("\n---", start);
        int end = doc.length();
        if (nextSection >= 0) end = Math.min(end, nextSection);
        if (hr >= 0) end = Math.min(end, hr);
        String section = doc.substring(start, end);

        assertThat(section)
                .as("idx_notifications_read_created の出自を V173（P2 Wave1 新設）として記述する")
                .contains("V173");
        assertThat(section)
                .as("索引出自の誤記 V64 が §3-C に残っていない")
                .doesNotContain("V64");
        assertThat(section)
                .as("クリーンアップ記述が物理削除ではなくアーカイブ移送を反映する")
                .contains("アーカイブ");
    }

    // ---- AC-15 ShedLock へ保持バッチ用ロック名を登録（現状 red） ----
    @Test
    @DisplayName("AC-15 ShedLockConfig Javadoc に保持バッチのロック名 notificationCleanupBatch を登録")
    void ac15_shedLockRegistersRetentionBatchName() {
        String config = readRepoFile(SHEDLOCK_CONFIG);
        assertThat(config)
                .as("保持（アーカイブ移送）バッチのロック名を ShedLockConfig Javadoc に登録する")
                .contains("notificationCleanupBatch");
    }
}
