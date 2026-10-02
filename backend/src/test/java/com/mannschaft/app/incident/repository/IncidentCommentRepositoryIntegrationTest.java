package com.mannschaft.app.incident.repository;

import com.mannschaft.app.incident.entity.IncidentCommentEntity;
import com.mannschaft.app.incident.entity.IncidentEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("IncidentCommentRepository の可視性 SQL")
class IncidentCommentRepositoryIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private IncidentCommentRepository commentRepository;

    @PersistenceContext
    private EntityManager em;

    @Test
    @DisplayName("内部・削除済みを除外し、createdAt 同値でも ID 昇順で返す")
    void findVisibleByIncidentId_excludesInternalAndDeletedInSql() {
        IncidentEntity incident = incidentRepository.save(IncidentEntity.builder()
                .scopeType("TEAM").scopeId(1L).title("テスト").status("REPORTED")
                .priority("MEDIUM").isSlaBreached(false).reportedBy(1L).build());
        IncidentCommentEntity first = commentRepository.save(comment(incident.getId(), "first", false));
        IncidentCommentEntity internal = commentRepository.save(comment(incident.getId(), "internal", true));
        IncidentCommentEntity deleted = commentRepository.save(comment(incident.getId(), "deleted", false));
        deleted.softDelete();
        commentRepository.save(deleted);
        IncidentCommentEntity second = commentRepository.save(comment(incident.getId(), "second", false));
        em.flush();
        em.createNativeQuery("UPDATE incident_comments SET created_at = '2026-01-01 00:00:00' WHERE incident_id = :incidentId")
                .setParameter("incidentId", incident.getId())
                .executeUpdate();
        em.clear();

        List<IncidentCommentEntity> memberResult =
                commentRepository.findVisibleByIncidentIdOrderByCreatedAtAsc(incident.getId(), false);
        List<IncidentCommentEntity> adminResult =
                commentRepository.findVisibleByIncidentIdOrderByCreatedAtAsc(incident.getId(), true);

        assertThat(memberResult).extracting(IncidentCommentEntity::getId)
                .containsExactly(first.getId(), second.getId());
        assertThat(adminResult).extracting(IncidentCommentEntity::getId)
                .containsExactly(first.getId(), internal.getId(), second.getId());
    }

    private IncidentCommentEntity comment(Long incidentId, String body, boolean internal) {
        return IncidentCommentEntity.builder()
                .incidentId(incidentId).userId(1L).body(body).isInternal(internal).build();
    }
}
