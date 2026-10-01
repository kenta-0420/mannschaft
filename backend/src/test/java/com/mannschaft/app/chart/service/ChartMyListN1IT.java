package com.mannschaft.app.chart.service;

import com.mannschaft.app.chart.dto.ChartRecordSummaryResponse;
import com.mannschaft.app.chart.entity.ChartPhotoEntity;
import com.mannschaft.app.chart.entity.ChartRecordEntity;
import com.mannschaft.app.chart.repository.ChartPhotoRepository;
import com.mannschaft.app.chart.repository.ChartRecordRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * マイカルテ一覧（ChartRecordService#listMyCharts）の N+1 解消を固定する試練（AC-22）。
 *
 * <p>写真件数の取得 SQL 数が、ページ内のカルテ件数（1件／20件）に依存しないことを
 * Hibernate {@link Statistics#getPrepareStatementCount()} で検証する。
 * ページサイズは両者 50 とし、件数クエリ（count）の有無が差を生まないようにする。</p>
 */
@Transactional
@DisplayName("マイカルテ一覧 N+1 解消（AC-22）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ChartMyListN1IT extends AbstractMySqlIntegrationTest {

    private static final Long TEAM_ID = 910001L;

    @Autowired
    private ChartRecordService service;
    @Autowired
    private ChartRecordRepository recordRepository;
    @Autowired
    private ChartPhotoRepository photoRepository;
    @PersistenceContext
    private EntityManager em;

    private void seed(Long userId, int records) {
        for (int i = 0; i < records; i++) {
            ChartRecordEntity r = recordRepository.save(ChartRecordEntity.builder()
                    .teamId(TEAM_ID).customerUserId(userId)
                    .visitDate(LocalDate.of(2026, 1, 1).plusDays(i))
                    .isSharedToCustomer(true).build());
            // 偶数番目は写真2枚、奇数番目は写真0枚
            if (i % 2 == 0) {
                for (int p = 0; p < 2; p++) {
                    photoRepository.save(ChartPhotoEntity.builder()
                            .chartRecordId(r.getId()).photoType("BEFORE")
                            .s3Key("k/" + userId + "/" + i + "/" + p)
                            .originalFilename("f.jpg").fileSizeBytes(1).contentType("image/jpeg")
                            .sortOrder(p).build());
                }
            }
        }
        em.flush();
        em.clear();
    }

    private Statistics stats() {
        Statistics s = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        s.setStatisticsEnabled(true);
        s.clear();
        return s;
    }

    @Test
    @DisplayName("AC-22: 1件と20件で SQL 回数が同じ／写真0枚は件数0")
    void sqlCountIndependentOfPageSize() {
        seed(920001L, 1);
        seed(920002L, 20);

        Statistics s = stats();
        Page<ChartRecordSummaryResponse> small = service.listMyCharts(920001L, TEAM_ID, PageRequest.of(0, 50));
        long smallCount = s.getPrepareStatementCount();
        assertThat(small.getContent()).hasSize(1);

        em.clear();
        s.clear();
        Page<ChartRecordSummaryResponse> large = service.listMyCharts(920002L, TEAM_ID, PageRequest.of(0, 50));
        long largeCount = s.getPrepareStatementCount();
        assertThat(large.getContent()).hasSize(20);

        System.out.println("AC22 SQL small=" + smallCount + " large=" + largeCount);
        assertThat(largeCount).as("20件でも1件と同じ SQL 回数").isEqualTo(smallCount);

        List<Integer> photoCounts = large.getContent().stream()
                .map(ChartRecordSummaryResponse::getPhotoCount).toList();
        // visitDate 降順: i=19(奇,0) i=18(偶,2) ...
        assertThat(photoCounts.get(0)).isEqualTo(0);
        assertThat(photoCounts.get(1)).isEqualTo(2);
        assertThat(small.getContent().get(0).getPhotoCount()).isEqualTo(2);
    }
}
