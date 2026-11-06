package com.mannschaft.app.common;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mannschaft.app.gdpr.GdprErrorCode;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * L1-A AC8: 実MySQLのSYSTEM_ADMIN人数境界と、本番退会APIの実Security/例外写像を固定する。
 * 所有user/roleだけを追加し、各テストの@Transactional rollbackで戻す。既存adminは変更しない。
 * 認証は既存実filter試験と同じuser(Long文字列)を使い、JWT検証自体は本試験の対象に含めない。
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class AccessControlLastAdminWithdrawalIntegrationTest extends AbstractMySqlIntegrationTest {
    private static final long ACTOR = 4022091411L;
    private static final long SECOND_ADMIN = 4022091412L;
    private static final long NON_ADMIN = 4022091413L;

    @Autowired private AccessControlService accessControl;
    @Autowired private UserRoleRepository roles;
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @PersistenceContext private EntityManager em;

    @AfterEach
    void 認証状態を掃除する() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void 最後のSYSTEM_ADMINだけを退会拒否する(int count) {
        prepareAdmins(count);
        assertThat(roles.countSystemAdmins()).isEqualTo(count);
        assertThat(roles.isSystemAdmin(ACTOR)).isEqualTo(count >= 1 ? 1 : 0);
        assertThat(roles.isSystemAdmin(NON_ADMIN)).isZero();
        assertThatCode(() -> accessControl.checkNotLastSystemAdmin(NON_ADMIN)).doesNotThrowAnyException();
        if (count == 1) {
            assertThatThrownBy(() -> accessControl.checkNotLastSystemAdmin(ACTOR))
                    .isInstanceOfSatisfying(BusinessException.class,
                            error -> assertThat(error.getErrorCode()).isSameAs(GdprErrorCode.GDPR_006));
        } else {
            assertThatCode(() -> accessControl.checkNotLastSystemAdmin(ACTOR)).doesNotThrowAnyException();
            if (count == 2) {
                assertThat(roles.isSystemAdmin(SECOND_ADMIN)).isEqualTo(1);
                assertThatCode(() -> accessControl.checkNotLastSystemAdmin(SECOND_ADMIN))
                        .doesNotThrowAnyException();
            }
        }
    }

    @Test
    void 唯一のSYSTEM_ADMINの退会HTTPは409と既存本文とWARNを返し利用者を変更しない() throws Exception {
        prepareAdmins(1);
        var before = jdbc.queryForMap("SELECT status, deleted_at, withdrawal_attempt_id, updated_at "
                + "FROM users WHERE id = ?", ACTOR);
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        Level previousLevel = logger.getLevel();
        var capture = new ListAppender<ILoggingEvent>();
        capture.start();
        logger.addAppender(capture);
        logger.setLevel(Level.WARN);
        try {
            var response = mvc.perform(delete("/api/v1/users/me")
                            .with(user(Long.toString(ACTOR)).roles("SYSTEM_ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("GDPR_006"))
                    .andExpect(jsonPath("$.error.message").value("管理者権限の移譲が必要です"))
                    .andReturn();
            assertThat(response.getResolvedException()).isInstanceOfSatisfying(BusinessException.class,
                    error -> assertThat(error.getErrorCode()).isSameAs(GdprErrorCode.GDPR_006));
            assertThat(capture.list).filteredOn(event -> event.getFormattedMessage().contains("GDPR_006"))
                    .singleElement().satisfies(event -> assertThat(event.getLevel()).isEqualTo(Level.WARN));
            assertThat(jdbc.queryForMap("SELECT status, deleted_at, withdrawal_attempt_id, updated_at "
                    + "FROM users WHERE id = ?", ACTOR)).isEqualTo(before);
            assertThat(roles.countSystemAdmins()).isEqualTo(1);
        } finally {
            logger.detachAppender(capture);
            logger.setLevel(previousLevel);
            capture.stop();
        }
    }

    private void prepareAdmins(int count) {
        assertThat(roles.countSystemAdmins())
                .as("他試験のSYSTEM_ADMINを変更せず、空の初期状態から所有fixtureだけを追加する")
                .isZero();
        MembershipTestHelper.insertActiveUser(em, ACTOR);
        MembershipTestHelper.insertActiveUser(em, SECOND_ADMIN);
        MembershipTestHelper.insertActiveUser(em, NON_ADMIN);
        if (count >= 1) {
            MembershipTestHelper.insertUserRole(em, ACTOR, "SYSTEM_ADMIN", null, null);
        }
        if (count >= 2) {
            MembershipTestHelper.insertUserRole(em, SECOND_ADMIN, "SYSTEM_ADMIN", null, null);
        }
        em.flush();
        em.clear();
    }
}
