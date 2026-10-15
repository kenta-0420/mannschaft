package com.mannschaft.app.translation;

import java.util.Optional;

/**
 * サポートする言語の一覧。
 */
public enum SupportedLanguage {

    JA("ja", "日本語"),
    EN("en", "English"),
    KO("ko", "한국어"),
    ZH("zh", "中文"),
    PT("pt", "Português"),
    ES("es", "Español"),
    DE("de", "Deutsch");

    private final String code;
    private final String displayName;

    SupportedLanguage(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * 言語コード文字列から {@link SupportedLanguage} を解決する。
     *
     * @param code 言語コード（例: "ja", "en"）
     * @return 一致する {@link SupportedLanguage}。存在しない場合は {@link Optional#empty()}
     */
    public static Optional<SupportedLanguage> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        for (SupportedLanguage lang : values()) {
            if (lang.code.equalsIgnoreCase(code)) {
                return Optional.of(lang);
            }
        }
        return Optional.empty();
    }
}
