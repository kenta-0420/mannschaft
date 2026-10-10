package com.mannschaft.app.diagnosis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 実GuardとMySQLで保存済み未承認セッションを検証する。filter認可の受入証拠には使用しない。 */
@AutoConfigureMockMvc(addFilters=false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class DiagnosisSavedDraftMutationGateIT extends AbstractMySqlIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRepository users;
    @Autowired private ConfigurableEnvironment environment;
    private static final String BASE="/api/v1/me/diagnoses/sessions";

    private JsonNode write(MockHttpServletRequestBuilder request,Object body,UUID key,int expected) throws Exception {
        return mapper.readTree(mvc.perform(request.header("Idempotency-Key",key.toString())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString()).path("data");
    }
    private JsonNode read(String id)throws Exception {
        return mapper.readTree(mvc.perform(get(BASE+"/"+id)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");
    }
    @Test void productionRejectsNewDraftWritesWithoutChangingStateButReturnsSavedSuccess() throws Exception {
        String[] originalProfiles=environment.getActiveProfiles().clone();
        var originalAuthentication=SecurityContextHolder.getContext().getAuthentication();
        try {
            environment.setActiveProfiles("test");
            Long owner=users.saveAndFlush(UserEntity.builder().email(UUID.randomUUID()+"@saved-draft.invalid")
                    .lastName("診断").firstName("本人").displayName("本人").isSearchable(false)
                    .locale("ja").timezone("Asia/Tokyo").status(UserEntity.UserStatus.ACTIVE).build()).getId();
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(owner.toString(),null,List.of()));
            UUID startKey=UUID.randomUUID();
            JsonNode started=write(post(BASE),Map.of(),startKey,201);String id=started.path("id").asText();
            var answers=new ArrayList<Map<String,Object>>();
            started.path("questions").forEach(q->answers.add(Map.of("questionId",q.path("id").asText(),"value",3)));
            UUID answerKey=UUID.randomUUID();var answerBody=Map.of("version",started.path("version").asText(),"answers",answers);
            JsonNode answered=write(put(BASE+"/"+id+"/answers"),answerBody,answerKey,200);
            var ties=new ArrayList<Map<String,Object>>();
            for(String axis:new String[]{"FAMILIAR_NEW","FOCUS_VARIETY","SPONTANEOUS_PLAN","SOLO_TOGETHER","EXPRESSION","NOTICE"})
                ties.add(Map.of("axisId",axis,"value",0));
            var completeBody=Map.of("version",answered.path("version").asText(),"answerRevision",answered.path("answerRevision").asText(),"tieAnswers",ties);
            environment.setActiveProfiles("test","prod");
            write(put(BASE+"/"+id+"/answers"),Map.of("version",answered.path("version").asText(),
                    "answers",List.of(Map.of("questionId",started.path("questions").get(0).path("id").asText(),"value",4))),UUID.randomUUID(),503);
            write(post(BASE+"/"+id+"/complete"),completeBody,UUID.randomUUID(),503);
            assertThat(read(id)).isEqualTo(answered);
            assertThat(write(post(BASE),Map.of(),startKey,201)).isEqualTo(started);
            assertThat(write(put(BASE+"/"+id+"/answers"),answerBody,answerKey,200)).isEqualTo(answered);
            environment.setActiveProfiles("test");
            UUID completeKey=UUID.randomUUID();JsonNode completed=write(post(BASE+"/"+id+"/complete"),completeBody,completeKey,200);
            assertThat(completed.path("status").asText()).isEqualTo("COMPLETED");
            environment.setActiveProfiles("production","test");
            assertThat(write(post(BASE+"/"+id+"/complete"),completeBody,completeKey,200)).isEqualTo(completed);
            assertThat(read(id)).isEqualTo(completed);
        } finally {
            environment.setActiveProfiles(originalProfiles);
            SecurityContextHolder.getContext().setAuthentication(originalAuthentication);
        }
    }
}
