package com.mannschaft.app.common.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PermissionNameCatalogGuardTest} の走査ロジック自体の正しさを実証する自己検証テスト。
 *
 * <h2>なぜ要るか</h2>
 * <p><b>番人が緑であることは、番人が守っていることの証明にならない。</b>走査が空振りしていても
 * 「違反ゼロ」と同じ緑になるためである（CMP-022 第二波の教訓：検出器は自分の偽陰性を最初に晒せ）。
 * 本クラスは合成ソース／合成 SQL（production には置かない）を直接スキャン関数へ与え、
 * <b>陽性対照</b>（検出すべきものを実際に検出する）と<b>陰性対照</b>（検出してはならないもので
 * 誤検出しない）を対で置く。</p>
 */
@DisplayName("PermissionNameCatalogGuardTest の走査ロジック（検出力＋誤検出耐性）")
class PermissionNameCatalogGuardScanningLogicTest {

    private static final String FQCN = "com.mannschaft.app.example.SyntheticService";

    /** 合成カタログ（実 migration とは無関係）。 */
    private static final Set<String> CATALOG = Set.of("REGISTERED_PERMISSION", "ANOTHER_REGISTERED");

    // ────────────────────────────────────────────────────────────
    // 陽性対照: 検出すべきものを実際に検出する
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("陽性: 文字列リテラル直書きの未登録権限名を検出する")
    void detectsUnregisteredStringLiteral() {
        String code = """
                package com.mannschaft.app.example;
                class SyntheticService {
                    void act(Long userId, Long teamId) {
                        accessControlService.checkPermission(userId, teamId, "TEAM", "GHOST_PERMISSION");
                    }
                }
                """;

        List<PermissionNameCatalogGuardTest.PermissionUsage> dead =
                PermissionNameCatalogGuardTest.deadUsages(CATALOG,
                        PermissionNameCatalogGuardTest.scanSources(Map.of(FQCN, code)));

        assertThat(dead).singleElement()
                .satisfies(u -> {
                    assertThat(u.permissionName()).isEqualTo("GHOST_PERMISSION");
                    assertThat(u.method()).isEqualTo("checkPermission");
                    assertThat(u.fqcn()).isEqualTo(FQCN);
                });
    }

    @Test
    @DisplayName("陽性: static final String 定数経由の未登録権限名を検出する")
    void detectsUnregisteredConstantReference() {
        String code = """
                package com.mannschaft.app.example;
                class SyntheticService {
                    private static final String PERMISSION_GHOST = "GHOST_VIA_CONSTANT";
                    boolean can(Long userId, Long orgId) {
                        return accessControlService.hasPermission(userId, orgId, "ORGANIZATION", PERMISSION_GHOST);
                    }
                }
                """;

        List<PermissionNameCatalogGuardTest.PermissionUsage> dead =
                PermissionNameCatalogGuardTest.deadUsages(CATALOG,
                        PermissionNameCatalogGuardTest.scanSources(Map.of(FQCN, code)));

        assertThat(dead).singleElement()
                .satisfies(u -> assertThat(u.permissionName()).isEqualTo("GHOST_VIA_CONSTANT"));
    }

    @Test
    @DisplayName("陽性: 他クラスの定数を修飾名（Foo.BAR）で参照しても解決して検出する")
    void detectsUnregisteredQualifiedConstantFromAnotherClass() {
        String policy = """
                package com.mannschaft.app.example;
                public final class SyntheticPolicy {
                    public static final String PERMISSION_GHOST_QUALIFIED = "GHOST_QUALIFIED";
                }
                """;
        String caller = """
                package com.mannschaft.app.example;
                class SyntheticService {
                    void act(Long userId, Long orgId) {
                        accessControlService.checkAdminOrHasPermission(
                                userId, orgId, "ORGANIZATION", SyntheticPolicy.PERMISSION_GHOST_QUALIFIED);
                    }
                }
                """;

        List<PermissionNameCatalogGuardTest.PermissionUsage> dead =
                PermissionNameCatalogGuardTest.deadUsages(CATALOG,
                        PermissionNameCatalogGuardTest.scanSources(Map.of(
                                FQCN, caller,
                                "com.mannschaft.app.example.SyntheticPolicy", policy)));

        assertThat(dead).singleElement()
                .satisfies(u -> assertThat(u.permissionName()).isEqualTo("GHOST_QUALIFIED"));
    }

