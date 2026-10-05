package com.mannschaft.app.schedule.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.UserOperationGuard;
import com.mannschaft.app.auth.service.UserRewardDeliveryGuard;
import com.mannschaft.app.schedule.AttendanceStatus;
import com.mannschaft.app.schedule.EventType;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.schedule.dto.AttendanceRequest;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.entity.ScheduleAttendanceEntity;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.repository.ScheduleAttendanceRepository;
import com.mannschaft.app.schedule.repository.ScheduleRanchTransportRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

/** 実auth/出欠源Bean/MySQLの本人初回境界。HTTP・TEAM/ORG・全purge競合は別証明。 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ScheduleRanchNativeTransportIT extends AbstractMySqlIntegrationTest {
    @Autowired private UserRepository users;
    @Autowired private ScheduleRepository schedules;
    @Autowired private ScheduleAttendanceRepository responses;
    @Autowired private ScheduleRanchTransportRepository rows;
    @Autowired private UserOperationGuard active;
    @Autowired private UserRewardDeliveryGuard delivery;
    @Autowired private ScheduleRanchNativeWriter writer;
    @Autowired private ScheduleRanchTransportWriter receiver;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.fasterxml.jackson.databind.ObjectMapper mapper;
    private Long owner;
    private Long scheduleId;
    private Long responseId;
    @BeforeEach void fixture() {
        owner=users.saveAndFlush(UserEntity.builder().email(UUID.randomUUID()+"@attendance-transport.invalid")
                .lastName("検証").firstName("本人").displayName("検証").isSearchable(false)
                .locale("ja").timezone("UTC").status(UserEntity.UserStatus.ACTIVE).build()).getId();
        scheduleId=schedules.saveAndFlush(ScheduleEntity.builder().userId(owner).title("検証予定")
                .startAt(LocalDateTime.of(2026,10,10,0,0)).eventType(EventType.OTHER)
                .visibility(ScheduleVisibility.MEMBERS_ONLY).minViewRole(MinViewRole.ANYONE)
                .status(ScheduleStatus.SCHEDULED).attendanceRequired(true).build()).getId();
        responseId=responses.saveAndFlush(ScheduleAttendanceEntity.builder().scheduleId(scheduleId)
                .userId(owner).status(AttendanceStatus.UNDECIDED).build()).getId();
    }
    @AfterEach void cleanupOwnRows() {
        if(owner==null) return;
        rows.deleteForUser(owner);
        if(responseId!=null) responses.deleteById(responseId);
        if(scheduleId!=null) schedules.deleteById(scheduleId);
        users.deleteById(owner);
    }
    @Test void proxyThenFirstSelfQualifiesOnceAndEditingDoesNotCreateAnotherFact() {
        var response=responses.findById(responseId).orElseThrow();
        response.respondProxy(AttendanceStatus.ATTENDING,"代理回答");responses.saveAndFlush(response);
        var first=respond("ABSENT");assertThat(first.capture()).isNotNull();
        assertThat(receive(first.capture())).isTrue();assertThat(receive(first.capture())).isFalse();
        var edit=respond("PARTIAL");assertThat(edit.capture()).isNull();
        assertThat(responses.findById(responseId).orElseThrow().getStatus()).isEqualTo(AttendanceStatus.PARTIAL);
        assertThat(outboxCount()).isEqualTo(1);
    }
    @Test void undecidedThenFirstPositiveResponseDoesNotUseRespondedAtAsProof() {
        assertThat(respond("UNDECIDED").capture()).isNull();
        assertThat(responses.findById(responseId).orElseThrow().getRespondedAt()).isNotNull();
        var first=respond("PARTIAL");assertThat(first.capture()).isNotNull();
        assertThat(receive(first.capture())).isTrue();assertThat(outboxCount()).isEqualTo(1);
    }
    @Test void purgedNativeProofRejectsLateCaptureWithoutRecreatingOutbox() {
        var first=respond("ABSENT");assertThat(receive(first.capture())).isTrue();
        rows.deleteForUser(owner);
        assertThat(receive(first.capture())).isFalse();assertThat(outboxCount()).isZero();
        assertThat(responses.findById(responseId).orElseThrow().getStatus()).isEqualTo(AttendanceStatus.ABSENT);
        assertThat(responses.findById(responseId).orElseThrow().getRanchFirstSelfAt()).isNull();
    }
    @Test void requestOriginCannotBeSuppliedOrExposedByJson() throws Exception {
        var request=new AttendanceRequest("ABSENT",null,null);
        request.captureRanchResponseOrigin(false);
        com.fasterxml.jackson.databind.node.ObjectNode json=mapper.valueToTree(request);
        assertThat(json.has("ranchSelfResponse")).isFalse();
        json.put("ranchSelfResponse",true);
        assertThat(mapper.treeToValue(json,AttendanceRequest.class).getRanchSelfResponse()).isNull();
    }
    private ScheduleRanchNativeWriter.Outcome respond(String status) {
        return active.withActiveUser(owner,() -> writer.respond(scheduleId,owner,new AttendanceRequest(status,null,null)));
    }
    private boolean receive(ScheduleRanchCapture capture) {
        return delivery.withLockedDeliveryUser(owner,state -> receiver.accept(capture));
    }
    private long outboxCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM schedule_ranch_outboxes WHERE recipient_user_id=?",Long.class,owner);
    }
}
