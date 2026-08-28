package com.mannschaft.app.todo.dto;

/**
 * SYSTEM 既定ステータスラベルのキャッシュ安全な View（issue #2544 D 群）。
 *
 * <h2>なぜ Entity をそのままキャッシュしないのか</h2>
 * <p>
 * {@code systemDefaultLabels} キャッシュは旧実装で
 * {@code Map<String, TodoStatusLabelEntity>} を載せていた。当該 Entity は
 * {@code @Getter}（setter なし）＋ {@code @SuperBuilder} ＋ protected な既定コンストラクタであり、
 * {@code RedisConfig} が使う素の {@code ObjectMapper}（フィールド可視性 {@code PUBLIC_ONLY}）では
 * 復元時に private フィールドへ書き込む経路が無い。すなわちキャッシュヒット時に
 * <b>全フィールドが null の抜け殻</b>が返り、既定ラベル表示が静かに壊れる
 * （例外にならないので fail-open ログにも残らない）。
 * </p>
 * <p>
 * 唯一の利用箇所（{@code TodoResponseConverter#systemDefaultLabelInfo}）が必要とするのは
 * ID・名称・バケット名・色の 4 項目だけなので、record へ射影してキャッシュする。
 * record は canonical constructor 経由で確実に往復できる。
 * </p>
 *
 * @param id     ラベル ID
 * @param name   ラベル名
 * @param bucket バケット名（{@code TodoStatusBucket#name()}）
 * @param color  表示色
 */
public record TodoStatusLabelView(Long id, String name, String bucket, String color) {
}
