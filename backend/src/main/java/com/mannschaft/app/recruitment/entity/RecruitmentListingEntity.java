package com.mannschaft.app.recruitment.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.recruitment.RecruitmentListingStatus;
import com.mannschaft.app.recruitment.RecruitmentParticipationType;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.RecruitmentVisibility;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * F03.11 募集型予約: 募集枠メインエンティティ。
 * Phase 1+5a で扱うステータスは DRAFT/OPEN/FULL/CLOSED/CANCELLED の5値。
 * AUTO_CANCELLED/COMPLETED は Phase 3 以降。
 */
@Entity
@Table(name = "recruitment_listings")
@SQLRestriction("deleted_at IS NULL AND moderation_hidden_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class RecruitmentListingEntity extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RecruitmentScopeType scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Column(nullable = false)
    private Long categoryId;

    private Long subcategoryId;

    private Long templateId;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RecruitmentParticipationType participationType;

    @Column(nullable = false)
    private LocalDateTime startAt;

    @Column(nullable = false)
    private LocalDateTime endAt;

    @Column(nullable = false)
    private LocalDateTime applicationDeadline;

    @Column(nullable = false)
    private LocalDateTime autoCancelAt;

    @Column(nullable = false)
    private Integer capacity;

    @Column(nullable = false)
    private Integer minCapacity;

    @Column(nullable = false)
    @Builder.Default
    private Integer confirmedCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer waitlistCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer waitlistMax = 100;

    @Column(nullable = false)
    @Builder.Default
    private Boolean paymentEnabled = false;

    private Integer price;

    /**
     * F22.1 市の謝礼決済: 札ごとの受領主体種別 {@code USER}/{@code TEAM}/{@code ORG}。
     * {@code payment_enabled=TRUE} 時に必須（chk_rl_payee）。既存札は NULL（決済無効・後方互換）。
     * 値は VARCHAR(8) で保持し、{@code RecruitmentScopeType}（TEAM/ORGANIZATION の2値）とは別系統。
     * 変換は ConnectAccountService 等に集約する（設計書 §4.1 実装注意）。
     */
    @Column(name = "payee_kind", length = 8)
    private String payeeKind;

    /**
     * F22.1 市の謝礼決済: {@code payee_kind=USER} の受領者（users.id 論理参照・FKなし）。
     * {@code payee_kind=USER} のとき必須・それ以外では NULL（chk_rl_payee_user）。
     */
    @Column(name = "payee_user_id")
    private Long payeeUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private RecruitmentVisibility visibility = RecruitmentVisibility.SCOPE_ONLY;

    /** カスタム公開範囲テンプレートID (F01.7)。visibility = CUSTOM_TEMPLATE の場合のみ使用 */
    @Column(name = "visibility_template_id")
    private Long visibilityTemplateId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private RecruitmentListingStatus status = RecruitmentListingStatus.DRAFT;

    @Column(length = 200)
    private String location;

    /**
     * 都道府県コード（JIS X 0401・CHAR(2)）。F22.1 市の地域フィルタ用。
     * {@code prefectures.code} を参照（FK なし・Service 検証）。地域未指定の札は NULL。
     */
    @Column(name = "prefecture_code", length = 2)
    private String prefectureCode;

    /**
     * 市区町村コード（JIS X 0402・CHAR(5)）。F22.1 市の地域フィルタ用。
     * {@code cities.code} を参照（FK なし・Service 検証）。市区町村未確定の札は NULL。
     */
    @Column(name = "city_code", length = 5)
    private String cityCode;

    private Long reservationLineId;

    @Column(length = 500)
    private String imageUrl;

    private Long cancellationPolicyId;

    @Column(nullable = false)
    private Long createdBy;

    private LocalDateTime cancelledAt;

    private Long cancelledBy;

    @Column(length = 200)
    private String cancelledReason;

    @Column(nullable = false)
    @Builder.Default
    private Integer participantCountCache = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer nextWaitlistPosition = 1;

    private LocalDateTime deletedAt;

    /** システム管理者による可逆的なモデレーション非表示日時。 */
    @Column(name = "moderation_hidden_at")
    private Instant moderationHiddenAt;

    // ===========================================
    // ステータス遷移メソッド
    // ===========================================

    /** DRAFT → OPEN に遷移する。 */
    public void publish() {
        if (this.status != RecruitmentListingStatus.DRAFT) {
            throw new IllegalStateException("DRAFT 以外からは publish できません: status=" + this.status);
        }
        this.status = RecruitmentListingStatus.OPEN;
    }

    /** 主催者によるキャンセル。 */
    public void cancelByAdmin(Long actorUserId, String reason) {
        if (this.status == RecruitmentListingStatus.CANCELLED
                || this.status == RecruitmentListingStatus.AUTO_CANCELLED
                || this.status == RecruitmentListingStatus.COMPLETED) {
            throw new IllegalStateException("既に終了状態の募集はキャンセルできません: status=" + this.status);
        }
        this.status = RecruitmentListingStatus.CANCELLED;
        this.cancelledAt = LocalDateTime.now();
        this.cancelledBy = actorUserId;
        this.cancelledReason = reason;
    }

    /** §5.4 バッチによる自動キャンセル（最小定員未達）。 */
    public void autoCancel() {
        if (this.status != RecruitmentListingStatus.OPEN && this.status != RecruitmentListingStatus.FULL) {
            throw new IllegalStateException("OPEN/FULL 以外は autoCancel できません: status=" + this.status);
        }
        this.status = RecruitmentListingStatus.AUTO_CANCELLED;
    }

    /**
     * F22.1 市「札を下げる」最終認証: FULL → COMPLETED に遷移する。
     *
     * <p>札が要件充足（{@code FULL}）した後、札主が F04.9 確認通知で最終認証
     * （{@code source_type='MARKET_FINALIZE'}）に応答したときに呼び出す。
     * 「要件充足だが未認証」は {@code FULL}、「最終認証済み」は {@code COMPLETED} で
     * 表現する（新カラム不要・02_api_design §6.1）。</p>
     *
     * @throws IllegalStateException FULL 以外の状態から呼び出した場合
     */
    public void finalizeComplete() {
        if (this.status != RecruitmentListingStatus.FULL) {
            throw new IllegalStateException("FULL 以外からは finalizeComplete できません: status=" + this.status);
        }
        this.status = RecruitmentListingStatus.COMPLETED;
    }

    /** 論理削除を行う。 */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * F22.1 市: 地域コード（都道府県・市区町村）を更新する。
     *
     * <p>呼び出し前に Service 層で {@code MarketRegionValidator} による整合検証
     * （{@code MARKET_001}）を済ませること。両方 null は「地域を問わない」札を表す。</p>
     *
     * @param prefectureCode 正規化済み都道府県コード（null 可）
     * @param cityCode       市区町村コード（null 可）
     */
    public void updateRegion(String prefectureCode, String cityCode) {
        this.prefectureCode = prefectureCode;
        this.cityCode = cityCode;
    }

    /**
     * テンプレートIDを紐付ける。
     * createFromTemplate() 時に、作成後にテンプレートIDを設定するために使用する。
     */
    public void assignTemplate(Long templateId) {
        this.templateId = templateId;
    }

    // ===========================================
    // 参加者カウント管理 (Service 層と連携)
    // ===========================================

    /**
     * 楽観的ロックの結果を反映する形で確定数を増やす。
     * Service層から原子クエリで status 更新済みの場合は再ロード後にこのメソッドで Java 側状態を同期する。
     */
    public void incrementConfirmed() {
        this.confirmedCount = this.confirmedCount + 1;
        if (this.status == RecruitmentListingStatus.OPEN
                && this.confirmedCount.intValue() >= this.capacity.intValue()) {
            this.status = RecruitmentListingStatus.FULL;
        }
        this.participantCountCache = this.participantCountCache + 1;
    }

    /**
     * 確定数を減らす。FULL 状態で空きが出れば OPEN に自動復帰 (§5.3)。
     */
    public void decrementConfirmed() {
        if (this.confirmedCount > 0) {
            this.confirmedCount = this.confirmedCount - 1;
        }
        if (this.status == RecruitmentListingStatus.FULL
                && this.confirmedCount.intValue() < this.capacity.intValue()) {
            this.status = RecruitmentListingStatus.OPEN;
        }
        if (this.participantCountCache > 0) {
            this.participantCountCache = this.participantCountCache - 1;
        }
    }

    /** キャンセル待ち数をインクリメントし、次の position 値を返す (§5.2 step8)。 */
    public int incrementWaitlistAndAcquirePosition() {
        if (this.waitlistCount.intValue() >= this.waitlistMax.intValue()) {
            throw new IllegalStateException("キャンセル待ち上限に達しています");
        }
        this.waitlistCount = this.waitlistCount + 1;
        int position = this.nextWaitlistPosition;
        this.nextWaitlistPosition = this.nextWaitlistPosition + 1;
        return position;
    }

    /** キャンセル待ち数をデクリメントする。 */
    public void decrementWaitlist() {
        if (this.waitlistCount > 0) {
            this.waitlistCount = this.waitlistCount - 1;
        }
    }

    // ===========================================
    // 編集 (§5.7)
    // ===========================================

    /**
     * 編集時の制約を強制しながら募集情報を更新する (§5.7)。
     * Service 層からも事前検証されるが、Entity 内でも防御的に再評価する。
     */
    public void updateForEdit(
            String title,
            String description,
            Long subcategoryId,
            LocalDateTime startAt,
            LocalDateTime endAt,
            LocalDateTime applicationDeadline,
            LocalDateTime autoCancelAt,
            Integer capacity,
            Integer minCapacity,
            Boolean paymentEnabled,
            Integer price,
            RecruitmentVisibility visibility,
            String location,
            Long reservationLineId,
            String imageUrl,
            Long cancellationPolicyId,
            String payeeKind,
            Long payeeUserId
    ) {
        if (this.status == RecruitmentListingStatus.COMPLETED
                || this.status == RecruitmentListingStatus.CANCELLED
                || this.status == RecruitmentListingStatus.AUTO_CANCELLED) {
            throw new IllegalStateException("終了済みの募集は編集できません: status=" + this.status);
        }
        if (capacity != null && capacity < this.confirmedCount) {
            throw new IllegalStateException("capacity を確定参加者数より少なく変更できません");
        }
        if (capacity != null && minCapacity != null && minCapacity > capacity) {
            throw new IllegalStateException("min_capacity > capacity は不正");
        }
        if (capacity != null && minCapacity == null && this.minCapacity > capacity) {
            throw new IllegalStateException("min_capacity > capacity は不正");
        }
        LocalDateTime effectiveStart = startAt != null ? startAt : this.startAt;
        LocalDateTime effectiveEnd = endAt != null ? endAt : this.endAt;
        LocalDateTime effectiveDeadline = applicationDeadline != null ? applicationDeadline : this.applicationDeadline;
        LocalDateTime effectiveAuto = autoCancelAt != null ? autoCancelAt : this.autoCancelAt;
        if (!effectiveStart.isBefore(effectiveEnd)) {
            throw new IllegalStateException("start_at < end_at が必要");
        }
        if (!effectiveDeadline.isBefore(effectiveStart)) {
            throw new IllegalStateException("application_deadline < start_at が必要");
        }
        if (effectiveAuto.isAfter(effectiveDeadline)) {
            throw new IllegalStateException("auto_cancel_at <= application_deadline が必要");
        }
        Boolean effectivePaymentEnabled = paymentEnabled != null ? paymentEnabled : this.paymentEnabled;
        Integer effectivePrice = price != null ? price : this.price;
        if (Boolean.TRUE.equals(effectivePaymentEnabled) && effectivePrice == null) {
            throw new IllegalStateException("決済を有効化する場合は料金が必要");
        }

        // F22.1 市 謝礼決済: 受領主体の防御的検証（DB chk_rl_payee / chk_rl_payee_user 相当・Service 検証の二重化）。
        // payeeKind=null は「変更なし」、空でない値は変更。effective 値で CHECK 不変条件を満たすことを確認する。
        String effectivePayeeKind = payeeKind != null ? payeeKind : this.payeeKind;
        Long effectivePayeeUserId = payeeUserId != null ? payeeUserId : this.payeeUserId;
        if (Boolean.TRUE.equals(effectivePaymentEnabled)
                && (effectivePayeeKind == null || effectivePayeeKind.isBlank())) {
            throw new IllegalStateException("決済を有効化する場合は受領主体（payeeKind）が必要");
        }
        if ("USER".equals(effectivePayeeKind) && effectivePayeeUserId == null) {
            throw new IllegalStateException("payeeKind=USER の場合は受領者ユーザー（payeeUserId）が必要");
        }
        if (effectivePayeeKind != null && !"USER".equals(effectivePayeeKind)) {
            // 非 USER（TEAM/ORG）では payee_user_id は NULL でなければならない（chk_rl_payee_user）。
            effectivePayeeUserId = null;
        }

        if (title != null) this.title = title;
        if (description != null) this.description = description;
        if (subcategoryId != null) this.subcategoryId = subcategoryId;
        if (startAt != null) this.startAt = startAt;
        if (endAt != null) this.endAt = endAt;
        if (applicationDeadline != null) this.applicationDeadline = applicationDeadline;
        if (autoCancelAt != null) this.autoCancelAt = autoCancelAt;
        if (capacity != null) this.capacity = capacity;
        if (minCapacity != null) this.minCapacity = minCapacity;
        if (paymentEnabled != null) this.paymentEnabled = paymentEnabled;
        if (price != null) this.price = price;
        if (visibility != null) this.visibility = visibility;
        if (location != null) this.location = location;
        if (reservationLineId != null) this.reservationLineId = reservationLineId;
        if (imageUrl != null) this.imageUrl = imageUrl;
        if (cancellationPolicyId != null) this.cancellationPolicyId = cancellationPolicyId;
        // 受領主体は CHECK 整合を取った effective 値で確定する（非 USER は user_id を NULL に正規化済み）。
        if (payeeKind != null) {
            this.payeeKind = effectivePayeeKind;
            this.payeeUserId = effectivePayeeUserId;
        } else if (payeeUserId != null) {
            // payeeKind 未指定だが payeeUserId のみ更新（既存 payeeKind=USER の受領者付け替え）。
            this.payeeUserId = effectivePayeeUserId;
        }
    }
}
