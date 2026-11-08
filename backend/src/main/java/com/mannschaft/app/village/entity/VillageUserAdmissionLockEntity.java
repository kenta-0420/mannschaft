package com.mannschaft.app.village.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

/**
 * 村への入村を USER 単位で直列化するためのロック行（CMP-260827-1808）。
 *
 * <p>USER の参加枠（{@code village_memberships.user_slot}）の割当は、同じ USER の入村どうしを
 * この行の排他ロックで一列に並べてから行う。auth ドメインの users 行をロックすると村の取引が
 * ドメインを越境する（D-3T）ため、村ドメインの中にロック専用の行を持つ。
 * 不変条件の正本は DB 制約（uk_vm_user_active_slot / uk_vm_active_subject）で、本ロックは
 * 並行入村での枠番号の衝突・ギャップロックのデッドロックを避けるためのもの。</p>
 *
 * <p>行は入村時に {@code INSERT ... ON DUPLICATE KEY UPDATE} で作成・ロックされ、内容は USER ID と
 * 最終ロック時刻だけ（業務データを持たない）。user_id に FK は張らない（原則1）。</p>
 */
@Entity
@Table(name = "village_user_admission_locks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class VillageUserAdmissionLockEntity extends UuidV7Entity {

    /** 入村を直列化する USER の ID（FK 張らない／原則1）。 */
    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    /** 最後に入村ロックを取った瞬間（UTC。SQL の UTC_TIMESTAMP(6) で書く）。 */
    @Column(name = "locked_at", nullable = false)
    private Instant lockedAt;
}
