package com.mannschaft.app.notification.confirmable.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.membership.ScopeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

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

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * グループを論理削除する。
     *
     * <p>削除後、テンプレートの既定グループとして参照されていても無視され、
     * 「既定＝配下すべて」に戻す（§3.1・AC-32）。</p>
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    public boolean isDeleted() {
        return this.deletedAt != null;
    }

    /** グループ名を変更する（更新API用）。 */
    public void rename(String newName) {
        this.name = newName;
    }
}
