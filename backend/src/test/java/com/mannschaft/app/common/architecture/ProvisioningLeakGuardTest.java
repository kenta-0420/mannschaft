package com.mannschaft.app.common.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 柱②-3 販促プロビジョニングゲート番人。
 *
 * <p>{@code TeamRepository} / {@code OrganizationRepository} の「PUBLIC 可視性で絞り込む」
 * クエリ（未認証でも到達しうる公開検索・sitemap・discover の直下）が、
 * {@code lifecycleStatus = ... .ACTIVE} 条件を併せ持つことを機械的に強制する。
 *
 * <p>PROVISIONED（承諾前の事前作成状態）スコープは作成時に必ず非公開可視性
 * （org: PRIVATE / team: MEMBERS_AND_ABOVE）で作られるため、今この瞬間の実データでは
 * この 2 条件が食い違う行は存在しない。だが「将来 ADMIN が visibility を PUBLIC へ
 * 変更する経路が生まれても、accept() されるまでは PUBLIC 系クエリに絶対に出現しない」
 * という不変条件を保つのはこの機械チェックだけであり、レビューの見落としに頼らない
 * （検体でなく判定の軸として書く）。</p>
 *
 * <p>ホワイトリストは「意図的に PROVISIONED 行を読む」経路（SYSTEM_ADMIN 管理系・
 * 承諾前の下見）に限定する。凍結値（本番人が現時点で確認した該当メソッド総数）を
 * アサートし、新規に PUBLIC 系クエリが増減した場合は本テストの数値を実測に合わせて
 * 更新すること（ホワイトリストへ安易に追加して回避するのは禁止）。
 */
class ProvisioningLeakGuardTest {

    private static final Path TEAM_REPOSITORY =
            Paths.get("src/main/java/com/mannschaft/app/team/repository/TeamRepository.java");
    private static final Path ORGANIZATION_REPOSITORY =
            Paths.get("src/main/java/com/mannschaft/app/organization/repository/OrganizationRepository.java");

    /** PUBLIC 可視性で絞り込むクエリの行を検出する。 */
    private static final Pattern PUBLIC_VISIBILITY_LINE = Pattern.compile("Visibility\\.PUBLIC");

    /** 同一クエリ内に併存すべき lifecycle_status 条件。 */
    private static final Pattern LIFECYCLE_ACTIVE = Pattern.compile("LifecycleStatus\\.ACTIVE");

    /**
     * 意図的に PROVISIONED を含めて読んでよい経路のホワイトリスト（メソッド名）。
     * 現時点では空（全 PUBLIC 系クエリに ACTIVE 条件を追加済み）。
     */
    private static final List<String> WHITELIST = List.of();

    /** 本番人が現時点で確認した「PUBLIC 可視性で絞り込むクエリ行」の凍結総数。 */
    private static final int FROZEN_TEAM_PUBLIC_QUERY_LINES = 6;
    private static final int FROZEN_ORGANIZATION_PUBLIC_QUERY_LINES = 7;

    @Test
    @DisplayName("柱②-3: TeamRepositoryのPUBLIC系クエリは全てlifecycleStatus=ACTIVE条件を伴う")
    void teamPublicQueriesRequireActiveLifecycleStatus() throws IOException {
        assertNoLeak(TEAM_REPOSITORY, FROZEN_TEAM_PUBLIC_QUERY_LINES);
    }

    @Test
    @DisplayName("柱②-3: OrganizationRepositoryのPUBLIC系クエリは全てlifecycleStatus=ACTIVE条件を伴う")
    void organizationPublicQueriesRequireActiveLifecycleStatus() throws IOException {
        assertNoLeak(ORGANIZATION_REPOSITORY, FROZEN_ORGANIZATION_PUBLIC_QUERY_LINES);
    }

    private void assertNoLeak(Path file, int expectedTotal) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        int publicQueryLineCount = 0;
        List<String> violations = new java.util.ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            Matcher m = PUBLIC_VISIBILITY_LINE.matcher(lines.get(i));
            if (!m.find()) {
                continue;
            }
            publicQueryLineCount++;

            String methodName = findEnclosingMethodName(lines, i);
            if (WHITELIST.contains(methodName)) {
                continue;
            }

            // 同一 @Query ブロック内（前後15行の窓）に ACTIVE 条件があるかを確認する。
            int windowStart = Math.max(0, i - 15);
            int windowEnd = Math.min(lines.size(), i + 15);
            boolean hasActiveCondition = false;
            for (int j = windowStart; j < windowEnd; j++) {
                if (LIFECYCLE_ACTIVE.matcher(lines.get(j)).find()) {
                    hasActiveCondition = true;
                    break;
                }
            }
            if (!hasActiveCondition) {
                violations.add(file.getFileName() + ":" + (i + 1) + " method=" + methodName
                        + " — PUBLIC可視性クエリにlifecycleStatus=ACTIVE条件が無い（PROVISIONED漏出の恐れ）");
            }
        }

        assertThat(violations).as("PROVISIONED漏出ゲート違反: %s", violations).isEmpty();
        assertThat(publicQueryLineCount)
                .as("PUBLIC可視性クエリ行の凍結総数（増減したら本テストの期待値を実測に合わせて更新すること）")
                .isEqualTo(expectedTotal);
    }

    /** 対象行より前を遡り、直近のメソッド宣言らしき行からメソッド名を推定する（レポート用途のみ）。 */
    private String findEnclosingMethodName(List<String> lines, int fromIndex) {
        Pattern methodDecl = Pattern.compile("\\b([A-Za-z][A-Za-z0-9_<>,\\s]*?)\\s+(\\w+)\\s*\\(");
        for (int i = fromIndex; i < Math.min(lines.size(), fromIndex + 20); i++) {
            String line = lines.get(i);
            if (line.contains("@Query") || line.trim().isEmpty() || line.trim().startsWith("//")
                    || line.trim().startsWith("*") || line.trim().startsWith("\"")) {
                continue;
            }
            Matcher m = methodDecl.matcher(line);
            if (m.find()) {
                return m.group(2);
            }
        }
        return "(unknown)";
    }
}
