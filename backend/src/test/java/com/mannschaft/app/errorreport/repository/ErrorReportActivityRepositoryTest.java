package com.mannschaft.app.errorreport.repository;

import com.mannschaft.app.errorreport.ErrorReportActivityType;
import com.mannschaft.app.errorreport.ErrorReportSeverity;
import com.mannschaft.app.errorreport.ErrorReportStatus;
import com.mannschaft.app.errorreport.entity.ErrorReportActivityEntity;
import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F12.5 Phase 2 — {@link ErrorReportActivityRepository} の結合テスト。
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("ErrorReportActivityRepository 結合テスト")
class ErrorReportActivityRepositoryTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private ErrorReportRepository errorReportRepository;

    @Autowired
    private ErrorReportActivityRepository repository;

    @PersistenceContext
    private EntityManager em;

    private ErrorReportEntity persistParent(String suffix) {
        LocalDateTime now = LocalDateTime.now();
        ErrorReportEntity report = ErrorReportEntity.builder()
                .errorMessage("err " + suffix)
                .pageUrl("/x")
                .occurredAt(now)
                .status(ErrorReportStatus.NEW)
                .severity(ErrorReportSeverity.LOW)
                .errorHash("act-hash-" + suffix)
                .firstOccurredAt(now)
                .lastOccurredAt(now)
                .build();
        return errorReportRepository.saveAndFlush(report);
    }

    @Test
    @DisplayName("save → findByErrorReportIdOrderByCreatedAtDesc で履歴を新しい順に取得できる")
    void shouldFindByErrorReportIdOrderByCreatedAtDesc() {
        ErrorReportEntity parent = persistParent("a");

        repository.saveAndFlush(ErrorReportActivityEntity.builder()
                .errorReportId(parent.getId())
                .actorId(1L)
                .activityType(ErrorReportActivityType.STATUS_CHANGED)
                .content("NEW → INVESTIGATING")
                .build());
        repository.saveAndFlush(ErrorReportActivityEntity.builder()
                .errorReportId(parent.getId())
                .actorId(1L)
                .activityType(ErrorReportActivityType.COMMENT_ADDED)
                .content("comment text")
                .createdAt(LocalDateTime.now().plusSeconds(1))
                .build());
        em.clear();

        Page<ErrorReportActivityEntity> page = repository
                .findByErrorReportIdOrderByCreatedAtDesc(parent.getId(), PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent().get(0).getActivityType())
                .isEqualTo(ErrorReportActivityType.COMMENT_ADDED);
        assertThat(page.getContent().get(1).getActivityType())
                .isEqualTo(ErrorReportActivityType.STATUS_CHANGED);
    }

    @Test
    @DisplayName("actorId が NULL でも保存できる（システム自動 / 退会管理者用）")
    void shouldAllowNullActorId() {
        ErrorReportEntity parent = persistParent("b");

        ErrorReportActivityEntity saved = repository.saveAndFlush(
                ErrorReportActivityEntity.builder()
                        .errorReportId(parent.getId())
                        .actorId(null)
                        .activityType(ErrorReportActivityType.AI_ANALYZED)
                        .content("system action")
                        .metadataJson("{\"system\":true}")
                        .build());
        em.clear();

        ErrorReportActivityEntity reloaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getActorId()).isNull();
        assertThat(reloaded.getMetadataJson()).contains("system");
    }
}
