package com.mannschaft.app.errorreport.repository;

import com.mannschaft.app.errorreport.ErrorReportSeverity;
import com.mannschaft.app.errorreport.ErrorReportStatus;
import com.mannschaft.app.errorreport.entity.ErrorReportAiAnalysisEntity;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F12.5 Phase 2 — {@link ErrorReportAiAnalysisRepository} の結合テスト。
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("ErrorReportAiAnalysisRepository 結合テスト")
class ErrorReportAiAnalysisRepositoryTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private ErrorReportRepository errorReportRepository;

    @Autowired
    private ErrorReportAiAnalysisRepository repository;

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
                .errorHash("ai-hash-" + suffix)
                .firstOccurredAt(now)
                .lastOccurredAt(now)
                .build();
        return errorReportRepository.saveAndFlush(report);
    }

    @Test
    @DisplayName("save → findByErrorReportIdOrderByCreatedAtDesc で履歴を新しい順に取得できる")
    void shouldFindByErrorReportIdOrderByCreatedAtDesc() {
        ErrorReportEntity parent = persistParent("a");

        ErrorReportAiAnalysisEntity first = ErrorReportAiAnalysisEntity.builder()
                .errorReportId(parent.getId())
                .modelName("claude-haiku-4-5")
                .estimatedCause("first cause")
                .status("SUCCESS")
                .build();
        repository.saveAndFlush(first);

        // 2件目を後で永続化（createdAt が後になる）
        ErrorReportAiAnalysisEntity second = ErrorReportAiAnalysisEntity.builder()
                .errorReportId(parent.getId())
                .modelName("claude-haiku-4-5")
                .estimatedCause("second cause")
                .status("SUCCESS")
                .createdAt(LocalDateTime.now().plusSeconds(1))
                .build();
        repository.saveAndFlush(second);
        em.clear();

        Page<ErrorReportAiAnalysisEntity> page = repository
                .findByErrorReportIdOrderByCreatedAtDesc(parent.getId(), PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent().get(0).getEstimatedCause()).isEqualTo("second cause");
    }

    @Test
    @DisplayName("findFirstByErrorReportIdAndStatusOrderByCreatedAtDesc — 最新 SUCCESS を取得できる")
    void shouldFindLatestSuccess() {
        ErrorReportEntity parent = persistParent("b");

        repository.saveAndFlush(ErrorReportAiAnalysisEntity.builder()
                .errorReportId(parent.getId()).modelName("m").status("FAILED")
                .errorMessage("fail").build());
        ErrorReportAiAnalysisEntity success = repository.saveAndFlush(ErrorReportAiAnalysisEntity.builder()
                .errorReportId(parent.getId()).modelName("m").status("SUCCESS")
                .estimatedCause("ok")
                .createdAt(LocalDateTime.now().plusSeconds(1))
                .build());
        em.clear();

        Optional<ErrorReportAiAnalysisEntity> latest = repository
                .findFirstByErrorReportIdAndStatusOrderByCreatedAtDesc(parent.getId(), "SUCCESS");

        assertThat(latest).isPresent();
        assertThat(latest.get().getId()).isEqualTo(success.getId());
        assertThat(latest.get().getEstimatedCause()).isEqualTo("ok");
    }

    @Test
    @DisplayName("updateRawResponseToNullByCreatedAtBefore — cutoff より古い raw_response を NULL 化する")
    void shouldNullifyOldRawResponses() {
        ErrorReportEntity parent = persistParent("c");
        LocalDateTime base = LocalDateTime.now();

        ErrorReportAiAnalysisEntity old = repository.saveAndFlush(
                ErrorReportAiAnalysisEntity.builder()
                        .errorReportId(parent.getId()).modelName("m").status("SUCCESS")
                        .rawResponse("old raw")
                        .createdAt(base.minusDays(31))
                        .build());
        ErrorReportAiAnalysisEntity recent = repository.saveAndFlush(
                ErrorReportAiAnalysisEntity.builder()
                        .errorReportId(parent.getId()).modelName("m").status("SUCCESS")
                        .rawResponse("new raw")
                        .createdAt(base.minusDays(1))
                        .build());
        em.clear();

        int updated = repository.updateRawResponseToNullByCreatedAtBefore(base.minusDays(30));
        em.flush();
        em.clear();

        assertThat(updated).isEqualTo(1);
        assertThat(repository.findById(old.getId()).orElseThrow().getRawResponse()).isNull();
        assertThat(repository.findById(recent.getId()).orElseThrow().getRawResponse())
                .isEqualTo("new raw");
    }
}
