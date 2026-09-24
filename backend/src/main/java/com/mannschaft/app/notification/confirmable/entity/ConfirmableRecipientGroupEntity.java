package com.mannschaft.app.notification.confirmable.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.membership.ScopeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * CMP-260920-1040 F04.9 確認通知の宛先グループ（軍議第8版確定稿 §3.1）。
 *
 * <p>「ターゲットの集合に名前を付けて保存したもの」。ユーザーを固定で登録するのではなく、
 * 送信時に展開する。同じスコープで名前を一意にする（削除されていない行の中で。AC-31）。</p>
 */
@Entity
@Table(name = "confirmable_recipient_groups")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder
@EqualsAndHashCode(callSuper = true)
public class ConfirmableRecipientGroupEntity extends UuidV7Entity {

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 20)
    private ScopeType scopeType;

    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "created_by")
    private Long createdBy;

    /** 論理削除日時。NULL の場合は有効なグループ。 */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /**
     * CI是正（CMP-260920-1040）: 引数なし {@code LocalDateTime.now()} を使う {@code @PrePersist}/
     * {@code @PreUpdate} の代わりに Hibernate の {@link CreationTimestamp}/{@link UpdateTimestamp}
     * （JVM既定ゾーン基準）を使う（docs/architecture/datetime_policy_utc_instant_vs_wallclock.md
     * 是正・番人 datetime_guard の新規クラス違反の根治。他の新規エンティティに倣う）。
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * グループを論理削除する。
     *
     * <p>削除後、テンプレートの既定グループとして参照されていても無視され、
     * 「既定＝配下すべて」に戻す（§3.1・AC-32）。</p>
     *
     * <p>CI是正（CMP-260920-1040）: 引数なし {@code LocalDateTime.now()} は番人違反のため、
     * 呼び出し側が {@code @Qualifier("wallClock")} の {@link Clock} を渡す（既存 now() 群と同じゾーン基準）。</p>
     *
     * @param clock 壁時計クロック（{@code @Qualifier("wallClock")}）
     */
    public void softDelete(Clock clock) {
        this.deletedAt = LocalDateTime.now(clock);
    }

    public boolean isDeleted() {
        return this.deletedAt != null;
    }

    /** グループ名を変更する（更新API用）。 */
    public void rename(String newName) {
        this.name = newName;
    }
}