    @Test
    @DisplayName("陽性: Repository の 3 引数メソッド（末尾が権限名）も検出する")
    void detectsUnregisteredNameInRepositoryCall() {
        String code = """
                package com.mannschaft.app.example;
                class SyntheticService {
                    boolean can(Long userId, Long orgId) {
                        return userRoleRepository.existsDeputyAdminWithPermissionInOrganization(
                                userId, orgId, "GHOST_DEPUTY");
                    }
                }
                """;

        List<PermissionNameCatalogGuardTest.PermissionUsage> dead =
                PermissionNameCatalogGuardTest.deadUsages(CATALOG,
                        PermissionNameCatalogGuardTest.scanSources(Map.of(FQCN, code)));

        assertThat(dead).singleElement()
                .satisfies(u -> assertThat(u.permissionName()).isEqualTo("GHOST_DEPUTY"));
    }

    @Test
    @DisplayName("陽性: 複数行に折り返した呼び出しでも検出する")
    void detectsAcrossLineBreaks() {
        String code = """
                package com.mannschaft.app.example;
                class SyntheticService {
                    void act(Long userId, Long teamId) {
                        accessControlService.checkPermission(
                                userId,
                                teamId,
                                "TEAM",
                                "GHOST_MULTILINE");
                    }
                }
                """;

        assertThat(PermissionNameCatalogGuardTest.deadUsages(CATALOG,
                PermissionNameCatalogGuardTest.scanSources(Map.of(FQCN, code))))
                .extracting(PermissionNameCatalogGuardTest.PermissionUsage::permissionName)
                .containsExactly("GHOST_MULTILINE");
    }

    // ────────────────────────────────────────────────────────────
    // 陰性対照: 検出してはならないもので誤検出しない
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("陰性: カタログに登録済みの権限名は違反にならない")
    void registeredPermissionIsNotFlagged() {
        String code = """
                package com.mannschaft.app.example;
                class SyntheticService {
                    void act(Long userId, Long teamId) {
                        accessControlService.checkPermission(userId, teamId, "TEAM", "REGISTERED_PERMISSION");
                    }
                }
                """;

        PermissionNameCatalogGuardTest.ScanResult scan =
                PermissionNameCatalogGuardTest.scanSources(Map.of(FQCN, code));

        assertThat(scan.resolved()).hasSize(1);
        assertThat(PermissionNameCatalogGuardTest.deadUsages(CATALOG, scan)).isEmpty();
    }

    @Test
    @DisplayName("陰性: コメント・Javadoc・テキストブロックの中の呼び出し表記では誤検出しない")
    void doesNotFlagOccurrencesInsideCommentsOrTextBlocks() {
        String code = """
                package com.mannschaft.app.example;
                /**
                 * 説明: accessControlService.checkPermission(userId, teamId, "TEAM", "GHOST_IN_JAVADOC") を呼ぶ。
                 */
                class SyntheticService {
                    // accessControlService.hasPermission(userId, teamId, "TEAM", "GHOST_IN_LINE_COMMENT")
                    /* accessControlService.hasPermission(userId, teamId, "TEAM", "GHOST_IN_BLOCK_COMMENT") */
                    String doc() {
                        return \"""
                                accessControlService.checkPermission(userId, teamId, "TEAM", "GHOST_IN_TEXT_BLOCK")
                                \""";
                    }
                }
                """;

        PermissionNameCatalogGuardTest.ScanResult scan =
                PermissionNameCatalogGuardTest.scanSources(Map.of(FQCN, code));

        assertThat(scan.resolved())
                .as("コメント・テキストブロック中の記述を呼び出しと誤認しないこと")
                .isEmpty();
        assertThat(PermissionNameCatalogGuardTest.deadUsages(CATALOG, scan)).isEmpty();
    }

    @Test
    @DisplayName("陰性: メソッド宣言そのもの・引数個数の違う同名メソッドを呼び出しと誤認しない")
    void doesNotFlagDeclarationsOrDifferentArity() {
        String code = """
                package com.mannschaft.app.example;
                class SyntheticService {
                    public boolean hasPermission(Long userId, Long scopeId, String scopeType, String permissionName) {
                        return false;
                    }
                    public void checkPermission(Long userId, String permissionName) {
                    }
                    void act(Long userId) {
                        somethingElse.hasPermission(userId, "GHOST_TWO_ARG");
                    }
                }
                """;

        PermissionNameCatalogGuardTest.ScanResult scan =
                PermissionNameCatalogGuardTest.scanSources(Map.of(FQCN, code));

        assertThat(scan.resolved())
                .as("宣言・別シグネチャは対象外であること")
                .isEmpty();
    }

    @Test
    @DisplayName("限界の明示: 変数経由で渡される権限名は解決できず、未解決として一覧に載る")
    void variablePermissionNameIsRecordedAsUnresolved() {
        String code = """
                package com.mannschaft.app.example;
                class SyntheticService {
                    void act(Long userId, Long orgId, String permissionName) {
                        accessControlService.checkPermission(userId, orgId, "ORGANIZATION", permissionName);
                    }
                }
                """;

        PermissionNameCatalogGuardTest.ScanResult scan =
                PermissionNameCatalogGuardTest.scanSources(Map.of(FQCN, code));

        assertThat(scan.resolved())
                .as("呼び出しチェーンを遡らないため解決できない（本番人の限界）")
                .isEmpty();
        assertThat(scan.unresolved())
                .as("解決できなかったことを黙って捨てず、必ず未解決として記録すること")
                .extracting(PermissionNameCatalogGuardTest.UnresolvedUsage::expression)
                .containsExactly("permissionName");
    }

