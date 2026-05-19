package com.mannschaft.app.gdpr.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AccountPurgedEvent 単体テスト")
class AccountPurgedEventTest {

    @Test
    @DisplayName("正常系: userId / emailHash が getter から取り出せる")
    void 正常_getter() {
        AccountPurgedEvent event = new AccountPurgedEvent(123L, "abcdef");

        assertThat(event.getUserId()).isEqualTo(123L);
        assertThat(event.getEmailHash()).isEqualTo("abcdef");
        assertThat(event.getOccurredAt()).isNotNull();
    }

    @Test
    @DisplayName("正常系: emailHash が null でも構築できる（監査ログ突合用、必須ではない）")
    void 正常_emailHashNull許容() {
        AccountPurgedEvent event = new AccountPurgedEvent(456L, null);

        assertThat(event.getUserId()).isEqualTo(456L);
        assertThat(event.getEmailHash()).isNull();
    }

    @Test
    @DisplayName("異常系: userId が null だと IllegalArgumentException")
    void 異常_userIdNull() {
        assertThatThrownBy(() -> new AccountPurgedEvent(null, "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");
    }
}
