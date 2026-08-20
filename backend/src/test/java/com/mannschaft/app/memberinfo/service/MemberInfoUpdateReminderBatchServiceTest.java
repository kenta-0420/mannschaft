package com.mannschaft.app.memberinfo.service;

import com.mannschaft.app.memberinfo.MemberInfoFieldType;
import com.mannschaft.app.memberinfo.TeamMemberInfoFieldEntity;
import com.mannschaft.app.memberinfo.TeamMemberInfoFieldRepository;
import com.mannschaft.app.memberinfo.TeamMemberInfoResponseEntity;
import com.mannschaft.app.memberinfo.TeamMemberInfoResponseRepository;
import com.mannschaft.app.memberinfo.batch.MemberInfoUpdateReminderBatchService;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.notification.service.NotificationHelper;
import org.junit.jupiter.api.DisplayName;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link MemberInfoUpdateReminderBatchService} の単体テスト。
 * F14.2 メンバー情報更新リマインダーバッチの動作を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MemberInfoUpdateReminderBatchService 単体テスト")
class MemberInfoUpdateReminderBatchServiceTest {

    @Mock
    private TeamMemberInfoFieldRepository fieldRepository;

    @Mock
    private TeamMemberInfoResponseRepository responseRepository;

    @Mock
    private MembershipRepository membershipRepository;

    @Mock
    private NotificationHelper notificationHelper;

    /** Issue #2715 CMP-055 lot C-5: newly added i18n dependencies. */
    @Mock private UserLocaleCache userLocaleCache;
    @Mock private MessageSource messageSource;

    @InjectMocks
    private MemberInfoUpdateReminderBatchService batchService;

    /**
     * Issue #2715 CMP-055 lot C-5/C-6: the bare MessageSource mock would return null for
     * title/body. Return the supplied default message so existing assertions keep working.
     */
    @org.junit.jupiter.api.BeforeEach
    void stubI18nMessageSource() {
        org.mockito.Mockito.lenient().when(messageSource.getMessage(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(2));
    }

    private static final Long TEAM_ID = 10L;
    private static final Long USER_ID = 1L;
    private static final Long FIELD_ID = 100L;
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 1, 1, 0, 0);

