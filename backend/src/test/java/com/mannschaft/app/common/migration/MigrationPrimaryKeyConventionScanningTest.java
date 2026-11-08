package com.mannschaft.app.common.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 実ファイルを既存番人へ渡し、UUID主キーと副参照の自動採番を区別する回帰試練。 */
class MigrationPrimaryKeyConventionScanningTest {

    @TempDir
    Path directory;

    @Test
    void BINARY16の単一主キーと副参照の自動採番は許可する() throws Exception {
        assertThat(scan("CREATE TABLE sample (id BINARY(16) NOT NULL, "
            + "notice_ref BIGINT NOT NULL AUTO_INCREMENT, PRIMARY KEY (id), UNIQUE KEY (notice_ref));"))
            .isEmpty();
    }

    @Test
    void CHAR36のインライン主キーと副参照の自動採番は許可する() throws Exception {
        assertThat(scan("create table `sample` (`id` char (36) primary key, "
            + "`notice_ref` bigint auto_increment unique);"))
            .isEmpty();
    }

    @Test
    void 名前付き制約と大小文字とバッククォートでもUUID主キーを認識する() throws Exception {
        assertThat(scan("CrEaTe TaBlE `sample` (`Id` BiNaRy ( 16 ) NOT NULL, "
            + "notice_ref BIGINT AUTO_INCREMENT UNIQUE, CONSTRAINT `pk_sample` PRIMARY KEY (`Id`));"))
            .isEmpty();
    }

    @Test
    void インライン自動採番主キーは拒否する() throws Exception {
        assertThat(scan("CREATE TABLE sample (id BIGINT AUTO_INCREMENT PRIMARY KEY);"))
            .hasSize(1);
    }

    @Test
    void テーブル制約の自動採番主キーは拒否する() throws Exception {
        assertThat(scan("create table `sample` (`id` bigint auto_increment, primary key (`id`));"))
            .hasSize(1);
    }

    @Test
    void UUID列があっても自動採番を含む複合主キーは拒否する() throws Exception {
        assertThat(scan("CREATE TABLE sample (id BINARY(16), notice_ref BIGINT AUTO_INCREMENT, "
            + "PRIMARY KEY (notice_ref, id));"))
            .hasSize(1);
    }

    @Test
    void 主キー不明の自動採番は拒否する() throws Exception {
        assertThat(scan("CREATE TABLE sample (id BINARY(16), notice_ref BIGINT AUTO_INCREMENT UNIQUE);"))
            .hasSize(1);
    }

    @Test
    void 非UUID主キーと副参照の自動採番は拒否する() throws Exception {
        assertThat(scan("CREATE TABLE sample (id VARCHAR(36) PRIMARY KEY, "
            + "notice_ref BIGINT AUTO_INCREMENT UNIQUE);"))
            .hasSize(1);
    }

    @Test
    void UUID型の長さが違う主キーは拒否する() throws Exception {
        assertThat(scan("CREATE TABLE sample (id BINARY(32) PRIMARY KEY, "
            + "notice_ref BIGINT AUTO_INCREMENT UNIQUE);"))
            .hasSize(1);
    }

    @Test
    void 解析できない主キー式は許可の証拠にしない() throws Exception {
        assertThat(scan("CREATE TABLE sample (id BINARY(16), notice_ref BIGINT AUTO_INCREMENT UNIQUE, "
            + "PRIMARY KEY (id(8)));"))
            .hasSize(1);
    }

    @Test
    void 重複した主キー宣言は許可の証拠にしない() throws Exception {
        assertThat(scan("CREATE TABLE sample (id BINARY(16) PRIMARY KEY, "
            + "notice_ref BIGINT AUTO_INCREMENT PRIMARY KEY);"))
            .hasSize(1);
    }

    @Test
    void コメントと一時表と後続文を永続表の自動採番と誤認しない() throws Exception {
        assertThat(scan("-- AUTO_INCREMENT\nCREATE TABLE sample (id BINARY(16) PRIMARY KEY, "
            + "label VARCHAR(50) DEFAULT 'don''t; split'); /* AUTO_INCREMENT */ "
            + "CREATE TEMPORARY TABLE scratch (id BIGINT AUTO_INCREMENT PRIMARY KEY); "
            + "ALTER TABLE other_table AUTO_INCREMENT = 100;"))
            .isEmpty();
    }

    @Test
    void 一時表の後ろの永続表の違反は検出する() throws Exception {
        assertThat(scan("CREATE TEMPORARY TABLE scratch (id BIGINT AUTO_INCREMENT PRIMARY KEY); "
            + "CREATE TABLE sample (id BIGINT AUTO_INCREMENT PRIMARY KEY);"))
            .singleElement().asString().contains("sample");
    }

    @Test
    void 既存のmajor境界を維持する() throws Exception {
        assertThat(scan("V69.1__sample.sql", "CREATE TABLE sample (id BIGINT AUTO_INCREMENT PRIMARY KEY);",
            Set.of())).isEmpty();
    }

    @Test
    void 後続migrationでUUID交換済みの除外を維持する() throws Exception {
        assertThat(scan("V70.1__sample.sql", "CREATE TABLE sample (id BIGINT AUTO_INCREMENT PRIMARY KEY);",
            Set.of("sample"))).isEmpty();
    }

    private List<String> scan(String sql) throws Exception {
        return scan("V70.1__sample.sql", sql, Set.of());
    }

    private List<String> scan(String name, String sql, Set<String> migrated) throws Exception {
        Path file = directory.resolve(name);
        Files.writeString(file, sql, StandardCharsets.UTF_8);
        List<String> violations = new ArrayList<>();
        Method collect = MigrationPrimaryKeyConventionTest.class.getDeclaredMethod(
            "collectViolations", Path.class, Set.class, List.class);
        collect.setAccessible(true);
        collect.invoke(null, file, migrated, violations);
        return violations;
    }
}
