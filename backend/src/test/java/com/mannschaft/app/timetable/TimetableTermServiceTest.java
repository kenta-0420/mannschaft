package com.mannschaft.app.timetable;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.team.service.TeamOrgMembershipQueryService;
import com.mannschaft.app.timetable.entity.TimetableTermEntity;
import com.mannschaft.app.timetable.repository.TimetableRepository;
import com.mannschaft.app.timetable.repository.TimetableTermRepository;
import com.mannschaft.app.timetable.service.TimetableTermService;
import com.mannschaft.app.timetable.service.TimetableTermService.CreateTermData;
import com.mannschaft.app.timetable.service.TimetableTermService.UpdateTermData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("TimetableTermService 単体テスト")
class TimetableTermServiceTest {

    @Mock private TimetableTermRepository termRepository;
    @Mock private TimetableRepository timetableRepository;
    @Mock private AccessControlService accessControlService;
    @Mock private TeamOrgMembershipQueryService teamOrgMembershipQueryService;
    @Mock private java.time.Clock clock;
    @InjectMocks private TimetableTermService service;

    private static final Long ACTOR_USER_ID = 100L;

    @Nested
    @DisplayName("createTerm")
    class CreateTerm {

        @Test
        @DisplayName("正常系: 学期が作成される")
        void 作成_正常_保存() {
            // Given
            given(termRepository.findByTeamIdAndAcademicYearOrderBySortOrder(1L, 2025))
                    .willReturn(List.of());
            given(termRepository.save(any(TimetableTermEntity.class))).willAnswer(inv -> inv.getArgument(0));

            CreateTermData data = new CreateTermData(2025, "1学期",
                    LocalDate.of(2025, 4, 1), LocalDate.of(2025, 7, 31), 1);

            // When
            TimetableTermEntity result = service.createTerm(1L, true, data, ACTOR_USER_ID);

            // Then
            assertThat(result.getName()).isEqualTo("1学期");
            verify(termRepository).save(any(TimetableTermEntity.class));
        }

        @Test
        @DisplayName("異常系: 同名学期が存在する場合TIMETABLE_020例外")
        void 作成_名前重複_例外() {
            // Given
            TimetableTermEntity existing = TimetableTermEntity.builder()
                    .teamId(1L).academicYear(2025).name("1学期")
                    .startDate(LocalDate.of(2025, 4, 1))
                    .endDate(LocalDate.of(2025, 7, 31)).sortOrder(1).build();
            // idを設定（excludeTermIdのチェック用）
            try {
                var field = existing.getClass().getSuperclass().getDeclaredField("id");
                field.setAccessible(true);
                field.set(existing, 99L);
            } catch (Exception ignored) {}
            given(termRepository.findByTeamIdAndAcademicYearOrderBySortOrder(1L, 2025))
                    .willReturn(List.of(existing));

            CreateTermData data = new CreateTermData(2025, "1学期",
                    LocalDate.of(2025, 10, 1), LocalDate.of(2025, 12, 31), 2);

            // When / Then
            assertThatThrownBy(() -> service.createTerm(1L, true, data, ACTOR_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TIMETABLE_020"));
        }
    }

    @Nested
    @DisplayName("updateTerm")
    class UpdateTerm {

        @Test
        @DisplayName("回帰: updateTermはfindByIdで取得した同一インスタンスをsaveする（toBuilderで新規行を作らない）")
        void 更新_同一インスタンスUPDATE() throws Exception {
            // Given
            TimetableTermEntity entity = TimetableTermEntity.builder()
                    .teamId(1L).organizationId(null).academicYear(2025)
                    .name("1学期")
                    .startDate(LocalDate.of(2025, 4, 1))
                    .endDate(LocalDate.of(2025, 7, 31))
                    .sortOrder(1).build();
            // id を反射でセット（BaseEntity の GeneratedValue フィールド）
            var idField = entity.getClass().getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, 10L);

            UpdateTermData data = new UpdateTermData(
                    "前期", LocalDate.of(2025, 4, 1), LocalDate.of(2025, 7, 31), 1);

            given(termRepository.findByIdForUpdate(10L)).willReturn(Optional.of(entity));
            // 重複チェックは空リストで通過
            given(termRepository.findByTeamIdAndAcademicYearOrderBySortOrder(1L, 2025))
                    .willReturn(List.of(entity));
            given(termRepository.save(any(TimetableTermEntity.class))).willAnswer(inv -> inv.getArgument(0));

            // When
            service.updateTerm(10L, data, ACTOR_USER_ID);

            // Then
            // toBuilder().build() で別インスタンスを save していたら id=null の新規行 INSERT になる。
            // 同一インスタンスを save することで UPDATE になっていることを検証する。
            ArgumentCaptor<TimetableTermEntity> captor = ArgumentCaptor.forClass(TimetableTermEntity.class);
            verify(termRepository).save(captor.capture());
            assertThat(captor.getValue()).isSameAs(entity);
            // id が保持されている（= INSERT でなく UPDATE）
            assertThat(captor.getValue().getId()).isEqualTo(10L);
            // フィールドが更新されている
            assertThat(captor.getValue().getName()).isEqualTo("前期");
        }
    }

