package com.mannschaft.app.common.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link VillageExistenceCheckCentralizationGuardTest} の走査ロジック自体の正しさを実証する自己検証テスト。
 *
 * <h2>なぜ要るか</h2>
 * <p><b>番人が緑であることは、番人が守っていることの証明にならない。</b>走査が空振りしていても
 * 「違反ゼロ」と同じ緑になるためである。本クラスは合成ソース（本番には置かない）を直接
 * {@code scan} へ与え、<b>陽性対照</b>（検出すべきものを検出する）と
 * <b>陰性対照</b>（検出してはならないもので誤検出しない）を対で置く。</p>
 */
@DisplayName("VillageExistenceCheckCentralizationGuardTest の走査ロジック（検出力＋誤検出耐性）")
class VillageExistenceCheckCentralizationGuardScanningLogicTest {

    private static final String PATH = "src/main/java/com/mannschaft/app/village/service/SyntheticService.java";

    // ────────────────────────────────────────────────────────────
    // 陽性対照
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("陽性: villageRepository.findById による存在確認を検出する")
    void detectsFindById() {
        String code = """
                package com.mannschaft.app.village.service;
                class SyntheticService {
                    private final VillageRepository villageRepository;
                    VillageEntity load(UUID villageId) {
                        return villageRepository.findById(villageId)
                                .orElseThrow(() -> new BusinessException(VillageErrorCode.VILLAGE_NOT_FOUND));
                    }
                }
                """;

        List<VillageExistenceCheckCentralizationGuardTest.Violation> found =
                VillageExistenceCheckCentralizationGuardTest.scan(Map.of(PATH, code));

        assertThat(found).singleElement().satisfies(v -> {
            assertThat(v.method()).isEqualTo("findById");
            assertThat(v.line()).isEqualTo(5);
            assertThat(v.path()).isEqualTo(PATH);
        });
    }

    @Test
    @DisplayName("陽性: 削除・凍結条件付きの findByIdAndDeletedAtIsNullAndArchivedAtIsNull も検出する")
    void detectsActiveFindById() {
        String code = """
                package com.mannschaft.app.village.service;
                class SyntheticService {
                    private final VillageRepository villageRepository;
                    Optional<VillageEntity> load(UUID villageId) {
                        return villageRepository.findByIdAndDeletedAtIsNullAndArchivedAtIsNull(villageId);
                    }
                }
                """;

        assertThat(VillageExistenceCheckCentralizationGuardTest.scan(Map.of(PATH, code)))
                .extracting(VillageExistenceCheckCentralizationGuardTest.Violation::method)
                .containsExactly("findByIdAndDeletedAtIsNullAndArchivedAtIsNull");
    }

    @Test
    @DisplayName("陽性: フィールド名を villageRepository 以外に変えてもすり抜けられない")
    void detectsRenamedField() {
        String code = """
                package com.mannschaft.app.village.service;
                class SyntheticService {
                    private final VillageRepository repo;
                    boolean exists(UUID villageId) {
                        return repo.existsById(villageId);
                    }
                }
                """;

        assertThat(VillageExistenceCheckCentralizationGuardTest.scan(Map.of(PATH, code)))
                .extracting(VillageExistenceCheckCentralizationGuardTest.Violation::method)
                .containsExactly("existsById");
    }

    @Test
    @DisplayName("陽性: 失敗メッセージ用に原文の行と行番号を保持している")
    void keepsOriginalSourceLine() {
        String code = """
                package com.mannschaft.app.village.service;
                class SyntheticService {
                    private final VillageRepository villageRepository;
                    void act(UUID villageId) {
                        villageRepository.getReferenceById(villageId);
                    }
                }
                """;

        VillageExistenceCheckCentralizationGuardTest.Violation v =
                VillageExistenceCheckCentralizationGuardTest.scan(Map.of(PATH, code)).get(0);

        assertThat(v.render()).contains(PATH + ":5", "[getReferenceById]",
                "villageRepository.getReferenceById(villageId);");
    }

    // ────────────────────────────────────────────────────────────
    // 陰性対照
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("陰性: 一覧・検索・集計クエリは存在確認ではないので検出しない")
    void ignoresListingQueries() {
        String code = """
                package com.mannschaft.app.village.service;
                class SyntheticService {
                    private final VillageRepository villageRepository;
                    void batch(Pageable pageable) {
                        villageRepository.findByDeletedAtIsNull(pageable);
                        villageRepository.findPilgrimageCandidateIds(PUBLIC, excludeIds, true, List.of());
                        villageRepository.save(entity);
                    }
                }
                """;

        assertThat(VillageExistenceCheckCentralizationGuardTest.scan(Map.of(PATH, code))).isEmpty();
    }

    @Test
    @DisplayName("陰性: Javadoc・コメント・文字列リテラル中の記述は誤検出しない")
    void ignoresCommentsAndLiterals() {
        String code = """
                package com.mannschaft.app.village.service;
                /** 以前は villageRepository.findById(villageId) を各自で呼んでいた。 */
                class SyntheticService {
                    private final VillageRepository villageRepository;
                    void doc() {
                        // villageRepository.findById(villageId) は禁止
                        String hint = "villageRepository.findById(villageId)";
                    }
                }
                """;

        assertThat(VillageExistenceCheckCentralizationGuardTest.scan(Map.of(PATH, code))).isEmpty();
    }

    @Test
    @DisplayName("陰性: 別リポジトリの findById は対象外（村の存在確認ではない）")
    void ignoresOtherRepositories() {
        String code = """
                package com.mannschaft.app.village.service;
                class SyntheticService {
                    private final VillageMembershipRepository membershipRepository;
                    void act(UUID id) {
                        membershipRepository.findById(id);
                    }
                }
                """;

        assertThat(VillageExistenceCheckCentralizationGuardTest.scan(Map.of(PATH, code))).isEmpty();
    }

    @Test
    @DisplayName("陰性: VillageRepository を持たないファイルは走査対象にならない")
    void ignoresUnrelatedFiles() {
        String code = """
                package com.mannschaft.app.team.service;
                class SyntheticService {
                    private final TeamRepository teamRepository;
                    void act(Long id) {
                        teamRepository.findById(id);
                    }
                }
                """;

        assertThat(VillageExistenceCheckCentralizationGuardTest.scan(Map.of(PATH, code))).isEmpty();
    }
}
