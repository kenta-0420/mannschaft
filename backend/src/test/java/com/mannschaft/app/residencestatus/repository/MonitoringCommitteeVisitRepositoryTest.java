package com.mannschaft.app.residencestatus.repository;

import com.mannschaft.app.residencestatus.entity.MonitoringCommitteeVisit;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F09.16 {@link MonitoringCommitteeVisitRepository} の統合テスト。
 *
 * <p>{@code considerationMemoEncrypted} は {@link com.mannschaft.app.common.EncryptedStringConverter}
 * で透過暗号化される。テストでは平文 → DB → 復号で同じ値が戻ることを確認する。</p>
 */
@Transactional
@DisplayName("MonitoringCommitteeVisitRepository 統合テスト")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class MonitoringCommitteeVisitRepositoryTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private MonitoringCommitteeVisitRepository repository;

    @PersistenceContext
    private EntityManager em;

    private static final Long ORG_ID = 9401L;
    private static final Long DWELLING_ID = 5401L;
    private static final Long REGISTRY_ID = 6401L;
    private static final Long SUBJECT_USER_ID = 7401L;
    private static final Long COMMITTEE_ID = 8401L;
    private static final Long VISITOR_USER_ID = 7402L;

    private MonitoringCommitteeVisit persistVisit(String contactResult, String memo, LocalDateTime visitedAt) {
        MonitoringCommitteeVisit e = MonitoringCommitteeVisit.builder()
                .organizationId(ORG_ID)
                .dwellingUnitId(DWELLING_ID)
                .residentRegistryId(REGISTRY_ID)
                .subjectUserId(SUBJECT_USER_ID)
                .committeeId(COMMITTEE_ID)
                .visitorUserId(VISITOR_USER_ID)
                .visitedAt(visitedAt)
                .contactResult(contactResult)
                .considerationMemoEncrypted(memo)
                .build();
        em.persist(e);
        em.flush();
        em.clear();
        return e;
    }

    @Test
    @DisplayName("committee_id 単位で訪問記録を直近順に取得できる")
    void findByCommitteeId_直近順() {
        persistVisit("MET", "1 回目訪問", LocalDateTime.now().minusDays(3));
        persistVisit("NO_RESPONSE", "2 回目訪問", LocalDateTime.now().minusDays(1));

        List<MonitoringCommitteeVisit> list =
                repository.findByCommitteeIdAndDeletedAtIsNullOrderByVisitedAtDesc(COMMITTEE_ID);

        assertThat(list).hasSize(2);
        // 直近順なので「2 回目訪問」が先頭
        assertThat(list.get(0).getContactResult()).isEqualTo("NO_RESPONSE");
    }

    @Test
    @DisplayName("considerationMemoEncrypted が透過暗号化・復号される（平文ラウンドトリップ）")
    void 配慮メモ暗号化ラウンドトリップ() {
        String plaintext = "高齢で耳が遠い・郵便受け前で大きめの声で声かけ要";
        persistVisit("MET", plaintext, LocalDateTime.now());

        List<MonitoringCommitteeVisit> list =
                repository.findByCommitteeIdAndDeletedAtIsNullOrderByVisitedAtDesc(COMMITTEE_ID);

        assertThat(list).hasSize(1);
        // Converter により復号された平文が取得できる
        assertThat(list.get(0).getConsiderationMemoEncrypted()).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("subject_user_id / resident_registry_id でも履歴を取得できる")
    void findBySubjectUserId_findByResidentRegistryId() {
        persistVisit("MET", "メモ", LocalDateTime.now());

        List<MonitoringCommitteeVisit> bySubject =
                repository.findBySubjectUserIdAndDeletedAtIsNullOrderByVisitedAtDesc(SUBJECT_USER_ID);
        assertThat(bySubject).hasSize(1);

        List<MonitoringCommitteeVisit> byRegistry =
                repository.findByResidentRegistryIdAndDeletedAtIsNullOrderByVisitedAtDesc(REGISTRY_ID);
        assertThat(byRegistry).hasSize(1);
    }
}
