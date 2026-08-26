package com.mannschaft.app.tournament.roster.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

/**
 * 自チーム分メンバー表の提出（UPSERT）リクエスト（F08.7.1/05 §4 PUT rosters/me）。
 *
 * <p>選手・ベンチ役員をまとめて全置換で提出する。{@code players} / {@code staff} は null 可（空扱い）。</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class SubmitRosterRequest {

    @Valid
    private List<PlayerEntry> players;

    @Valid
    private List<StaffEntry> staff;

    @Getter
    @Setter
    @NoArgsConstructor
    public static class PlayerEntry {
        @NotNull
        private Long userId;

        private Boolean isStarter;

        private Integer jerseyNumber;

        @Size(max = 30)
        private String position;

        @Size(max = 32)
        private String registrationNumber;

        /** 着用ユニフォームセット（自チームの team_uniform_set・NULL 可） */
        private UUID uniformSetId;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class StaffEntry {
        @NotBlank
        @Size(max = 32)
        private String role;

        @NotBlank
        @Size(max = 128)
        private String name;

        /** アプリ登録済みなら設定（NULL 可＝外部スタッフ） */
        private Long userId;
    }
}