    @Test
    @DisplayName("バッチが正常実行され notify が呼ばれる（期限切れメンバーが存在する場合）")
    void run_withOverdueMembers_notifyCalled() {
        // チームIDリストを返す
        given(fieldRepository.findDistinctTeamIdsWithRefreshInterval()).willReturn(List.of(TEAM_ID));

        // アクティブメンバーを1件返す
        MembershipEntity membership = buildMembership(USER_ID);
        given(membershipRepository.findAllActiveByScope(ScopeType.TEAM, TEAM_ID))
                .willReturn(List.of(membership));

        // 期限切れフィールドを返す（confirmedAt が古いので期限切れ）
        TeamMemberInfoFieldEntity field = buildField(FIELD_ID, 12);
        given(fieldRepository.findByTeamIdAndIsActiveTrueOrderBySortOrderAsc(TEAM_ID))
                .willReturn(List.of(field));

        // 期限切れのレスポンス（confirmedAt が13ヶ月前 → 12ヶ月インターバルで期限切れ）
        TeamMemberInfoResponseEntity overdueResponse = TeamMemberInfoResponseEntity.builder()
                .teamId(TEAM_ID)
                .userId(USER_ID)
                .fieldId(FIELD_ID)
                .confirmedAt(BASE_TIME.minusMonths(13))
                .lastReminderSentAt(null)
                .build();
        given(responseRepository.findByFieldIdIn(List.of(FIELD_ID)))
                .willReturn(List.of(overdueResponse));
        given(responseRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        batchService.run();

        verify(notificationHelper, atLeastOnce()).notify(
                eq(USER_ID), anyString(), anyString(), anyString(),
                anyString(), eq(TEAM_ID), any(), eq(TEAM_ID), anyString(), isNull());
    }

    @Test
    @DisplayName("期限切れでないフィールドのメンバーは通知されない")
    void run_withNonOverdueMembers_notifyNotCalled() {
        given(fieldRepository.findDistinctTeamIdsWithRefreshInterval()).willReturn(List.of(TEAM_ID));

        MembershipEntity membership = buildMembership(USER_ID);
        given(membershipRepository.findAllActiveByScope(ScopeType.TEAM, TEAM_ID))
                .willReturn(List.of(membership));

        // 12ヶ月インターバルのフィールド
        TeamMemberInfoFieldEntity field = buildField(FIELD_ID, 12);
        given(fieldRepository.findByTeamIdAndIsActiveTrueOrderBySortOrderAsc(TEAM_ID))
                .willReturn(List.of(field));

        // まだ期限切れでないレスポンス（confirmedAt が1ヶ月前 → まだ有効）
        TeamMemberInfoResponseEntity freshResponse = TeamMemberInfoResponseEntity.builder()
                .teamId(TEAM_ID)
                .userId(USER_ID)
                .fieldId(FIELD_ID)
                .confirmedAt(BASE_TIME.minusMonths(1))
                .lastReminderSentAt(null)
                .build();
        // confirmedAt.plusMonths(12) > now なので期限切れではない
        // BASE_TIME の1ヶ月前 + 12ヶ月 = BASE_TIME の11ヶ月後 → 未来（有効）
        // ただし LocalDateTime.now() を使っているため、テストの「今」に依存する。
        // 確実に期限切れにならないよう、confirmedAt を現在の1ヶ月前に設定する
        TeamMemberInfoResponseEntity notOverdueResponse = TeamMemberInfoResponseEntity.builder()
                .teamId(TEAM_ID)
                .userId(USER_ID)
                .fieldId(FIELD_ID)
                .confirmedAt(LocalDateTime.now().minusMonths(1)) // 1ヶ月前 → 12ヶ月インターバルなのでまだ有効
                .lastReminderSentAt(null)
                .build();

        given(responseRepository.findByFieldIdIn(List.of(FIELD_ID)))
                .willReturn(List.of(notOverdueResponse));

        batchService.run();

        verify(notificationHelper, never()).notify(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("BATCH_LIMIT (500) 超過時の繰り越し動作 — 500件目まで処理される")
    void run_batchLimitExceeded_processes500Members() {
        // チームを複数用意し、合計で500件超のメンバーを作成
        Long TEAM_ID_1 = 1L;
        Long TEAM_ID_2 = 2L;

        given(fieldRepository.findDistinctTeamIdsWithRefreshInterval())
                .willReturn(List.of(TEAM_ID_1, TEAM_ID_2));

        // チーム1に300名のメンバー
        List<MembershipEntity> team1Members = LongStream.rangeClosed(1, 300)
                .mapToObj(this::buildMembership)
                .collect(Collectors.toList());
        given(membershipRepository.findAllActiveByScope(ScopeType.TEAM, TEAM_ID_1))
                .willReturn(team1Members);

        // チーム2に300名のメンバー（合計600名 > BATCH_LIMIT=500）
        List<MembershipEntity> team2Members = LongStream.rangeClosed(301, 600)
                .mapToObj(this::buildMembership)
                .collect(Collectors.toList());
        given(membershipRepository.findAllActiveByScope(ScopeType.TEAM, TEAM_ID_2))
                .willReturn(team2Members);

        // 両チームともに同じフィールドを持つ
        Long FIELD_ID_1 = 100L;
        Long FIELD_ID_2 = 200L;

        TeamMemberInfoFieldEntity field1 = buildFieldForTeam(FIELD_ID_1, TEAM_ID_1, 12);
        TeamMemberInfoFieldEntity field2 = buildFieldForTeam(FIELD_ID_2, TEAM_ID_2, 12);

        given(fieldRepository.findByTeamIdAndIsActiveTrueOrderBySortOrderAsc(TEAM_ID_1))
                .willReturn(List.of(field1));
        given(fieldRepository.findByTeamIdAndIsActiveTrueOrderBySortOrderAsc(TEAM_ID_2))
                .willReturn(List.of(field2));

        // 全メンバーのレスポンスは空（未回答 = 期限切れ扱い）
        given(responseRepository.findByFieldIdIn(List.of(FIELD_ID_1))).willReturn(List.of());
        given(responseRepository.findByFieldIdIn(List.of(FIELD_ID_2))).willReturn(List.of());
        given(responseRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        batchService.run();

        // BATCH_LIMIT=500 のため、チーム1の300名 + チーム2の200名 = 500名分のnotifyが呼ばれる
        // チーム2の残り100名はskipされる
        verify(notificationHelper, times(500)).notify(
                anyLong(), anyString(), anyString(), anyString(),
                anyString(), anyLong(), any(), anyLong(), anyString(), isNull());
    }

    // ========================================
    // ヘルパー
    // ========================================

    private MembershipEntity buildMembership(Long userId) {
        return MembershipEntity.builder()
                .userId(userId)
                .scopeType(ScopeType.TEAM)
                .scopeId(TEAM_ID)
                .joinedAt(BASE_TIME)
                .build();
    }

    private TeamMemberInfoFieldEntity buildField(Long id, Integer intervalMonths) {
        return buildFieldForTeam(id, TEAM_ID, intervalMonths);
    }

    private TeamMemberInfoFieldEntity buildFieldForTeam(Long id, Long teamId, Integer intervalMonths) {
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
