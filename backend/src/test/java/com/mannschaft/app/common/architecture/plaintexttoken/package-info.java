/**
 * 番人 {@code PlaintextSecretTokenGuardArchTest}（CMP-260826-1457 番人1〜6）の自己検証用 fixture。
 *
 * <p>本番の番人は {@code ProductionClasses}（テストクラスを除く）だけを走査するため、ここに置いた
 * 違反クラスは本番の判定に混入しない。{@code PlaintextSecretTokenGuardConditionTest} がこのパッケージだけを
 * 取り込み、陽性（違反として検出されるべき形）と陰性（検出されてはならない形）の両方を評価する。</p>
 *
 * <p>{@code @SpringBootTest}（{@code ddl-auto: create}）の Hibernate エンティティ走査には入るため、Entity は
 * {@code @Id} を持つ妥当な形にし、テーブル名は {@code plaintext_guard_fixture_*} に揃える。
 * Repository は {@code @NoRepositoryBean} を付け、Spring Data の Bean にならないようにする。
 * Service 相当のクラスは Spring の stereotype 注釈を付けない（Bean にならない）。</p>
 */
package com.mannschaft.app.common.architecture.plaintexttoken;
