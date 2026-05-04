package com.mannschaft.app.membership;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F00.5 メンバーシップ基盤の DDL 制約検証統合テスト（Phase 3 で再構築予定）。
 *
 * <p><b>Phase 2 では @Disabled。理由</b>:</p>
 *
 * <p>Phase 2 リリース時点で 3 種類のアプローチを試行したが、いずれも CI 環境で
 * 期待した検証ができなかった:</p>
 *
 * <ol>
 *   <li><b>v1 案（Spring Boot Test 経由）</b>: {@code AbstractMySqlIntegrationTest} を継承する形。
 *       しかし {@code application-test.yml} が {@code ddl-auto=create-drop} +
 *       {@code flyway.enabled=false} を指定しているため、Hibernate がスキーマを生成し、
 *       Flyway 由来の CHECK / 部分 UNIQUE 制約が DB に乗らない。
 *       「拒否されるはずなのに INSERT が成功する」状態で 4 件のテストが AssertionError。</li>
 *
 *   <li><b>v2 案（Spring 非依存・Flyway 直接適用）</b>: {@code @SpringBootTest} を外し、
 *       Testcontainers の MySQL に Flyway を Java API で直接 migrate する形。
 *       しかし本番運用中の main 全 Flyway マイグレ（V2.001 〜 V60.005 の数百ファイル）を
 *       Testcontainers の素の MySQL 8.0 で連続適用すると、本案件と無関係なマイグレで
 *       {@code SQLSyntaxErrorException} が発生する（CI 環境で初めて全件 Flyway 適用が
 *       実行されたことで発覚した別件のバグ）。これは F00.5 の責任範囲外。</li>
 *
 *   <li><b>v3 案（F00.5 単独 Flyway）</b>: V60.001 〜 V60.005 だけを test 用フォルダに
 *       コピーする案。しかし memberships は users への FK を持つため、users 等の
 *       依存テーブルがないと CREATE TABLE 自体が失敗する。FK 依存マイグレを
 *       本番から切り離して保守するのは非現実的。</li>
 * </ol>
 *
 * <p><b>Phase 3 での再構築方針</b>:</p>
 *
 * <ul>
 *   <li>Phase 3 では Service 層を memberships に切り替えるため、{@code MembershipService} の
 *       統合テスト基盤を改めて整備する。その際に DDL 制約発火検証も合わせて組み込む。</li>
 *   <li>Phase 3 着手前に <b>main の Flyway マイグレが Testcontainers MySQL 8.0 で
 *       通る状態</b> にする別 PR を起こす（v2 案で発見された別件の syntax error 修正）。</li>
 *   <li>その後、本テストを再有効化し DDL 制約発火を JdbcTemplate ベースで検証する。</li>
 * </ul>
 *
 * <p><b>Phase 2 では DDL 検証は次の 3 段で代替する</b>:</p>
 *
 * <ol>
 *   <li>マイグレファイル自体のコードレビュー（PR レビュー時）</li>
 *   <li>dev / staging への適用時の手動 dry-run（設計書 §14.4）</li>
 *   <li>{@code MembershipServiceTest}（モック）が Service 層の振る舞いをカバー</li>
 * </ol>
 *
 * <p>設計書: docs/features/F00.5_membership_basis.md §14.2 / §14.4</p>
 */
@DisplayName("F00.5 メンバーシップ基盤 DDL 制約検証（Phase 3 で再構築）")
@Disabled("Phase 3 で Service 統合テスト基盤と一緒に再構築する。詳細は本クラス Javadoc 参照")
class MembershipBasisIntegrationTest {

    @Test
    @DisplayName("プレースホルダー: Phase 3 で実装")
    void placeholder() {
        // 実装は Phase 3 で行う。
    }
}
