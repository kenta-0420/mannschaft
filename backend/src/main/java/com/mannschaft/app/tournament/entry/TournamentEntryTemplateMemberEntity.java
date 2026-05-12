package com.mannschaft.app.tournament.entry;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * 大会エントリーテンプレートメンバーエンティティ。
 *
 * <p>エントリーテンプレートに含まれる選手（メンバー）一覧を表す。
 * template_id は同一ドメイン内の tournament_entry_templates への参照（CASCADE 許可）。
 * user_id は users テーブルへのクロスドメイン参照のため FK を持たない。
 * created_at / updated_at はテンプレート本体（TournamentEntryTemplateEntity）で管理する。</p>
 */
@Entity
@Table(name = "tournament_entry_template_members")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class TournamentEntryTemplateMemberEntity extends UuidV7Entity {

    /** 同ドメイン内参照: tournament_entry_templates.id */
    @Column(nullable = false, columnDefinition = "CHAR(36)")
    private UUID templateId;

    /** クロスドメイン参照: users.id（FK なし、アプリ層で整合性保証） */
    @Column(nullable = false)
    private Long userId;

    private Integer jerseyNumber;

    @Column(length = 30)
    private String position;

    @Column(nullable = false)
    @Builder.Default
    private Short sortOrder = (short) 0;
}
