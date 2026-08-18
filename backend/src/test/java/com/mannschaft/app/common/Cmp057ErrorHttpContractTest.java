package com.mannschaft.app.common;

import com.mannschaft.app.membership.MembershipErrorCode;
import com.mannschaft.app.schedule.ScheduleErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CMP-057 の業務例外から HTTP status/envelope への契約。 */
class Cmp057ErrorHttpContractTest {

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
            .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
            .build();

    @Test
    void invalid_input_codes_are_http_400() throws Exception {
        assertStatusAndCode("/cmp052/invalid-target", "SCHEDULE_093", HttpStatus.BAD_REQUEST);
        assertStatusAndCode("/cmp052/invalid-color", "MEMBERSHIP_023", HttpStatus.BAD_REQUEST);
    }

    @Test
    void missing_member_codes_are_http_404() throws Exception {
        assertStatusAndCode("/cmp052/missing-target", "SCHEDULE_094", HttpStatus.NOT_FOUND);
        assertStatusAndCode("/cmp052/missing-member", "MEMBERSHIP_024", HttpStatus.NOT_FOUND);
    }

    private void assertStatusAndCode(String path, String code, HttpStatus status) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().is(status.value()))
                .andExpect(jsonPath("$.error.code").value(code));
    }

    @RestController
    static class ProbeController {
        @GetMapping("/cmp052/invalid-target")
        void invalidTarget() {
            throw new BusinessException(ScheduleErrorCode.INVALID_TARGET_SELECTION);
        }

        @GetMapping("/cmp052/missing-target")
        void missingTarget() {
            throw new BusinessException(ScheduleErrorCode.SCHEDULE_TARGET_MEMBER_NOT_FOUND);
        }

        @GetMapping("/cmp052/invalid-color")
        void invalidColor() {
            throw new BusinessException(MembershipErrorCode.MEMBERSHIP_023);
        }

        @GetMapping("/cmp052/missing-member")
        void missingMember() {
            throw new BusinessException(MembershipErrorCode.MEMBERSHIP_024);
        }
    }
}
