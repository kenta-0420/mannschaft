package com.mannschaft.app.common.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
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

    /**
     * {@code INSERT INTO user_roles (...cols...) VALUES (...vals...)} の
     * {@code role_id} カラムに<b>リテラル 4（MEMBER）または 5（SUPPORTER）</b>を与える行を検出する。
     *
     * <p>所属ロール名リテラル（{@code 'MEMBER'}）だけでなく、role_id の<b>数値</b>で本番不能な
     * 所属ロールを植える抜け道（例: {@code AbstractSpotlightIT} の filler）も塞ぐ。
     * 判定は「列リストにおける {@code role_id} の序数位置に対応する VALUES のトークンが厳密に
     * {@code 4} か {@code 5}」に限定する。バインドパラメータ（{@code :rid} / {@code ?}）・変数・
     * ADMIN 等の別数値（2/3/6 等）や、team_id 等の別カラムの 4/5 は対象外（誤検出しない）。</p>
     */
    private static final Pattern USER_ROLES_INSERT_COLS_VALS = Pattern.compile(
            "INSERT\\s+INTO\\s+user_roles\\s*\\(([^)]*)\\)\\s*VALUES\\s*\\(([^)]*)\\)",
            Pattern.CASE_INSENSITIVE);

    static boolean containsNumericMembershipRoleIdInsert(String source) {
        // Java 文字列連結（"..." + "..."）を畳んでから引用符を除去し、SQL を連続テキスト化する。
        String norm = source.replaceAll("\"\\s*\\+\\s*\"", "");
        norm = norm.replace("\"", " ");
        norm = norm.replaceAll("\\s+", " ");

        Matcher m = USER_ROLES_INSERT_COLS_VALS.matcher(norm);
        while (m.find()) {
            String[] cols = m.group(1).split(",");
            String[] vals = m.group(2).split(",");
            int idx = -1;
            for (int i = 0; i < cols.length; i++) {
                if (cols[i].trim().equalsIgnoreCase("role_id")) {
                    idx = i;
                    break;
                }
            }
            if (idx < 0 || idx >= vals.length) {
                continue;
            }
            String v = vals[idx].trim();
            if ("4".equals(v) || "5".equals(v)) {
                return true;
            }
        }
        return false;
    }

    static boolean isViolation(String source) {
        return containsRawUserRolesMembershipInsert(source)
                || containsNumericMembershipRoleIdInsert(source);
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
                if (isViolation(source)) {
                    violations.add(TEST_SOURCE_ROOT.relativize(p).toString());
                }
            }
        }

        if (violations.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("所属ロール(MEMBER/SUPPORTER)を user_roles へ生 SQL で INSERT している試験ソースがあります"
                + "（ロール名リテラル 'MEMBER'/'SUPPORTER' または role_id 数値 4/5）。"
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

    @Test
    @DisplayName("数値role_id検出器の自己検証: role_idリテラル4/5を検出し、権限ロール数値/別カラム/バインドは誤検出しない")
    void 数値role_id検出器は自身の偽陰性を晒す() {
        // 陽性: role_id 列に数値 4（MEMBER）/ 5（SUPPORTER）を直書き
        String posMember =
                "\"INSERT INTO user_roles (user_id, role_id, team_id, organization_id) "
                        + "VALUES (:uid, 4, :tid, :oid)\"";
        String posSupporter =
                "\"INSERT INTO user_roles (user_id, role_id) VALUES (:uid, 5)\"";
        assertTrue(containsNumericMembershipRoleIdInsert(posMember),
                "role_id=4(MEMBER) の数値 INSERT を検出できていない（偽陰性）");
        assertTrue(containsNumericMembershipRoleIdInsert(posSupporter),
                "role_id=5(SUPPORTER) の数値 INSERT を検出できていない（偽陰性）");

        // 陰性1: ADMIN 等の別数値 role_id（2/3/6）は正当
        String negAdmin =
                "\"INSERT INTO user_roles (user_id, role_id, team_id) VALUES (:uid, 2, :tid)\"";
        assertFalse(containsNumericMembershipRoleIdInsert(negAdmin),
                "権限ロールの数値 role_id(2) を誤検出している");

        // 陰性2: role_id はバインド、別カラム（team_id）にたまたま 4 が入る
        String negOtherCol =
                "\"INSERT INTO user_roles (user_id, role_id, team_id) VALUES (:uid, :rid, 4)\"";
        assertFalse(containsNumericMembershipRoleIdInsert(negOtherCol),
                "role_id 以外のカラムの 4 を誤検出している");

        // 陰性3: role_id を変数/バインドで渡す（roleId("MEMBER") 相当の解決後の値）
        String negBind =
                "\"INSERT INTO user_roles (user_id, role_id, team_id) VALUES (:uid, :rid, :tid)\"";
        assertFalse(containsNumericMembershipRoleIdInsert(negBind),
                "バインドの role_id を誤検出している");
    }
}
