package com.mannschaft.app.tournament.scorekeeper;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 大会のスコアキーパー指名エンティティ（F08.7 順位UI 項目③）。
 *
 * <p>「特定の大会において、主催組織 ADMIN 以外にもスコア入力を許可するユーザー」を表す指名レコード。
 * スコアキーパーは<strong>新しいロールではなく、指名された user_id</strong> で表現する
 * （F08.10 {@code MatchAccessService} の {@code scorekeeperUserId} 方式に倣う）。指名管理は主催組織 ADMIN が行う。</p>
 *
 * <p>原則準拠:</p>
 * <ul>
 *   <li>新規テーブルゆえ主キーは UUIDv7（原則 6・{@link UuidV7Entity} 継承）。id は BINARY(16)。</li>
 *   <li>{@code tournamentId} / {@code userId} / {@code createdBy} は他テーブルへの ID 参照のみ。
 *       クロスドメイン FK は張らない（原則 1）。参照整合性はアプリ層で保証する。</li>
 *   <li>指名は履歴ではなく現在の権限状態であるため、解除は物理削除（hard delete）で行う
 *       （論理削除カラムは持たない）。</li>
 * </ul>
 *
 * <p>設計: docs/features/F08.7_standings_ui（項目③ スコア入力編集権限の細分化）</p>
 */
@Entity
@Table(name = "tournament_scorekeepers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class TournamentScorekeeperEntity extends UuidV7Entity {

    /** 対象大会（tournaments.id への ID 参照・FK なし／原則1） */
    @Column(nullable = false)
    private Long tournamentId;

    /** スコアキーパーに指名されたユーザー（users.id への ID 参照・FK なし／原則1） */
    @Column(nullable = false)
    private Long userId;

    /** 指名した主催組織 ADMIN の user_id（退会時も履歴として保持） */
    @Column(nullable = false)
    private Long createdBy;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }
}
