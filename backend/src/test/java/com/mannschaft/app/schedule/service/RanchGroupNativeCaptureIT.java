package com.mannschaft.app.schedule.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.UserOperationGuard;
import com.mannschaft.app.auth.service.UserRewardDeliveryGuard;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.schedule.AttendanceStatus;
import com.mannschaft.app.schedule.EventType;
import com.mannschaft.app.schedule.MinResponseRole;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.schedule.dto.AttendanceRequest;
import com.mannschaft.app.schedule.entity.ScheduleAttendanceEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleAttendanceRepository;
import com.mannschaft.app.schedule.repository.ScheduleRanchTransportRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.timeline.dto.CreatePostRequest;
import com.mannschaft.app.timeline.service.TimelineRanchNativeOperationFacade;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 実Beanと確定済みfixtureで通常TEAM/ORG認可を検証する。HTTP・pool2・添付は別証明。 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@TestPropertySource(properties="ranch.source.timeline.queue-capacity=0")
class RanchGroupNativeCaptureIT extends AbstractMySqlIntegrationTest {
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private OrganizationRepository organizations;
    @Autowired private MembershipRepository memberships;
    @Autowired private RoleRepository roles;
    @Autowired private UserRoleRepository assignments;
    @Autowired private ScheduleRepository schedules;
    @Autowired private ScheduleAttendanceRepository responses;
    @Autowired private ScheduleRanchTransportRepository transportRows;
    @Autowired private UserOperationGuard active;
    @Autowired private UserRewardDeliveryGuard delivery;
    @Autowired private ScheduleRanchNativeWriter writer;
    @Autowired private ScheduleRanchTransportWriter receiver;
    @Autowired private TimelineRanchNativeOperationFacade timeline;
    @Autowired private JdbcTemplate jdbc;
    private Long owner;
    private Long outsider;
    private Long group;
    private String scope;
    private Long membership;
    private Long assignment;
    private Long schedule;
    private final List<Long> ownResponses=new ArrayList<>();
    private final List<Long> ownPosts=new ArrayList<>();

