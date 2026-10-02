package com.mannschaft.app.team.dto;

import com.mannschaft.app.team.entity.TeamEntity;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * システム管理ダッシュボード「全チーム一覧」レスポンス DTO（team ドメイン所有）。
 *
 * <p>{@link TeamEntity} 直返しを廃し、システム管理画面で必要な項目のみを明示的に返す。
 * 楽観ロック用 {@code version}・論理削除日時 {@code deletedAt}・拡張プロフィール等の内部項目は含めない。
 * フィールド名は Entity のシリアライズ名と一致させ、フロントエンドを無風化する。</p>
 *
 * <p><b>配置理由（ドメイン境界の原則）</b>: 本 DTO と対応する {@code SystemAdminTeamSummaryMapper} は
 * {@link TeamEntity}（team ドメイン）を参照するため、team ドメイン内に配置する。admin ドメインへ
 * 置くと D-1 クロスドメイン Entity 参照違反となるため、所有権を team ドメインに置く設計とした。</p>
 */
@Getter
@Builder
public class SystemAdminTeamSummaryResponse {

    private final Long id;
    private final String slug;
    private final String name;
    private final String nameKana;
    private final String nickname1;
    private final String nickname2;
    private final String template;
    private final String prefecture;
    private final String city;
    private final TeamEntity.Visibility visibility;
    private final Boolean supporterEnabled;
    private final Long memberCount;
    private final LocalDateTime archivedAt;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
