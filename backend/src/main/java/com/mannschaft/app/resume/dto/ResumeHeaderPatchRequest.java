package com.mannschaft.app.resume.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * 履歴書ヘッダー部分更新リクエスト（F01.10）。
 *
 * <p>設計書: {@code docs/features/F01.10_mypage_resume.md} §5.x
 *
 * <p>{@code PATCH /api/v1/resumes/{id}} で使用する。
 * 送信された非 null フィールドのみ更新する（null = 変更なし）。
 * 子要素（学歴・職歴等）は含まない。子要素を更新する場合は
 * {@link ResumeFullSaveRequest} を使った {@code PUT} を使用すること。
 */
public record ResumeHeaderPatchRequest(
        /** バージョン名（1〜100 文字）。null = 変更なし。 */
        @Size(min = 1, max = 100) String title,
        /** 出力時の元号フォーマット（"WESTERN" or "JAPANESE"）。null = 変更なし。 */
        String eraFormat,
        /** 現住所。null = 変更なし。 */
        String currentAddress,
        /** 現住所フリガナ。null = 変更なし。 */
        String currentAddressKana,
        /** 連絡先住所。null = 変更なし。 */
        String contactAddress,
        /** 連絡先住所フリガナ。null = 変更なし。 */
        String contactAddressKana,
        /** 連絡先電話番号。null = 変更なし。 */
        String contactPhone,
        /** 連絡先メールアドレス。null = 変更なし。 */
        @Email String contactEmail,
        /** 志望動機。null = 変更なし。 */
        String motivation,
        /** 自己 PR・特技・趣味。null = 変更なし。 */
        String selfPr,
        /** 本人希望記入欄。null = 変更なし。 */
        String personalRequest,
        /** 通勤所要時間（分）。null = 変更なし。 */
        Integer commuteMinutes,
        /** 扶養家族数（配偶者を除く）。null = 変更なし。 */
        Integer dependentsCount,
        /** 配偶者の有無。null = 変更なし。 */
        Boolean hasSpouse,
        /** 配偶者の扶養義務。null = 変更なし。 */
        Boolean spouseSupport,
        /** 職務要約（職務経歴書冒頭）。null = 変更なし。 */
        String careerSummary,
        /** 活かせる経験・知識・技術（職務経歴書・散文ブロック）。null = 変更なし。 */
        String skillsSummary
) {
}
