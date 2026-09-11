package com.mannschaft.app.billing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Billing Center PR6a: 契約単位の同時 mutation 耐久 lease（{@code active_billing_contract_operation_pointers}）。
 *
 * <p>V196 で新設。1契約（{@code contract_id}）につき「今進行中の
 * {@link BillingContractOperationEntity}」を高々1件だけ許すための lease テーブル。
 * {@code contract_id} が<b>主キーそのもの</b>（{@code PRIMARY KEY (contract_id)}）であり、
 * {@link com.mannschaft.app.common.entity.UuidV7Entity} は継承<b>しない</b>
 * （自動採番の代理キーを持たず、業務キー＝主キーの構成。DDL に {@code id} 列自体が存在しない）。</p>
 *
 * <p><b>{@link ActiveContractPointerEntity}（{@code active_contract_pointers}）とは別表</b>である。
 * あちらは PLAN/ADDON スロット（scope_kind, scope_id, contract_kind, addon_feature_key）の
 * 一意性を担保する既存表で、契約作成そのものの重複防止に使う。本 Entity は逆に
 * 「1契約に同時に複数の操作 Saga が走らないこと」を担保する、契約<b>操作</b>の排他制御用の
 * 全く別の lease であり、対象も粒度も異なる。名前の類似（Active…Pointer）による混同に注意。</p>
 *
 * <p>{@code operation_id} は {@code UNIQUE KEY uk_abcop_operation} により1操作につき高々1件の
 * lease しか持てない（同一操作が複数契約の lease を同時に握ることはない設計）。</p>
 *
 * <p>解放（DELETE）は操作が terminal 状態
 * （{@link BillingOperationStatus#APPLIED}/{@link BillingOperationStatus#FAILED}/
 * {@link BillingOperationStatus#CANCELLED}）に確定したタイミングで行う想定（実装は別部隊）。</p>
 *
 * <p>設計書: docs/features/F20.1_entitlement_billing/05_billing_center.md §5</p>
 */
@Entity
@Table(name = "active_billing_contract_operation_pointers")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
@EqualsAndHashCode
public class ActiveBillingContractOperationPointerEntity {

    /** 主キー（{@code billing_contracts.id}・自動採番ではない。lease対象契約そのもの）。 */
    @Id
    @Column(name = "contract_id", columnDefinition = "BINARY(16)")
    private UUID contractId;

    /** 現在進行中の {@code billing_contract_operations.id}（{@code uk_abcop_operation} により一意）。 */
    @Column(name = "operation_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID operationId;

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
}
