package com.mannschaft.app.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mannschaft.app.common.timezone.TimezoneContextHolder;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.config.jackson.LocalDateTimeTimezoneSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-023 の非JST CIで、アプリ層の基準ゾーンが JVM 既定ゾーンから独立していることを固定する。
 *
 * <p>通常スイートは従来どおり JST で実行する。専用CIは
 * {@code -Ptest.timezone=America/Los_Angeles} を指定し、この同じ契約を非JST JVMで検証する。
 * 対象は明示ゾーン化済みの共通基盤だけとし、返済ロットごとに検証範囲を広げる。</p>
 */
@DisplayName("CMP-023 非JSTタイムゾーン契約")
class NonJstTimezoneContractTest {

    @AfterEach
    void tearDown() {
        TimezoneContextHolder.clear();
    }

    @Test
    @DisplayName("Gradleで指定したタイムゾーンがテストJVMへ届く")
    void 指定したタイムゾーンでテストJVMが起動する() {
        String expected = System.getProperty("mannschaft.test.expected-jvm-timezone");

        assertThat(expected)
                .as("build.gradle.kts が期待タイムゾーンをテストJVMへ渡していること")
                .isNotBlank();
        assertThat(ZoneId.systemDefault())
                .as("-Ptest.timezone の値が user.timezone としてテストJVMへ反映されること")
                .isEqualTo(ZoneId.of(expected));
    }

    @Test
    @DisplayName("壁時計ClockはJVM既定ゾーンではなく明示したSERVER_ZONEを使う")
    void 壁時計Clockは明示した基準ゾーンを使う() {
        assertThat(new ClockConfig().wallClock().getZone())
                .isEqualTo(UserZoneLocalDateTimeParser.SERVER_ZONE);
    }

    @Test
    @DisplayName("日時JSON出力はJVM既定ゾーンに依存せずSERVER_ZONEからUTCへ変換する")
    void 日時JSON出力は明示した基準ゾーンを使う() throws Exception {
        SimpleModule timezoneModule = new SimpleModule("TimezoneModule");
        timezoneModule.addSerializer(LocalDateTime.class, new LocalDateTimeTimezoneSerializer());
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .registerModule(timezoneModule)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        TimezoneContextHolder.set(ZoneId.of("UTC"));

        String json = objectMapper.writeValueAsString(LocalDateTime.of(2026, 5, 22, 9, 15, 20));

        assertThat(json).isEqualTo("\"2026-05-22T00:15:20Z\"");
    }
}
