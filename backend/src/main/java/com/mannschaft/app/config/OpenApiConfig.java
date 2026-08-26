package com.mannschaft.app.config;

import com.mannschaft.app.reflection.RecallDirection;
import com.mannschaft.app.schedule.controller.OrgScheduleController;
import com.mannschaft.app.schedule.controller.TeamScheduleController;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.ParameterCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * springdoc-openapi グローバル設定。
 *
 * <p>ネストされた record / enum など springdoc が自動で named component として
 * 登録しないスキーマを明示的に {@code components/schemas} に追加する。</p>
 */
@Configuration
public class OpenApiConfig {

    /**
     * 型付きパス変数 {@link OrgScopeId} / {@link TeamScopeId}（課題 #12・案A）を、OpenAPI 上では
     * 素の {@code Long}（{@code integer/int64}）として出力する。
     *
     * <p><b>なぜ {@code ParameterCustomizer} なのか（根本原因）:</b> これらの型には
     * {@code Converter<String, OrgScopeId>} / {@code Converter<String, TeamScopeId>} が登録されている。
     * springdoc はパス変数のスキーマを解決する際、登録済みコンバータの<b>ソース型（String）</b>を見て
     * {@code {type: string}} と推論する。この推論はパラメータ処理段で行われ、モデル層の型置換
     * （{@code SpringDocUtils.replaceWithClass}）を<b>素通り</b>するため、置換方式では
     * {@code {orgId}}/{@code {teamId}} が {@code integer} から {@code string} へドリフトしてしまう
     * （当初 replaceWithClass を試みたが CI の OpenAPI Drift Check で string 化が判明）。
     * そこでパラメータ処理の正当な拡張点である {@code ParameterCustomizer} で、対象型のパス変数の
     * スキーマを {@code integer/int64} に明示上書きし、従来（{@code Long} 時代）の表現を維持して
     * 生成物のドリフトを根治する。対象は本 2 型のパス変数のみ（3 コントローラ計 11 箇所）。</p>
     *
     * <p><b>チーム/組織スケジュール系2コントローラは対象外（CMP-054 P1是正）:</b>
     * {@link TeamScheduleController} / {@link OrgScheduleController} の {@code teamPublicId} /
     * {@code orgPublicId} は、実装が数値ID・slug の両方を受け付ける（{@code ScopeSlugResolution} の
     * 数値高速パス＋slug 解決）。{@code integer/int64} と描画するのは実装より狭い契約を宣言する嘘に
     * なるため、この2コントローラだけはこの Customizer を適用せず、springdoc の既定推論
     * （登録済み {@code Converter<String, ...>} のソース型＝{@code string}）に委ねる。
     * 既存4コントローラ（{@code EventDismissalController} 等）も同様に slug を受け付けており
     * 同じ嘘を抱えているが、それらの契約修正は本変更の対象外（別課題）。</p>
     */
    @Bean
    public ParameterCustomizer scopeIdParameterCustomizer() {
        return (parameterModel, methodParameter) -> {
            if (parameterModel == null) {
                return null;
            }
            Class<?> type = methodParameter.getParameterType();
            if (type != OrgScopeId.class && type != TeamScopeId.class) {
                return parameterModel;
            }
            Class<?> declaringClass = methodParameter.getDeclaringClass();
            if (declaringClass == TeamScheduleController.class || declaringClass == OrgScheduleController.class) {
                return parameterModel;
            }
            parameterModel.setSchema(new IntegerSchema().format("int64"));
            return parameterModel;
        };
    }

    /**
     * servers フィールドを相対パス {@code "/"} に正規化する。
     *
     * <p>springdoc は デフォルトで生成時のホスト名・ポートを servers に埋め込む。
     * {@code generateOpenApiDocs} タスクが {@code :8082} でフォーク起動するため、
     * 実行環境が変わるたびに servers の url が変化し、git diff に無意味なノイズが混入する。
     * このカスタマイザーで常に {@code "/"} に上書きすることで生成結果を決定論的にする。</p>
     *
     * <p>openapi-typescript の {@code generate:types} はサーバー URL を型生成に使用しないため、
     * フロントエンドの型生成への影響はない。</p>
     */
    @Bean
    public OpenApiCustomizer serverUrlNormalizationCustomizer() {
        return openApi -> openApi.setServers(List.of(new Server().url("/")));
    }

    /**
     * {@link com.mannschaft.app.reflection.RecallDirection} を named component として登録する。
     *
     * <p>springdoc は ネストされた record フィールドの enum を inline 展開するため、
     * {@code @Schema(ref = ...)} を使ってもフィールドに {@code $ref} は付くが
     * スキーマ本体が {@code components/schemas} に存在しない問題が生じる（PR #1917 根治）。
     * このカスタマイザーで手動登録することで常に named component として出力される。</p>
     */
    @Bean
    public OpenApiCustomizer recallDirectionSchemaCustomizer() {
        return openApi -> {
            if (openApi.getComponents() == null) {
                openApi.components(new Components());
            }
            if (openApi.getComponents().getSchemas() == null
                    || !openApi.getComponents().getSchemas().containsKey("RecallDirection")) {
                @SuppressWarnings("rawtypes")
                Schema<String> schema = new StringSchema();
                schema.setName("RecallDirection");
                schema.setDescription(
                        "暗記カード（TERM_CARD）の出題方向（§13-B）。"
                                + "到来済み想起予定日数のパリティで決定論的に算出する。");
                schema.setEnum(Arrays.stream(RecallDirection.values())
                        .map(Enum::name)
                        .collect(Collectors.toList()));
                openApi.getComponents().addSchemas("RecallDirection", schema);
            }
        };
    }

