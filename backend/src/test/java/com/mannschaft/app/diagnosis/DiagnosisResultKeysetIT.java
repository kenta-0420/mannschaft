package com.mannschaft.app.diagnosis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.diagnosis.dto.DiagnosisNumberSummary;
import com.mannschaft.app.diagnosis.dto.DiagnosisResultSummary;
import com.mannschaft.app.diagnosis.entity.DiagnosisResultEntity;
import com.mannschaft.app.diagnosis.repository.DiagnosisResultRepository;
import com.mannschaft.app.diagnosis.service.DiagnosisResultCursorCodec;
import com.mannschaft.app.diagnosis.service.DiagnosisResultListReader;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** AC68: synthetic本人結果だけを保存し、実MySQL keyset/HTTP境界/一定SQL数を測る。 */
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class DiagnosisResultKeysetIT extends AbstractMySqlIntegrationTest {
    private static final Instant AT = Instant.parse("2026-10-07T02:00:00.123456Z");
    private static final String BASE = "/api/v1/me/diagnoses/results";
    @Autowired private UserRepository users;
    @Autowired private DiagnosisResultRepository results;
    @Autowired private DiagnosisResultListReader reader;
    @Autowired private DiagnosisResultCursorCodec cursors;
    @Autowired private ObjectMapper mapper;
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private EntityManagerFactory entityManagers;
    private final List<Long> syntheticUsers = new ArrayList<>();

    private Long user() {
        Long id = users.saveAndFlush(UserEntity.builder().email(UUID.randomUUID() + "@keyset.invalid")
                .lastName("合成").firstName("一覧").displayName("synthetic keyset fixture")
                .isSearchable(false).status(UserEntity.UserStatus.ACTIVE)
                .locale("ja").timezone("UTC").build()).getId();
        syntheticUsers.add(id);
        return id;
    }

    private List<UUID> seed(Long userId, int count) throws Exception {
        // Same positive prefix makes the binary MySQL ID order explicit without signed UUID ambiguity.
        long prefix = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        List<UUID> ids = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            UUID id = new UUID(prefix, index + 1L);
            DiagnosisMethod method = index % 2 == 0 ? DiagnosisMethod.DIAGNOSIS : DiagnosisMethod.BIRTH_STYLE;
            var summary = new DiagnosisResultSummary(id, method, AT, "keyset-fixture-v1",
                    "fixture-questionnaire", "fixture-scoring", "fixture-normalization", "fixture-rule",
                    "fixture-mapping", method == DiagnosisMethod.DIAGNOSIS ? "000000" : null,
                    Map.of(), method == DiagnosisMethod.BIRTH_STYLE ? new DiagnosisNumberSummary(6, 6, 24, 33) : null,
                    Map.of("ja", "synthetic saved description"), Map.of());
            results.save(DiagnosisResultEntity.builder().id(id).userId(userId).method(method)
                    .sourceProfileRevision(method == DiagnosisMethod.BIRTH_STYLE ? 0L : null)
                    .summarySnapshot(mapper.writeValueAsString(summary))
                    .completedAt(AT).createdAt(AT).updatedAt(AT).build());
            ids.add(id);
        }
        results.flush();
        return ids.stream().sorted(Comparator.reverseOrder()).toList();
    }

    private void auth(Long id) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(id.toString(), null, List.of()));
    }

    @AfterEach
    void removeOnlySyntheticFixtureRows() {
        SecurityContextHolder.clearContext();
        for (Long id : syntheticUsers) {
            new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                results.deleteByUserId(id);
                results.flush();
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM diagnosis_results WHERE user_id = ?",
                        Integer.class, id)).isZero();
                users.deleteById(id);
                users.flush();
            });
        }
        syntheticUsers.clear();
    }

    @Test
    void zeroOneHundredAndHundredOneRowsKeepExactSameTimestampKeysetForLimitsOneAndHundred() throws Exception {
        Long other = user(); seed(other, 3);
        for (int count : new int[]{0, 1, 100, 101}) {
            Long owner = user(); List<UUID> expected = seed(owner, count);
            for (int limit : new int[]{1, 100}) {
                List<UUID> seen = new ArrayList<>();
                String cursor = null;
                // One empty read at cardinality0; finite guard detects a cursor that never terminates.
                int pages = Math.max(1, (count + limit - 1) / limit);
                for (int pageIndex = 0; pageIndex < pages; pageIndex++) {
                    var page = reader.list(owner, null, cursor, limit);
                    assertThat(page.getData()).hasSize(Math.min(limit, count - seen.size()));
                    for (var row : page.getData()) {
                        seen.add(row.id());
                        assertThat(row.completedAt()).isEqualTo(AT);
                        assertThat(results.findById(row.id()).orElseThrow().getCompletedAt()).isEqualTo(AT);
                        var json = mapper.valueToTree(row);
                        assertThat(json.path("completedAt").asText()).isEqualTo(AT.toString());
                        for (String field : List.of("birthDate", "lastName", "firstName", "lastNameKana",
                                "firstNameKana", "answers", "source", "sourceProfileRevision", "confirmationRef")) {
                            assertThat(json.has(field)).as("private field %s", field).isFalse();
                        }
                    }
                    cursor = page.getMeta().getNextCursor();
                    boolean more = seen.size() < count;
                    assertThat(page.getMeta().isHasNext()).isEqualTo(more);
                    if (more) {
                        assertThat(cursor).isNotBlank();
                        var position = cursors.decode(owner, null, cursor);
                        assertThat(position.completedAt()).isEqualTo(AT);
                        assertThat(position.id()).isEqualTo(seen.getLast());
                    } else {
                        assertThat(cursor).isNull();
                    }
                }
                assertThat(seen).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
            }
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM diagnosis_results WHERE user_id = ?",
                    Integer.class, owner)).isEqualTo(count);
            auth(owner);
            for (int limit : new int[]{0, 1, 100, 101}) {
                mvc.perform(get(BASE).param("limit", Integer.toString(limit)))
                        .andExpect(limit == 0 || limit == 101 ? status().isBadRequest() : status().isOk());
            }
        }
        assertThat(reader.list(other, null, null, 100).getData()).hasSize(3);
    }

    @Test
    void methodAndOwnerBoundCursorsRejectForeignFilterTamperAndOldFormatWithHttp400() throws Exception {
        Long owner = user(); List<UUID> expected = seed(owner, 101);
        Long other = user(); seed(other, 2);
        String all = reader.list(owner, null, null, 1).getMeta().getNextCursor();
        var quiz = reader.list(owner, DiagnosisMethod.DIAGNOSIS, null, 100);
        var birth = reader.list(owner, DiagnosisMethod.BIRTH_STYLE, null, 100);
        assertThat(quiz.getData()).hasSize(51).allMatch(row -> row.method() == DiagnosisMethod.DIAGNOSIS);
        assertThat(birth.getData()).hasSize(50).allMatch(row -> row.method() == DiagnosisMethod.BIRTH_STYLE);
        List<UUID> filtered = new ArrayList<>();
        String cursor = null;
        for (int index = 0; index < 51; index++) {
            var page = reader.list(owner, DiagnosisMethod.DIAGNOSIS, cursor, 1);
            assertThat(page.getData()).hasSize(1);
            assertThat(page.getData().getFirst().method()).isEqualTo(DiagnosisMethod.DIAGNOSIS);
            filtered.add(page.getData().getFirst().id());
            cursor = page.getMeta().getNextCursor();
        }
        assertThat(cursor).isNull();
        assertThat(filtered).containsExactlyElementsOf(quiz.getData().stream().map(DiagnosisResultSummary::id).toList())
                .doesNotHaveDuplicates();
        auth(other);
        mvc.perform(get(BASE).param("cursor", all)).andExpect(status().isBadRequest());
        auth(owner);
        mvc.perform(get(BASE).param("cursor", all).param("method", "DIAGNOSIS"))
                .andExpect(status().isBadRequest());
        String quizCursor = reader.list(owner, DiagnosisMethod.DIAGNOSIS, null, 1).getMeta().getNextCursor();
        mvc.perform(get(BASE).param("cursor", quizCursor)).andExpect(status().isBadRequest());
        mvc.perform(get(BASE).param("cursor", quizCursor).param("method", "BIRTH_STYLE"))
                .andExpect(status().isBadRequest());
        String tampered = (all.charAt(0) == 'A' ? 'B' : 'A') + all.substring(1);
        String legacy = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                (AT + "|" + expected.getFirst()).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        for (String invalid : List.of(tampered, legacy, "A".repeat(129))) {
            mvc.perform(get(BASE).param("cursor", invalid)).andExpect(status().isBadRequest());
        }
        mvc.perform(get(BASE).param("limit", "100")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM diagnosis_results WHERE user_id = ?",
                Integer.class, owner)).isEqualTo(101);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM diagnosis_results WHERE user_id = ?",
                Integer.class, other)).isEqualTo(2);
    }

    @Test
    void oneBoundedRepositoryQueryPerReadDoesNotGrowAcrossZeroOneHundredAndHundredOneRows() throws Exception {
        var statistics = entityManagers.unwrap(SessionFactory.class).getStatistics();
        boolean originallyEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            for (int count : new int[]{0, 1, 100, 101}) {
                Long owner = user(); seed(owner, count);
                for (int limit : new int[]{1, 100}) {
                    statistics.clear();
                    var page = reader.list(owner, null, null, limit);
                    assertThat(statistics.getQueryExecutionCount()).as("HQL count=%s limit=%s", count, limit).isEqualTo(1);
                    assertThat(statistics.getPrepareStatementCount()).as("SQL count=%s limit=%s", count, limit).isEqualTo(1);
                    assertThat(statistics.getEntityLoadCount()).as("limit+1 fetch bound").isEqualTo(Math.min(count, limit + 1));
                    assertThat(page.getData()).hasSize(Math.min(count, limit));
                    if (page.getMeta().getNextCursor() != null) {
                        statistics.clear();
                        reader.list(owner, null, page.getMeta().getNextCursor(), limit);
                        assertThat(statistics.getQueryExecutionCount()).isEqualTo(1);
                        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
                    }
                }
            }
        } finally {
            statistics.setStatisticsEnabled(originallyEnabled);
        }
    }
}
