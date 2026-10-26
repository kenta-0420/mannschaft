package com.mannschaft.app.visibility.dto;

import com.mannschaft.app.visibility.VisibilityTemplateRuleType;

/**
 * 可視性テンプレートのルール 1 件を表すキャッシュ安全な View（issue #2544 D 群）。
 *
 * <h2>なぜ Entity をそのままキャッシュしないのか</h2>
 * <p>
 * {@code visibilityTemplate} キャッシュ（TTL 5 分・<b>閲覧認可の中核</b>）は旧実装で
 * {@code List<VisibilityTemplateRuleEntity>} を載せていた。当該 Entity は
 * {@code @Getter}（setter なし）＋ {@code @SuperBuilder} ＋ {@code @NoArgsConstructor} であり、
 * {@code RedisConfig} が使う素の {@code ObjectMapper}（フィールド可視性 {@code PUBLIC_ONLY}）では
 * 既定コンストラクタで空インスタンスを作った後に private フィールドへ書き込む経路が無い。
 * すなわちキャッシュヒット時に<b>全フィールドが null の抜け殻</b>が返り、
 * ルール評価が静かに崩れる（例外にならないので fail-open ログにも残らない）。
 * さらに {@code @ManyToOne(fetch = LAZY)} の {@code template} を辿るため
 * Hibernate プロキシがシリアライズ経路に混入する危険もある。
 * </p>
 * <p>
 * そこで評価に必要な値だけを持つ record（＝全項目が canonical constructor 経由で復元でき、
 * Jackson が確実に往復できる形）へ射影してキャッシュする。
 * ルール評価ロジックが参照するのは {@code ruleType} / {@code ruleTargetId} /
 * {@code ruleTargetText}、およびログ出力用の {@code ruleId} / {@code templateId} のみである。
 * </p>
 *
 * @param ruleId         ルール ID（ログ出力用）
 * @param templateId     所属テンプレート ID（ログ出力用。LAZY 関連を辿らないよう ID のみ保持する）
 * @param ruleType       ルール種別
 * @param ruleTargetId   ルール対象 ID（ユーザー / チーム / 組織 ID）
 * @param ruleTargetText ルール対象テキスト（{@code @USER_PRIMARY_TEAM} 等のプレースホルダ）
 */
public record VisibilityTemplateRuleView(
        Long ruleId,
        Long templateId,
        VisibilityTemplateRuleType ruleType,
        Long ruleTargetId,
        String ruleTargetText) {
}
