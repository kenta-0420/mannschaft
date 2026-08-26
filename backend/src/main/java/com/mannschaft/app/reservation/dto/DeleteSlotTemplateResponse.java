package com.mannschaft.app.reservation.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.UUID;

/**
 * 週間テンプレート削除結果DTO（F03.4.2 §4 DELETE）。
 *
 * <p>{@code orphanedSlotCount} = 物理削除により {@code template_id} が SET NULL された
 * 生成済み枠数（情報提供のみ・枠自体は通常枠として残る）。</p>
 */
@Builder
@Getter
public class DeleteSlotTemplateResponse {

    UUID id;
    boolean deleted;
    long orphanedSlotCount;
}
