package com.mannschaft.app.role.batch;

import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.role.service.RoleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link UserRolePurgeBackfillBatchService} 単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserRolePurgeBackfillBatchService 単体テスト")
class UserRolePurgeBackfillBatchServiceTest {

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private RoleService roleService;

    @InjectMocks
    private UserRolePurgeBackfillBatchService batchService;

    // -----------------------------------------------------------------------
    // テストデータ生成ヘルパー
    // -----------------------------------------------------------------------

    private UserRoleEntity buildOrgRole(Long id, Long userId, Long organizationId) {
        return UserRoleEntity.builder()
                .id(id)
                .userId(userId)
                .roleId(1L)
                .organizationId(organizationId)
                .build();
    }

    private UserRoleEntity buildTeamRole(Long id, Long userId, Long teamId) {
        return UserRoleEntity.builder()
                .id(id)
                .userId(userId)
                .roleId(1L)
                .teamId(teamId)
                .build();
    }

    private UserRoleEntity buildSystemAdminRole(Long id, Long userId) {
        return UserRoleEntity.builder()
                .id(id)
                .userId(userId)
                .roleId(99L)
                // teamId・organizationId ともに NULL = SYSTEM_ADMIN
                .build();
    }

    // -----------------------------------------------------------------------
    // 正常系テスト
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("正常系: 孤児 userId が複数件ある場合、全件 removeMemberWithoutAdminCheck を呼ぶ")
    void backfill_正常_孤児複数件() {
        Long userId1 = 100L;
        Long userId2 = 200L;

        given(userRoleRepository.findOrphanUserIds(any()))
                .willReturn(List.of(userId1, userId2));

        UserRoleEntity role1 = buildOrgRole(1L, userId1, 10L);
        UserRoleEntity role2 = buildTeamRole(2L, userId1, 20L);
        given(userRoleRepository.findAllByUserId(userId1))
                .willReturn(List.of(role1, role2));

        UserRoleEntity role3 = buildOrgRole(3L, userId2, 30L);
        given(userRoleRepository.findAllByUserId(userId2))
                .willReturn(List.of(role3));

        assertThatCode(() -> batchService.backfill()).doesNotThrowAnyException();

        // userId1 の 2 件
        verify(roleService).removeMemberWithoutAdminCheck(10L, "ORGANIZATION", userId1);
        verify(roleService).removeMemberWithoutAdminCheck(20L, "TEAM", userId1);
        // userId2 の 1 件
        verify(roleService).removeMemberWithoutAdminCheck(30L, "ORGANIZATION", userId2);
    }

    @Test
    @DisplayName("正常系: 孤児 userId が 0 件のとき removeMemberWithoutAdminCheck は呼ばれない")
    void backfill_正常_孤児0件() {
        given(userRoleRepository.findOrphanUserIds(any()))
                .willReturn(List.of());

        assertThatCode(() -> batchService.backfill()).doesNotThrowAnyException();

        verify(roleService, never()).removeMemberWithoutAdminCheck(anyLong(), anyString(), anyLong());
    }

    @Test
    @DisplayName("正常系: SYSTEM_ADMIN 行（teamId・organizationId 共 NULL）はスキップし他の行は処理する")
    void backfill_正常_SystemAdmin行はスキップ() {
        Long userId = 100L;

        given(userRoleRepository.findOrphanUserIds(any()))
                .willReturn(List.of(userId));

        UserRoleEntity sysAdmin = buildSystemAdminRole(1L, userId);
        UserRoleEntity orgRole = buildOrgRole(2L, userId, 10L);
        given(userRoleRepository.findAllByUserId(userId))
                .willReturn(List.of(sysAdmin, orgRole));

        assertThatCode(() -> batchService.backfill()).doesNotThrowAnyException();

        // SYSTEM_ADMIN はスキップし、ORG ロールのみ削除（合計1回のみ呼ばれる）
        verify(roleService, times(1)).removeMemberWithoutAdminCheck(anyLong(), anyString(), eq(userId));
        verify(roleService, times(1)).removeMemberWithoutAdminCheck(10L, "ORGANIZATION", userId);
    }

    // -----------------------------------------------------------------------
    // 異常系テスト
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("異常系: 1件の userId で例外が発生しても、残りの userId は処理を継続する")
    void backfill_異常_1件失敗しても継続() {
        Long userId1 = 100L;
        Long userId2 = 200L;

        given(userRoleRepository.findOrphanUserIds(any()))
                .willReturn(List.of(userId1, userId2));

        // userId1 は findAllByUserId が例外を投げる
        given(userRoleRepository.findAllByUserId(userId1))
                .willThrow(new RuntimeException("DB error for userId1"));

        UserRoleEntity role2 = buildOrgRole(3L, userId2, 30L);
        given(userRoleRepository.findAllByUserId(userId2))
                .willReturn(List.of(role2));

        // 例外が外に漏れないこと
        assertThatCode(() -> batchService.backfill()).doesNotThrowAnyException();

        // userId2 の処理は正常に実行される
        verify(roleService).removeMemberWithoutAdminCheck(30L, "ORGANIZATION", userId2);
    }

    @Test
    @DisplayName("異常系: 同一 userId の 1 つのロール削除が失敗しても他のロールは処理を継続する")
    void backfill_異常_1ロール失敗しても同一userId内の他ロール継続() {
        Long userId = 100L;

        given(userRoleRepository.findOrphanUserIds(any()))
                .willReturn(List.of(userId));

        UserRoleEntity role1 = buildOrgRole(1L, userId, 10L);
        UserRoleEntity role2 = buildTeamRole(2L, userId, 20L);
        given(userRoleRepository.findAllByUserId(userId))
                .willReturn(List.of(role1, role2));

        // role1（ORG 10L）の削除が例外を投げる
        willThrow(new RuntimeException("delete failed"))
                .given(roleService).removeMemberWithoutAdminCheck(eq(10L), eq("ORGANIZATION"), eq(userId));

        // 例外が外に漏れないこと
        assertThatCode(() -> batchService.backfill()).doesNotThrowAnyException();

        // role1 の試みは行われた
        verify(roleService).removeMemberWithoutAdminCheck(10L, "ORGANIZATION", userId);
        // role2 も処理される
        verify(roleService).removeMemberWithoutAdminCheck(20L, "TEAM", userId);
    }

    @Test
    @DisplayName("正常系: BATCH_SIZE 上限を Pageable に正しく渡している")
    void backfill_正常_BATCH_SIZEでPageRequest作成() {
        // BATCH_SIZE=50 で PageRequest.of(0, 50) が渡されることを確認
        given(userRoleRepository.findOrphanUserIds(PageRequest.of(0, 50)))
                .willReturn(List.of());

        assertThatCode(() -> batchService.backfill()).doesNotThrowAnyException();

        verify(userRoleRepository).findOrphanUserIds(PageRequest.of(0, 50));
    }
}
