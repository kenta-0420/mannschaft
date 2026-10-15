package com.mannschaft.app.common.architecture.fixtures;

/**
 * fixture: D-6 番人の偽陰性ゼロ証明メタテスト用のダミー DTO（応答型）。
 *
 * <p>{@code @jakarta.persistence.Entity} を持たない素の DTO。Controller の戻り型に用いても
 * D-6 番人は違反として検出<b>してはならない</b>（＝合格すべき対照ケース）。
 */
public record DummyD6ResponseDto(Long id, String label) {
}
