package com.mannschaft.app.gdpr.dto;

/**
 * GDPR データエクスポート処理で使用するユーザーのメール送信情報。
 *
 * <p>auth ドメインの {@code UserRepository} を gdpr ドメインの
 * {@code DataExportService} (非同期処理内) から直接参照するとドメイン境界原則5
 * （@Transactional はドメイン内に閉じる）に違反するため、
 * 呼び出し元 ({@code GdprController}) で事前に取得した情報を本レコードに詰めて渡す設計とした。</p>
 *
 * @param userId    ユーザーID
 * @param email     メールアドレス
 * @param lastName  姓
 * @param firstName 名
 */
public record UserEmailInfo(
        Long userId,
        String email,
        String lastName,
        String firstName
) {}
