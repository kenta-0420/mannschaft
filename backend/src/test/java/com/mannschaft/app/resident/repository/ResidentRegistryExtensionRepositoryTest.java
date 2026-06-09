package com.mannschaft.app.resident.repository;

import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.resident.entity.DeathStatus;
import com.mannschaft.app.resident.entity.DwellingUnitEntity;
import com.mannschaft.app.resident.entity.OccupancyStatus;
import com.mannschaft.app.resident.entity.ResidentRegistryEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F09.15 / F09.16 連携: {@link ResidentRegistryRepository} の死亡関連・居住実態関連カラム拡張テスト。
 *
 * <p>V9.102（死亡関連 5 カラム）と V9.103（居住実態 5 カラム）の ALTER TABLE で追加された
 * カラムが、JPA エンティティ経由で正しく永続化・取得できることを検証する。</p>
 */
@DisplayName("ResidentRegistryRepository F09.15/16 拡張カラム結合テスト")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@Transactional
class ResidentRegistryExtensionRepositoryTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private ResidentRegistryRepository residentRegistryRepository;

    @Autowired
    private DwellingUnitRepository dwellingUnitRepository;

    @Autowired
    private EncryptionService encryptionService;

    @PersistenceContext
    private EntityManager em;

    /**
     * dwelling_units は teams への FK 制約を持つため、テスト用の team を native query で投入する。
     */
    private Long persistTeam(String name) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, created_at, updated_at, slug) "
                        + "VALUES (:name, 'PUBLIC', 1, 0, 0, NOW(), NOW(), LEFT(REPLACE(UUID(), '-', ''), 22))")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM teams WHERE name = :name ORDER BY id DESC LIMIT 1")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    /**
     * テスト用の dwelling_unit を作成する（FK 制約を満たすため事前作成が必要）。
     */
    private DwellingUnitEntity persistDwellingUnit(String unitNumber) {
        Long teamId = persistTeam("F0915-S1C-team-" + unitNumber);
        DwellingUnitEntity unit = DwellingUnitEntity.builder()
                .scopeType("TEAM")
                .teamId(teamId)
                .unitNumber(unitNumber)
                .build();
        return dwellingUnitRepository.saveAndFlush(unit);
    }

    /**
     * 暗号化が必要な PII フィールドを埋めた最小構成の居住者エンティティを返す。
     */
    private ResidentRegistryEntity baseResident(Long dwellingUnitId) {
        return ResidentRegistryEntity.builder()
                .dwellingUnitId(dwellingUnitId)
                .residentType("OWNER")
                .lastName("山田")
                .firstName("太郎")
                .ownershipRatio(new BigDecimal("1.0000"))
                .moveInDate(LocalDate.now())
                .build();
    }

    @Test
    @DisplayName("デフォルト値: 新規居住者は死亡状態 ALIVE / 居住実態 UNKNOWN / セカンドハウス false で保存される")
    void shouldDefaultDeathStatusAliveAndOccupancyUnknown() {
        DwellingUnitEntity unit = persistDwellingUnit("101");

        ResidentRegistryEntity saved = residentRegistryRepository.saveAndFlush(baseResident(unit.getId()));
        em.clear();

        ResidentRegistryEntity reloaded = residentRegistryRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getDeathStatus()).isEqualTo(DeathStatus.ALIVE);
        assertThat(reloaded.getDeathStatusChangedAt()).isNull();
        assertThat(reloaded.getDeathStatusChangedBy()).isNull();
        assertThat(reloaded.getPresumedDeathScore()).isNull();
        assertThat(reloaded.getActivityLastSeenAt()).isNull();
        assertThat(reloaded.getOccupancyStatus()).isEqualTo(OccupancyStatus.UNKNOWN);
        assertThat(reloaded.getIsSecondaryHome()).isFalse();
        assertThat(reloaded.getLastAnnualReviewAt()).isNull();
        assertThat(reloaded.getAnnualReviewDueAt()).isNull();
        assertThat(reloaded.getAgeEstimated()).isNull();
    }

    @Test
    @DisplayName("F09.15: updateDeathStatus で死亡状態と変更者・変更日時が永続化される")
    void shouldUpdateDeathStatusFields() {
        DwellingUnitEntity unit = persistDwellingUnit("102");
        ResidentRegistryEntity saved = residentRegistryRepository.saveAndFlush(baseResident(unit.getId()));

        saved.updateDeathStatus(DeathStatus.SUSPECTED, 999L);
        saved.updatePresumedDeathScore(85, LocalDateTime.now().minusDays(30));
        residentRegistryRepository.saveAndFlush(saved);
        em.clear();

        ResidentRegistryEntity reloaded = residentRegistryRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getDeathStatus()).isEqualTo(DeathStatus.SUSPECTED);
        assertThat(reloaded.getDeathStatusChangedBy()).isEqualTo(999L);
        assertThat(reloaded.getDeathStatusChangedAt()).isNotNull();
        assertThat(reloaded.getPresumedDeathScore()).isEqualTo(85);
        assertThat(reloaded.getActivityLastSeenAt()).isNotNull();
    }

    @Test
    @DisplayName("F09.15: 死亡状態を CONFIRMED へ遷移できる")
    void shouldTransitionToConfirmed() {
        DwellingUnitEntity unit = persistDwellingUnit("103");
        ResidentRegistryEntity saved = residentRegistryRepository.saveAndFlush(baseResident(unit.getId()));

        saved.updateDeathStatus(DeathStatus.CONFIRMED, 1L);
        residentRegistryRepository.saveAndFlush(saved);
        em.clear();

        ResidentRegistryEntity reloaded = residentRegistryRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getDeathStatus()).isEqualTo(DeathStatus.CONFIRMED);
    }

    @Test
    @DisplayName("F09.16: updateOccupancyStatus と recordAnnualReview で居住実態・年次更新カラムが永続化される")
    void shouldUpdateOccupancyAndAnnualReviewFields() {
        DwellingUnitEntity unit = persistDwellingUnit("104");
        ResidentRegistryEntity saved = residentRegistryRepository.saveAndFlush(baseResident(unit.getId()));

        saved.updateOccupancyStatus(OccupancyStatus.RENTED_OUT, false);
        saved.recordAnnualReview(LocalDateTime.now(), LocalDate.now().plusYears(1));
        residentRegistryRepository.saveAndFlush(saved);
        em.clear();

        ResidentRegistryEntity reloaded = residentRegistryRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getOccupancyStatus()).isEqualTo(OccupancyStatus.RENTED_OUT);
        assertThat(reloaded.getIsSecondaryHome()).isFalse();
        assertThat(reloaded.getLastAnnualReviewAt()).isNotNull();
        assertThat(reloaded.getAnnualReviewDueAt()).isEqualTo(LocalDate.now().plusYears(1));
    }

    @Test
    @DisplayName("F09.16: SECONDARY_HOME 区分のとき isSecondaryHome=true として保存できる")
    void shouldPersistSecondaryHomeFlag() {
        DwellingUnitEntity unit = persistDwellingUnit("105");
        ResidentRegistryEntity entity = baseResident(unit.getId()).toBuilder()
                .occupancyStatus(OccupancyStatus.SECONDARY_HOME)
                .isSecondaryHome(true)
                .ageEstimated(72)
                .build();

        ResidentRegistryEntity saved = residentRegistryRepository.saveAndFlush(entity);
        em.clear();

        ResidentRegistryEntity reloaded = residentRegistryRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getOccupancyStatus()).isEqualTo(OccupancyStatus.SECONDARY_HOME);
        assertThat(reloaded.getIsSecondaryHome()).isTrue();
        assertThat(reloaded.getAgeEstimated()).isEqualTo(72);
        // EncryptionService が注入されていることをトリガに、PII 復号も問題ないことを確認
        assertThat(encryptionService).isNotNull();
        assertThat(reloaded.getLastName()).isEqualTo("山田");
    }
}
