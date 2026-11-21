package com.mannschaft.app.errorreport.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/** HTTPのnull/blank=400と区別し、純粋関数のnullable入力と解析境界を固定する。 */
class DiagnosticUrlTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\u0000", "/p\u0085?token=SYNTHETIC", "/\ufeffp?token=SYNTHETIC"})
    void nullable入力と制御文字は有効な固定pathへ倒す(String input) {
        String normalized = DiagnosticUrl.normalize(input);
        assertThat(normalized).isEqualTo("/invalid-url");
        assertThat(URI.create(normalized).getRawPath()).isEqualTo("/invalid-url");
        assertThat(DiagnosticUrl.normalize(normalized)).isEqualTo(normalized);
    }

    @Test
    void IPv6とencodedDelimiterを保ちながらuserinfoとqueryfragmentを除去する() {
        String normalized = DiagnosticUrl.normalize(
                "http://user:PASS@[::1]:8081/a%3Fpart%23anchor?token=QUERY#FRAGMENT");
        assertThat(normalized).isEqualTo("http://[::1]:8081/a%3Fpart%23anchor");
        assertThat(DiagnosticUrl.normalize(normalized)).isEqualTo(normalized);
        URI uri = URI.create(normalized);
        assertThat(uri.getRawPath()).isEqualTo("/a%3Fpart%23anchor");
        assertThat(uri.getRawUserInfo()).isNull();
        assertThat(uri.getRawQuery()).isNull();
        assertThat(uri.getRawFragment()).isNull();
    }
}
