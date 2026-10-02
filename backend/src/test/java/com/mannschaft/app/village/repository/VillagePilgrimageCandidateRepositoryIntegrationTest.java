package com.mannschaft.app.village.repository;

import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link VillageRepository#findPilgrimageCandidateIds} 結合テスト。
 *
 * <p>{@code VillagePilgrimageBatchService} が全村ロード＋アプリ側フィルタ（ユーザー数×村数の
 * オーダー）から SQL 側の WHERE 句絞り込みへ載せ替えたことに伴い、削除済み・凍結・UNLISTED の除外、
 * カテゴリ一致、参加済み/ピン済み除外の各条件が実 DB 上で意図通り機能することを検証する
 * （モックでは JPQL の正しさを検証できないため）。ソートは {@code ORDER BY RAND()} の性能問題を
 * 避けるため行わず、候補 ID 集合の絞り込みのみを検証する（ランダム選定はアプリ側）。</p>
 */
@Transactional
@DisplayName("VillageRepository#findPilgrimageCandidateIds 結合テスト")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class VillagePilgrimageCandidateRepositoryIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private VillageRepository villageRepository;

    private VillageEntity persistVillage(String category, VillageVisibility visibility,
                                          boolean deleted, boolean archived) {
        VillageEntity v = VillageEntity.builder()
                .slug("v-" + UUID.randomUUID().toString().substring(0, 8))
                .name("結合テスト村")
                .type(VillageType.COMMUNITY)
                .joinPolicy(VillageJoinPolicy.FREE)
                .visibility(visibility)
                .category(category)
                .build();
        if (deleted) {
            v.setDeletedAt(LocalDateTime.now());
        }
        if (archived) {
            v.setArchivedAt(LocalDateTime.now());
        }
        return villageRepository.save(v);
    }

    private List<UUID> excludeDummy() {
        // NOT IN 句は空集合を許容しないため、実在しないダミー ID を 1 件渡す。
        return List.of(UUID.randomUUID());
    }

    @Test
    @DisplayName("PUBLIC かつ未削除・未凍結の村が候補になる")
    void PUBLIC未削除未凍結が候補になる() {
        VillageEntity candidate = persistVillage("sports", VillageVisibility.PUBLIC, false, false);

        List<UUID> result = villageRepository.findPilgrimageCandidateIds(
                VillageVisibility.PUBLIC, excludeDummy(), true, List.of("__NONE__"));

        assertThat(result).contains(candidate.getId());
    }

    @Test
    @DisplayName("削除済み・凍結・UNLISTED の村は候補から除外される")
    void 削除済み凍結UNLISTEDは除外される() {
        VillageEntity deleted = persistVillage("sports", VillageVisibility.PUBLIC, true, false);
        VillageEntity archived = persistVillage("sports", VillageVisibility.PUBLIC, false, true);
        VillageEntity unlisted = persistVillage("sports", VillageVisibility.UNLISTED, false, false);

        List<UUID> result = villageRepository.findPilgrimageCandidateIds(
                VillageVisibility.PUBLIC, excludeDummy(), true, List.of("__NONE__"));

        assertThat(result).doesNotContain(deleted.getId(), archived.getId(), unlisted.getId());
    }

    @Test
    @DisplayName("categoriesEmpty=false のとき、指定カテゴリに一致する村のみ返る")
    void カテゴリ一致のみ返る() {
        VillageEntity matched = persistVillage("sports", VillageVisibility.PUBLIC, false, false);
        VillageEntity unmatched = persistVillage("music", VillageVisibility.PUBLIC, false, false);

        List<UUID> result = villageRepository.findPilgrimageCandidateIds(
                VillageVisibility.PUBLIC, excludeDummy(), false, Set.of("sports"));

        assertThat(result).contains(matched.getId());
        assertThat(result).doesNotContain(unmatched.getId());
    }

    @Test
    @DisplayName("excludeIds に含まれる村（参加済み/ピン済み相当）は候補から除外される")
    void 除外ID指定の村は候補から除外される() {
        VillageEntity excluded = persistVillage("sports", VillageVisibility.PUBLIC, false, false);
        VillageEntity other = persistVillage("sports", VillageVisibility.PUBLIC, false, false);

        List<UUID> result = villageRepository.findPilgrimageCandidateIds(
                VillageVisibility.PUBLIC, List.of(excluded.getId()), true, List.of("__NONE__"));

        assertThat(result)
                .contains(other.getId())
                .doesNotContain(excluded.getId());
    }
}
