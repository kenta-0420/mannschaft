package com.mannschaft.app.common.architecture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-260826-1455: role変更とBANの実行者認可は現役メンバーの正準述語を使う。
 *
 * <p>既存の村番人と同じくソースを読み、コメント・文字列は既存scannerで除去する。
 * 対象をVillageMembershipServiceのchangeRole/banのactor取得式だけに固定し、
 * 参加時のBAN再加入拒否や対象取得で適法に使う在籍述語は検査しない。
 * この局所的な書式契約の変更時は、本番のHTTP/実DB契約も併せて検証する。</p>
 */
class VillageMembershipModeratorPredicateGuardTest {

    private static final Path SOURCE = Path.of("src/main/java/com/mannschaft/app/village/service/VillageMembershipService.java");
    private static final Path BULLETIN_SOURCE = Path.of("src/main/java/com/mannschaft/app/village/service/VillageBulletinAccessService.java");
    private static final String CANONICAL = "findActiveByVillageIdAndSubject";
    private static final String RAW = "findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull";
    private static final Pattern ACTOR_QUERY = Pattern.compile(
            "VillageMembershipEntity\\s+actor\\s*=\\s*membershipRepository\\s*(.*?)\\.orElseThrow", Pattern.DOTALL);

    @ParameterizedTest
    @ValueSource(strings = {"changeRole", "ban"})
    void 村長操作の認可_実行者取得_正準述語だけを使う(String method) throws IOException {
        assertThat(usesCanonicalActorQuery(Files.readString(SOURCE, StandardCharsets.UTF_8), method))
                .as("VillageMembershipService#%s の実行者認可は正準述語を使う", method)
                .isTrue();
    }

    @Test
    void 掲示板モデレーター認可_正準述語だけを使う() throws IOException {
        assertThat(bulletinModeratorUsesCanonical(Files.readString(BULLETIN_SOURCE, StandardCharsets.UTF_8)))
                .as("VillageBulletinAccessService#checkVillageBulletinModerator は正準述語を使う")
                .isTrue();
    }

    @Test
    void 番人_掲示板に生述語を混入_拒否する() {
        String body = "public void checkVillageBulletinModerator() { membershipRepository." + RAW
                + "(a).filter(m -> true); membershipRepository." + CANONICAL + "(a); }"
                + " public void requireHeadmanOrElder() { }";
        assertThat(bulletinModeratorUsesCanonical(body)).isFalse();
        assertThat(bulletinModeratorUsesCanonical(
                "public void checkVillageBulletinModerator() { membershipRepository." + RAW + "(a); }"
                        + " public void requireHeadmanOrElder() { }")).isFalse();
    }

    private static boolean bulletinModeratorUsesCanonical(String source) {
        String masked = JavaSourceScanningUtils.maskCommentsAndLiterals(source);
        int start = masked.indexOf("public void checkVillageBulletinModerator(");
        if (start < 0) return false;
        int end = masked.indexOf("public void requireHeadmanOrElder(", start + 1);
        if (end < 0) return false;
        String body = masked.substring(start, end);
        return Pattern.compile("\\." + CANONICAL + "\\s*\\(").matcher(body).find() && !body.contains(RAW);
    }

    @Test
    void 番人_正準呼出しを削除_拒否する() {
        String source = fixture("membershipRepository.otherQuery(villageId)");

        assertThat(usesCanonicalActorQuery(source, "changeRole")).isFalse();
        assertThat(usesCanonicalActorQuery(source, "ban")).isFalse();
    }

    @Test
    void 番人_正準呼出しに生述語を混入_拒否する() {
        String source = fixture("membershipRepository." + CANONICAL + "(villageId)"
                + ".or(() -> membershipRepository." + RAW + "(villageId))");

        assertThat(usesCanonicalActorQuery(source, "changeRole")).isFalse();
        assertThat(usesCanonicalActorQuery(source, "ban")).isFalse();
    }

    @Test
    void 番人_実行者以外の適法な生述語_許可する() {
        String source = fixture("membershipRepository." + CANONICAL + "(villageId)");

        assertThat(usesCanonicalActorQuery(source, "changeRole")).isTrue();
        assertThat(usesCanonicalActorQuery(source, "ban")).isTrue();
    }

    @Test
    void 番人_コメントだけに正準呼出しがある_拒否する() {
        String source = fixture("membershipRepository.otherQuery(villageId) /* ." + CANONICAL + "(villageId) */");

        assertThat(usesCanonicalActorQuery(source, "changeRole")).isFalse();
        assertThat(usesCanonicalActorQuery(source, "ban")).isFalse();
    }

    private static boolean usesCanonicalActorQuery(String source, String method) {
        String masked = JavaSourceScanningUtils.maskCommentsAndLiterals(source);
        int start = masked.indexOf("public MembershipResponse " + method + "(");
        if (start < 0) return false;
        String endMarker = method.equals("changeRole")
                ? "public MembershipResponse ban(" : "private VillageEntity loadActiveVillage(";
        int end = masked.indexOf(endMarker, start + 1);
        if (end < 0) return false;
        Matcher actor = ACTOR_QUERY.matcher(masked.substring(start, end));
        if (!actor.find()) return false;
        String query = actor.group(1);
        return Pattern.compile("\\." + CANONICAL + "\\s*\\(").matcher(query).find()
                && !query.contains(RAW);
    }

    private static String fixture(String actorExpression) {
        // 参加・対象取得の生述語を含めても、実行者の2取得式だけを検査する。
        return """
                public MembershipResponse join() {
                    return membershipRepository.%s(villageId);
                }
                public MembershipResponse changeRole() {
                    VillageMembershipEntity actor = %s.orElseThrow();
                    VillageMembershipEntity target = membershipRepository.%s(villageId);
                }
                public MembershipResponse ban() {
                    VillageMembershipEntity actor = %s.orElseThrow();
                    VillageMembershipEntity target = membershipRepository.%s(villageId);
                }
                private VillageEntity loadActiveVillage() { }
                """.formatted(RAW, actorExpression, RAW, actorExpression, RAW);
    }
}
