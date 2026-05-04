package com.mannschaft.app.membership.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 役職終了リクエスト DTO。
 *
 * <p>設計書: docs/features/F00.5_membership_basis.md §6.2 / §7.4.3</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class EndPositionRequest {

    /** 役職離任日時。NULL の場合はサーバ側で {@code NOW()} がセットされる。 */
    private LocalDateTime endedAt;
}
