package com.mannschaft.app.role;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** DB・Spring・Testcontainers を起動せず、C7 の失敗時診断の安全性だけを検証する。 */
class InviteAcceptanceRowWaitDiagnosticsTest {

    @Test
    @DisplayName("診断採取・出力が失敗しても元のAssertionErrorを同一instanceで保持し秘密を出さない")
    void diagnosticFailurePreservesOriginalAssertion() {
        AssertionError original = new AssertionError("元の10秒失敗");
        String secret = "token=secret-sentinel jdbc:mysql://private-host secret-password";
        AtomicReference<String> output = new AtomicReference<>();

        AssertionError actual = InviteAcceptanceItSupport.RowWaitDiagnostics.preserveFailure(original,
                () -> { throw new IllegalStateException(secret); }, output::set);

        assertThat(actual).isSameAs(original).hasMessage("元の10秒失敗").hasNoCause();
        assertThat(actual.getSuppressed()).isEmpty();
        assertThat(output.get()).contains("IllegalStateException").doesNotContain(secret, "private-host", "secret-password");
        assertThat(InviteAcceptanceItSupport.RowWaitDiagnostics.preserveFailure(original, () -> "safe",
                value -> { throw new IllegalStateException(secret); })).isSameAs(original);
    }

    @Test
    @DisplayName("SQLが返す4分類だけを許可し想定外のデータ値は出力しない")
    void lockDataProjectionRejectsUnexpectedValues() {
        for (String category : new String[] {"NULL", "PRIMARY_TARGET", "PRIMARY_OTHER", "NONPRIMARY_REDACTED"}) {
            assertThat(InviteAcceptanceItSupport.RowWaitDiagnostics.dataClass(category)).isEqualTo(category);
        }
        assertThat(InviteAcceptanceItSupport.RowWaitDiagnostics.dataClass("secret-token-sentinel")).isEqualTo("UNKNOWN");
        assertThat(InviteAcceptanceItSupport.RowWaitDiagnostics.dataClass(null)).isEqualTo("UNKNOWN");
        assertThat(InviteAcceptanceItSupport.RowWaitDiagnostics.label("token='secret-sentinel'")).isEqualTo("REDACTED");
    }

    @Test
    @DisplayName("日本語と補助文字を含む出力も改行込み8KiB以内でUTF8境界を保つ")
    void reportIsBoundedInUtf8Bytes() {
        String original = "診断😀".repeat(3000);
        AtomicReference<String> output = new AtomicReference<>();
        AssertionError failure = new AssertionError("timeout");

        assertThat(InviteAcceptanceItSupport.RowWaitDiagnostics.preserveFailure(failure, () -> original, output::set))
                .isSameAs(failure);

        byte[] bytes = output.get().getBytes(StandardCharsets.UTF_8);
        assertThat(bytes.length + 2).isLessThanOrEqualTo(8192);
        assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo(output.get());
        assertThat(output.get()).endsWith("\n[TRUNCATED]");
        assertThat(original).startsWith(output.get().substring(0, output.get().length() - "\n[TRUNCATED]".length()));
        assertThat(InviteAcceptanceItSupport.RowWaitDiagnostics.bounded("safe")).isEqualTo("safe");
    }
}
