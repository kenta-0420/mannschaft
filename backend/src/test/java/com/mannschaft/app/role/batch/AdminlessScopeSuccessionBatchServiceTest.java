package com.mannschaft.app.role.batch;

import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.role.service.RoleSuccessionService;
import com.mannschaft.app.role.service.RoleSuccessionService.BatchSuccessionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 柱①「ADMINゼロ根治」§13 — {@link AdminlessScopeSuccessionBatchService} の受け入れテスト（AC9）。
 *
 * <p>正本: docs/architecture/account_purge_last_admin_succession.md §13。
 * 金型: {@code VillageHeadmanSuccessionBatchServiceTest}（F17.1）。
 * 検分反映（P2-1）でスコープ ID 取得を keyset ページング化したため、
 * {@code findTeamIdsWithoutActiveAdminPage(afterId, pageSize)} を段階的にモックする
 * （ページサイズ未満の返却でループが終了する前提）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminlessScopeSuccessionBatchService 受け入れテスト（AC9・柱①ADMINゼロ根治）")
class AdminlessScopeSuccessionBatchServiceTest {

    @Mock
    private UserRoleRepository userRoleRepository;
    @Mock
    private RoleSuccessionService roleSuccessionService;

    @InjectMocks
    private AdminlessScopeSuccessionBatchService batchService;

    @Test
    @DisplayName("AC9: バッチ処理対象時点でADMIN数0のactiveスコープが0件になる（昇格 or archive）")
    void バッチ実行後ADMIN数0のスコープが0件になる() {
        given(userRoleRepository.findTeamIdsWithoutActiveAdminPage(0L, 500)).willReturn(List.of(10L, 20L));
        given(userRoleRepository.findOrganizationIdsWithoutActiveAdminPage(0L, 500)).willReturn(List.of(30L));
        given(roleSuccessionService.promoteForBatchSuccession(10L, "TEAM"))
                .willReturn(BatchSuccessionResult.PROMOTED);
        given(roleSuccessionService.promoteForBatchSuccession(20L, "TEAM"))
                .willReturn(BatchSuccessionResult.ARCHIVED);
        given(roleSuccessionService.promoteForBatchSuccession(30L, "ORGANIZATION"))
                .willReturn(BatchSuccessionResult.PROMOTED);

        int corrected = batchService.run();

        // 3件すべて是正（昇格2件+アーカイブ1件）→ 実行後は対象スコープの是正が全件完了する。
        assertThat(corrected).isEqualTo(3);
        verify(roleSuccessionService).promoteForBatchSuccession(10L, "TEAM");
        verify(roleSuccessionService).promoteForBatchSuccession(20L, "TEAM");
        verify(roleSuccessionService).promoteForBatchSuccession(30L, "ORGANIZATION");
    }

    @Test
    @DisplayName("AC9: 1スコープの失敗が他スコープ処理を止めない")
    void 一部スコープの失敗が他スコープ処理を止めない() {
        given(userRoleRepository.findTeamIdsWithoutActiveAdminPage(0L, 500)).willReturn(List.of(10L, 20L));
        given(userRoleRepository.findOrganizationIdsWithoutActiveAdminPage(0L, 500)).willReturn(List.of());
        willThrow(new RuntimeException("是正失敗（テスト用）"))
                .given(roleSuccessionService).promoteForBatchSuccession(eq(10L), eq("TEAM"));
        given(roleSuccessionService.promoteForBatchSuccession(20L, "TEAM"))
                .willReturn(BatchSuccessionResult.PROMOTED);

        int corrected = batchService.run();

        // scopeId=10 で例外が起きても scopeId=20 の是正は実行され続ける。
        assertThat(corrected).isEqualTo(1);
        verify(roleSuccessionService, times(1)).promoteForBatchSuccession(20L, "TEAM");
    }

    @Test
    @DisplayName("AC9/P2-1: 500件ちょうどのページを返すと次ページも取得する（keysetページング継続）")
    void ページサイズちょうどなら次ページも取得する() {
        List<Long> fullPage = java.util.stream.LongStream.rangeClosed(1, 500).boxed().toList();
        given(userRoleRepository.findTeamIdsWithoutActiveAdminPage(0L, 500)).willReturn(fullPage);
        given(userRoleRepository.findTeamIdsWithoutActiveAdminPage(500L, 500)).willReturn(List.of());
        given(userRoleRepository.findOrganizationIdsWithoutActiveAdminPage(0L, 500)).willReturn(List.of());
        given(roleSuccessionService.promoteForBatchSuccession(org.mockito.ArgumentMatchers.anyLong(), eq("TEAM")))
                .willReturn(BatchSuccessionResult.PROMOTED);

        int corrected = batchService.run();

        assertThat(corrected).isEqualTo(500);
        verify(userRoleRepository).findTeamIdsWithoutActiveAdminPage(500L, 500);
    }
}
