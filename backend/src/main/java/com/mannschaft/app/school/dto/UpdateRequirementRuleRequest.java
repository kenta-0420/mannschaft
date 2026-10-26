package com.mannschaft.app.school.dto;

import com.mannschaft.app.school.entity.RequirementCategory;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/** F03.13 Phase 10: 出席要件規程 更新リクエスト DTO。全フィールド任意。 */
@Getter
@Setter
@NoArgsConstructor
public class UpdateRequirementRuleRequest {

    /** 対象学期ID（NULLなら年度通算） */
    private Long termId;

    /** 規程カテゴリ */
    private RequirementCategory category;

    /** 規程名 */
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
    private Boolean countSickBayAsPresent;

    /** 別室登校を出席扱いにするか */
    private Boolean countSeparateRoomAsPresent;

    /** 図書室登校を出席扱いにするか */
    private Boolean countLibraryAsPresent;

    /** オンライン登校を出席扱いにするか */
    private Boolean countOnlineAsPresent;

    /** 家庭学習を公欠扱いにするか */
    private Boolean countHomeLearningAsOfficialAbsence;

    /** 遅刻N回で欠席1日換算（0=換算なし） */
    private Byte countLateAsAbsenceThreshold;

    /** 警告発火しきい値（%） */
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private BigDecimal warningThresholdRate;

    /** 有効開始日 */
    private LocalDate effectiveFrom;

    /** 有効終了日（NULLなら無期限） */
    private LocalDate effectiveUntil;
}
