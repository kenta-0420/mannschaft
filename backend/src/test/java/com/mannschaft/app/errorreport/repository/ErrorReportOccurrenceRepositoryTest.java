package com.mannschaft.app.errorreport.repository;

import com.mannschaft.app.errorreport.ErrorReportSeverity;
import com.mannschaft.app.errorreport.ErrorReportStatus;
import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
import com.mannschaft.app.errorreport.entity.ErrorReportOccurrenceEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F12.5 Phase 2 — {@link ErrorReportOccurrenceRepository} の結合テスト。
 * save / findByErrorReportIdOrderByOccurredAtDesc / findByUserIdOrderByOccurredAtDesc /
 * deleteByOccurredAtBefore / anonymizeByUserId のスモーク確認を行う。
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("ErrorReportOccurrenceRepository 結合テスト")
class ErrorReportOccurrenceRepositoryTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private ErrorReportRepository errorReportRepository;

    @Autowired
    private ErrorReportOccurrenceRepository repository;

    @PersistenceContext
    private EntityManager em;

    private ErrorReportEntity persistParent(String hashSuffix) {
        LocalDateTime now = LocalDateTime.now();
        ErrorReportEntity report = ErrorReportEntity.builder()
                .errorMessage("test error " + hashSuffix)
                .pageUrl("/test")
                .occurredAt(now)
                .status(ErrorReportStatus.NEW)
                .severity(ErrorReportSeverity.LOW)
                .errorHash("hash-" + hashSuffix)
                .firstOccurredAt(now)
                .lastOccurredAt(now)
                .build();
        return errorReportRepository.saveAndFlush(report);
    }

    @Test
    @DisplayName("save → findByErrorReportIdOrderByOccurredAtDesc で発生履歴を新しい順に取得できる")
    void shouldFindByErrorReportIdOrderByOccurredAtDesc() {
        ErrorReportEntity parent = persistParent("a");
        LocalDateTime base = LocalDateTime.now();

        ErrorReportOccurrenceEntity older = ErrorReportOccurrenceEntity.builder()
                .errorReportId(parent.getId())
                .pageUrl("/foo")
                .occurredAt(base.minusMinutes(10))
                .build();
        ErrorReportOccurrenceEntity newer = ErrorReportOccurrenceEntity.builder()
                .errorReportId(parent.getId())
                .pageUrl("/bar")
                .occurredAt(base)
                .build();
        repository.save(older);
        repository.save(newer);
        em.flush();
        em.clear();

        Page<ErrorReportOccurrenceEntity> page = repository
                .findByErrorReportIdOrderByOccurredAtDesc(parent.getId(), PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent().get(0).getPageUrl()).isEqualTo("/bar");
        assertThat(page.getContent().get(1).getPageUrl()).isEqualTo("/foo");
    }

    @Test
    @DisplayName("findByUserIdOrderByOccurredAtDesc — userId で絞り込める")
    void shouldFindByUserId() {
        ErrorReportEntity parent = persistParent("b");
        LocalDateTime now = LocalDateTime.now();

        repository.save(ErrorReportOccurrenceEntity.builder()
                .errorReportId(parent.getId()).userId(100L).pageUrl("/x").occurredAt(now).build());
        repository.save(ErrorReportOccurrenceEntity.builder()
                .errorReportId(parent.getId()).userId(200L).pageUrl("/y").occurredAt(now).build());
        em.flush();
        em.clear();

        List<ErrorReportOccurrenceEntity> result =
                repository.findByUserIdOrderByOccurredAtDesc(100L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPageUrl()).isEqualTo("/x");
    }

    @Test
    @DisplayName("deleteByOccurredAtBefore — cutoff より古いレコードのみ削除される")
    void shouldDeleteByOccurredAtBefore() {
        ErrorReportEntity parent = persistParent("c");
        LocalDateTime base = LocalDateTime.now();

        repository.save(ErrorReportOccurrenceEntity.builder()
                .errorReportId(parent.getId()).pageUrl("/old")
                .occurredAt(base.minusDays(31)).build());
        repository.save(ErrorReportOccurrenceEntity.builder()
                .errorReportId(parent.getId()).pageUrl("/new")
                .occurredAt(base.minusDays(1)).build());
        em.flush();
        em.clear();

        int deleted = repository.deleteByOccurredAtBefore(base.minusDays(30));
        em.flush();
        em.clear();

        assertThat(deleted).isEqualTo(1);
        Page<ErrorReportOccurrenceEntity> remaining = repository
                .findByErrorReportIdOrderByOccurredAtDesc(parent.getId(), PageRequest.of(0, 10));
        assertThat(remaining.getTotalElements()).isEqualTo(1);
        assertThat(remaining.getContent().get(0).getPageUrl()).isEqualTo("/new");
    }

    @Test
    @DisplayName("anonymizeByUserId — 指定 userId の ip_address / user_agent を NULL 化する")
    void shouldAnonymizeByUserId() {
        ErrorReportEntity parent = persistParent("d");
        LocalDateTime now = LocalDateTime.now();

        ErrorReportOccurrenceEntity saved = repository.save(ErrorReportOccurrenceEntity.builder()
                .errorReportId(parent.getId())
                .userId(300L)
                .pageUrl("/anon")
                .ipAddress("192.0.2.1")
                .userAgent("Mozilla/5.0")
                .occurredAt(now)
                .build());
        em.flush();
        em.clear();

        int updated = repository.anonymizeByUserId(300L);
        em.flush();
        em.clear();

        assertThat(updated).isEqualTo(1);
        ErrorReportOccurrenceEntity reloaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getIpAddress()).isNull();
        assertThat(reloaded.getUserAgent()).isNull();
    }
}
