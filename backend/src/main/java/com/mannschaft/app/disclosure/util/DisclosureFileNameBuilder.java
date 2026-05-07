package com.mannschaft.app.disclosure.util;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * 重要事項説明書（参考）出力ファイル名生成ユーティリティ。
 *
 * <p>命名規約: {@code {yyyyMMdd}_重要事項説明書(参考)_{物件名}_{部屋番号}.{ext}}
 *
 * <p>例: {@code 20260507_重要事項説明書(参考)_サンプルマンション_301.pdf}
 *
 * <p>{@link com.mannschaft.app.common.pdf.PdfFileNameBuilder} のパターンを踏襲。
 */
public class DisclosureFileNameBuilder {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String FORBIDDEN_CHARS_REGEX = "[/\\\\:*?\"<>|]";
    private static final int MAX_LENGTH = 120;
    private static final String DOC_TYPE = "重要事項説明書(参考)";

    private final String extension;
    private LocalDate date;
    private String propertyName;
    private String unitNumber;

    private DisclosureFileNameBuilder(String extension) {
        this.extension = Objects.requireNonNull(extension, "extension は必須です");
    }

    /**
     * @param extension 拡張子（"pdf", "xlsx", "docx" 等、ドット不要）
     */
    public static DisclosureFileNameBuilder of(String extension) {
        return new DisclosureFileNameBuilder(extension);
    }

    public DisclosureFileNameBuilder date(LocalDate date) {
        this.date = Objects.requireNonNull(date, "date は必須です");
        return this;
    }

    public DisclosureFileNameBuilder propertyName(String propertyName) {
        this.propertyName = propertyName;
        return this;
    }

    public DisclosureFileNameBuilder unitNumber(String unitNumber) {
        this.unitNumber = unitNumber;
        return this;
    }

    public String build() {
        Objects.requireNonNull(date, "date は必須です。date() を呼び出してください");

        StringBuilder sb = new StringBuilder();
        sb.append(date.format(DATE_FORMAT));
        sb.append("_").append(DOC_TYPE);

        if (propertyName != null && !propertyName.isBlank()) {
            sb.append("_").append(sanitize(propertyName));
        }
        if (unitNumber != null && !unitNumber.isBlank()) {
            sb.append("_").append(sanitize(unitNumber));
        }
        sb.append(".").append(extension);

        String result = sb.toString();
        int extLen = extension.length() + 1;
        if (result.length() > MAX_LENGTH) {
            result = result.substring(0, MAX_LENGTH - extLen) + "." + extension;
        }
        return result;
    }

    /** RFC 5987 UTF-8 エンコード済みファイル名（Content-Disposition 用）。 */
    public String buildEncoded() {
        String raw = build();
        return URLEncoder.encode(raw, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String sanitize(String input) {
        return input.replaceAll(FORBIDDEN_CHARS_REGEX, "_");
    }
}
