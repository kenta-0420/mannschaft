package com.mannschaft.app.diagnosis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

/** 実Facade/Guard/Writer/MySQLで途中再開と回答版を検証する。HTTP filter認可は実Security ITへ分離する。 */
@AutoConfigureMockMvc(addFilters=false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class DiagnosisQuizPersistenceIT extends AbstractMySqlIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRepository users;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired private com.mannschaft.app.diagnosis.service.DiagnosisSessionWriter sessionWriter;
    @Autowired private com.mannschaft.app.diagnosis.repository.DiagnosisSessionRepository sessions;
    @Autowired private com.mannschaft.app.diagnosis.service.DiagnosisSessionSnapshotCodec snapshotCodec;
    @Autowired private java.time.Clock clock;
    @Autowired private jakarta.persistence.EntityManagerFactory entityManagers;
    private static final String BASE="/api/v1/me/diagnoses";
    private Long owner;
    @BeforeEach void setup() {
        owner=users.saveAndFlush(UserEntity.builder().email(UUID.randomUUID()+"@quiz-persistence.invalid")
                .lastName("診断").firstName("本人").displayName("本人").isSearchable(false)
                .locale("ja").timezone("Asia/Tokyo").status(UserEntity.UserStatus.ACTIVE).build()).getId();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(owner.toString(),null,List.of()));
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    private JsonNode mutate(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,Object body,UUID key,int expected) throws Exception {
        return mapper.readTree(mvc.perform(request.header("Idempotency-Key",key.toString()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body))).andExpect(status().is(expected))
                .andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString()).path("data");
    }
    private JsonNode resume(String id)throws Exception {
        return mapper.readTree(mvc.perform(get(BASE+"/sessions/"+id)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString()).path("data");
    }
    private JsonNode pending() throws Exception {
        return mapper.readTree(mvc.perform(get(BASE+"/sessions/pending")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString()).path("data");
    }
    private byte[] sessionBytes(JsonNode session) {
        var id=UUID.fromString(session.path("id").asText());
        return java.nio.ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
    @Test void 新版稼働後も旧版の途中回答を当時の設問で再開完了し結果を保持する() throws Exception {
        var legacy = DiagnosisQuestionnaireTestFixture.legacy(mapper);
        var partial = legacy.questions().subList(0, 12).stream()
                .map(question -> new com.mannschaft.app.diagnosis.dto.DiagnosisAnswer(question.id(), 3)).toList();
        String frozen = snapshotCodec.encodeDefinition(legacy);
        var saved = sessions.saveAndFlush(com.mannschaft.app.diagnosis.entity.DiagnosisSessionEntity.builder()
                .id(com.mannschaft.app.common.UuidV7.generate()).userId(owner).status(DiagnosisStatus.STARTED)
                .questionnaireVersion(legacy.questionnaireVersion()).scoringVersion(legacy.scoringVersion())
                .questionsSnapshot(frozen)
                .answersSnapshot(snapshotCodec.encodeAnswers(
                        new com.mannschaft.app.diagnosis.service.DiagnosisSessionSnapshotCodec.Answers(partial, Map.of())))
                .answerRevision(1).createdAt(clock.instant()).updatedAt(clock.instant()).build());
        String id = saved.getId().toString();

        var current = mutate(post(BASE + "/sessions"), Map.of(), UUID.randomUUID(), 201);
        assertThat(current.path("questionnaireVersion").asText()).isEqualTo("draft-20261010-v2");
        assertThat(current.path("questions")).isNotEqualTo(mapper.valueToTree(legacy.questions()));
        var resumed = resume(id);
        assertThat(resumed.path("questionnaireVersion").asText()).isEqualTo("draft-20261003-v1");
        assertThat(resumed.path("questions")).isEqualTo(mapper.valueToTree(legacy.questions()));
        assertThat(resumed.path("answers")).isEqualTo(mapper.valueToTree(partial));
        var remaining = legacy.questions().subList(12, 24).stream()
                .map(question -> Map.<String, Object>of("questionId", question.id(), "value", 3)).toList();
        resumed = mutate(put(BASE + "/sessions/" + id + "/answers"),
                Map.of("version", resumed.path("version").asText(), "answers", remaining), UUID.randomUUID(), 200);
        resumed = mutate(post(BASE + "/sessions/" + id + "/complete"), completeBody(resumed, List.of()), UUID.randomUUID(), 200);
        assertThat(resumed.path("tieQuestions")).isEqualTo(mapper.valueToTree(legacy.ties()));
        var choices = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < DiagnosisAxis.values().length; index++) {
            choices.add(Map.of("axisId", DiagnosisAxis.values()[index].name(), "value", index % 2));
        }
        resumed = mutate(post(BASE + "/sessions/" + id + "/complete"), completeBody(resumed, choices), UUID.randomUUID(), 200);
        assertThat(resumed.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(resume(id)).isEqualTo(resumed);
        assertThat(jdbc.queryForObject("SELECT questions_snapshot FROM diagnosis_sessions WHERE id = ?",
                String.class, sessionBytes(resumed))).isEqualTo(frozen);
        var result = readResult(resumed.path("resultId").asText());
        assertThat(result.path("questionnaireVersion").asText()).isEqualTo(legacy.questionnaireVersion());
        assertThat(result.path("scoringVersion").asText()).isEqualTo(legacy.scoringVersion());
        assertThat(result.path("typeCode").asText()).isEqualTo("010101");
        assertThat(result.path("axes").size()).isEqualTo(6);
        result.path("axes").forEach(value -> assertThat(value.asInt()).isZero());
        assertThat(result.path("axisDescriptions")).isEqualTo(mapper.valueToTree(legacy.axisDescriptions()));
        assertThat(result.path("descriptionSnapshot")).isEqualTo(mapper.valueToTree(legacy.descriptionSnapshot()));
        for (var tie : legacy.ties()) {
            assertThat(result.path("axisSelections").path(tie.axisId().name()).path("zero")).isEqualTo(mapper.valueToTree(tie.zero()));
            assertThat(result.path("axisSelections").path(tie.axisId().name()).path("one")).isEqualTo(mapper.valueToTree(tie.one()));
        }
        String resultSnapshot = jdbc.queryForObject("SELECT summary_snapshot FROM diagnosis_results WHERE id = ?",
                String.class, resultBytes(result));
        mutate(post(BASE + "/sessions"), Map.of(), UUID.randomUUID(), 201);
        assertThat(readResult(result.path("id").asText())).isEqualTo(result);
        assertThat(jdbc.queryForObject("SELECT summary_snapshot FROM diagnosis_results WHERE id = ?",
                String.class, resultBytes(result))).isEqualTo(resultSnapshot);
    }

    @Test void 新版の交互表示を再開しても保持し逆極性回答を六軸へ正しく採点する() throws Exception {
        var session = mutate(post(BASE + "/sessions"), Map.of(), UUID.randomUUID(), 201);
        assertThat(session.path("questionnaireVersion").asText()).isEqualTo("draft-20261010-v2");
        var ids = new ArrayList<String>(); session.path("questions").forEach(question -> ids.add(question.path("id").asText()));
        assertThat(ids).containsExactly("Q01", "Q05", "Q09", "Q13", "Q17", "Q21",
                "Q02", "Q06", "Q10", "Q14", "Q18", "Q22",
                "Q03", "Q07", "Q11", "Q15", "Q19", "Q23",
                "Q04", "Q08", "Q12", "Q16", "Q20", "Q24");
        String id = session.path("id").asText();
        var frozenQuestions = session.path("questions").deepCopy();
        var answers = new ArrayList<Map<String, Object>>();
        session.path("questions").forEach(question -> answers.add(Map.of("questionId", question.path("id").asText(),
                "value", question.path("polarity").asInt() > 0 ? 1 : 5)));
        session = mutate(put(BASE + "/sessions/" + id + "/answers"),
                Map.of("version", session.path("version").asText(), "answers", answers), UUID.randomUUID(), 200);
        assertThat(resume(id).path("questions")).isEqualTo(frozenQuestions);
        session = mutate(post(BASE + "/sessions/" + id + "/complete"), completeBody(session, List.of()), UUID.randomUUID(), 200);
        var result = readResult(session.path("resultId").asText());
        assertThat(result.path("questionnaireVersion").asText()).isEqualTo("draft-20261010-v2");
        assertThat(result.path("typeCode").asText()).isEqualTo("000000");
        for (var axis : DiagnosisAxis.values()) assertThat(result.path("axes").path(axis.name()).asInt()).isEqualTo(-8);
        assertThat(result.path("axisSelections").path("NOTICE").path("one").path("en").asText())
                .isEqualTo("Enjoy small differences in leaves or stones");
    }
    @Test void 未完了検索は本人の保存回答と凍結設問を返し取消を除外する() throws Exception {
        assertThat(pending().isNull()).isTrue();
        var session=mutate(post(BASE+"/sessions"),Map.of(),UUID.randomUUID(),201);
        String id=session.path("id").asText();
        session=mutate(put(BASE+"/sessions/"+id+"/answers"),Map.of("version",session.path("version").asText(),
                "answers",List.of(Map.of("questionId",session.path("questions").get(0).path("id").asText(),"value",2))),UUID.randomUUID(),200);
        String frozen=jdbc.queryForObject("SELECT questions_snapshot FROM diagnosis_sessions WHERE id = ?",String.class,sessionBytes(session));
        assertThat(pending()).isEqualTo(session);
        assertThat(jdbc.queryForObject("SELECT questions_snapshot FROM diagnosis_sessions WHERE id = ?",String.class,sessionBytes(session))).isEqualTo(frozen);
        var cancelled=mutate(post(BASE+"/sessions"),Map.of(),UUID.randomUUID(),201);
        mutate(post(BASE+"/sessions/"+cancelled.path("id").asText()+"/cancel"),Map.of("version",cancelled.path("version").asText()),UUID.randomUUID(),200);
        assertThat(pending()).isEqualTo(session);
        jdbc.update("UPDATE diagnosis_sessions SET user_id = ? WHERE id = ?",owner+1000000,sessionBytes(session));
        assertThat(pending().isNull()).isTrue();
    }
    @Test void 同点保留を復元し完了済みを除外する() throws Exception {
        var started=mutate(post(BASE+"/sessions"),Map.of(),UUID.randomUUID(),201);
        var tie=mutate(post(BASE+"/sessions"),Map.of(),UUID.randomUUID(),201);
        var answers=new ArrayList<Map<String,Object>>();
        tie.path("questions").forEach(q->answers.add(Map.of("questionId",q.path("id").asText(),"value",3)));
        tie=mutate(put(BASE+"/sessions/"+tie.path("id").asText()+"/answers"),Map.of("version",tie.path("version").asText(),"answers",answers),UUID.randomUUID(),200);
        tie=mutate(post(BASE+"/sessions/"+tie.path("id").asText()+"/complete"),completeBody(tie,List.of(Map.of("axisId","FAMILIAR_NEW","value",1))),UUID.randomUUID(),200);
        assertThat(tie.path("tieQuestions").size()).isEqualTo(5);
        assertThat(pending()).isEqualTo(tie);
        var choices=new ArrayList<Map<String,Object>>();
        tie.path("tieQuestions").forEach(q->choices.add(Map.of("axisId",q.path("axisId").asText(),"value",0)));
        mutate(post(BASE+"/sessions/"+tie.path("id").asText()+"/complete"),completeBody(tie,choices),UUID.randomUUID(),200);
        assertThat(pending()).isEqualTo(started);
    }
    @Test void 同時刻の未完了は符号なしUUID順で最新一件を返す() throws Exception {
        var lower=mutate(post(BASE+"/sessions"),Map.of(),UUID.randomUUID(),201);
        var higher=mutate(post(BASE+"/sessions"),Map.of(),UUID.randomUUID(),201);
        // BINARY(16)とJava UUIDの符号付き比較が異なる境界を跨ぐ。
        var lowId=UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff");
        var highId=UUID.fromString("80000000-0000-0000-0000-000000000000");
        var lowBytes=java.nio.ByteBuffer.allocate(16).putLong(lowId.getMostSignificantBits()).putLong(lowId.getLeastSignificantBits()).array();
        var highBytes=java.nio.ByteBuffer.allocate(16).putLong(highId.getMostSignificantBits()).putLong(highId.getLeastSignificantBits()).array();
        jdbc.update("UPDATE diagnosis_sessions SET id = ?, updated_at = '2026-01-01 00:00:00.000001' WHERE id = ?",lowBytes,sessionBytes(lower));
        jdbc.update("UPDATE diagnosis_sessions SET id = ?, updated_at = '2026-01-01 00:00:00.000001' WHERE id = ?",highBytes,sessionBytes(higher));
        // 二つのstatus間の比較も同じ順序を守る。24中立回答から同点snapshotを作る。
        var all=new ArrayList<Map<String,Object>>();
        higher.path("questions").forEach(q->all.add(Map.of("questionId",q.path("id").asText(),"value",3)));
        var snapshot=mapper.createObjectNode();
        snapshot.set("answers",mapper.valueToTree(all));
        snapshot.set("tieAnswers",mapper.createObjectNode());
        jdbc.update("UPDATE diagnosis_sessions SET status = 'TIE_BREAK_REQUIRED', answers_snapshot = ? WHERE id = ?",mapper.writeValueAsString(snapshot),highBytes);
        assertThat(pending().path("id").asText()).isEqualTo(highId.toString());
    }
    @Test void 非ACTIVE本人の未完了検索を拒否する() throws Exception {
        jdbc.update("UPDATE users SET status = 'FROZEN' WHERE id = ?",owner);
        mvc.perform(get(BASE+"/sessions/pending")).andExpect(status().isForbidden())
                .andExpect(header().string("Cache-Control","no-store"));
    }
    @Test void 未完了履歴が増えても二検索と最大二行だけを読む() throws Exception {
        var original=mutate(post(BASE+"/sessions"),Map.of(),UUID.randomUUID(),201);
        byte[] source=sessionBytes(original);
        long prefix=UUID.randomUUID().getMostSignificantBits();
        for(int index=1;index<=100;index++) {
            var id=new UUID(prefix,index);
            byte[] bytes=java.nio.ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
            // 合成の旧STARTED履歴を増やす。同点側は空なのでentity loadは一件。
            jdbc.update("INSERT INTO diagnosis_sessions (id,user_id,status,questionnaire_version,scoring_version,questions_snapshot,answers_snapshot,answer_revision,version,result_id,created_at,updated_at) SELECT ?,user_id,status,questionnaire_version,scoring_version,questions_snapshot,answers_snapshot,answer_revision,version,result_id,created_at,'2026-01-01 00:00:00.000001' FROM diagnosis_sessions WHERE id = ?",bytes,source);
        }
        var stats=entityManagers.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        boolean enabled=stats.isStatisticsEnabled();
        stats.setStatisticsEnabled(true);stats.clear();
        try {
            assertThat(sessionWriter.readPending(owner).id().toString()).isEqualTo(original.path("id").asText());
            assertThat(stats.getQueryExecutionCount()).isEqualTo(2);
            assertThat(stats.getEntityLoadCount()).isEqualTo(1);
        } finally { stats.setStatisticsEnabled(enabled); }
    }
    private Map<String,Object> completeBody(JsonNode session,List<Map<String,Object>> ties) {
        return Map.of("version",session.path("version").asText(),"answerRevision",session.path("answerRevision").asText(),"tieAnswers",ties);
    }
    private JsonNode completedTieResult() throws Exception {
        JsonNode session=mutate(post(BASE+"/sessions"),Map.of(),UUID.randomUUID(),201);
        String id=session.path("id").asText();
        var answers=new ArrayList<Map<String,Object>>();
        session.path("questions").forEach(q -> answers.add(Map.of("questionId",q.path("id").asText(),"value",3)));
        session=mutate(put(BASE+"/sessions/"+id+"/answers"),Map.of("version",session.path("version").asText(),"answers",answers),UUID.randomUUID(),200);
        var ties=new ArrayList<Map<String,Object>>();
        int side=0;
        for(var axis:DiagnosisAxis.values()) { ties.add(Map.of("axisId",axis.name(),"value",side)); side=1-side; }
        session=mutate(post(BASE+"/sessions/"+id+"/complete"),completeBody(session,ties),UUID.randomUUID(),200);
        return readResult(session.path("resultId").asText());
    }
    private JsonNode readResult(String id) throws Exception {
        return mapper.readTree(mvc.perform(get(BASE+"/results/"+id)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString()).path("data");
    }
    private byte[] resultBytes(JsonNode result) {
        var id=UUID.fromString(result.path("id").asText());
        return java.nio.ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
    private JsonNode makeLegacy(JsonNode result) throws Exception {
        var old=((com.fasterxml.jackson.databind.node.ObjectNode)result).deepCopy();
        old.remove("axisSelections"); old.put("resultSchemaVersion","diagnosis-result-v1");
        jdbc.update("UPDATE diagnosis_results SET summary_snapshot = ? WHERE id = ?",mapper.writeValueAsString(old),resultBytes(result));
        return old;
    }
    @Test void 完成結果は同点の本人選択と保存された極ラベルを返す() throws Exception {
        var result=completedTieResult();
        assertThat(result.path("typeCode").asText()).isEqualTo("010101");
        assertThat(result.path("resultSchemaVersion").asText()).isEqualTo("diagnosis-result-v1");
        assertThat(result.path("axisSelections").size()).isEqualTo(6);
        assertThat(result.path("axes").path("FOCUS_VARIETY").asInt()).isZero();
        assertThat(result.path("axisSelections").path("FOCUS_VARIETY").path("side").asInt()).isEqualTo(1);
        assertThat(result.path("axisSelections").path("FOCUS_VARIETY").path("one").path("ja").asText())
                .isEqualTo("いくつかの楽しみを少しずつ");
    }
    @Test void 旧結果は本人セッションの凍結極ラベルで補足し保存結果を書き換えない() throws Exception {
        var result=completedTieResult(); var old=makeLegacy(result);
        var restored=readResult(result.path("id").asText());
        assertThat(restored.path("axisSelections").path("NOTICE").path("side").asInt()).isEqualTo(1);
        assertThat(restored.path("axisSelections").path("NOTICE").path("one").path("ja").asText())
                .isEqualTo("葉や石の小さな違いを味わう");
        assertThat(restored.path("descriptionSnapshot")).isEqualTo(old.path("descriptionSnapshot"));
        assertThat(mapper.readTree(jdbc.queryForObject("SELECT summary_snapshot FROM diagnosis_results WHERE id = ?",String.class,resultBytes(result))))
                .isEqualTo(old);
    }
    @Test void 旧結果の採点版欠落は推測せず元説明を返す() throws Exception {
        var result=completedTieResult(); var old=(com.fasterxml.jackson.databind.node.ObjectNode)makeLegacy(result);
        old.remove("scoringVersion");
        jdbc.update("UPDATE diagnosis_results SET summary_snapshot = ? WHERE id = ?",mapper.writeValueAsString(old),resultBytes(result));
        var restored=readResult(result.path("id").asText());
        assertThat(restored.path("axisSelections").isNull()).isTrue();
        assertThat(restored.path("descriptionSnapshot")).isEqualTo(old.path("descriptionSnapshot"));
    }
    @Test void 旧結果の本人セッションが無ければ別本人のセッションから補わない() throws Exception {
        var result=completedTieResult(); var old=makeLegacy(result);
        jdbc.update("UPDATE diagnosis_sessions SET user_id = ? WHERE result_id = ?",owner+1000000,resultBytes(result));
        var restored=readResult(result.path("id").asText());
        assertThat(restored.path("axisSelections").isNull()).isTrue();
        assertThat(restored.path("descriptionSnapshot")).isEqualTo(old.path("descriptionSnapshot"));
    }
    @Test void partialAnswersResumeAndSavedTieIsInvalidatedByNewAnswerRevision() throws Exception {
        JsonNode session=mutate(post(BASE+"/sessions"),Map.of(),UUID.randomUUID(),201);String id=session.path("id").asText();
        var first=new ArrayList<Map<String,Object>>();var remaining=new ArrayList<Map<String,Object>>();int index=0;
        for(JsonNode question:session.path("questions")) {
            (index++<12?first:remaining).add(Map.of("questionId",question.path("id").asText(),"value",3));
        }
        session=mutate(put(BASE+"/sessions/"+id+"/answers"),Map.of("version",session.path("version").asText(),"answers",first),UUID.randomUUID(),200);
        assertThat(resume(id)).isEqualTo(session);assertThat(session.path("answers").size()).isEqualTo(12);
        mvc.perform(post(BASE+"/sessions/"+id+"/complete").header("Idempotency-Key",UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(completeBody(session,List.of()))))
                .andExpect(status().isBadRequest());
        session=mutate(put(BASE+"/sessions/"+id+"/answers"),Map.of("version",session.path("version").asText(),"answers",remaining),UUID.randomUUID(),200);
        assertThat(session.path("answers").size()).isEqualTo(24);
        session=mutate(post(BASE+"/sessions/"+id+"/complete"),completeBody(session,List.of(Map.of("axisId","FAMILIAR_NEW","value",0))),UUID.randomUUID(),200);
        assertThat(session.path("status").asText()).isEqualTo("TIE_BREAK_REQUIRED");assertThat(session.path("tieQuestions").size()).isEqualTo(5);
        assertThat(resume(id)).isEqualTo(session);
        mutate(put(BASE+"/sessions/"+id+"/answers"),Map.of("version",session.path("version").asText(),"answers",List.of()),UUID.randomUUID(),400);
        assertThat(resume(id)).isEqualTo(session); // 空回答で版や保存済み同点回答が変化しない。
        String oldRevision=session.path("answerRevision").asText();
        String changeId=null;for(JsonNode question:session.path("questions"))if(question.path("axis").asText().equals("FOCUS_VARIETY")){changeId=question.path("id").asText();break;}
        assertThat(changeId).isNotNull();
        session=mutate(put(BASE+"/sessions/"+id+"/answers"),Map.of("version",session.path("version").asText(),"answers",List.of(Map.of("questionId",changeId,"value",4))),UUID.randomUUID(),200);
        assertThat(session.path("answerRevision").asText()).isNotEqualTo(oldRevision);
        mvc.perform(post(BASE+"/sessions/"+id+"/complete").header("Idempotency-Key",UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("version",session.path("version").asText(),"answerRevision",oldRevision,"tieAnswers",List.of()))))
                .andExpect(status().isConflict());
        session=mutate(post(BASE+"/sessions/"+id+"/complete"),completeBody(session,List.of()),UUID.randomUUID(),200);
        List<String> pending=new ArrayList<>();session.path("tieQuestions").forEach(q->pending.add(q.path("axisId").asText()));
        assertThat(pending).contains("FAMILIAR_NEW").doesNotContain("FOCUS_VARIETY").hasSize(5);
    }
    @Test void answerCommandReplayDoesNotAdvanceRevisionAndWrongBodyIsConflict() throws Exception {
        JsonNode session=mutate(post(BASE+"/sessions"),Map.of(),UUID.randomUUID(),201);String id=session.path("id").asText();
        UUID key=UUID.randomUUID();String question=session.path("questions").get(0).path("id").asText();
        var body=Map.of("version",session.path("version").asText(),"answers",List.of(Map.of("questionId",question,"value",3)));
        JsonNode saved=mutate(put(BASE+"/sessions/"+id+"/answers"),body,key,200);
        assertThat(mutate(put(BASE+"/sessions/"+id+"/answers"),body,key,200)).isEqualTo(saved);
        assertThat(resume(id)).isEqualTo(saved);
        mvc.perform(put(BASE+"/sessions/"+id+"/answers").header("Idempotency-Key",key.toString()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("version","0","answers",List.of(Map.of("questionId",question,"value",4))))))
                .andExpect(status().isConflict());
        assertThat(resume(id)).isEqualTo(saved);
    }
}
