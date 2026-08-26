package com.mannschaft.app.reservation.entity;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

/**
 * {@link ReservationMenuLineEntity} の複合主キー（menu_id × line_id・F03.4.1 §3）。
 *
 * <p>提供関係は 1 組 1 行のリレーション表であり、サロゲートキー不要
 * （行はメニュー上限20×ライン上限20=最大400行/チームでシャーディング負荷にならない・§11）。</p>
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class ReservationMenuLineId implements Serializable {

    private static final long serialVersionUID = 1L;

    /** メニューID（BINARY(16) UUIDv7）。 */
    private UUID menuId;

    /** ラインID（reservation_lines.id・BIGINT UNSIGNED）。 */
    private Long lineId;
}
