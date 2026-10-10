package com.mannschaft.app.schedule.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.jdbc.JdbcUtcCalendar;
import com.mannschaft.app.schedule.repository.ScheduleRanchTransportRepository;
import com.mannschaft.app.ranch.reward.api.RanchRewardEnvelope;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 現在statusから資格を作らず、源保存時の本人初回証拠だけを照合する。 */
@Service
@RequiredArgsConstructor
class ScheduleRanchTransportWriter {
    private final ScheduleRanchTransportRepository transport;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=false)
    boolean accept(ScheduleRanchCapture capture) {
        var fact=capture.payload();
        if(fact.actorKind()!=RanchRewardEnvelope.ActorKind.USER || fact.originalAdminId()!=null
                || !Objects.equals(fact.actorUserId(),fact.subjectUserId())
                || !Objects.equals(fact.subjectUserId(),fact.recipientUserId())
                || fact.facts().proxy() || !fact.facts().firstQualified()) return false;
        var rows=jdbc.query("SELECT ranch_first_self_at,ranch_first_self_user_id,schedule_id FROM schedule_attendances "
                +"WHERE id=? AND user_id=? AND is_ranch_response_history_known=TRUE "
                +"AND is_ranch_self_response_observed=TRUE FOR UPDATE",
                (rs,index) -> new NativeProof(rs.getTimestamp(1,JdbcUtcCalendar.fresh()),rs.getObject(2,Long.class),rs.getObject(3,Long.class)),
                Long.parseLong(fact.canonicalSourceId()),fact.recipientUserId());
        if(rows.isEmpty() || rows.getFirst().at()==null
                || !Objects.equals(rows.getFirst().user(),fact.recipientUserId())
                || !rows.getFirst().at().toInstant().equals(fact.occurredAt())) return false;
        try {
            return transport.insertQualified(fact,rows.getFirst().scheduleId(),mapper.writeValueAsString(fact),clock.instant().truncatedTo(ChronoUnit.MICROS));
        } catch(JsonProcessingException invalid) {
            throw new IllegalArgumentException("出欠源payloadを保存できません");
        }
    }
    private record NativeProof(Timestamp at,Long user,Long scheduleId) { }
}
