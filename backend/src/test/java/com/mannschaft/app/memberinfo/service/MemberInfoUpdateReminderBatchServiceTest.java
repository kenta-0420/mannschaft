package com.mannschaft.app.memberinfo.service;

import com.mannschaft.app.memberinfo.MemberInfoFieldType;
import com.mannschaft.app.memberinfo.TeamMemberInfoFieldEntity;
import com.mannschaft.app.memberinfo.TeamMemberInfoFieldRepository;
import com.mannschaft.app.memberinfo.batch.MemberInfoUpdateReminderBatchService;
import com.mannschaft.app.memberinfo.batch.MemberInfoUpdateReminderRunner;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link MemberInfoUpdateReminderBatchService}（オーケストレータ）の単体テスト。
 *
 * <p>Issue #2834 / CMP-056 第2群ロット2 の是正後は、本クラスは<b>トランザクションを持たない
 * オーケストレータ</b>であり、1 メンバーぶんの確定と通知は {@link MemberInfoUpdateReminderRunner} が
 * {@code REQUIRES_NEW} で担う。よってここでは「対象の列挙」「BATCH_LIMIT」「1 件の失敗で後続が
 * 止まらないこと」だけを検証し、期限切れ判定・クールダウン・通知の中身は
 * {@code MemberInfoUpdateReminderRunnerTest} が担当する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MemberInfoUpdateReminderBatchService 単体テスト（Issue #2834 / CMP-056）")
class MemberInfoUpdateReminderBatchServiceTest {

