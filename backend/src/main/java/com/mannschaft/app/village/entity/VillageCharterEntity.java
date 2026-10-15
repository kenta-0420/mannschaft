package com.mannschaft.app.village.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 村憲章（親・1村1憲章）エンティティ（F17.3・設計書 §13.1.1）。
 *
 * <p>村ごとの「拠りどころ＝憲章」の親。制定日({@code enactedAt})は初回作成時に自動セットし
 * 以後不変、改定日({@code lastRevisedAt})は手動「改正を確定」でのみ更新する（§8）。</p>
 *
 * <p>{@code version}（{@link Version}）は<b>層2 楽観ロック</b>で、全構造変更 EP
 * （{@code POST}/{@code DELETE}/{@code PATCH order}）でバンプし、{@code PATCH order} は
 * 親行の悲観ロック取得後に楽観一致検査を行う（§7）。{@code village_id} は同一ドメインだが
 * 村既存作法に倣い FK非付与＋UNIQUE（§13.1.1）。論理削除（{@code deletedAt}）で原則3。</p>
 */
@Entity
@Table(name = "village_charters")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class VillageCharterEntity extends UuidV7Entity {

    /** 村スコープ（FK非付与＋UNIQUE・原則1/村既存作法）。 */
    @Column(name = "village_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID villageId;

    /** 制定日（初回作成時に自動セット・不変・§8.1）。 */
    @Column(name = "enacted_at", nullable = false)
    private LocalDateTime enactedAt;

    /** 改定日（手動「改正を確定」・未改正は NULL・§8.2）。 */
    @Column(name = "last_revised_at")
    private LocalDateTime lastRevisedAt;

    /** 論理削除（原則3）。 */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** @Version（層2・PATCH order 楽観検査＋全構造変更でバンプ・§7）。 */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
