package com.mannschaft.app.village.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 村サービス群が村の存在確認を自前で書き戻していないことを見張る番人（受け入れ条件 AC-6）。
 *
 * <h2>なぜソース走査という手段なのか</h2>
 * <p>守りたいのは「各サービスが村の存在確認を<b>自分では書かない</b>」という構造上の性質であり、
 * これは振る舞いテストでは捕まえられない。存在確認を 1 箇所でも自前に戻すと、
 * その 1 経路だけ {@code visibility} を見ずに応答が割れ、
 * 非公開村の存在オラクルが静かに復活する（本戦役以前がまさにその状態だった）。
 * 15 サービスすべてを毎回人手で見直すことはできないため、機械に見張らせる。</p>
 *
 * <p>{@link VillageAccessGate} 自身は {@code villageRepository.findById} を使う唯一の正しい場所なので
 * 対象外とする。</p>
 */
@DisplayName("AC-6: 村サービス群に自前の村存在確認が残っていないこと")
class VillageAccessGateAdoptionGuardTest {

    /** 本戦役でゲートへ寄せた 15 サービス。 */
    private static final List<String> GATED_SERVICES = List.of(
            "PostingIdentityService",
            "VillageCalendarService",
            "VillageCharterService",
            "VillageFestivalParticipationService",
            "VillageFestivalService",
            "VillageJoinRequestService",
            "VillageLobbyService",
            "VillageMatchRecruitService",
            "VillageMeetupService",
            "VillageMembershipService",
            "VillagePinService",
            "VillageReportService",
            "VillageRepresentativeService",
            "VillageSearchService",
            "VillageSerendipityService");

    private static final Pattern FORBIDDEN = Pattern.compile(
            "villageRepository\\s*\\.\\s*(findById|findByIdAndDeletedAtIsNullAndArchivedAtIsNull)\\s*\\(");

    private static final Path SERVICE_PACKAGE =
            Paths.get("src", "main", "java", "com", "mannschaft", "app", "village", "service");

    @Test
    @DisplayName("15 サービスは villageRepository.findById / findByIdAndDeletedAtIsNullAndArchivedAtIsNull で村の存在確認をしない")
    void gatedServicesDoNotLoadVillageThemselves() throws IOException {
        Path dir = resolveServiceDir();
        List<String> violations = new ArrayList<>();

        for (String service : GATED_SERVICES) {
            Path file = dir.resolve(service + ".java");
            assertThat(file).as("対象サービスのソースが見つからない: %s", file.toAbsolutePath()).exists();

            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (FORBIDDEN.matcher(lines.get(i)).find()) {
                    violations.add(service + ".java:" + (i + 1) + " → " + lines.get(i).trim());
                }
            }
        }

        assertThat(violations)
                .as("村の存在確認は VillageAccessGate に一元化すること"
                        + "（自前で書き戻すと visibility を見落とし、非公開村の存在オラクルが復活する）")
                .isEmpty();
    }

    /**
     * テストの CWD は Gradle 実行時 {@code backend/} だが、リポジトリ直下から起動される場合もある。
     * どちらでも解決できるように両方試す。
     */
    private Path resolveServiceDir() {
        Path fromBackend = SERVICE_PACKAGE;
        if (Files.isDirectory(fromBackend)) {
            return fromBackend;
        }
        Path fromRepoRoot = Paths.get("backend").resolve(SERVICE_PACKAGE);
        assertThat(fromRepoRoot)
                .as("村サービスのソースディレクトリが見つからない（CWD=%s）", Paths.get("").toAbsolutePath())
                .isDirectory();
        return fromRepoRoot;
    }
}
