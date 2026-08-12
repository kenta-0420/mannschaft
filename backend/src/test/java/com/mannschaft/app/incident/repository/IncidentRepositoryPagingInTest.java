package com.mannschaft.app.incident.repository;

import com.mannschaft.app.incident.entity.IncidentEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-028 Phase D — {@link IncidentRepository#findByScopeTypeAndScopeIdAndStatus} 結合テスト。
 *
 * <p>受け入れ条件 AC-D3（歯抜けゼロ）・AC-D4（総件数正確）を実 MySQL に対して検証する。
 * 旧実装（スコープ配下の全件ロード → メモリで status フィルタ → 手動ページング）は、
 * status で除外される行が間に混ざると要求件数を割り込む「歯抜け」があったため、
 * <strong>対象 status と対象外 status を交互に挿入した</strong>フィクスチャで検証する
 * （全件が対象 status のみのフィクスチャでは歯抜けを検出できない —
 * {@code ActivityResultRepositoryVisibilityInTest} と同じ教訓）。</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("IncidentRepository.findByScopeTypeAndScopeIdAndStatus — 結合テスト")
class IncidentRepositoryPagingInTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private IncidentRepository incidentRepository;

    @PersistenceContext
    private EntityManager em;

    private static final String SCOPE_TYPE = "TEAM";
    private static final Long SCOPE_ID = 100L;
    private static final Long REPORTER_ID = 999L;

    @BeforeEach
    void setUp() {
        // AC-D3 の赤を検出できるフィクスチャ: 対象status(REPORTED) 20件の間に
        // 対象外status(RESOLVED)を10件、別スコープの REPORTED を5件挟み込む。
        for (int i = 0; i < 20; i++) {
            persistIncident(SCOPE_ID, "REPORTED");
            if (i < 10) {
                persistIncident(SCOPE_ID, "RESOLVED");
            }
            if (i < 5) {
                persistIncident(999L, "REPORTED");
            }
        }
        em.flush();
        em.clear();
    }

    /**
     * AC-D3: 対象 status の行が20件存在する以上、size=20 で必ず20件返る。
     * 旧実装は対象外 status の行が間に混ざるとここで20件を割り込んでいた（歯抜け）。
     */
    @Test
    @DisplayName("AC-D3: 対象status20件+対象外status混在でも size=20 で必ず20件返る（歯抜けゼロ）")
    void 歯抜けゼロ() {
        Page<IncidentEntity> page = incidentRepository.findByScopeTypeAndScopeIdAndStatus(
                SCOPE_TYPE, SCOPE_ID, "REPORTED", PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(20);
        assertThat(page.getContent()).allMatch(i -> i.getStatus().equals("REPORTED"));
        assertThat(page.getContent()).allMatch(i -> i.getScopeId().equals(SCOPE_ID));
    }

    /**
     * AC-D4: 総件数は実際の該当件数（scope一致 AND status=REPORTED = 20件）と一致し、
     * スコープ配下の全行数（30件）や近似値にはならない。
     */
    @Test
    @DisplayName("AC-D4: 総件数は実該当件数と一致する（近似ではない）")
    void 総件数は実該当件数と一致() {
        Page<IncidentEntity> page = incidentRepository.findByScopeTypeAndScopeIdAndStatus(
                SCOPE_TYPE, SCOPE_ID, "REPORTED", PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(20L);
    }

    /**
     * AC-D6（拒否側）: status絞り込みなし（null）でも、別スコープの行は一切含まれない。
     */
    @Test
    @DisplayName("AC-D6（拒否側）: 別スコープの行は漏れない")
    void 別スコープは漏れない() {
        Page<IncidentEntity> page = incidentRepository.findByScopeTypeAndScopeIdAndStatus(
                SCOPE_TYPE, SCOPE_ID, null, PageRequest.of(0, 100));

        assertThat(page.getContent()).hasSize(30); // 20 REPORTED + 10 RESOLVED（同一scope分）
        assertThat(page.getContent()).allMatch(i -> i.getScopeId().equals(SCOPE_ID));
    }

    /**
     * AC-D6（許可側）: status=null（絞り込みなし）を指定した場合、対象 status 以外
     * （RESOLVED）も同一スコープであれば正しく含まれる。
     */
    @Test
    @DisplayName("AC-D6（許可側）: statusをnullにすると同一スコープの全statusが含まれる")
    void statusなしなら全status含まれる() {
        Page<IncidentEntity> page = incidentRepository.findByScopeTypeAndScopeIdAndStatus(
                SCOPE_TYPE, SCOPE_ID, null, PageRequest.of(0, 100));

        assertThat(page.getContent()).anyMatch(i -> i.getStatus().equals("RESOLVED"));
        assertThat(page.getTotalElements()).isEqualTo(30L);
    }

    private void persistIncident(Long scopeId, String status) {
        IncidentEntity entity = IncidentEntity.builder()
                .scopeType(SCOPE_TYPE)
                .scopeId(scopeId)
                .title("インシデント")
                .status(status)
                .priority("MEDIUM")
                .isSlaBreached(false)
                .reportedBy(REPORTER_ID)
                .build();
        em.persist(entity);
    }
}
