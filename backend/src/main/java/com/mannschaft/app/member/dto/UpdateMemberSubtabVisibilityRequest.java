package com.mannschaft.app.member.dto;

import com.mannschaft.app.dashboard.MinRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * CMP-260919-1140 Phase 1: メンバーサブタブ可視性設定の一括更新リクエスト。
 */
@Getter
@Setter
@NoArgsConstructor
public class UpdateMemberSubtabVisibilityRequest {

    @NotEmpty
    @Valid
    private List<SubtabVisibilityUpdateItem> subtabs;

    @Getter
    @Setter
    @NoArgsConstructor
    public static class SubtabVisibilityUpdateItem {
        @NotNull
        private String subtabKey;

        @NotNull
        private MinRole minRole;
    }
}
