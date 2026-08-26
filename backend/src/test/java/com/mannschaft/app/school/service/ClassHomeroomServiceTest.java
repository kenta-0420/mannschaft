package com.mannschaft.app.school.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.school.dto.ClassHomeroomCreateRequest;
import com.mannschaft.app.school.dto.ClassHomeroomResponse;
import com.mannschaft.app.school.dto.ClassHomeroomUpdateRequest;
import com.mannschaft.app.school.entity.ClassHomeroomEntity;
import com.mannschaft.app.school.error.SchoolErrorCode;
import com.mannschaft.app.school.repository.ClassHomeroomRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import org.mockito.ArgumentCaptor;

/**
 * {@link ClassHomeroomService} 単体テスト。
 *
 * <p>設計書 §5.6 の学級担任設定 CRUD を検証する:</p>
 * <ul>
 *   <li>createHomeroom 正常系 — 新規登録成功</li>
 *   <li>createHomeroom 重複エラー — 同年度の現役担任が既に存在する場合 HOMEROOM_ALREADY_EXISTS</li>
 *   <li>updateHomeroom 正常系 — 担任変更・有効終了日設定</li>
 *   <li>updateHomeroom 未発見 — 指定ID が存在しない場合 HOMEROOM_NOT_FOUND</li>
 *   <li>listHomerooms 正常系 — 複数件取得</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ClassHomeroomServiceTest {

    @Mock
    private ClassHomeroomRepository classHomeroomRepository;

    @Mock
    private AccessControlService accessControlService;

    @Spy
    private ObjectMapper objectMapper;

    @InjectMocks
    private ClassHomeroomService classHomeroomService;

    private static final Long TEAM_ID = 1L;
    private static final Long ADMIN_USER_ID = 100L;
    private static final Long TEACHER_USER_ID = 200L;
    private static final Integer ACADEMIC_YEAR = 2026;

    @Nested
    @DisplayName("createHomeroom")
    class CreateHomeroom {

        @Test
        @DisplayName("正常系: 学級担任設定を新規登録できる")
        void success() {
            given(accessControlService.isAdminOrAbove(ADMIN_USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(classHomeroomRepository.existsByTeamIdAndAcademicYearAndEffectiveUntilIsNull(TEAM_ID, ACADEMIC_YEAR))
                    .willReturn(false);
            given(classHomeroomRepository.save(any())).willAnswer(inv -> {
                ClassHomeroomEntity e = inv.getArgument(0);
                ReflectionTestUtils.setField(e, "id", 1L);
                return e;
            });

            ClassHomeroomCreateRequest request = new ClassHomeroomCreateRequest();
            ReflectionTestUtils.setField(request, "homeroomTeacherUserId", TEACHER_USER_ID);
            ReflectionTestUtils.setField(request, "academicYear", ACADEMIC_YEAR);
            ReflectionTestUtils.setField(request, "effectiveFrom", LocalDate.of(2026, 4, 1));

            ClassHomeroomResponse response = classHomeroomService.createHomeroom(TEAM_ID, request, ADMIN_USER_ID);

            assertThat(response.getTeamId()).isEqualTo(TEAM_ID);
            assertThat(response.getHomeroomTeacherUserId()).isEqualTo(TEACHER_USER_ID);
            assertThat(response.getAcademicYear()).isEqualTo(ACADEMIC_YEAR);
            verify(classHomeroomRepository).save(any());
        }

        @Test
        @DisplayName("異常系: 同年度の現役担任設定が既に存在する場合 HOMEROOM_ALREADY_EXISTS")
        void duplicate() {
            given(accessControlService.isAdminOrAbove(ADMIN_USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(classHomeroomRepository.existsByTeamIdAndAcademicYearAndEffectiveUntilIsNull(TEAM_ID, ACADEMIC_YEAR))
                    .willReturn(true);

            ClassHomeroomCreateRequest request = new ClassHomeroomCreateRequest();
            ReflectionTestUtils.setField(request, "homeroomTeacherUserId", TEACHER_USER_ID);
            ReflectionTestUtils.setField(request, "academicYear", ACADEMIC_YEAR);
            ReflectionTestUtils.setField(request, "effectiveFrom", LocalDate.of(2026, 4, 1));

            assertThatThrownBy(() -> classHomeroomService.createHomeroom(TEAM_ID, request, ADMIN_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(SchoolErrorCode.HOMEROOM_ALREADY_EXISTS);

            verify(classHomeroomRepository, never()).save(any());
        }

        @Test
        @DisplayName("異常系: ADMIN 権限なしの場合 COMMON_002")
        void noPermission() {
            given(accessControlService.isAdminOrAbove(ADMIN_USER_ID, TEAM_ID, "TEAM")).willReturn(false);

            ClassHomeroomCreateRequest request = new ClassHomeroomCreateRequest();
            ReflectionTestUtils.setField(request, "homeroomTeacherUserId", TEACHER_USER_ID);
            ReflectionTestUtils.setField(request, "academicYear", ACADEMIC_YEAR);
            ReflectionTestUtils.setField(request, "effectiveFrom", LocalDate.of(2026, 4, 1));

            assertThatThrownBy(() -> classHomeroomService.createHomeroom(TEAM_ID, request, ADMIN_USER_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("updateHomeroom")
    class UpdateHomeroom {

        @Test
        @DisplayName("正常系: 有効終了日を設定して担任設定を終了できる")
        void endHomeroom() {
            given(accessControlService.isAdminOrAbove(ADMIN_USER_ID, TEAM_ID, "TEAM")).willReturn(true);

            ClassHomeroomEntity existing = ClassHomeroomEntity.builder()
                    .teamId(TEAM_ID)
                    .homeroomTeacherUserId(TEACHER_USER_ID)
                    .academicYear(ACADEMIC_YEAR)
                    .effectiveFrom(LocalDate.of(2026, 4, 1))
                    .effectiveUntil(null)
                    .createdBy(ADMIN_USER_ID)
                    .build();
            ReflectionTestUtils.setField(existing, "id", 10L);

            given(classHomeroomRepository.findById(10L)).willReturn(Optional.of(existing));
            given(classHomeroomRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            ClassHomeroomUpdateRequest request = new ClassHomeroomUpdateRequest();
            ReflectionTestUtils.setField(request, "effectiveUntil", LocalDate.of(2027, 3, 31));

            ClassHomeroomResponse response = classHomeroomService.updateHomeroom(TEAM_ID, 10L, request, ADMIN_USER_ID);

            assertThat(response.getEffectiveUntil()).isEqualTo(LocalDate.of(2027, 3, 31));
        }

        @Test
        @DisplayName("異常系: 存在しない ID を指定すると HOMEROOM_NOT_FOUND")
        void notFound() {
            given(accessControlService.isAdminOrAbove(ADMIN_USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(classHomeroomRepository.findById(999L)).willReturn(Optional.empty());

            ClassHomeroomUpdateRequest request = new ClassHomeroomUpdateRequest();

            assertThatThrownBy(() -> classHomeroomService.updateHomeroom(TEAM_ID, 999L, request, ADMIN_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(SchoolErrorCode.HOMEROOM_NOT_FOUND);
        }
    }

    /**
     * listHomerooms の認可は「管理者・VIEW_ATTENDANCE 保持者は全件／担任本人は自分の担任分のみ／
     * それ以外は COMMON_002」である。肯定側だけでは判定が常に true でも緑になるため、
     * 必ず否定側と対で検証する。
     */
    @Nested
    @DisplayName("listHomerooms")
    class ListHomerooms {

        private static final Long OTHER_TEACHER_USER_ID = 400L;
        private static final Long OUTSIDER_USER_ID = 500L;

        private ClassHomeroomEntity homeroomOf(Long teacherUserId, Long id) {
            ClassHomeroomEntity e = ClassHomeroomEntity.builder()
                    .teamId(TEAM_ID)
                    .homeroomTeacherUserId(teacherUserId)
                    .academicYear(ACADEMIC_YEAR)
                    .effectiveFrom(LocalDate.of(2026, 4, 1))
                    .createdBy(ADMIN_USER_ID)
                    .build();
            ReflectionTestUtils.setField(e, "id", id);
            return e;
        }

        @Test
        @DisplayName("肯定系: 管理者は担任本人でなくても全件を閲覧できる")
        void adminSeesAll() {
            given(accessControlService.isAdminOrAbove(ADMIN_USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(classHomeroomRepository.findByTeamIdAndAcademicYearOrderByEffectiveFromDesc(TEAM_ID, ACADEMIC_YEAR))
                    .willReturn(List.of(homeroomOf(TEACHER_USER_ID, 1L), homeroomOf(OTHER_TEACHER_USER_ID, 2L)));

            List<ClassHomeroomResponse> result =
                    classHomeroomService.listHomerooms(TEAM_ID, ACADEMIC_YEAR, ADMIN_USER_ID);

            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("肯定系: VIEW_ATTENDANCE を委任された者は管理者でなくても全件を閲覧できる")
        void permissionHolderSeesAll() {
            given(accessControlService.isAdminOrAbove(OUTSIDER_USER_ID, TEAM_ID, "TEAM")).willReturn(false);
            given(accessControlService.hasPermission(OUTSIDER_USER_ID, TEAM_ID, "TEAM", "VIEW_ATTENDANCE"))
                    .willReturn(true);
            given(classHomeroomRepository.findByTeamIdAndAcademicYearOrderByEffectiveFromDesc(TEAM_ID, ACADEMIC_YEAR))
                    .willReturn(List.of(homeroomOf(TEACHER_USER_ID, 1L), homeroomOf(OTHER_TEACHER_USER_ID, 2L)));

            List<ClassHomeroomResponse> result =
                    classHomeroomService.listHomerooms(TEAM_ID, ACADEMIC_YEAR, OUTSIDER_USER_ID);

            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("肯定系かつ絞り込み: 担任本人は自分が担任の学級だけが返り、他人の担任設定は返らない")
        void teacherSeesOnlyOwnHomerooms() {
            given(accessControlService.isAdminOrAbove(TEACHER_USER_ID, TEAM_ID, "TEAM")).willReturn(false);
            given(accessControlService.hasPermission(TEACHER_USER_ID, TEAM_ID, "TEAM", "VIEW_ATTENDANCE"))
                    .willReturn(false);
            given(classHomeroomRepository.existsByTeamIdAndHomeroomTeacherUserId(TEAM_ID, TEACHER_USER_ID))
                    .willReturn(true);
            given(classHomeroomRepository.findByTeamIdAndAcademicYearOrderByEffectiveFromDesc(TEAM_ID, ACADEMIC_YEAR))
                    .willReturn(List.of(homeroomOf(TEACHER_USER_ID, 1L), homeroomOf(OTHER_TEACHER_USER_ID, 2L)));

            List<ClassHomeroomResponse> result =
                    classHomeroomService.listHomerooms(TEAM_ID, ACADEMIC_YEAR, TEACHER_USER_ID);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getHomeroomTeacherUserId()).isEqualTo(TEACHER_USER_ID);
        }

        @Test
        @DisplayName("否定系: 管理者でも担任本人でもない者は COMMON_002 で拒否される（空一覧を返さない）")
        void outsiderIsRejected() {
            given(accessControlService.isAdminOrAbove(OUTSIDER_USER_ID, TEAM_ID, "TEAM")).willReturn(false);
            given(accessControlService.hasPermission(OUTSIDER_USER_ID, TEAM_ID, "TEAM", "VIEW_ATTENDANCE"))
                    .willReturn(false);
            given(classHomeroomRepository.existsByTeamIdAndHomeroomTeacherUserId(TEAM_ID, OUTSIDER_USER_ID))
                    .willReturn(false);

            assertThatThrownBy(() ->
                    classHomeroomService.listHomerooms(TEAM_ID, ACADEMIC_YEAR, OUTSIDER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);

            // 認可は絞り込みの前段で成立していること（拒否時にデータを読みに行かない）。
            verify(classHomeroomRepository, never())
                    .findByTeamIdAndAcademicYearOrderByEffectiveFromDesc(any(), any());
        }

        @Test
        @DisplayName("否定系: 別チームの管理者は当該チームでは管理者でなく担任でもないため拒否される")
        void adminOfAnotherTeamIsRejected() {
            // 別チームの管理者は「このチームでは」isAdminOrAbove=false になる。
            given(accessControlService.isAdminOrAbove(ADMIN_USER_ID, TEAM_ID, "TEAM")).willReturn(false);
            given(accessControlService.hasPermission(ADMIN_USER_ID, TEAM_ID, "TEAM", "VIEW_ATTENDANCE"))
                    .willReturn(false);
            given(classHomeroomRepository.existsByTeamIdAndHomeroomTeacherUserId(TEAM_ID, ADMIN_USER_ID))
                    .willReturn(false);

            assertThatThrownBy(() ->
                    classHomeroomService.listHomerooms(TEAM_ID, ACADEMIC_YEAR, ADMIN_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
        }
    }

    // ========================================
    // toBuilder 更新破壊 回帰テスト
    // ========================================

    /**
     * toBuilder().build() で作り直すと BaseEntity.id が引き継がれず id=null の新インスタンスになり
     * INSERT 化して行が重複するバグの回帰テスト。
     */
    @Nested
    @DisplayName("toBuilder更新破壊回帰")
    class ToBuilderUpdateRegression {

        private static final Long EXISTING_ID = 10L;
        private static final Long NEW_TEACHER_USER_ID = 300L;

        @Test
        @DisplayName("updateHomeroom: 取得した同一インスタンスを id 保持のまま UPDATE する（新インスタンス化しない）")
        void updateHomeroom_既存行をUPDATE_id保持() {
            // Given
            given(accessControlService.isAdminOrAbove(ADMIN_USER_ID, TEAM_ID, "TEAM")).willReturn(true);

            ClassHomeroomEntity existing = ClassHomeroomEntity.builder()
                    .teamId(TEAM_ID)
                    .homeroomTeacherUserId(TEACHER_USER_ID)
                    .academicYear(ACADEMIC_YEAR)
                    .effectiveFrom(LocalDate.of(2026, 4, 1))
                    .effectiveUntil(null)
                    .createdBy(ADMIN_USER_ID)
                    .build();
            ReflectionTestUtils.setField(existing, "id", EXISTING_ID);

            given(classHomeroomRepository.findById(EXISTING_ID)).willReturn(Optional.of(existing));

            ArgumentCaptor<ClassHomeroomEntity> captor =
                    ArgumentCaptor.forClass(ClassHomeroomEntity.class);
            given(classHomeroomRepository.save(captor.capture())).willAnswer(inv -> inv.getArgument(0));

            ClassHomeroomUpdateRequest request = new ClassHomeroomUpdateRequest();
            ReflectionTestUtils.setField(request, "homeroomTeacherUserId", NEW_TEACHER_USER_ID);
            ReflectionTestUtils.setField(request, "effectiveUntil", LocalDate.of(2027, 3, 31));

            // When
            classHomeroomService.updateHomeroom(TEAM_ID, EXISTING_ID, request, ADMIN_USER_ID);

            // Then: save に渡るのは取得した同一インスタンスで、id が保持されている（=UPDATE 経路）
            ClassHomeroomEntity saved = captor.getValue();
            assertThat(saved).isSameAs(existing);
            assertThat(saved.getId()).isEqualTo(EXISTING_ID);
            assertThat(saved.getHomeroomTeacherUserId()).isEqualTo(NEW_TEACHER_USER_ID);
            assertThat(saved.getEffectiveUntil()).isEqualTo(LocalDate.of(2027, 3, 31));
        }
    }
}
