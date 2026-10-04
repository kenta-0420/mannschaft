package com.mannschaft.app.ranch;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.ranch.repository.RanchCommandRepository;
import com.mannschaft.app.ranch.service.RanchCommandQueryReader;
import com.mannschaft.app.ranch.service.RanchEnrollmentWriter;
import com.mannschaft.app.ranch.service.RanchStateAssembler;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** command個別読取は実PRIMARY/MySQLで本人のみ成功し、反復GETは無書込。 */
@TestPropertySource(properties = "mannschaft.ranch.development-fixtures=true")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RanchCommandQueryReaderIT extends AbstractMySqlIntegrationTest {
    private static final RanchStateAssembler.ExternalProjection PROJECTION =
            new RanchStateAssembler.ExternalProjection(false, "DISABLED", false,
                    false, null, null, List.of());

    @Autowired private UserRepository users;
    @Autowired private RanchEnrollmentWriter enrollment;
    @Autowired private RanchCommandQueryReader reader;
    @Autowired private RanchCommandRepository commands;

    @Test
    void ownerCanReadSavedResultButAnotherUserSees404Shape() {
        Long me = users.saveAndFlush(RanchTestFixture.user()).getId();
        Long other = users.saveAndFlush(RanchTestFixture.user()).getId();
        var created = enrollment.enroll(me, UUID.randomUUID(),
                Instant.parse("2026-10-04T02:00:00.123456789Z"), PROJECTION);
        long before = commands.countByUserId(me);
        var first = reader.get(me, created.commandId());
        var second = reader.get(me, created.commandId());
        assertThat(second).isEqualTo(first);
        assertThat(first.result().get("dinosaur").get("stage").asText())
                .isEqualTo("EGG");
        assertThatThrownBy(() -> reader.get(other, created.commandId()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> reader.get(me, UUID.randomUUID()))
                .isInstanceOf(BusinessException.class);
        assertThat(commands.countByUserId(me)).isEqualTo(before);
        assertThat(commands.countByUserId(other)).isZero();
    }
}
