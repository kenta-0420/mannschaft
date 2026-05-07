package com.mannschaft.app.disclosure.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 重要事項説明書 様式テンプレートエンティティ。
 * 都道府県別・国交省標準書式・組織カスタムを統合管理する。
 * F09.14 設計書 §3 disclosure_form_templates テーブル定義に対応。
 *
 * <p>システム提供（{@code is_system_template=TRUE}）の場合 {@code scope_type/scope_id} は NULL、
 * カスタム（{@code is_system_template=FALSE}）の場合は {@code ORGANIZATION} スコープ必須。
 * DB の CHECK 制約と Service 層で重ねて検証する。</p>
 */
@Entity
@Table(name = "disclosure_form_templates")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class DisclosureFormTemplateEntity extends BaseEntity {

    /** 様式コード（例: MLIT_STANDARD_2024 / TOKYO_2025）。 */
    @Column(nullable = false, length = 50)
    private String code;

    @Column(nullable = false, length = 150)
    private String name;

    /** JIS 都道府県コード（例: 13=東京）。NULL=全国共通。 */
    @Column(length = 2)
    private String prefectureCode;

    @Column(nullable = false, length = 20)
    private String version;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isStandard = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isSystemTemplate = false;

    /** カスタム時のみ ORGANIZATION。システム提供は NULL。 */
    @Column(length = 20)
    private String scopeType;

    private Long scopeId;

    /** form_schema JSON（フォーム項目定義）。 */
    @Column(columnDefinition = "JSON", nullable = false)
    private String formSchema;

    /** Thymeleaf テンプレートパス（PDF）。 */
    @Column(length = 500)
    private String pdfTemplatePath;

    /** Excel テンプレートのリソースキー。 */
    @Column(length = 500)
    private String excelTemplateKey;

    private LocalDate effectiveFrom;

    private LocalDate effectiveUntil;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    /** カスタムテンプレートの作成者。システム提供は NULL。 */
    private Long createdBy;

    @Version
    @Column(name = "version_lock", nullable = false)
    private Long versionLock;

    private LocalDateTime deletedAt;

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * 有効/無効フラグを切り替える。
     */
    public void changeActive(boolean isActive) {
        this.isActive = isActive;
    }

    /**
     * 適用期間を更新する。
     */
    public void updateEffectivePeriod(LocalDate effectiveFrom, LocalDate effectiveUntil) {
        this.effectiveFrom = effectiveFrom;
        this.effectiveUntil = effectiveUntil;
    }

    /**
     * form_schema JSON を更新する（カスタムテンプレートのみ）。
     */
    public void updateFormSchema(String formSchema) {
        this.formSchema = formSchema;
    }

    /**
     * 名称を更新する。
     */
    public void rename(String name) {
        this.name = name;
    }
}
