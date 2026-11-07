package com.mannschaft.app.social.announcement.audience;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F01.2.1 AC-G144（K07 の実装側の担保。Codex 指摘8）— ソース走査の番人。
 *
 * <p>告知の宛先候補を {@code UserRoleRepository.findTeamIdsByOrganizationId}（{@code user_roles} の組織ロール行
 * ∪ ACTIVE の加盟）で作ると、加盟していないチームが候補に紛れる。social ドメインからこの呼び出しが消え、
 * 候補の検証が team ドメインの ACTIVE 加盟専用クエリ {@code TeamOrgMembershipQueryService.findActiveTeamIdsIn}
 * だけで行われることをソースで固定する。</p>
 */
@DisplayName("F01.2.1 AC-G144 宛先候補は ACTIVE 加盟専用のクエリだけで検証する（ソース走査）")
class BroadcastTargetCandidateSourceGuardTest {

    private static final Path SOCIAL = Paths.get("src/main/java/com/mannschaft/app/social");
    private static final Path RESOLVER = Paths.get(
            "src/main/java/com/mannschaft/app/social/announcement/audience/BroadcastAudienceResolver.java");
    private static final Path QUERY_SERVICE = Paths.get(
            "src/main/java/com/mannschaft/app/team/service/TeamOrgMembershipQueryService.java");

    @Test
    @DisplayName("social ドメインに findTeamIdsByOrganizationId の呼び出しが1件も無い")
    void socialDoesNotCallUserRoleCandidateQuery() throws IOException {
        assertThat(Files.isDirectory(SOCIAL)).as("走査対象が見つからない（作業ディレクトリが backend でない）").isTrue();
        List<Path> offenders;
        try (Stream<Path> files = Files.walk(SOCIAL)) {
            offenders = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> read(p).contains("findTeamIdsByOrganizationId"))
                    .toList();
        }
        assertThat(offenders).isEmpty();
    }

    @Test
    @DisplayName("候補の検証は BroadcastAudienceResolver から findActiveTeamIdsIn を呼んで行う")
    void resolverUsesActiveMembershipQuery() {
        assertThat(Files.exists(RESOLVER)).as("BroadcastAudienceResolver が無い").isTrue();
        assertThat(read(RESOLVER)).contains("findActiveTeamIdsIn(");
        assertThat(read(QUERY_SERVICE)).contains("public List<Long> findActiveTeamIdsIn(");
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(p.toString(), e);
        }
    }
}
