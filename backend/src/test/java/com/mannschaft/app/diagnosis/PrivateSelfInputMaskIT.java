package com.mannschaft.app.diagnosis;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

/** 実HTTP出生/診断と実Spring例外Beanで入力の非露出を検証する。認可試験は別実Security ITへ分離する。 */
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class PrivateSelfInputMaskIT extends AbstractMySqlIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private GlobalExceptionHandler handler;
    private static final String MARKER = "synthetic-name-DOB-token-never-log";

    @Test void malformedPrivateJsonDoesNotEchoNameDateOrTokenInHttpOrLog() throws Exception {
        Long userId = users.saveAndFlush(UserEntity.builder().email(UUID.randomUUID() + "@mask.invalid")
                .lastName("試験").firstName("本人").displayName("本人").isSearchable(false)
                .locale("ja").timezone("Asia/Tokyo").status(UserEntity.UserStatus.ACTIVE).build()).getId();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> capture = new ListAppender<>(); capture.start(); logger.addAppender(capture);
        try {
            for (var request : List.of(put("/api/v1/me/birth-profile"), post("/api/v1/me/diagnoses/birth-style-results"))) {
                String body = "{\"lastName\":\"" + MARKER + "\",\"birthDate\":\"2099-99-99\",\"confirmationRef\":\"" + MARKER + "\",";
                String response = mvc.perform(request.header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"))
                        .andReturn().getResponse().getContentAsString();
                assertThat(response).doesNotContain(MARKER, "2099-99-99");
            }
            assertThat(capture.list).isNotEmpty();
            assertThat(capture.list).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain(MARKER, "2099-99-99");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(capture); capture.stop(); SecurityContextHolder.clearContext();
        }
    }

    @Test void malformedResultUuidDoesNotEchoOpaqueValueInHttpOrLog() throws Exception {
        Long userId = users.saveAndFlush(UserEntity.builder().email(UUID.randomUUID() + "@mask-id.invalid")
                .lastName("試験").firstName("本人").displayName("本人").isSearchable(false)
                .locale("ja").timezone("Asia/Tokyo").status(UserEntity.UserStatus.ACTIVE).build()).getId();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> capture = new ListAppender<>(); capture.start(); logger.addAppender(capture);
        try {
            String response = mvc.perform(get("/api/v1/me/diagnoses/results/" + MARKER))
                    .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain(MARKER);
            assertThat(capture.list).isNotEmpty().allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain(MARKER);
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally { logger.detachAppender(capture); capture.stop(); SecurityContextHolder.clearContext(); }
    }

    @Test void threePrivatePrefixesUseFixedParseClassificationAndOtherPathKeepsExistingBehavior() {
        // ranchの実HTTPはCORE合流後のE2Eで別検証。このcaseは同じ実Beanのprefix分類だけの証拠。
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> capture = new ListAppender<>(); capture.start(); logger.addAppender(capture);
        var original = RequestContextHolder.getRequestAttributes();
        try {
            for (String prefix : List.of("/api/v1/me/birth-profile", "/api/v1/me/diagnoses", "/api/v1/me/ranch")) {
                MockHttpServletRequest request = new MockHttpServletRequest("POST", prefix + "/synthetic");
                RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
                var response = handler.handleHttpMessageNotReadable(new HttpMessageNotReadableException(MARKER, new MockHttpInputMessage(new byte[0])));
                assertThat(response.getStatusCode().value()).isEqualTo(400);
                assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
            }
            assertThat(capture.list).hasSize(3);
            assertThat(capture.list).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).contains("classification=JSON_PARSE").doesNotContain(MARKER);
                assertThat(event.getThrowableProxy()).isNull();
            });
            capture.list.clear();
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest("POST", "/api/v1/unchanged-example")));
            var response = handler.handleHttpMessageNotReadable(new HttpMessageNotReadableException("existing-classification", new MockHttpInputMessage(new byte[0])));
            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(response.getHeaders().containsKey("Cache-Control")).isFalse();
            assertThat(capture.list).singleElement().satisfies(event -> assertThat(event.getFormattedMessage()).contains("Message not readable: existing-classification"));
        } finally {
            RequestContextHolder.setRequestAttributes(original); logger.detachAppender(capture); capture.stop();
        }
    }
}
