package com.mannschaft.app.loyalty;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.GlobalExceptionHandler;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 設計§9のstatus/codeを実MVC→実GlobalExceptionHandlerで固定し、既定400への置換を防ぐ。 */
class LoyaltyErrorStatusHttpTest {
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ErrorProbe())
            .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource())).build();

    @ParameterizedTest
    @CsvSource({"LOYALTY_001,404", "LOYALTY_002,403", "LOYALTY_003,403", "LOYALTY_004,400",
            "LOYALTY_005,409", "LOYALTY_006,409", "LOYALTY_007,409", "LOYALTY_008,400",
            "LOYALTY_009,400", "LOYALTY_010,400", "LOYALTY_011,404", "LOYALTY_012,429",
            "LOYALTY_013,409", "LOYALTY_014,409", "LOYALTY_015,403"})
    void 全15業務コードのHTTP写像が設計と一致する(String code, int expectedStatus) throws Exception {
        mvc.perform(get("/loyalty-error-probe/" + code))
                .andExpect(status().is(expectedStatus)).andExpect(jsonPath("$.error.code").value(code));
    }

    @RestController
    static class ErrorProbe {
        @GetMapping("/loyalty-error-probe/{code}")
        void reject(@PathVariable("code") String code) {
            throw new BusinessException(LoyaltyErrorCode.valueOf(code));
        }
    }
}
