package com.mannschaft.app.receipt.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.receipt.ReceiptScopeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;


/**
 * 領収書番号の採番シーケンス（F08.12 §3.2）。
 *
 * <p>従来の採番は発行者設定行そのものを {@code PESSIMISTIC_WRITE} でロックしていた。
 * 団体スコープは行がテナントごとに分かれるため実害が小さいが、PLATFORM は全プラット
 * フォームで 1 行であり、月次一括発行が全件直列化する。本表は採番だけを分離し、
 * さらに {@code period_key}（YYYYMM）で行を割ることで別月の発行が競合しないようにする。</p>
 *
 * <p><b>適用範囲</b>: PLATFORM スコープに先行適用する。団体スコープは
 * {@code receipt_issuer_settings.next_receipt_number} 方式のまま据え置く（後方互換。§8）。</p>
 */
@Entity
@Table(name = "receipt_number_sequences",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_rns_scope_period",
                columnNames = {"scope_type", "scope_id", "period_key"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class ReceiptNumberSequenceEntity extends UuidV7Entity {

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 20)
    private ReceiptScopeType scopeType;

    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    /** 期間キー（{@code YYYYMM}）。 */
    @Column(name = "period_key", nullable = false, length = 8)
    private String periodKey;

    @Column(name = "next_number", nullable = false)
    @Builder.Default
    private Integer nextNumber = 1;

    // created_at / updated_at は DB 既定（DEFAULT CURRENT_TIMESTAMP / ON UPDATE
    // CURRENT_TIMESTAMP）が全て面倒を見るため、Java 側にフィールドを持たない。
    // 本エンティティのロジックはこれらを読まないので、LocalDateTime を新規に増やして
    // 時刻方針（docs/architecture/datetime_policy_utc_instant_vs_wallclock.md）に
    // 反する必要はない。

    /**
     * 番号レンジを確保し、開始番号を返す。
     *
     * <p>レンジ確保後に本体トランザクションがロールバックすると番号が飛ぶが、
     * <b>インボイス制度・電子帳簿保存法とも連番の連続性は要件ではない</b>
     * （重複禁止・改ざん禁止が要件）ため、欠番を許容する。設計上の明示的な選択である。</p>
     *
     * @param count 確保する個数（1 以上）
     * @return 確保したレンジの開始番号
     */
    public int reserve(int count) {
        if (count < 1) {
            throw new IllegalArgumentException("count must be >= 1: " + count);
        }
        int start = this.nextNumber;
        this.nextNumber = start + count;
        return start;
    }
}
