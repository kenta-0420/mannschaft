package com.mannschaft.app.ranch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.ranch.repository.RanchDinosaurRepository;
import com.mannschaft.app.ranch.repository.RanchOwnerRepository;
import com.mannschaft.app.ranch.service.RanchPersonalDataExportService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 本人exportに牧場状態を含め、他人行と内部hash/key証跡を出さない。 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "mannschaft.ranch.development-fixtures=true")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RanchPersonalDataExportServiceIT extends AbstractMySqlIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private RanchPersonalDataExportService exports;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RanchOwnerRepository owners;
    @Autowired private RanchDinosaurRepository dinosaurs;
    private Long me;
    private Long other;

    @BeforeEach
    void createSyntheticUsers() {
        me = users.saveAndFlush(RanchTestFixture.user()).getId();
        other = users.saveAndFlush(RanchTestFixture.user()).getId();
    }

    @Test
    void exportsOnlySelfAndNoTechnicalProofColumns() throws Exception {
        for (Long userId : new Long[] {me, other}) {
            mvc.perform(post("/api/v1/me/ranch").with(user(userId.toString()))
                            .header("Idempotency-Key", UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isCreated());
            RanchGdprFixture.populate(jdbc, userId,
                    owners.findByUserId(userId).orElseThrow().getId(),
                    dinosaurs.findByUserId(userId).orElseThrow().getId());
        }
        var body = json.readTree(exports.exportUser(me));
        assertThat(body.path("owners").size()).isEqualTo(1);
        assertThat(body.path("dinosaurs").size()).isEqualTo(1);
        assertThat(body.path("roomPlacements").size()).isEqualTo(3);
        assertThat(body.path("commands").size()).isEqualTo(1);
        for (String category : new String[] {"owners", "dinosaurs", "participationPeriods",
                "careWeekBudgets", "affinityUnits", "pointLedger", "commands",
                "roomPlacements", "collectibleInventory", "rewardWeekBudgets",
                "rewardDecisions"}) {
            assertThat(body.path(category).size()).as(category).isPositive();
            body.path(category).forEach(row ->
                    assertThat(row.path("user_id").asLong()).as(category).isEqualTo(me));
        }
        assertThat(body.path("commands").get(0).has("body_hash")).isFalse();
        assertThat(body.path("commands").get(0).has("idempotency_key")).isFalse();
        assertThat(body.toString()).doesNotContain("canonical_key_hash", "acquisition_key",
                "assignment_input_hash");
        assertThat(body.path("owners").get(0).path("user_id").asLong()).isNotEqualTo(other);
    }
}
