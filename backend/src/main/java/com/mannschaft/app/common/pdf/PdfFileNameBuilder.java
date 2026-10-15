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
     * 拡張子を指定してファイル名を生成する（PDF 以外の形式にも対応）。
     *
     * <p>{@link #build()} と同じ命名規約（禁止文字 sanitize・最大長 100・{yyyyMMdd}_{文書種別}_{識別名}）を
     * 使用するが、拡張子を引数で受け取る。{@link #build()} の後方互換は維持する。
     *
     * <p>設計書: {@code docs/features/F01.10_mypage_resume.md} §7.3（案 a 確定）
     *
     * @param extension 拡張子（例: ".xlsx", ".pdf"。先頭ドットを含む）
     * @return ファイル名（例: "20260525_履歴書_山田太郎.xlsx"）
     */
    public String buildWithExtension(String extension) {
        Objects.requireNonNull(date, "date は必須です。date() を呼び出してください");
        Objects.requireNonNull(extension, "extension は必須です");

        StringBuilder sb = new StringBuilder();
        sb.append(date.format(DATE_FORMAT));
        sb.append("_");
        sb.append(sanitize(documentType));

        if (identifier != null && !identifier.isBlank()) {
            sb.append("_");
            sb.append(sanitize(identifier));
        }

        sb.append(extension);

        String result = sb.toString();
        if (result.length() > MAX_LENGTH) {
            // 拡張子部分の長さを確保してから切り詰める
            int extLen = extension.length();
            result = result.substring(0, MAX_LENGTH - extLen) + extension;
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