    @Nested
    @DisplayName("日付の前後検証（A2）")
    class DateOrder {

        @Test
        @DisplayName("作成: 開始日が終了日より後なら COMMON_001 で endDate の fieldErrors を返す（保存しない）")
        void 作成_開始日が終了日より後_COMMON_001() {
            CreateTermData data = new CreateTermData(2025, "後期",
                    LocalDate.of(2025, 12, 31), LocalDate.of(2025, 10, 1), 1);

            assertThatThrownBy(() -> service.createTerm(1L, true, data, ACTOR_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> {
                        BusinessException be = (BusinessException) ex;
                        assertThat(be.getErrorCode().getCode()).isEqualTo("COMMON_001");
                        assertThat(be.getFieldErrors()).extracting("field").containsExactly("endDate");
                    });
            verify(termRepository, never()).save(any(TimetableTermEntity.class));
        }

        @Test
        @DisplayName("作成: 開始日と終了日が同日でも COMMON_001")
        void 作成_同日_COMMON_001() {
            CreateTermData data = new CreateTermData(2025, "後期",
                    LocalDate.of(2025, 10, 1), LocalDate.of(2025, 10, 1), 1);

            assertThatThrownBy(() -> service.createTerm(1L, false, data, ACTOR_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("COMMON_001"));
        }

        @Test
        @DisplayName("更新: 省略項目を既存値で補った後の範囲で判定する（終了日だけ前に動かすと COMMON_001）")
        void 更新_補完後の範囲が逆転_COMMON_001() throws Exception {
            TimetableTermEntity entity = TimetableTermEntity.builder()
                    .teamId(1L).academicYear(2025).name("1学期")
                    .startDate(LocalDate.of(2025, 4, 1))
                    .endDate(LocalDate.of(2025, 7, 31)).sortOrder(1).build();
            var idField = entity.getClass().getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, 10L);
            given(termRepository.findByIdForUpdate(10L)).willReturn(Optional.of(entity));

            UpdateTermData data = new UpdateTermData(null, null, LocalDate.of(2025, 3, 31), null);

            assertThatThrownBy(() -> service.updateTerm(10L, data, ACTOR_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("COMMON_001"));
            verify(termRepository, never()).save(any(TimetableTermEntity.class));
        }
    }

    @Nested
    @DisplayName("getTeamTerms（D4）")
    class GetTeamTerms {

        @Test
        @DisplayName("organizationId 省略: 所属組織を照合せずチーム固有の学期だけを返す")
        void 組織ID省略_チーム固有のみ() {
            TimetableTermEntity teamTerm = TimetableTermEntity.builder()
                    .teamId(1L).academicYear(2025).name("1学期")
                    .startDate(LocalDate.of(2025, 4, 1))
                    .endDate(LocalDate.of(2025, 7, 31)).sortOrder(1).build();
            given(termRepository.findByTeamIdOrderByAcademicYearDescSortOrder(1L))
                    .willReturn(List.of(teamTerm));

            List<TimetableTermEntity> result = service.getTeamTerms(1L, null, ACTOR_USER_ID);

            assertThat(result).containsExactly(teamTerm);
            verify(accessControlService).checkMembership(ACTOR_USER_ID, 1L, "TEAM");
            verify(termRepository, never()).findByOrganizationIdOrderByAcademicYearDescSortOrder(any());
        }

        @Test
        @DisplayName("organizationId 指定: 所属していない組織なら TIMETABLE_002（従来どおり）")
        void 組織ID指定_非所属_例外() {
            given(teamOrgMembershipQueryService.findActiveOrganizationIds(1L)).willReturn(List.of(5L));

            assertThatThrownBy(() -> service.getTeamTerms(1L, 6L, ACTOR_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TIMETABLE_002"));
        }
    }

    @Nested
    @DisplayName("updateTerm の行ロック（P2: 並行削除の取り消し防止）")
    class UpdateTermLock {

        @Test
        @DisplayName("学期の行を悲観ロックで取ってから更新する（読み取り後の並行削除を、全列 UPDATE で取り消さない）")
        void 更新は行ロック経由() {
            TimetableTermEntity entity = TimetableTermEntity.builder()
                    .teamId(1L).academicYear(2025).name("1学期")
                    .startDate(LocalDate.of(2025, 4, 1))
                    .endDate(LocalDate.of(2025, 7, 31)).sortOrder(1).build();
            given(termRepository.findByIdForUpdate(10L)).willReturn(Optional.of(entity));
            given(termRepository.findByTeamIdAndAcademicYearOrderBySortOrder(1L, 2025)).willReturn(List.of());
            given(termRepository.save(any(TimetableTermEntity.class))).willAnswer(inv -> inv.getArgument(0));

            service.updateTerm(10L, new UpdateTermData("改名", null, null, null), ACTOR_USER_ID);

            verify(termRepository).findByIdForUpdate(10L);
            verify(termRepository, never()).findById(any());
        }

        @Test
        @DisplayName("ロックを取った時点で学期が論理削除済みなら TIMETABLE_002 で拒否し、保存しない")
        void 削除済みなら404() {
            given(termRepository.findByIdForUpdate(10L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateTerm(10L, new UpdateTermData("改名", null, null, null), ACTOR_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TIMETABLE_002"));
            verify(termRepository, never()).save(any(TimetableTermEntity.class));
        }
    }

    @Nested
    @DisplayName("deleteTerm（B1: 論理削除）")
    class DeleteTerm {

        private TimetableTermEntity teamTerm() {
            return TimetableTermEntity.builder()
                    .teamId(1L).academicYear(2025).name("1学期")
                    .startDate(LocalDate.of(2025, 4, 1))
                    .endDate(LocalDate.of(2025, 7, 31)).sortOrder(1).build();
        }

        @Test
        @DisplayName("未削除の時間割が残る学期は削除できず TIMETABLE_022")
        void 未削除の時間割あり_409() {
            TimetableTermEntity term = teamTerm();
            given(termRepository.findByIdForUpdate(10L)).willReturn(Optional.of(term));
            given(timetableRepository.countLiveByTermIdLocked(10L)).willReturn(1L);

            assertThatThrownBy(() -> service.deleteTerm(10L, ACTOR_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TIMETABLE_022"));
            assertThat(term.getDeletedAt()).isNull();
            verify(termRepository, never()).delete(any(TimetableTermEntity.class));
        }

        @Test
        @DisplayName("論理削除済みの時間割しか無い学期は論理削除される（物理削除しない）")
        void 論理削除済み時間割のみ_学期を論理削除() {
            TimetableTermEntity term = teamTerm();
            given(termRepository.findByIdForUpdate(10L)).willReturn(Optional.of(term));
            given(timetableRepository.countLiveByTermIdLocked(10L)).willReturn(0L);
            given(clock.instant()).willReturn(java.time.Instant.parse("2026-11-09T00:00:00Z"));

            service.deleteTerm(10L, ACTOR_USER_ID);

            assertThat(term.getDeletedAt()).isNotNull();
            verify(termRepository).save(term);
            verify(termRepository, never()).delete(any(TimetableTermEntity.class));
            verify(accessControlService).checkAdminOrAbove(ACTOR_USER_ID, 1L, "TEAM");
            // P2: 時間割の作成と直列化するため、学期の行を悲観ロックで取り、ロック下で参照を数える
            verify(termRepository).findByIdForUpdate(10L);
            verify(timetableRepository).countLiveByTermIdLocked(10L);
        }

        @Test
        @DisplayName("ロック取得後に学期が既に論理削除されていれば TIMETABLE_002（二重削除）")
        void ロック後に消えていれば404() {
            TimetableTermEntity term = teamTerm();
            given(termRepository.findByIdForUpdate(10L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.deleteTerm(10L, ACTOR_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TIMETABLE_002"));
        }

        @Test
        @DisplayName("組織学期も同じ規則で論理削除される")
        void 組織学期_論理削除() {
            TimetableTermEntity term = TimetableTermEntity.builder()
                    .organizationId(7L).academicYear(2025).name("前期")
                    .startDate(LocalDate.of(2025, 4, 1))
                    .endDate(LocalDate.of(2025, 9, 30)).sortOrder(1).build();
            given(termRepository.findByIdForUpdate(11L)).willReturn(Optional.of(term));
            given(timetableRepository.countLiveByTermIdLocked(11L)).willReturn(0L);
            given(clock.instant()).willReturn(java.time.Instant.parse("2026-11-09T00:00:00Z"));

            service.deleteTerm(11L, ACTOR_USER_ID);

            assertThat(term.getDeletedAt()).isNotNull();
            verify(accessControlService).checkAdminOrAbove(ACTOR_USER_ID, 7L, "ORGANIZATION");
        }
    }

    @Nested
    @DisplayName("getByTermId")
    class GetByTermId {

        @Test
        @DisplayName("異常系: 学期不在でTIMETABLE_002例外")
        void 取得_不在_例外() {
            // Given
            given(termRepository.findById(1L)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.getByTermId(1L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TIMETABLE_002"));
        }
    }
}
