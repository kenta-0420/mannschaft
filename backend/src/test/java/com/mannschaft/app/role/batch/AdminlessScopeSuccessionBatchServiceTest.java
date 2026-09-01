package com.mannschaft.app.role.batch;

import com.mannschaft.app.role.service.RoleSuccessionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 柱①「ADMINゼロ根治」§13 — {@link AdminlessScopeSuccessionBatchService} の受け入れテスト（試練・red）。
 *
 * <p>正本: docs/architecture/account_purge_last_admin_succession.md §13。
 * 金型: {@code VillageHeadmanSuccessionBatchServiceTest}（F17.1）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminlessScopeSuccessionBatchService 受け入れテスト（AC9・柱①ADMINゼロ根治）")
class AdminlessScopeSuccessionBatchServiceTest {

    @Mock
    private RoleSuccessionService roleSuccessionService;

    @InjectMocks
    private AdminlessScopeSuccessionBatchService batchService;

    @Test
    @DisplayName("AC9: バッチ処理対象時点でADMIN数0のactiveスコープが0件になる（昇格 or archive）")
    void バッチ実行後ADMIN数0のスコープが0件になる() {
        // 実装後は run() が例外なく完了し、是正件数（>=0）を返すことを期待する。
        // 骨格段階では常に UnsupportedOperationException を投げるため red で正しい。
        assertThatCode(() -> {
            int corrected = batchService.run();
            assertThat(corrected).isGreaterThanOrEqualTo(0);
        }).doesNotThrowAnyException();
        // TODO 出陣で実装後: run() の戻り値（是正件数）が全 admin-less スコープを
        // カバーし、実行後に ADMIN 数0の active スコープが0件であることを検証する。
    }

    @Test
    @DisplayName("AC9: 1スコープの失敗が他スコープ処理を止めない")
    void 一部スコープの失敗が他スコープ処理を止めない() {
        // 実装後は1スコープで例外が起きても run() 全体は完走する（他スコープの是正を継続する）
        // ことを期待する。骨格段階では常に UnsupportedOperationException を投げるため red で正しい。
        assertThatCode(() -> batchService.run())
                .doesNotThrowAnyException();
        // TODO 出陣で実装後: 個別スコープの是正失敗をモックで注入し、残りのスコープへの
        // 是正処理が継続されることを検証する（村ドメイン VillageHeadmanSuccessionBatchService の
        // 「1件失敗が全体を止めない」流儀を踏襲）。
    }
}
