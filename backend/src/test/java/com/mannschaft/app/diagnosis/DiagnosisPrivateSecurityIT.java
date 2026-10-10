package com.mannschaft.app.diagnosis;

import com.mannschaft.app.admin.filter.AdminImpersonationFilter;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 実 SecurityConfig/AdminImpersonationFilter 経由で本人の私有情報を保護する。 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class DiagnosisPrivateSecurityIT extends AbstractMySqlIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    private String owner;
    @BeforeEach void setup() {
        owner=users.saveAndFlush(UserEntity.builder().email(UUID.randomUUID()+"@diagnosis.invalid")
                .lastName("山田").firstName("健太").lastNameKana("ヤマダ").firstNameKana("ケンタ")
                .birthDate("1990-01-22").displayName("本人").isSearchable(false)
                .locale("ja").timezone("Asia/Tokyo").status(UserEntity.UserStatus.ACTIVE).build()).getId().toString();
    }
    @Test @DisplayName("未認証では出生情報も診断履歴も401")
    void 未認証は拒否() throws Exception {
        for(String path : new String[]{"/api/v1/me/birth-profile","/api/v1/me/diagnoses/results","/api/v1/me/diagnoses/sessions/pending"})
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
    }
    @Test @DisplayName("管理者変身は本人私有API403、本人の管理者利用は許可")
    void 管理者変身を拒否() throws Exception {
        for(String path : new String[]{"/api/v1/me/birth-profile","/api/v1/me/diagnoses/results","/api/v1/me/diagnoses/sessions/pending"}) {
            mvc.perform(get(path).with(user("999999999").roles("SYSTEM_ADMIN"))
                    .header(AdminImpersonationFilter.HEADER_IMPERSONATE,owner)).andExpect(status().isForbidden());
            mvc.perform(get(path).with(user(owner).roles("SYSTEM_ADMIN"))).andExpect(status().isOk());
        }
    }
    @Test @DisplayName("一般ユーザーの偽装headerは403、通常本人閲覧は許可")
    void 一般偽装ヘッダーを拒否() throws Exception {
        mvc.perform(get("/api/v1/me/birth-profile").with(user(owner).roles("MEMBER"))
                .header(AdminImpersonationFilter.HEADER_IMPERSONATE,owner)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/me/birth-profile").with(user(owner).roles("MEMBER"))).andExpect(status().isOk());
    }
}
