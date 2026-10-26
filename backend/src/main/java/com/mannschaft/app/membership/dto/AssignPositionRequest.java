package com.mannschaft.app.membership.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 役職割当リクエスト DTO。
 *
 * <p>設計書: docs/features/F00.5_membership_basis.md §6.2 / §7.4</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class AssignPositionRequest {

    @NotNull
    private Long positionId;

    /** 役職就任日時。NULL の場合はサーバ側で {@code NOW()} がセットされる。 */
    private LocalDateTime startedAt;

    /** 役職を付与した管理者の userId。 */
    private Long assignedBy;
}
