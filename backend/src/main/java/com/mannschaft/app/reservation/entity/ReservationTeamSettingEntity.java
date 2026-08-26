package com.mannschaft.app.reservation.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.reservation.ReservationResourceNameType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.experimental.SuperBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * チームごとの予約設定エンティティ（1チーム1行）。
 *
 * <p>将来の予約認可ゲートの汎用受け皿として機能する。
 * team_id は teams ドメインへのクロスドメイン参照のため FK なし、
 * インデックスのみで整合性をアプリ層で保証する（アーキ原則1）。</p>
 *
 * <p>レコードが存在しない場合は {@code allow_public_reservation = false} /
 * {@code resource_name_type = DEFAULT} / {@code resource_name_custom = null} として扱う。
 * upsert は後続の Service 層で実装する。</p>
 */
@Entity
@Table(name = "reservation_team_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class ReservationTeamSettingEntity extends UuidV7Entity {

    /** チームID（teams テーブルへのクロスドメイン参照・FK なし）。 */
    @Column(name = "team_id", nullable = false, unique = true)
    private Long teamId;

    /** 一般公開予約を許可するか。デフォルト false（非公開）。 */
    @Column(name = "allow_public_reservation", nullable = false)
    @Builder.Default
    private boolean allowPublicReservation = false;

    /**
     * 予約対象の呼称プリセット（F03.4.5 §5）。デフォルト {@code DEFAULT}
     * （未設定チームの後方互換フォールバック・従来どおり「予約対象」表示）。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "resource_name_type", nullable = false, length = 10)
    @Builder.Default
    private ReservationResourceNameType resourceNameType = ReservationResourceNameType.DEFAULT;

    /**
     * {@code resourceNameType = CUSTOM} のときの自由入力呼称。
     * {@code CUSTOM} 以外では常に {@code null}（Service 層で正規化を保証する）。
     */
    @Column(name = "resource_name_custom", length = 30)
    private String resourceNameCustom;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 一般公開予約の許可フラグを更新する。
     *
     * @param allow 許可する場合 true
     */
    public void updateAllowPublicReservation(boolean allow) {
        this.allowPublicReservation = allow;
    }

    /**
     * 呼称設定を更新する。
     *
     * <p>呼び出し側（{@code ReservationTeamSettingService}）で「{@code CUSTOM} のとき custom 必須」
     * 「{@code CUSTOM} 以外は custom を {@code null} に正規化」のバリデーション・正規化を完了させた
     * 上で呼ぶこと（本メソッドは単純な代入のみを行う）。</p>
     *
     * @param resourceNameType   呼称プリセット（非 null）
     * @param resourceNameCustom 自由入力呼称（{@code CUSTOM} 以外では {@code null}）
     */
    public void updateResourceName(ReservationResourceNameType resourceNameType, String resourceNameCustom) {
        this.resourceNameType = resourceNameType;
        this.resourceNameCustom = resourceNameCustom;
    }
}
