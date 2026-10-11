package com.mannschaft.app.shift;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.shift.entity.ShiftHourlyRateEntity;
import com.mannschaft.app.shift.repository.ShiftHourlyRateRepository;
import com.mannschaft.app.shift.service.ShiftHourlyRateDeletionFacade;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 時給削除（{@link ShiftHourlyRateDeletionFacade#deleteHourlyRate}）の認可契約テスト。
 *
 * <p>削除には HTTP エンドポイントが無いため、サービス層を実 MySQL で直接検証する。
 * 作成・参照と同じ方針（本人 + 当該チーム ADMIN、対象側 BOLA 封鎖、違反は COMMON_002 / 403）。
 * 権限あり / 権限なし / 他テナントを固定する。</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("shift 時給削除 認可契約テスト")
class ShiftHourlyRateDeleteAuthzIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private ShiftHourlyRateDeletionFacade service;

    @Autowired
    private ShiftHourlyRateRepository hourlyRateRepository;

    @PersistenceContext
    private EntityManager em;

    private Long adminTeamAId;
    private Long adminTeamBId;
    private Long memberTeamAId;
    private Long otherMemberTeamAId;
    private Long rateId;

    @BeforeEach
    void setUp() {
        Long teamAId = insertTeam("DEL時給 チームA");
        Long teamBId = insertTeam("DEL時給 チームB");
        adminTeamAId = insertUser("del-rate-admin-a@example.com");
        adminTeamBId = insertUser("del-rate-admin-b@example.com");
        memberTeamAId = insertUser("del-rate-member-a@example.com");
        otherMemberTeamAId = insertUser("del-rate-other-a@example.com");

        MembershipTestHelper.insertMembership(em, adminTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminTeamBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, memberTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, otherMemberTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);

        rateId = hourlyRateRepository.save(ShiftHourlyRateEntity.builder()
                .userId(memberTeamAId)
                .teamId(teamAId)
                .hourlyRate(new BigDecimal("1200.00"))
                .effectiveFrom(LocalDate.of(2026, 3, 1))
                .build()).getId();
        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("他テナントのADMINは削除できず403、行は残る")
    void 他テナントADMINは拒否() {
        assertThatThrownBy(() -> service.deleteHourlyRate(rateId, adminTeamBId))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.COMMON_002));
        assertThat(hourlyRateRepository.findById(rateId)).isPresent();
    }

    @Test
    @DisplayName("同一チームの一般メンバーは他人の時給を削除できず403、行は残る")
    void 同一チーム一般メンバーは他人の分を拒否() {
        assertThatThrownBy(() -> service.deleteHourlyRate(rateId, otherMemberTeamAId))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.COMMON_002));
        assertThat(hourlyRateRepository.findById(rateId)).isPresent();
    }

    @Test
    @DisplayName("存在しないIDは権限の有無にかかわらず403（存在有無を漏らさない）")
    void 存在しないIDは403() {
        assertThatThrownBy(() -> service.deleteHourlyRate(999_999_999L, adminTeamAId))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.COMMON_002));
    }

    @Test
    @DisplayName("正常系: 当該チームのADMINは削除できる")
    void 正当ADMINは削除できる() {
        service.deleteHourlyRate(rateId, adminTeamAId);
        em.flush();
        em.clear();
        assertThat(hourlyRateRepository.findById(rateId)).isEmpty();
    }

    @Test
    @DisplayName("認可ファサードは非TX（クラスにも@Transactionalを付けない。D-3T回避の前提）")
    void ファサードは非TX() {
        assertThat(ShiftHourlyRateDeletionFacade.class
                .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isFalse();
        assertThat(java.util.Arrays.stream(ShiftHourlyRateDeletionFacade.class.getDeclaredMethods())
                .anyMatch(m -> m.isAnnotationPresent(
                        org.springframework.transaction.annotation.Transactional.class))).isFalse();
    }

    @Test
    @DisplayName("正常系: 本人は自分の時給を削除できる")
    void 本人は削除できる() {
        service.deleteHourlyRate(rateId, memberTeamAId);
        em.flush();
        em.clear();
        assertThat(hourlyRateRepository.findById(rateId)).isEmpty();
    }

    private Long insertUser(String email) {
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'WAVE6', 'テスト', 'WAVE6 テスト', 'ACTIVE', "
                                + "1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }

    private Long insertTeam(String name) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                                + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
