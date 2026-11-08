package com.mannschaft.app.loyalty;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.loyalty.dto.ApproveRequest;
import com.mannschaft.app.loyalty.dto.SelfAwardBootstrapRequest;
import com.mannschaft.app.loyalty.dto.StaffAwardRequest;
import com.mannschaft.app.loyalty.dto.UpdateProgramRequest;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 機密入力のMVC binding→実GlobalExceptionHandler→実ログを検査する。
 * Probeは業務や認可を代行せず、本番DTOのパース失敗だけを起こす。Service/Repository mockは使わない。
 * 本番EPの実認可と実DBはLoyaltyHttpContractITが担当する。
 */
class LoyaltyInputPrivacyHttpTest {
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new BindingProbe())
            .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource())).build();

    @Test
    void AC18_UUID不正に混ぜたrawQRをログ応答へコピーしない() throws Exception {
        String secret = "PRIVATE-QR-UUID-LEAK-PROBE";
        rejected("/api/v1/teams/1/loyalty/awards", "{\"qrToken\":\"" + secret
                + "\",\"ruleId\":\"" + secret + "\",\"expectedRuleVersion\":0,\"expectedProgramVersion\":0}", secret, false);
    }

    @Test
    void AC18_不正JSONに混ぜた受付tokenをログ応答へコピーしない() throws Exception {
        String secret = "PRIVATE-LOCATION-TOKEN-LEAK-PROBE";
        rejected("/api/v1/loyalty/self-award-bootstrap/me", "{\"locationToken\":\"" + secret + "\" BROKEN}", secret, false);
    }

    @Test
    void AC18_数値型不正に混ぜた照合コードをログ応答へコピーしない() throws Exception {
        String secret = "ABCDEFGH";
        rejected("/api/v1/teams/1/loyalty/award-requests/00000000-0000-0000-0000-000000000001/approve",
                "{\"verificationCode\":\"" + secret + "\",\"expectedRequestVersion\":\"" + secret + "\"}", secret, false);
    }

    @Test
    void AC18_enum不正へ混ぜた入力もloyalty境界で値を記録しない() throws Exception {
        String secret = "PRIVATE-QR-ENUM-LEAK-PROBE";
        rejected("/api/v1/teams/1/loyalty/settings", "{\"isEnabled\":true,\"targetAudience\":\"" + secret
                + "\",\"selfAwardMode\":\"AUTO\"}", secret, true);
    }

    private void rejected(String path, String body, String secret, boolean put) throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> capture = new ListAppender<>();
        capture.start(); logger.addAppender(capture);
        try {
            var result = mvc.perform((put ? put(path) : post(path)).contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("COMMON_001"))
                    .andReturn();
            assertThat(capture.list).extracting(ILoggingEvent::getFormattedMessage)
                    .allSatisfy(message -> assertThat(message).doesNotContain(secret));
            assertThat(result.getResponse().getContentAsString()).doesNotContain(secret);
            assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        } finally {
            logger.detachAppender(capture); capture.stop();
        }
    }

    @RestController
    static class BindingProbe {
        @PostMapping("/api/v1/teams/{team}/loyalty/awards")
        void award(@RequestBody StaffAwardRequest body) { }
        @PostMapping("/api/v1/loyalty/self-award-bootstrap/me")
        void bootstrap(@RequestBody SelfAwardBootstrapRequest body) { }
        @PostMapping("/api/v1/teams/{team}/loyalty/award-requests/{request}/approve")
        void approve(@RequestBody ApproveRequest body) { }
        @PutMapping("/api/v1/teams/{team}/loyalty/settings")
        void settings(@RequestBody UpdateProgramRequest body) { }
    }
}