    @BeforeEach void users() { owner=saveUser();outsider=saveUser(); }
    private Long saveUser() {
        return users.saveAndFlush(UserEntity.builder().email(UUID.randomUUID()+"@group-native.invalid")
                .lastName("検証").firstName("本人").displayName("検証").isSearchable(false)
                .locale("ja").timezone("UTC").status(UserEntity.UserStatus.ACTIVE).build()).getId();
    }
    private void group(String value) {
        scope=value;
        String slug="ranch-"+UUID.randomUUID().toString().substring(0,20);
        group="TEAM".equals(scope)
                ? teams.saveAndFlush(TeamEntity.builder().slug(slug).name("検証チーム")
                    .visibility(TeamEntity.Visibility.PUBLIC).supporterEnabled(false).build()).getId()
                : organizations.saveAndFlush(OrganizationEntity.builder().slug(slug).name("検証組織")
                    .orgType(OrganizationEntity.OrgType.ASSOCIATION).visibility(OrganizationEntity.Visibility.PUBLIC)
                    .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE).supporterEnabled(false).build()).getId();
        membership=memberships.saveAndFlush(MembershipEntity.builder().userId(owner)
                .scopeType(ScopeType.valueOf(scope)).scopeId(group).joinedAt(LocalDateTime.now()).build()).getId();
        // 既存ロール正本と同じ優先度。共有ロール行は消去せず、所有する割当だけ後始末する。
        role("ADMIN",2);role("DEPUTY_ADMIN",3);role("MEMBER",4);role("SUPPORTER",5);
        assignment=assignments.saveAndFlush(UserRoleEntity.builder().userId(owner).roleId(role("ADMIN",2))
                .teamId("TEAM".equals(scope)?group:null).organizationId("ORGANIZATION".equals(scope)?group:null).build()).getId();
        schedule=schedules.saveAndFlush(ScheduleEntity.builder().title("検証予定")
                .teamId("TEAM".equals(scope)?group:null).organizationId("ORGANIZATION".equals(scope)?group:null)
                .startAt(LocalDateTime.of(2026,10,10,0,0)).eventType(EventType.OTHER)
                .visibility(ScheduleVisibility.MEMBERS_ONLY).minViewRole(MinViewRole.ANYONE)
                .minResponseRole(MinResponseRole.MEMBER_PLUS).status(ScheduleStatus.SCHEDULED).attendanceRequired(true).build()).getId();
        for(Long user:List.of(owner,outsider)) ownResponses.add(responses.saveAndFlush(
                ScheduleAttendanceEntity.builder().scheduleId(schedule).userId(user).status(AttendanceStatus.UNDECIDED).build()).getId());
    }
    private Long role(String name,int priority) {
        return roles.findByName(name).map(RoleEntity::getId).orElseGet(() -> roles.saveAndFlush(
                RoleEntity.builder().name(name).displayName(name).priority(priority).isSystem(true).build()).getId());
    }
    private CreatePostRequest post() {
        return new CreatePostRequest("グループ本文",scope,group.toString(),"USER",null,null,null,null,null,null,null,null);
    }
    @ParameterizedTest @ValueSource(strings={"TEAM","ORGANIZATION"})
    void committedMemberUsesOriginalBusinessRulesAndCapturesActualGroup(String value) {
        group(value);
        var result=active.withActiveUser(owner,() -> writer.respond(schedule,owner,new AttendanceRequest("ATTENDING",null,null)));
        assertThat(result.capture()).isNotNull();
        assertThat(result.capture().payload().scopeType().name()).isEqualTo(scope);
        assertThat(result.capture().payload().canonicalScopeId()).isEqualTo(group.toString());
        boolean first=delivery.withLockedDeliveryUser(owner,state -> receiver.accept(result.capture()));
        boolean repeated=delivery.withLockedDeliveryUser(owner,state -> receiver.accept(result.capture()));
        assertThat(first).isTrue();assertThat(repeated).isFalse();
        var saved=timeline.create(post(),group,owner,false).orElseThrow();ownPosts.add(saved.getId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM timeline_posts WHERE id=? AND scope_type=? AND scope_id=? "
                +"AND is_ranch_origin_known=TRUE AND ranch_qualified_user_id=? AND ranch_qualified_at IS NOT NULL",
                Integer.class,saved.getId(),scope,group,owner)).isEqualTo(1);
        // 有限queue OFFでも本体commit成功。transport/consumerの成功に読み替えない。
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM timeline_ranch_outboxes WHERE recipient_user_id=?",Integer.class,owner)).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"TEAM","ORGANIZATION"})
    void outsiderCannotBypassExistingMembershipOrResponseRole(String value) {
        group(value);
        assertThatThrownBy(() -> active.withActiveUser(outsider,() -> writer.respond(schedule,outsider,
                new AttendanceRequest("ATTENDING",null,null))))
                .isInstanceOfSatisfying(BusinessException.class,error -> assertThat(error.getErrorCode()).isEqualTo(CommonErrorCode.COMMON_002));
        assertThat(responses.findById(ownResponses.get(1)).orElseThrow().getStatus()).isEqualTo(AttendanceStatus.UNDECIDED);
        assertThatThrownBy(() -> timeline.create(post(),group,outsider,false))
                .isInstanceOfSatisfying(BusinessException.class,error -> assertThat(error.getErrorCode()).isEqualTo(CommonErrorCode.COMMON_002));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM timeline_posts WHERE user_id=?",Integer.class,outsider)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM schedule_ranch_outboxes WHERE recipient_user_id=?",Integer.class,outsider)).isZero();
    }
    @AfterEach void cleanupOwnRows() {
        if(owner!=null) transportRows.deleteForUser(owner);
        if(outsider!=null) transportRows.deleteForUser(outsider);
        for(Long post:ownPosts) jdbc.update("DELETE FROM timeline_posts WHERE id=?",post);
        for(Long response:ownResponses) responses.deleteById(response);
        if(schedule!=null) schedules.deleteById(schedule);
        if(assignment!=null) assignments.deleteById(assignment);
        if(membership!=null) memberships.deleteById(membership);
        if(group!=null) { if("TEAM".equals(scope)) teams.deleteById(group);else organizations.deleteById(group); }
        if(outsider!=null) users.deleteById(outsider);
        if(owner!=null) users.deleteById(owner);
    }
}
