package com.mannschaft.app.admin.systemlog;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * ログテキスト内の PII（個人識別情報）をマスキングするユーティリティ。
 * スロークエリログや SSR エラーログに含まれる機密情報を保護する。
 */
@Component
public class SystemLogPiiMasker {

    /** メールアドレスの正規表現パターン */
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("\\b[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,7}\\b");

    /** SQL の機密カラム値をマスキングするパターン。キーワードの後の引用符付き値を対象とする */
    private static final Pattern SQL_VALUE_PATTERN =
            Pattern.compile(
                    "(?i)(email|password|phone|birth|address|token|secret)\\s*=\\s*'[^']*'");

    /**
     * テキスト内の PII をマスキングする。
     * <ul>
     *   <li>メールアドレス → {@code ***@***.***}</li>
     *   <li>SQL の機密カラム値 → {@code keyword='***'}</li>
     * </ul>
     *
     * @param text マスキング対象のテキスト
     * @return マスキング後のテキスト。null の場合は null を返す
     */
    public String mask(String text) {
        if (text == null) {
            return null;
        }
        // まず SQL の値をマスキング（メールがSQL値として含まれている場合も対応）
        String masked = SQL_VALUE_PATTERN.matcher(text)
                .replaceAll(m -> m.group(1) + "='***'");
        // 次にメールアドレスをマスキング
        masked = EMAIL_PATTERN.matcher(masked).replaceAll("***@***.***");
        return masked;
    }
}
