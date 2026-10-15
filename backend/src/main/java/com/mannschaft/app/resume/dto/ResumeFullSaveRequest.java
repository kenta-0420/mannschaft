package com.mannschaft.app.resume.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 履歴書フル一括保存リクエスト（F01.10）。
 *
 * <p>設計書: {@code docs/features/F01.10_mypage_resume.md} §5.6
 *
 * <p>{@code PUT /api/v1/resumes/{id}} で使用する。
 * ヘッダ情報と学歴・職歴・資格・スキルの子要素をまとめて一度に保存する
 * 入力摩擦最小化のための宣言的置換保存。
 *
 * <p>楽観ロック: {@code version} フィールドに {@code GET} で取得した値を設定すること。
 * 競合した場合はサーバー側で RESUME_010 エラーを返す。
 */
public record ResumeFullSaveRequest(
        /** バージョン名（必須）。 */
        @NotBlank String title,
        /** 出力時の元号フォーマット（"WESTERN" or "JAPANESE"）。 */
        String eraFormat,
        /** 現住所。 */
        String currentAddress,
        /** 現住所フリガナ。 */
        String currentAddressKana,
        /** 連絡先住所（現住所と異なる場合）。 */
        String contactAddress,
        /** 連絡先住所フリガナ。 */
        String contactAddressKana,
        /** 連絡先電話番号。 */
        String contactPhone,
        /** 連絡先メールアドレス。 */
        @Email String contactEmail,
        /** 志望動機（最大 1000 文字）。 */
        @Size(max = 1000) String motivation,
        /** 自己 PR・特技・趣味（最大 1000 文字）。 */
        @Size(max = 1000) String selfPr,
        /** 本人希望記入欄（最大 500 文字）。 */
        @Size(max = 500) String personalRequest,
        /** 通勤所要時間（分）。 */
        Integer commuteMinutes,
        /** 扶養家族数（配偶者を除く）。 */
        Integer dependentsCount,
        /** 配偶者の有無。 */
        Boolean hasSpouse,
        /** 配偶者の扶養義務。 */
        Boolean spouseSupport,
        /** 職務要約（職務経歴書冒頭・最大 800 文字）。 */
        @Size(max = 800) String careerSummary,
        /** 活かせる経験・知識・技術（職務経歴書・散文ブロック・最大 1000 文字）。 */
        @Size(max = 1000) String skillsSummary,
        /**
         * 楽観ロック用バージョン。{@code GET} で取得した {@code version} の値をそのまま送ること。
         * 競合した場合は RESUME_010 エラーを返す。
         */
        Long version,
        /** 学歴リスト（最大 30 件）。 */
        List<EducationSaveDto> educations,
        /** 職歴リスト（最大 30 件）。 */
        List<CareerSaveDto> careers,
        /** 免許・資格リスト（最大 50 件）。 */
        List<QualificationSaveDto> qualifications,
        /** 構造化スキルリスト（最大 50 件）。 */
        List<SkillSaveDto> skills
) {

    /** 学歴保存用子 DTO。 */
    public record EducationSaveDto(
            /** 既存 ID（UUID 文字列）。null = 新規登録。 */
            String id,
            /** 入学・卒業年（必須）。 */
            @NotNull Integer entryYear,
            /** 入学・卒業月（null = 月不明）。 */
            Integer entryMonth,
            /** 学校名・学部・学科等の記述（必須・最大 255 文字）。 */
            @NotBlank @Size(max = 255) String description,
            /** 表示順。 */
            int displayOrder
    ) {
    }

    /** 職歴保存用子 DTO。 */
    public record CareerSaveDto(
            /** 既存 ID（UUID 文字列）。null = 新規登録。 */
            String id,
            /** 入社年（必須）。 */
            @NotNull Integer entryYear,
            /** 入社月（null = 月不明）。 */
            Integer entryMonth,
            /** 退社年（null = 在職中）。 */
            Integer endYear,
            /** 退社月（null = 在職中）。 */
            Integer endMonth,
            /** 現職フラグ。 */
            boolean isCurrent,
            /** 会社名（必須・最大 255 文字）。 */
            @NotBlank @Size(max = 255) String companyName,
            /** 部署・役職（最大 255 文字）。 */
            @Size(max = 255) String department,
            /** 雇用形態（最大 50 文字）。 */
            @Size(max = 50) String employmentType,
            /** 事業内容概要（最大 500 文字）。 */
            @Size(max = 500) String businessSummary,
            /** 職務内容詳細（最大 2000 文字）。 */
            @Size(max = 2000) String jobDescription,
            /** 実績・成果（最大 1000 文字）。 */
            @Size(max = 1000) String achievements,
            /** 履歴書（rirekisho）への出力対象フラグ。 */
            boolean includeInRirekisho,
            /** 職務経歴書（shokumukeireki）への出力対象フラグ。 */
            boolean includeInShokumukeireki,
            /** 表示順。 */
            int displayOrder
    ) {
    }

    /** 免許・資格保存用子 DTO。 */
    public record QualificationSaveDto(
            /** 既存 ID（UUID 文字列）。null = 新規登録。 */
            String id,
            /** 取得年（必須）。 */
            @NotNull Integer acquiredYear,
            /** 取得月（null = 月不明）。 */
            Integer acquiredMonth,
            /** 資格・免許名（必須・最大 255 文字）。 */
            @NotBlank @Size(max = 255) String name,
            /** 補足メモ（最大 255 文字）。 */
            @Size(max = 255) String note,
            /** 表示順。 */
            int displayOrder
    ) {
    }

    /** 構造化スキル保存用子 DTO。 */
    public record SkillSaveDto(
            /** 既存 ID（UUID 文字列）。null = 新規登録。 */
            String id,
            /** スキル名（必須・最大 100 文字）。 */
            @NotBlank @Size(max = 100) String skillName,
            /** 習熟度（"BEGINNER" / "INTERMEDIATE" / "ADVANCED" / "EXPERT" / null）。 */
            String level,
            /** 補足説明（最大 500 文字）。 */
            @Size(max = 500) String description,
            /** 表示順。 */
            int displayOrder
    ) {
    }
}
