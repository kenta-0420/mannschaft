package com.mannschaft.app.school.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
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

    @Nested
    @DisplayName("listHomerooms")
    class ListHomerooms {

        @Test
        @DisplayName("正常系: 指定年度の担任設定一覧を返す")
        void success() {
            ClassHomeroomEntity e1 = ClassHomeroomEntity.builder()
                    .teamId(TEAM_ID)
                    .homeroomTeacherUserId(TEACHER_USER_ID)
                    .academicYear(ACADEMIC_YEAR)
                    .effectiveFrom(LocalDate.of(2026, 4, 1))
                    .createdBy(ADMIN_USER_ID)
                    .build();
            ReflectionTestUtils.setField(e1, "id", 1L);

            // checkPermission は void のため例外を投げないのがデフォルト動作
            given(classHomeroomRepository.findByTeamIdAndAcademicYearOrderByEffectiveFromDesc(TEAM_ID, ACADEMIC_YEAR))
                    .willReturn(List.of(e1));

            List<ClassHomeroomResponse> result = classHomeroomService.listHomerooms(TEAM_ID, ACADEMIC_YEAR, ADMIN_USER_ID);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getHomeroomTeacherUserId()).isEqualTo(TEACHER_USER_ID);
        }
    }
}
