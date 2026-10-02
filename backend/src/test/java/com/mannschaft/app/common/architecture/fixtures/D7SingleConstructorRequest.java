package com.mannschaft.app.common.architecture.fixtures;

/**
 * D-7 番人の<b>偽陽性ゼロ</b>証明用 fixture: 全フィールド {@code final}・setter 無しだが
 * コンストラクタが<b>1 本だけ</b>の DTO
 * （main では Lombok {@code @Getter + @RequiredArgsConstructor} で生成される形）。
 *
 * <p>本リポは {@code -parameters}（{@code build.gradle.kts} の {@code JavaCompile} 設定）を
 * 有効にしており、Spring Boot 既定の {@code ParameterNamesModule} と併せて
 * <b>唯一の引数付きコンストラクタ</b>は暗黙の properties-based creator として採用される。
 * よって {@code @JsonCreator} が無くても壊れない＝ D-7 違反ではない
 * （実測の裏取りは {@code JsonRequestBodyCreatorArchTest} の Javadoc 参照 —
 * {@code SendMessageRequest} は初版がまさにこの形で正常動作していた）。
 *
 * <p>「全フィールド final ＋ setter 無し」だけを条件にすると偽陽性になることの反証。
 */
public class D7SingleConstructorRequest {

    private final Long categoryId;

    private final String title;

    public D7SingleConstructorRequest(Long categoryId, String title) {
        this.categoryId = categoryId;
        this.title = title;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public String getTitle() {
        return title;
    }
}
