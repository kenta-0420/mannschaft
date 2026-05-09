package com.mannschaft.app.admin.systemlog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SystemLogPiiMasker} の単体テスト。
 */
@DisplayName("SystemLogPiiMasker 単体テスト")
class SystemLogPiiMaskerTest {

    private SystemLogPiiMasker masker;

    @BeforeEach
    void setUp() {
        masker = new SystemLogPiiMasker();
    }

    @Test
    @DisplayName("メールアドレスをマスキングする")
    void mask_email() {
        String input = "ユーザーのメール: user@example.com が登録されました";
        String result = masker.mask(input);
        assertThat(result).isEqualTo("ユーザーのメール: ***@***.*** が登録されました");
    }

    @Test
    @DisplayName("複数のメールアドレスをすべてマスキングする")
    void mask_multipleEmails() {
        String input = "from: sender@example.com, to: receiver@company.co.jp";
        String result = masker.mask(input);
        // 両方のメールアドレスがマスキングされていることを確認
        assertThat(result).doesNotContain("sender@example.com");
        assertThat(result).doesNotContain("receiver@company.co.jp");
        assertThat(result).contains("***@***.***");
    }

    @Test
    @DisplayName("SQL の email カラム値をマスキングする")
    void mask_sqlEmailValue() {
        String input = "UPDATE users SET email='user@example.com' WHERE id=1";
        String result = masker.mask(input);
        assertThat(result).doesNotContain("user@example.com");
        assertThat(result).contains("email='***'");
    }

    @Test
    @DisplayName("SQL の password カラム値をマスキングする")
    void mask_sqlPasswordValue() {
        // password=value 形式（UPDATE/SET 文）をマスキングする
        String input = "UPDATE users SET password='secret123' WHERE id=1";
        String result = masker.mask(input);
        assertThat(result).contains("password='***'");
        assertThat(result).doesNotContain("secret123");
    }

    @Test
    @DisplayName("SQL の phone カラム値をマスキングする")
    void mask_sqlPhoneValue() {
        String input = "WHERE phone='090-1234-5678'";
        String result = masker.mask(input);
        assertThat(result).contains("phone='***'");
        assertThat(result).doesNotContain("090-1234-5678");
    }

    @Test
    @DisplayName("SQL の token カラム値をマスキングする")
    void mask_sqlTokenValue() {
        String input = "SET token='abc123xyz' WHERE user_id=42";
        String result = masker.mask(input);
        assertThat(result).contains("token='***'");
        assertThat(result).doesNotContain("abc123xyz");
    }

    @Test
    @DisplayName("大文字小文字を区別せず SQL の機密カラム値をマスキングする")
    void mask_sqlCaseInsensitive() {
        String input = "SET PASSWORD='P@ssw0rd!'";
        String result = masker.mask(input);
        assertThat(result).contains("PASSWORD='***'");
        assertThat(result).doesNotContain("P@ssw0rd!");
    }

    @Test
    @DisplayName("マスキング対象外のテキストは変更しない")
    void mask_nonSensitiveText() {
        String input = "SELECT id, name FROM users WHERE id = 42 LIMIT 10";
        String result = masker.mask(input);
        assertThat(result).isEqualTo(input);
    }

    @Test
    @DisplayName("null を渡すと null を返す")
    void mask_null() {
        assertThat(masker.mask(null)).isNull();
    }

    @Test
    @DisplayName("空文字列は空文字列を返す")
    void mask_emptyString() {
        assertThat(masker.mask("")).isEqualTo("");
    }

    @Test
    @DisplayName("スロークエリログの実際のエントリをマスキングできる")
    void mask_slowQueryLogEntry() {
        String input = """
                # Time: 2026-05-09T01:00:00.000000Z
                # User@Host: mannschaft[mannschaft] @ localhost []
                # Query_time: 2.5 Lock_time: 0.001 Rows_sent: 1
                SET timestamp=1620000000;
                UPDATE users SET email='test@example.com', password='secret' WHERE id=1;
                """;
        String result = masker.mask(input);
        assertThat(result).doesNotContain("test@example.com");
        assertThat(result).doesNotContain("secret");
        assertThat(result).contains("email='***'");
        assertThat(result).contains("password='***'");
    }
}
