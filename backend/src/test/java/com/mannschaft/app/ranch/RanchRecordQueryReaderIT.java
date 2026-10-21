package com.mannschaft.app.ranch;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.ranch.entity.RanchPointLedgerEntity;
import com.mannschaft.app.ranch.repository.RanchPointLedgerRepository;
import com.mannschaft.app.ranch.service.RanchEnrollmentWriter;
import com.mannschaft.app.ranch.service.RanchRecordQueryReader;
import com.mannschaft.app.ranch.service.RanchStateAssembler;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 同時刻台帳のkeyset、本人署名cursor、反復GET無書込を実MySQLで検証する。 */
@TestPropertySource(properties = "mannschaft.ranch.development-fixtures=true")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RanchRecordQueryReaderIT extends AbstractMySqlIntegrationTest {
    private static final RanchStateAssembler.ExternalProjection PROJECTION =
            new RanchStateAssembler.ExternalProjection(false, "DISABLED", false,
                    false, null, null, List.of());
    private static final Instant NOW = Instant.parse("2026-10-04T02:00:00.123456Z");

    @Autowired private UserRepository users;
    @Autowired private RanchEnrollmentWriter enrollment;
    @Autowired private RanchPointLedgerRepository ledger;
    @Autowired private RanchRecordQueryReader reader;

    @Test
    void sameTimestampPagesHaveNoDuplicatesAndAnotherUserCursorFails() {
        Long me = users.saveAndFlush(RanchTestFixture.user()).getId();
        Long other = users.saveAndFlush(RanchTestFixture.user()).getId();
        var created = enrollment.enroll(me, UUID.randomUUID(), NOW, PROJECTION);
        for (int index = 0; index < 3; index++) {
            ledger.save(RanchPointLedgerEntity.builder()
                    .ownerId(created.ownerId()).userId(me)
                    .commandId(UUID.randomUUID()).entryKind("CARE")
                    .deltaPoints(0).balanceAfter(0).deltaXp(index)
                    .dinosaurId(created.dinosaurId()).ruleSnapshot("{}")
                    .occurredAt(NOW).createdAt(NOW).build());
        }
        ledger.flush();

        List<UUID> seen = new ArrayList<>();
        String cursor = null;
        for (int pageIndex = 0; pageIndex < 3; pageIndex++) {
            var page = reader.page(me, cursor, 1);
            assertThat(page.getData()).hasSize(1);
            var row = page.getData().get(0);
            assertThat(row.sourceLink()).isNull();
            assertThat(row.sourceType()).isNull();
            seen.add(row.id());
            cursor = page.getMeta().getNextCursor();
            if (pageIndex == 0) {
                String foreignCursor = cursor;
                assertThatThrownBy(() -> reader.page(other, foreignCursor, 1))
                        .isInstanceOf(BusinessException.class);
            }
        }
        assertThat(seen).doesNotHaveDuplicates();
        assertThat(cursor).isNull();
        assertThat(ledger.findByUserIdOrderByOccurredAtDescIdDesc(me)).hasSize(3);
        assertThat(reader.page(other, null, 20).getData()).isEmpty();
        assertThatThrownBy(() -> reader.page(me, null, 0))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> reader.page(me, null, 101))
                .isInstanceOf(BusinessException.class);
    }
}
