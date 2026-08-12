package com.mannschaft.app.common.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 試験ソース中の「所属ロール（MEMBER / SUPPORTER）を {@code user_roles} へ生 SQL で INSERT する」
 * フィクスチャを機械的に禁止する静的走査番人（CMP-027・段B3）。
 *
 * <h2>背景</h2>
 * <p>{@code V60.010} で {@code user_roles} から MEMBER/SUPPORTER 行は削除され {@code memberships} へ
 * 完全移行した。にもかかわらずテストが {@code user_roles} へ MEMBER/SUPPORTER を張ると、
 * 本番で成立しえない状態をフィクスチャが作り、下向き再帰の memberships 取りこぼし（CMP-027）のような
 * 欠陥を「永久に緑」で覆い隠す。{@link com.mannschaft.app.support.test.MembershipTestHelper#insertUserRole}
 * は実行時にこれを拒否するが、ヘルパーを介さない生 {@code INSERT INTO user_roles ... 'MEMBER'} は
 * すり抜ける。本番人はソーステキストを走査してその抜け道を塞ぐ（補完的・最小実装）。</p>
 *
 * <p>所属は必ず {@code memberships}（{@code MembershipTestHelper.insertMembership}）で表現すること。
 * {@code user_roles} が正しいのは権限ロール（ADMIN/DEPUTY_ADMIN/GUEST/SYSTEM_ADMIN）のみ。</p>
 */
class UserRolesMembershipRoleInsertGuardTest {

    /** 試験ソースのルート（worktree からの相対パス。CWD=module dir を前提）。 */
    private static final Path TEST_SOURCE_ROOT = Paths.get("src", "test", "java");

    /**
     * 生 {@code INSERT INTO user_roles ...} 文の近傍に、所属ロール名リテラル {@code 'MEMBER'} /
     * {@code 'SUPPORTER'} が現れるパターン。role_id（数値バインド）で張る正当な user_roles INSERT
     * （ADMIN 等の権限ロール）には role 名リテラルが出ないため誤検出しない。
     */
    private static final Pattern RAW_USER_ROLES_MEMBERSHIP_INSERT = Pattern.compile(
            "INSERT\\s+INTO\\s+user_roles[\\s\\S]{0,400}?'(MEMBER|SUPPORTER)'",
            Pattern.CASE_INSENSITIVE);

    /** この番人自身のファイル名（自己検証用サンプルにパターンを含むため走査対象から除外する）。 */
    private static final String SELF_FILE_NAME = "UserRolesMembershipRoleInsertGuardTest.java";

    static boolean containsRawUserRolesMembershipInsert(String source) {
        return RAW_USER_ROLES_MEMBERSHIP_INSERT.matcher(source).find();
    }

    @Test
    @DisplayName("試験ソースに生INSERT INTO user_roles ... 'MEMBER'/'SUPPORTER' が存在しない")
    void 生user_rolesへの所属ロールINSERTが存在しない() throws IOException {
        assertTrue(Files.isDirectory(TEST_SOURCE_ROOT),
                "試験ソースルートが見つからない: " + TEST_SOURCE_ROOT.toAbsolutePath()
                        + "（CWD=" + Paths.get("").toAbsolutePath() + "）");

        List<String> violations = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(TEST_SOURCE_ROOT)) {
            List<Path> javaFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals(SELF_FILE_NAME))
                    .toList();
            for (Path p : javaFiles) {
                String source = Files.readString(p);
                if (containsRawUserRolesMembershipInsert(source)) {
                    violations.add(TEST_SOURCE_ROOT.relativize(p).toString());
                }
            }
        }

        if (violations.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("所属ロール(MEMBER/SUPPORTER)を user_roles へ生 SQL で INSERT している試験ソースがあります。"
                + "V60.010 以降これは本番で成立しえない状態であり、死んだ機能を「永久に緑」で隠します。"
                + "所属は memberships（MembershipTestHelper.insertMembership）で表現してください。\n違反ファイル:\n");
        for (String v : violations) {
            sb.append("  ✗ ").append(v).append('\n');
        }
        fail(sb.toString());
    }

    @Test
    @DisplayName("検出器の自己検証: 生user_roles×所属ロールを検出し、正当なフィクスチャは誤検出しない")
    void 検出器は自身の偽陰性を晒す() {
        // 陽性: 生 INSERT INTO user_roles に 'MEMBER' リテラルを含む（＝本番不能フィクスチャ）
        String positiveMember =
                "em.createNativeQuery(\"INSERT INTO user_roles (user_id, role_name, team_id) "
                        + "VALUES (:uid, 'MEMBER', :tid)\").executeUpdate();";
        String positiveSupporter =
                "\"INSERT INTO user_roles (user_id, role_name) VALUES (:uid, 'SUPPORTER')\"";
        assertTrue(containsRawUserRolesMembershipInsert(positiveMember),
                "生 user_roles×MEMBER を検出できていない（偽陰性）");
        assertTrue(containsRawUserRolesMembershipInsert(positiveSupporter),
                "生 user_roles×SUPPORTER を検出できていない（偽陰性）");

        // 陰性1: role_id を数値バインドで張る正当な user_roles INSERT（権限ロール用）
        String benignRoleId =
                "\"INSERT INTO user_roles (user_id, role_id, team_id) VALUES (:uid, :rid, :tid)\"";
        assertFalse(containsRawUserRolesMembershipInsert(benignRoleId),
                "role_id バインドの正当な user_roles INSERT を誤検出している");

        // 陰性2: memberships への INSERT（role_kind に MEMBER が出るが user_roles ではない）
        String benignMembership =
                "\"INSERT INTO memberships (user_id, scope_type, scope_id, role_kind) "
                        + "VALUES (:uid, 'TEAM', :sid, 'MEMBER')\"";
        assertFalse(containsRawUserRolesMembershipInsert(benignMembership),
                "memberships への MEMBER INSERT を誤検出している");

        // 陰性3: 単なる 'MEMBER' 文字列リテラル（アサーション等・user_roles INSERT と無関係）
        String benignLiteral = "assertThat(role).isEqualTo(\"MEMBER\");";
        assertFalse(containsRawUserRolesMembershipInsert(benignLiteral),
                "無関係な 'MEMBER' 文字列を誤検出している");
    }
}