    @Test
    @DisplayName("限界の明示: 同じ単純名の定数が複数の値を持つ場合は曖昧として未解決に落とす")
    void ambiguousConstantIsUnresolved() {
        String a = """
                package com.mannschaft.app.example;
                class SyntheticA {
                    private static final String PERMISSION_X = "VALUE_A";
                }
                """;
        String b = """
                package com.mannschaft.app.example;
                class SyntheticB {
                    private static final String PERMISSION_X = "VALUE_B";
                    void act(Long userId, Long teamId) {
                        accessControlService.checkPermission(userId, teamId, "TEAM", PERMISSION_X);
                    }
                }
                """;

        PermissionNameCatalogGuardTest.ScanResult scan =
                PermissionNameCatalogGuardTest.scanSources(Map.of(
                        "com.mannschaft.app.example.SyntheticA", a,
                        "com.mannschaft.app.example.SyntheticB", b));

        assertThat(scan.resolved()).isEmpty();
        assertThat(scan.unresolved()).hasSize(1);
    }

    // ────────────────────────────────────────────────────────────
    // カタログ抽出（SQL 側）の陽性・陰性対照
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("陽性: VALUES 単発 / VALUES 複数行 / INSERT IGNORE / SELECT FROM DUAL の 4 形式を抽出する")
    void extractsAllInsertForms() {
        String sql = """
                INSERT INTO permissions (name, display_name, scope, created_at, updated_at)
                VALUES ('SINGLE_VALUES', '単発', 'TEAM', NOW(), NOW());

                INSERT IGNORE INTO permissions (name, display_name, scope, created_at, updated_at) VALUES
                    ('MULTI_ONE',   '複数1', 'TEAM',         NOW(), NOW()),
                    ('MULTI_TWO',   '複数2', 'ORGANIZATION', NOW(), NOW());

                INSERT INTO permissions (name, display_name, scope, created_at, updated_at)
                SELECT 'IDEMPOTENT', '再実行安全', 'TEAM', NOW(), NOW()
                FROM DUAL
                WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE name = 'IDEMPOTENT');
                """;

        assertThat(PermissionNameCatalogGuardTest.parseCatalogFromSql(sql, "synthetic.sql"))
                .containsExactlyInAnyOrder("SINGLE_VALUES", "MULTI_ONE", "MULTI_TWO", "IDEMPOTENT");
    }

    @Test
    @DisplayName("陽性: 列順が name 先頭でなくても列リストから正しい位置を読む")
    void respectsColumnOrder() {
        String sql = """
                INSERT INTO permissions (display_name, name, scope, created_at, updated_at)
                VALUES ('表示名', 'NAME_IN_SECOND_COLUMN', 'TEAM', NOW(), NOW());
                """;

        assertThat(PermissionNameCatalogGuardTest.parseCatalogFromSql(sql, "synthetic.sql"))
                .containsExactly("NAME_IN_SECOND_COLUMN");
    }

    @Test
    @DisplayName("陰性: role_permissions など別テーブルへの INSERT や、コメント中の INSERT を拾わない")
    void ignoresOtherTablesAndComments() {
        String sql = """
                -- INSERT INTO permissions (name) VALUES ('GHOST_IN_COMMENT');
                /* INSERT INTO permissions (name) VALUES ('GHOST_IN_BLOCK_COMMENT'); */
                INSERT INTO role_permissions (role_id, permission_id, is_default, created_at)
                SELECT r.id, p.id, 1, NOW()
                FROM roles r CROSS JOIN permissions p
                WHERE r.name = 'ADMIN' AND p.name IN ('REGISTERED_PERMISSION');
                """;

        assertThat(PermissionNameCatalogGuardTest.parseCatalogFromSql(sql, "synthetic.sql")).isEmpty();
    }

    @Test
    @DisplayName("未対応形式は黙って読み飛ばさず例外で止まる（抽出漏れを静かな緑にしない）")
    void unsupportedInsertFormFailsLoudly() {
        String sql = """
                INSERT INTO permissions (name, display_name, scope, created_at, updated_at)
                SET name = 'UNSUPPORTED_FORM';
                """;

        assertThatThrownBy(() -> PermissionNameCatalogGuardTest.parseCatalogFromSql(sql, "synthetic.sql"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未対応形式");
    }
}
