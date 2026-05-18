package com.mannschaft.app.mail.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sesv2.model.MailFromDomainNotVerifiedException;
import software.amazon.awssdk.services.sesv2.model.MessageRejectedException;
import software.amazon.awssdk.services.sesv2.model.SendingPausedException;
import software.amazon.awssdk.services.sesv2.model.TooManyRequestsException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SesExceptionClassifier} の単体テスト。
 */
@DisplayName("SesExceptionClassifier 単体テスト")
class SesExceptionClassifierTest {

    private final SesExceptionClassifier classifier = new SesExceptionClassifier();

    @Test
    @DisplayName("MessageRejectedException は永久失敗")
    void messageRejected_isPermanent() {
        MessageRejectedException ex = MessageRejectedException.builder().message("rejected").build();
        assertThat(classifier.isPermanent(ex)).isTrue();
    }

    @Test
    @DisplayName("MailFromDomainNotVerifiedException は永久失敗")
    void mailFromDomainNotVerified_isPermanent() {
        MailFromDomainNotVerifiedException ex = MailFromDomainNotVerifiedException.builder().message("nv").build();
        assertThat(classifier.isPermanent(ex)).isTrue();
    }

    @Test
    @DisplayName("SendingPausedException は永久失敗")
    void sendingPaused_isPermanent() {
        SendingPausedException ex = SendingPausedException.builder().message("sp").build();
        assertThat(classifier.isPermanent(ex)).isTrue();
    }

    @Test
    @DisplayName("TooManyRequestsException は一時失敗")
    void tooManyRequests_isTransient() {
        TooManyRequestsException ex = TooManyRequestsException.builder().message("throttle").build();
        assertThat(classifier.isPermanent(ex)).isFalse();
    }

    @Test
    @DisplayName("SdkClientException は一時失敗")
    void sdkClient_isTransient() {
        SdkClientException ex = SdkClientException.builder().message("network").build();
        assertThat(classifier.isPermanent(ex)).isFalse();
    }

    @Test
    @DisplayName("一般 RuntimeException は一時失敗扱い")
    void runtimeException_isTransient() {
        assertThat(classifier.isPermanent(new RuntimeException("boom"))).isFalse();
    }

    @Test
    @DisplayName("null は一時失敗扱い (false)")
    void nullThrowable_isTransient() {
        assertThat(classifier.isPermanent(null)).isFalse();
    }
}
