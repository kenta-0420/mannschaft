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
