package com.mannschaft.app.reservation.dto;

/**
 * テンプレ保存＋同期自動生成の統合レスポンス（F03.4.5 §3.1・POST/PATCH 共通）。
 *
 * <p>保存結果（{@link SlotTemplateResponse}）と生成結果（{@link SlotGenerationResultDto}）を
 * 1 レスポンスに包む。これにより「保存したのに枠がない」というマスター指摘の混乱が構造的に消え、
 * FE は保存レスポンスから「28日先までの枠を◯件作成しました」を即時トースト提示できる。</p>
 */
public record SlotTemplateSaveResponse(
        SlotTemplateResponse template,
        SlotGenerationResultDto generation) {
}
