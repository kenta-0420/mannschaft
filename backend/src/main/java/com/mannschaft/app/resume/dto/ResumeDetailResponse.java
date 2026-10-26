package com.mannschaft.app.resume.dto;

import lombok.Builder;

import java.util.List;

/**
 * 履歴書フル取得レスポンス（F01.10）。
 *
 * <p>設計書: {@code docs/features/F01.10_mypage_resume.md} §5.5
 *
 * <p>単一バージョン取得 {@code GET /api/v1/resumes/{id}} および
 * 作成・保存・複製の結果として返す DTO。学歴・職歴・資格・スキルの
 * 子要素を含む。
 *
 * <p>PII フィールド（住所・連絡先）は Entity の AES-256-GCM 暗号化カラムから
 * Service が複合して返す。
 */
@Builder
public record ResumeDetailResponse(
        /** 履歴書 ID（UUID 文字列）。 */
        String id,
        /** バージョン名。 */
        String title,
        /** 出力時の元号フォーマット（"WESTERN" or "JAPANESE"）。 */
        String eraFormat,
        /** 証明写真の Presigned URL（null = 未設定）。 */
        String photoUrl,
        /** 現住所（復号済み）。 */
        String currentAddress,
        /** 現住所フリガナ（復号済み）。 */
        String currentAddressKana,
        /** 連絡先住所（復号済み）。 */
        String contactAddress,
        /** 連絡先住所フリガナ（復号済み）。 */
        String contactAddressKana,
        /** 連絡先電話番号（復号済み）。 */
        String contactPhone,
        /** 連絡先メールアドレス（復号済み）。 */
        String contactEmail,
        /** 志望動機。 */
        String motivation,
        /** 自己 PR・特技・趣味。 */
        String selfPr,
        /** 本人希望記入欄。 */
        String personalRequest,
        /** 通勤所要時間（分）。 */
        Integer commuteMinutes,
        /** 扶養家族数（配偶者を除く）。 */
        Integer dependentsCount,
        /** 配偶者の有無。 */
        Boolean hasSpouse,
        /** 配偶者の扶養義務。 */
        Boolean spouseSupport,
        /** 職務要約（職務経歴書冒頭）。 */
        String careerSummary,
        /** 活かせる経験・知識・技術（職務経歴書・散文ブロック）。 */
        String skillsSummary,
        /** 楽観ロック用バージョン。PUT 一括保存時にこの値を送り返す。 */
        Long version,
        /** 学歴リスト（表示順）。 */
        List<EducationDto> educations,
        /** 職歴リスト（表示順）。 */
        List<CareerDto> careers,
        /** 免許・資格リスト（表示順）。 */
        List<QualificationDto> qualifications,
        /** 構造化スキルリスト（表示順）。 */
        List<SkillDto> skills
) {

    /** 学歴ネスト DTO。 */
    @Builder
    public record EducationDto(
            /** 学歴 ID（UUID 文字列）。 */
            String id,
            /** 入学・卒業年。 */
            int entryYear,
            /** 入学・卒業月（null = 月不明）。 */
            Integer entryMonth,
            /** 学校名・学部・学科等の記述。 */
            String description,
            /** 表示順。 */
            int displayOrder
    ) {
    }

    /** 職歴ネスト DTO。 */
    @Builder
    public record CareerDto(
            /** 職歴 ID（UUID 文字列）。 */
            String id,
            /** 入社年。 */
            int entryYear,
            /** 入社月（null = 月不明）。 */
            Integer entryMonth,
            /** 退社年（null = 在職中）。 */
            Integer endYear,
            /** 退社月（null = 在職中）。 */
            Integer endMonth,
            /** 現職フラグ。 */
            boolean isCurrent,
            /** 会社名。 */
            String companyName,
            /** 部署・役職。 */
            String department,
            /** 雇用形態。 */
            String employmentType,
            /** 事業内容概要（職務経歴書用）。 */
            String businessSummary,
            /** 職務内容詳細（職務経歴書用）。 */
            String jobDescription,
            /** 実績・成果（職務経歴書用）。 */
            String achievements,
            /** 履歴書（rirekisho）への出力対象フラグ。 */
            boolean includeInRirekisho,
            /** 職務経歴書（shokumukeireki）への出力対象フラグ。 */
            boolean includeInShokumukeireki,
            /** 表示順。 */
            int displayOrder
    ) {
    }

    /** 免許・資格ネスト DTO。 */
    @Builder
    public record QualificationDto(
            /** 資格 ID（UUID 文字列）。 */
            String id,
            /** 取得年。 */
            int acquiredYear,
            /** 取得月（null = 月不明）。 */
            Integer acquiredMonth,
            /** 資格・免許名。 */
            String name,
            /** 補足メモ。 */
            String note,
            /** 表示順。 */
            int displayOrder
    ) {
    }

    /** 構造化スキルネスト DTO。 */
    @Builder
    public record SkillDto(
            /** スキル ID（UUID 文字列）。 */
            String id,
            /** スキル名。 */
            String skillName,
            /** 習熟度（"BEGINNER" / "INTERMEDIATE" / "ADVANCED" / "EXPERT" / null）。 */
            String level,
            /** 補足説明。 */
            String description,
            /** 表示順。 */
            int displayOrder
    ) {
    }
}