    /**
     * 全スキーマのプロパティ順をアルファベット順に正規化し、生成を決定論的にする。
     *
     * <p><b>背景（根本原因）:</b> springdoc/Jackson は、レコードコンポーネント以外の
     * 派生 getter（例: {@code getScopeIdOrDefault()}・{@code isRemindAtValid()}）や、
     * Spring Data の {@code Page}/{@code Pageable}/{@code Sort} ラッパーのプロパティを
     * {@code Class.getDeclaredMethods()} の反射順で収集する。この反射順は JVM 実行ごとに
     * 変動しうる（JLS で順序未規定）ため、{@code generateOpenApiDocs} を複数回走らせると
     * 同一コードでも当該スキーマのプロパティ順だけが入れ替わり、{@code docs/openapi.json} に
     * 非決定的な差分（ノイズ）が生じる。これにより 2 回目以降の生成で diff がゼロにならず、
     * フロントエンドの {@code generate:types} 由来の生成型もドリフトし、FE CI の
     * 生成型ドリフト検査が間欠的に fail する温床となる。</p>
     *
     * <p><b>対処方針:</b> 当初は「経験的に非決定と判明したスキーマ群のみ」を対象に絞ろうとしたが、
     * 低カーディナリティのスキーマは複数回の生成でも偶然プロパティ順が一致しうるため、
     * 連続生成の比較（サンプリング）では非決定スキーマを網羅的に列挙できない（実際に
     * {@code SharedMemoEntryResponse}・{@code EntryMemberSummaryItemResponse} は 4・5 回目の
     * 生成で初めて表面化した）。したがって絞り込み方式では決定性を「保証」できない。
     * そこで <b>全ての named component スキーマのプロパティをキーのアルファベット順に
     * 再帰的に固定する</b>ことで、どのスキーマが反射順非決定であっても出力を完全に
     * 決定論化する。これは生成物（自動生成 JSON）に対する一回限りの正準化であり、
     * 本対応の取り込み後はあらゆる再生成が byte 一致する。</p>
     *
     * <p><b>正規化対象外:</b> プロパティ値の内部フィールド順
     * （{@code type}/{@code description}/{@code $ref} 等）・{@code required}/{@code enum} 配列順・
     * {@code paths}/{@code components.schemas}/{@code tags} のトップレベル順は、いずれも実測で
     * 安定（連続生成で一致）しているため触らない。これにより不要な差分を抑える。</p>
     *
     * <p>本カスタマイザーは {@link Order} を最も低い優先度に設定し、
     * 他のカスタマイザー（{@link #recallDirectionSchemaCustomizer()} 等）が
     * スキーマを追加した後に最後に走るようにしている。</p>
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    public OpenApiCustomizer deterministicSchemaPropertyOrderCustomizer() {
        return openApi -> {
            if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
                return;
            }
            openApi.getComponents().getSchemas().values().forEach(this::sortPropertiesRecursively);
        };
    }

    /**
     * スキーマのプロパティをキーのアルファベット順に再帰的に並べ替える。
     *
     * <p>直下の {@code properties} に加え、インライン展開された入れ子オブジェクト
     * （{@code properties} の各値・{@code items}・{@code additionalProperties}・
     * {@code allOf}/{@code anyOf}/{@code oneOf}）も辿って正規化する。
     * 入れ子の大半は {@code $ref} 参照だが、インライン object が非決定だった場合にも
     * 取りこぼさないよう再帰する。</p>
     *
     * @param schema 対象スキーマ（{@code null} 安全）
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void sortPropertiesRecursively(Schema schema) {
        if (schema == null) {
            return;
        }
        Map<String, Schema> properties = schema.getProperties();
        if (properties != null && !properties.isEmpty()) {
            Map<String, Schema> sorted = new LinkedHashMap<>(new TreeMap<>(properties));
            schema.setProperties(sorted);
            sorted.values().forEach(this::sortPropertiesRecursively);
        }
        if (schema.getItems() != null) {
            sortPropertiesRecursively(schema.getItems());
        }
        Object additionalProperties = schema.getAdditionalProperties();
        if (additionalProperties instanceof Schema) {
            sortPropertiesRecursively((Schema) additionalProperties);
        }
        sortComposedSchemas(schema.getAllOf());
        sortComposedSchemas(schema.getAnyOf());
        sortComposedSchemas(schema.getOneOf());
    }

    /**
     * {@code allOf}/{@code anyOf}/{@code oneOf} の各要素スキーマを再帰的に正規化する。
     *
     * @param composed 合成スキーマのリスト（{@code null} 安全）
     */
    @SuppressWarnings("rawtypes")
    private void sortComposedSchemas(List<Schema> composed) {
        if (composed != null) {
            composed.forEach(this::sortPropertiesRecursively);
        }
    }
}
