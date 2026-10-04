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
        assertThat(resume(id)).isEqualTo(session);String oldRevision=session.path("answerRevision").asText();
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
