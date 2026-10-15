package com.mannschaft.app.school.dto;

import com.mannschaft.app.school.entity.RequirementCategory;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/** F03.13 Phase 10: 出席要件規程 作成リクエスト DTO。 */
@Getter
@Setter
@NoArgsConstructor
public class CreateRequirementRuleRequest {

    /** 組織スコープID（teamId と排他） */
    private Long organizationId;

    /** チームスコープID（organizationId と排他） */
    private Long teamId;

    /** 対象学期ID（NULLなら年度通算） */
    private Long termId;

    /** 学年度（例: 2025） */
    @NotNull
    private Short academicYear;

    /** 規程カテゴリ */
    @NotNull
    private RequirementCategory category;

    /** 規程名 */
    @NotBlank
    @Size(max = 100)
    private String name;

    /** 規程の説明 */
    private String description;

    /** 最小出席率（%） */
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private BigDecimal minAttendanceRate;

    /** 最大欠席日数 */
    @Min(0)
    private Short maxAbsenceDays;

    /** 最大欠席率（%） */
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private BigDecimal maxAbsenceRate;

    /** 保健室登校を出席扱いにするか */
    private Boolean countSickBayAsPresent = true;

    /** 別室登校を出席扱いにするか */
    private Boolean countSeparateRoomAsPresent = true;

    /** 図書室登校を出席扱いにするか */
    private Boolean countLibraryAsPresent = true;

    /** オンライン登校を出席扱いにするか */
    private Boolean countOnlineAsPresent = true;

    /** 家庭学習を公欠扱いにするか */
    private Boolean countHomeLearningAsOfficialAbsence = false;

    /** 遅刻N回で欠席1日換算（0=換算なし） */
    private Byte countLateAsAbsenceThreshold = 0;

    /** 警告発火しきい値（%） */
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private BigDecimal warningThresholdRate;

    /** 有効開始日 */
    @NotNull
    private LocalDate effectiveFrom;

    /** 有効終了日（NULLなら無期限） */
    private LocalDate effectiveUntil;
}