    private static final Long TEAM_ID = 1L;
    private static final Long TEAM_ID_2 = 2L;
    private static final Long FIELD_ID = 100L;
    private static final Long FIELD_ID_2 = 200L;
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 1, 1, 0, 0);

    @Mock
    private TeamMemberInfoFieldRepository fieldRepository;

    @Mock
    private MembershipRepository membershipRepository;

    @Mock
    private MemberInfoUpdateReminderRunner memberInfoUpdateReminderRunner;

    @InjectMocks
    private MemberInfoUpdateReminderBatchService batchService;

    @Test
    @DisplayName("対象チームが無い場合 → Runner を呼ばない")
    void run_noTeams_noRunnerCall() {
        given(fieldRepository.findDistinctTeamIdsWithRefreshInterval()).willReturn(List.of());

        batchService.run();

        verify(memberInfoUpdateReminderRunner, never()).markReminderSent(any(), any(), anyList(), any());
    }

    @Test
    @DisplayName("refreshIntervalMonths を持つフィールドが無いチームはスキップされる")
    void run_noTargetField_skipsTeam() {
        given(fieldRepository.findDistinctTeamIdsWithRefreshInterval()).willReturn(List.of(TEAM_ID));
        given(membershipRepository.findAllActiveByScope(ScopeType.TEAM, TEAM_ID))
                .willReturn(List.of(buildMembership(10L)));
        given(fieldRepository.findByTeamIdAndIsActiveTrueOrderBySortOrderAsc(TEAM_ID))
                .willReturn(List.of(buildField(FIELD_ID, TEAM_ID, null)));

        batchService.run();

        verify(memberInfoUpdateReminderRunner, never()).markReminderSent(any(), any(), anyList(), any());
    }

    @Test
    @DisplayName("AC-1: 1メンバーぶんが失敗しても後続メンバーは処理される（catch はオーケストレータ側）")
    void run_oneMemberFails_continuesWithRest() {
        given(fieldRepository.findDistinctTeamIdsWithRefreshInterval()).willReturn(List.of(TEAM_ID));
        given(membershipRepository.findAllActiveByScope(ScopeType.TEAM, TEAM_ID))
                .willReturn(List.of(buildMembership(10L), buildMembership(11L), buildMembership(12L)));
        given(fieldRepository.findByTeamIdAndIsActiveTrueOrderBySortOrderAsc(TEAM_ID))
                .willReturn(List.of(buildField(FIELD_ID, TEAM_ID, 6)));
        willThrow(new RuntimeException("模擬 DB 例外"))
                .given(memberInfoUpdateReminderRunner).markReminderSent(eq(TEAM_ID), eq(11L), anyList(), any());

        assertThatCode(() -> batchService.run()).doesNotThrowAnyException();

        // 失敗した 11L の後も 12L が処理される（是正前は全体が rollback-only になり全件巻き戻っていた）。
        verify(memberInfoUpdateReminderRunner).markReminderSent(eq(TEAM_ID), eq(10L), anyList(), any());
        verify(memberInfoUpdateReminderRunner).markReminderSent(eq(TEAM_ID), eq(11L), anyList(), any());
        verify(memberInfoUpdateReminderRunner).markReminderSent(eq(TEAM_ID), eq(12L), anyList(), any());
    }

    @Test
    @DisplayName("GDPRマスキング済み（userId=null）のメンバーは Runner に渡さない")
    void run_maskedMember_isSkipped() {
        given(fieldRepository.findDistinctTeamIdsWithRefreshInterval()).willReturn(List.of(TEAM_ID));
        given(membershipRepository.findAllActiveByScope(ScopeType.TEAM, TEAM_ID))
                .willReturn(List.of(buildMembership(null), buildMembership(10L)));
        given(fieldRepository.findByTeamIdAndIsActiveTrueOrderBySortOrderAsc(TEAM_ID))
                .willReturn(List.of(buildField(FIELD_ID, TEAM_ID, 6)));

        batchService.run();

        verify(memberInfoUpdateReminderRunner, times(1))
                .markReminderSent(any(), any(), anyList(), any());
        verify(memberInfoUpdateReminderRunner).markReminderSent(eq(TEAM_ID), eq(10L), anyList(), any());
    }

    @Test
    @DisplayName("BATCH_LIMIT(500) を超えるメンバーは翌日へ繰り越される")
    void run_batchLimit_stopsAt500() {
        given(fieldRepository.findDistinctTeamIdsWithRefreshInterval()).willReturn(List.of(TEAM_ID, TEAM_ID_2));
        given(membershipRepository.findAllActiveByScope(ScopeType.TEAM, TEAM_ID))
                .willReturn(LongStream.range(0, 300).mapToObj(this::buildMembership).toList());
        given(membershipRepository.findAllActiveByScope(ScopeType.TEAM, TEAM_ID_2))
                .willReturn(LongStream.range(1000, 1300).mapToObj(this::buildMembership).toList());
        given(fieldRepository.findByTeamIdAndIsActiveTrueOrderBySortOrderAsc(TEAM_ID))
                .willReturn(List.of(buildField(FIELD_ID, TEAM_ID, 6)));
        given(fieldRepository.findByTeamIdAndIsActiveTrueOrderBySortOrderAsc(TEAM_ID_2))
                .willReturn(List.of(buildField(FIELD_ID_2, TEAM_ID_2, 6)));

        batchService.run();

        // チーム1の300名 + チーム2の200名 = 500名で打ち切り
        verify(memberInfoUpdateReminderRunner, times(500))
                .markReminderSent(any(), any(), anyList(), any());
    }

    private MembershipEntity buildMembership(Long userId) {
        return MembershipEntity.builder()
                .userId(userId)
                .scopeType(ScopeType.TEAM)
                .scopeId(TEAM_ID)
                .joinedAt(BASE_TIME)
                .build();
    }

    private TeamMemberInfoFieldEntity buildField(Long id, Long teamId, Integer intervalMonths) {
        TeamMemberInfoFieldEntity entity = TeamMemberInfoFieldEntity.builder()
                .teamId(teamId)
                .fieldName("テストフィールド")
                .fieldType(MemberInfoFieldType.TEXT)
                .isRequired(false)
                .isSensitive(false)
                .refreshIntervalMonths(intervalMonths)
                .sortOrder(0)
                .build();
        try {
            java.lang.reflect.Field f = findDeclaredField(entity.getClass(), "id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException("IDフィールド設定失敗: " + e.getMessage(), e);
        }
        return entity;
    }

    private java.lang.reflect.Field findDeclaredField(Class<?> clazz, String name) throws NoSuchFieldException {
        while (clazz != null) {
            try {
                return clazz.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
