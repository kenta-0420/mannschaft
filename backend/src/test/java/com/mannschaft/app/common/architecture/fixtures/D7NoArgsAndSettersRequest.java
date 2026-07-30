package com.mannschaft.app.common.architecture.fixtures;

/**
 * D-7 番人の<b>偽陽性ゼロ</b>証明用 fixture: 本リポで最も多い
 * 「引数無しコンストラクタ ＋ 全引数コンストラクタ ＋ setter」様式
 * （main では Lombok {@code @Data + @NoArgsConstructor + @AllArgsConstructor} で生成される形）。
 *
 * <p>コンストラクタは<b>2 本</b>あり {@code @JsonCreator} は無いが、
 * <b>引数無しコンストラクタがあるため Jackson は既定 creator で実体を生成でき</b>、
 * その後 setter で値を注入できる。したがって D-7 違反ではない。
 *
 * <p>この fixture は「コンストラクタ 2 本以上」だけを条件にすると<b>大量の偽陽性</b>が出ることの
 * 反証でもある（番人が引数無しコンストラクタの有無を見ていることの担保）。
 */
public class D7NoArgsAndSettersRequest {

    private Long categoryId;

    private String title;

    public D7NoArgsAndSettersRequest() {
    }

    public D7NoArgsAndSettersRequest(Long categoryId, String title) {
        this.categoryId = categoryId;
        this.title = title;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }
}
