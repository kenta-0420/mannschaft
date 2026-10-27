package com.mannschaft.app.dashboard.service;

import com.mannschaft.app.circulation.CirculationStatus;
import com.mannschaft.app.circulation.CirculationMode;
import com.mannschaft.app.circulation.RecipientStatus;
import com.mannschaft.app.circulation.entity.CirculationDocumentEntity;
import com.mannschaft.app.circulation.entity.CirculationRecipientEntity;
import com.mannschaft.app.circulation.repository.CirculationRecipientRepository;
import com.mannschaft.app.schedule.AttendanceStatus;
import com.mannschaft.app.schedule.EventType;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.schedule.entity.ScheduleAttendanceEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleAttendanceRepository;
import com.mannschaft.app.social.announcement.AnnouncementFeedEntity;
import com.mannschaft.app.social.announcement.AnnouncementFeedQueryRepository;
import com.mannschaft.app.social.announcement.AnnouncementScopeType;
import com.mannschaft.app.social.announcement.AnnouncementSourceType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.survey.SurveyStatus;
import com.mannschaft.app.survey.entity.SurveyEntity;
import com.mannschaft.app.survey.entity.SurveyResponseEntity;
import com.mannschaft.app.survey.repository.SurveyRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 候補JPQLが元の対象本人・論理削除条件を維持し、時刻で過小除外しないことを実MySQLで検証する。 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ActionRequiredCandidateQueryIT extends AbstractMySqlIntegrationTest {
    private static final Long USER = 8_135_402L;
    private static final long SCOPE = 9_135_402L;
    @PersistenceContext private EntityManager em;
    @Autowired private CirculationRecipientRepository circulation;
    @Autowired private SurveyRepository survey;
    @Autowired private ScheduleAttendanceRepository attendance;
    @Autowired private AnnouncementFeedQueryRepository feed;

    @Test
    void 回覧候補は本人PENDINGかつACTIVEと論理削除を旧queryと同じにする() {
        MembershipTestHelper.insertActiveUser(em, USER);
        circulation("TEAM", SCOPE, USER, CirculationStatus.ACTIVE, RecipientStatus.PENDING, false);
        circulation("ORGANIZATION", SCOPE, USER, CirculationStatus.ACTIVE, RecipientStatus.PENDING, false);
        circulation("TEAM", SCOPE + 1, USER, CirculationStatus.ACTIVE, RecipientStatus.STAMPED, false);
        circulation("TEAM", SCOPE + 2, USER, CirculationStatus.DRAFT, RecipientStatus.PENDING, false);
        circulation("TEAM", SCOPE + 3, USER, CirculationStatus.ACTIVE, RecipientStatus.PENDING, true);
        circulation("TEAM", SCOPE + 4, USER + 1, CirculationStatus.ACTIVE, RecipientStatus.PENDING, false);
        em.flush();
        em.clear();
        List<Long> ids = scopes(5);
        assertThat(circulation.findUnconfirmedScopeIds("TEAM", ids, USER)).containsExactly(SCOPE);
        assertThat(circulation.findUnconfirmedScopeIds("ORGANIZATION", ids, USER)).containsExactly(SCOPE);
        for (Long id : ids) {
            assertThat(circulation.findUnconfirmedDocumentsForUserInScope("TEAM", id, USER).isEmpty())
                    .isEqualTo(!id.equals(SCOPE));
        }
    }

    @Test
    void アンケ候補は本人未回答と論理削除を保ち期限やrecipient条件を足さない() {
        MembershipTestHelper.insertActiveUser(em, USER);
        survey("TEAM", SCOPE, SurveyStatus.PUBLISHED, false);
        survey("ORGANIZATION", SCOPE, SurveyStatus.PUBLISHED, false);
        var answered = survey("TEAM", SCOPE + 1, SurveyStatus.PUBLISHED, false);
        em.persist(SurveyResponseEntity.builder().surveyId(answered.getId()).questionId(1L).userId(USER).build());
        survey("TEAM", SCOPE + 2, SurveyStatus.DRAFT, false);
        survey("TEAM", SCOPE + 3, SurveyStatus.PUBLISHED, true);
        var answeredByOther = survey("TEAM", SCOPE + 4, SurveyStatus.PUBLISHED, false);
        em.persist(SurveyResponseEntity.builder().surveyId(answeredByOther.getId()).questionId(1L).userId(USER + 1).build());
        em.flush();
        em.clear();
        assertThat(survey.findUnansweredScopeIds("TEAM", scopes(5), USER)).containsExactlyInAnyOrder(SCOPE, SCOPE + 4);
        assertThat(survey.findUnansweredScopeIds("ORGANIZATION", scopes(5), USER)).containsExactly(SCOPE);
        for (Long id : scopes(5)) {
            assertThat(survey.findUnansweredPublishedForUserInScope("TEAM", id, USER).isEmpty())
                    .isEqualTo(id != SCOPE && id != SCOPE + 4);
        }
    }

    @Test
    void 出欠候補は取消予定と過去時刻を保守的に残し回答済や論理削除は除外する() {
        MembershipTestHelper.insertActiveUser(em, USER);
        attendance(SCOPE, SCOPE, USER, AttendanceStatus.UNDECIDED, null, true, false, false);
        attendance(SCOPE + 1, null, USER, AttendanceStatus.UNDECIDED, null, true, false, true);
        attendance(SCOPE + 2, null, USER, AttendanceStatus.UNDECIDED, LocalDateTime.now(), true, false, false);
        attendance(SCOPE + 3, null, USER, AttendanceStatus.UNDECIDED, null, false, false, false);
        attendance(SCOPE + 4, null, USER, AttendanceStatus.UNDECIDED, null, true, true, false);
        attendance(SCOPE + 5, null, USER + 1, AttendanceStatus.UNDECIDED, null, true, false, false);
        attendance(SCOPE + 6, null, USER, AttendanceStatus.ATTENDING, null, true, false, false);
        em.flush();
        em.clear();
        assertThat(attendance.findUnansweredTeamScopeIds(scopes(7), USER)).containsExactlyInAnyOrder(SCOPE, SCOPE + 1);
        assertThat(attendance.findUnansweredOrganizationScopeIds(scopes(7), USER)).containsExactly(SCOPE);
        assertThat(attendance.findUnansweredUpcomingForUserInTeam(SCOPE, USER, LocalDateTime.now())).hasSize(1);
        assertThat(attendance.findUnansweredUpcomingForUserInOrganization(SCOPE, USER, LocalDateTime.now())).hasSize(1);
        assertThat(attendance.findUnansweredUpcomingForUserInTeam(SCOPE + 1, USER, LocalDateTime.now())).isEmpty();
    }

    @Test
    void Inbox候補は全履歴を含め最終queryの期限削除可視性windowを変更しない() {
        var now = LocalDateTime.now();
        feed(AnnouncementScopeType.TEAM, SCOPE, null, null, "MEMBERS_AND_ABOVE", now.plusDays(1));
        feed(AnnouncementScopeType.TEAM, SCOPE + 1, now.minusDays(1), null, "MEMBERS_AND_ABOVE", null);
        feed(AnnouncementScopeType.TEAM, SCOPE + 2, null, now, "MEMBERS_AND_ABOVE", null);
        feed(AnnouncementScopeType.TEAM, SCOPE + 3, null, null, "ADMINS_ONLY", null);
        feed(AnnouncementScopeType.ORGANIZATION, SCOPE, null, null, "MEMBERS_AND_ABOVE", null);
        em.flush();
        em.clear();
        assertThat(feed.findScopeIdsWithHistory(AnnouncementScopeType.TEAM, scopes(5)))
                .containsExactlyInAnyOrder(SCOPE, SCOPE + 1, SCOPE + 2, SCOPE + 3);
        assertThat(feed.findScopeIdsWithHistory(AnnouncementScopeType.ORGANIZATION, scopes(5))).containsExactly(SCOPE);
        assertThat(feed.findByScope(AnnouncementScopeType.TEAM, SCOPE, Set.of("MEMBERS_AND_ABOVE"), null, 500)).hasSize(1);
        for (long id = SCOPE + 1; id <= SCOPE + 3; id++) {
            assertThat(feed.findByScope(AnnouncementScopeType.TEAM, id, Set.of("MEMBERS_AND_ABOVE"), null, 500)).isEmpty();
        }
    }

    private List<Long> scopes(int count) {
        return java.util.stream.LongStream.range(SCOPE, SCOPE + count).boxed().toList();
    }

    private void circulation(String type, Long scope, Long user, CirculationStatus status, RecipientStatus recipient, boolean deleted) {
        var document = CirculationDocumentEntity.builder().scopeType(type).scopeId(scope).createdBy(USER)
                .title("候補回覧").body("候補抽出検証").status(status).circulationMode(CirculationMode.SEQUENTIAL)
                .dueDate(java.time.LocalDate.now().minusDays(1)).deletedAt(deleted ? LocalDateTime.now() : null).build();
        em.persist(document);
        em.persist(CirculationRecipientEntity.builder().documentId(document.getId()).userId(user).status(recipient).build());
    }

    private SurveyEntity survey(String type, Long scope, SurveyStatus status, boolean deleted) {
        var entity = SurveyEntity.builder().scopeType(type).scopeId(scope).title("候補アンケ").status(status)
                .createdBy(USER).expiresAt(LocalDateTime.now().minusDays(1)).deletedAt(deleted ? LocalDateTime.now() : null).build();
        em.persist(entity);
        return entity;
    }

    private void attendance(Long team, Long org, Long user, AttendanceStatus status, LocalDateTime responded,
            boolean required, boolean deleted, boolean past) {
        var schedule = ScheduleEntity.builder().teamId(team).organizationId(org).title("候補予定")
                .startAt(LocalDateTime.now().plusDays(past ? -1 : 30)).eventType(EventType.EVENT)
                .visibility(ScheduleVisibility.MEMBERS_ONLY).minViewRole(MinViewRole.MEMBER_PLUS)
                .status(ScheduleStatus.CANCELLED).createdBy(USER).attendanceRequired(required)
                .deletedAt(deleted ? LocalDateTime.now() : null).build();
        em.persist(schedule);
        em.persist(ScheduleAttendanceEntity.builder().scheduleId(schedule.getId()).userId(user)
                .status(status).respondedAt(responded).build());
    }

    private void feed(AnnouncementScopeType type, Long scope, LocalDateTime expires, LocalDateTime deleted,
            String visibility, LocalDateTime starts) {
        em.persist(AnnouncementFeedEntity.builder().scopeType(type).scopeId(scope).sourceType(AnnouncementSourceType.SCHEDULE)
                .sourceId(scope).titleCache("候補告知").expiresAt(expires).sourceDeletedAt(deleted)
                .visibility(visibility).startsAt(starts).build());
    }
}
