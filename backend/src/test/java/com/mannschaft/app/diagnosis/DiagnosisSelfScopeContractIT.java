package com.mannschaft.app.diagnosis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AC52/53/54/55/57/62/63/69: MySQL経由の本人診断・出生情報の先行試練。
 * DiagnosisController#start DiagnosisController#getSession DiagnosisController#answer
 * DiagnosisController#complete DiagnosisController#cancel DiagnosisController#listResults
 * DiagnosisController#getResult DiagnosisController#birthResult
 * BirthProfileController#get BirthProfileController#update BirthProfileController#confirm の本人束縛を実HTTPで検証する。
 * HTTPフィルターの401/CSRFは別security試練へ分離し、この金型はprincipal境界を対象にする。
 */
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class DiagnosisSelfScopeContractIT extends AbstractMySqlIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRepository users;
    private Long owner;
    private Long other;
    private static final String BASE = "/api/v1/me/diagnoses";

    @BeforeEach void setup() {
        owner = user(); other = user();
        auth(owner);
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    private Long user() {
        return users.saveAndFlush(UserEntity.builder().email(UUID.randomUUID()+"@diagnosis.invalid")
                .lastName("山田").firstName("健太").lastNameKana("ヤマダ").firstNameKana("ケンタ")
                .birthDate("1990-01-22").displayName("診断試験").isSearchable(false)
                .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo").build()).getId();
    }
    private void auth(Long id) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(id.toString(), null, List.of()));
    }
    private JsonNode mutation(MockHttpServletRequestBuilder req, Object body, String key, int code) throws Exception {
        return mapper.readTree(mvc.perform(req.header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body))).andExpect(status().is(code))
                .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString()).path("data");
    }
    private JsonNode start() throws Exception {
        return mutation(post(BASE+"/sessions"), Map.of(), UUID.randomUUID().toString(), 201);
    }
    private List<Map<String,Object>> answers(JsonNode session, int value) {
        List<Map<String,Object>> result=new ArrayList<>();
        session.path("questions").forEach(q -> result.add(Map.of("questionId",q.path("id").asText(),"value",value)));
        return result;
    }
    @Test @DisplayName("IT52/69: 24問中立回答はtie6、本人選択で一結果、再送は不変")
    void 六軸同点を本人で確定し成功再送を保持() throws Exception {
        JsonNode s=start();
        assertThat(s.path("questions").size()).isEqualTo(24);
        assertThat(s.path("answers").size()).isZero();
        String id=s.path("id").asText();
        s=mutation(put(BASE+"/sessions/"+id+"/answers"),Map.of("version",s.path("version").asText(),"answers",answers(s,3)), UUID.randomUUID().toString(),200);
        s=mutation(post(BASE+"/sessions/"+id+"/complete"),Map.of("version",s.path("version").asText(),"answerRevision",s.path("answerRevision").asText(),"tieAnswers",List.of()),UUID.randomUUID().toString(),200);
        assertThat(s.path("status").asText()).isEqualTo("TIE_BREAK_REQUIRED");
        assertThat(s.path("tieQuestions").size()).isEqualTo(6);
        List<Map<String,Object>> choices=new ArrayList<>();
        s.path("tieQuestions").forEach(q -> choices.add(Map.of("axisId",q.path("axisId").asText(),"value",0)));
        Map<String,Object> body=Map.of("version",s.path("version").asText(),"answerRevision",s.path("answerRevision").asText(),"tieAnswers",choices);
        String key=UUID.randomUUID().toString();
        JsonNode completed=mutation(post(BASE+"/sessions/"+id+"/complete"),body,key,200);
        assertThat(completed.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(completed.path("resultId").asText()).isNotBlank();
        assertThat(mutation(post(BASE+"/sessions/"+id+"/complete"),body,key,200)).isEqualTo(completed);
        String resultId=completed.path("resultId").asText();
        mvc.perform(get(BASE+"/results/"+resultId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.typeCode").value("000000"))
                .andExpect(jsonPath("$.data.birthDate").doesNotExist()).andExpect(jsonPath("$.data.answers").doesNotExist());
        auth(other);
        mvc.perform(get(BASE+"/sessions/"+id)).andExpect(status().isNotFound());
        mvc.perform(get(BASE+"/results/"+resultId)).andExpect(status().isNotFound());
    }
    @Test @DisplayName("IT52/69: 回答型・特権field・cancel後完了を拒否")
    void 不正回答と取消後の完了を拒否() throws Exception {
        JsonNode s=start();String id=s.path("id").asText();
        for (String value : List.of("0","6","null","true","1.5")) {
            mvc.perform(put(BASE+"/sessions/"+id+"/answers").header("Idempotency-Key",UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"version\":\"0\",\"answers\":[{\"questionId\":\"Q01\",\"value\":"+value+"}]}"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post(BASE+"/sessions").header("Idempotency-Key",UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"typeCode\":\"111111\",\"userId\":1}" )).andExpect(status().isBadRequest());
        JsonNode c=mutation(post(BASE+"/sessions/"+id+"/cancel"),Map.of("version",s.path("version").asText()),UUID.randomUUID().toString(),200);
        mvc.perform(post(BASE+"/sessions/"+id+"/complete").header("Idempotency-Key",UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("version",c.path("version").asText(),"answerRevision",c.path("answerRevision").asText(),"tieAnswers",List.of()))))
                .andExpect(status().isConflict());
    }
    @Test @DisplayName("IT55/57/63: ranch不参加で本人出生結果、プロフィール変更後同keyは旧結果")
    void プロフィール変更後の成功再送は保存済み結果() throws Exception {
        JsonNode p=mapper.readTree(mvc.perform(get("/api/v1/me/birth-profile")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString()).path("data");
        JsonNode confirmation=mutation(post("/api/v1/me/birth-profile/confirmations"),Map.of("revision",p.path("revision").asText(),"useConfirmed",true),UUID.randomUUID().toString(),201);
        Map<String,Object> request=Map.of("confirmationRef",confirmation.path("confirmationRef").asText());
        String key=UUID.randomUUID().toString();
        JsonNode original=mutation(post(BASE+"/birth-style-results"),request,key,201);
        assertThat(original.path("numberSummary").path("lifePathNumber").asInt()).isEqualTo(6);
        mutation(put("/api/v1/me/birth-profile"),Map.of("revision",p.path("revision").asText(),"lastName","山田","firstName","花子","lastNameKana","ヤマダ","firstNameKana","ハナコ","birthDate","1991-02-23"),UUID.randomUUID().toString(),200);
        assertThat(mutation(post(BASE+"/birth-style-results"),request,key,201)).isEqualTo(original);
        mvc.perform(post(BASE+"/birth-style-results").header("Idempotency-Key",UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request))).andExpect(status().isConflict());
        mvc.perform(get(BASE+"/results")).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
        auth(other);
        mvc.perform(get(BASE+"/results")).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(post(BASE+"/birth-style-results").header("Idempotency-Key",UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request))).andExpect(status().isNotFound());
    }
    @Test @DisplayName("出生プロフィールの生年月日型・日付・余分なfieldは値を露出せず400")
    void 出生情報の不正型を拒否() throws Exception {
        for(String date : List.of("null", "true", "19900122", "{}", "[]", "\"1990-02-30\"", "\"1990-1-2\"")) {
            String body="{\"revision\":\"0\",\"lastName\":\"山田\",\"firstName\":\"健太\","
                    +"\"lastNameKana\":\"ヤマダ\",\"firstNameKana\":\"ケンタ\",\"birthDate\":"+date+"}";
            String response=mvc.perform(put("/api/v1/me/birth-profile").header("Idempotency-Key",UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain("山田", "健太", "1990-02-30", "1990-1-2");
        }
        mvc.perform(put("/api/v1/me/birth-profile").header("Idempotency-Key",UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":\"0\",\"userId\":123}"))
                .andExpect(status().isBadRequest());
    }

}
