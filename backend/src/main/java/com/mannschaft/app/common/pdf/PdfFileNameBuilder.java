package com.mannschaft.app.common.pdf;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * PDF ファイル名生成ユーティリティ。
 * 命名規約: {yyyyMMdd}_{文書種別}_{識別名}.pdf
 */
public class PdfFileNameBuilder {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String FORBIDDEN_CHARS_REGEX = "[/\\\\:*?\"<>|]";
    private static final int MAX_LENGTH = 100;

    private final String documentType;
    private LocalDate date;
    private String identifier;

    private PdfFileNameBuilder(String documentType) {
        this.documentType = Objects.requireNonNull(documentType, "documentType は必須です");
    }

    public static PdfFileNameBuilder of(String documentType) {
        return new PdfFileNameBuilder(documentType);
    }

    public PdfFileNameBuilder date(LocalDate date) {
        this.date = Objects.requireNonNull(date, "date は必須です");
        return this;
    }

    public PdfFileNameBuilder identifier(String identifier) {
        this.identifier = identifier;
        return this;
    }

    public String build() {
        Objects.requireNonNull(date, "date は必須です。date() を呼び出してください");

        StringBuilder sb = new StringBuilder();
        sb.append(date.format(DATE_FORMAT));
        sb.append("_");
        sb.append(sanitize(documentType));

        if (identifier != null && !identifier.isBlank()) {
            sb.append("_");
            sb.append(sanitize(identifier));
        }

        sb.append(".pdf");

        String result = sb.toString();
        if (result.length() > MAX_LENGTH) {
            result = result.substring(0, MAX_LENGTH - 4) + ".pdf";
        }

        return result;
    }

    /**
     * RFC 5987 UTF-8 エンコード済みファイル名を返す（Content-Disposition 用）。
     */
    public String buildEncoded() {
        String raw = build();
        return URLEncoder.encode(raw, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String sanitize(String input) {
        return input.replaceAll(FORBIDDEN_CHARS_REGEX, "_");
    }
}
