package com.mannschaft.app.common.architecture.fixtures;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * fixture: D-6 番人（{@link com.mannschaft.app.common.architecture.ControllerEntityResponseArchTest}）
 * の偽陰性ゼロ証明メタテスト用のダミー JPA Entity。
 *
 * <p>クラス名は<b>あえて {@code *Entity} で終わらせる必要はない</b>が、ここでは分かりやすさの
 * ため付けている。番人は命名ではなく {@code @jakarta.persistence.Entity} アノテーションの有無で
 * 判定するため、このクラスが「Controller の戻り型に露出してはならない Entity」として検出される。
 *
 * <p>本番プロダクションコードではなく test 配下の fixture であり、番人本体は
 * {@code @AnalyzeClasses(importOptions = DoNotIncludeTests.class)} で test を除外するため
 * 本番の D-6 解析には一切混入しない。ただし {@code @SpringBootTest}（{@code ddl-auto: create}）
 * の Hibernate エンティティスキャン対象にはなるため、{@code @Id} を持つ妥当な Entity として
 * 定義し、テストスキーマ生成を壊さないようにしている。
 */
@Entity
@Table(name = "dummy_d6_exposed_entity")
public class DummyD6ExposedEntity {

    @Id
    private Long id;

    private String secret;

    public Long getId() {
        return id;
    }

    public String getSecret() {
        return secret;
    }
}
