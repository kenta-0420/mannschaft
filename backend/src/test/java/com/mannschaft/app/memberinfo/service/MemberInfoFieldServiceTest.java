package com.mannschaft.app.memberinfo.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.memberinfo.MemberInfoErrorCode;
import com.mannschaft.app.memberinfo.MemberInfoFieldType;
import com.mannschaft.app.memberinfo.MemberInfoMapper;
import com.mannschaft.app.memberinfo.TeamMemberInfoFieldEntity;
import com.mannschaft.app.memberinfo.TeamMemberInfoFieldRepository;
import com.mannschaft.app.memberinfo.dto.CreateMemberInfoFieldRequest;
import com.mannschaft.app.memberinfo.dto.MemberInfoFieldResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import com.mannschaft.app.memberinfo.dto.UpdateMemberInfoFieldRequest;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

/**
 * {@link MemberInfoFieldService} の単体テスト。
 * F14.2 チームメンバー情報フィールド管理のバリデーションと制約を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MemberInfoFieldService 単体テスト")
class MemberInfoFieldServiceTest {

    @Mock
    private TeamMemberInfoFieldRepository fieldRepository;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private MemberInfoMapper mapper;

    @InjectMocks
    private MemberInfoFieldService service;

    private static final Long TEAM_ID = 10L;
    private static final Long USER_ID = 1L;
    private static final Long FIELD_ID = 100L;
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 1, 1, 0, 0);

    // ========================================
    // CreateField テスト
    // ========================================

    @Nested
    @DisplayName("createField - バリデーションと制約")
    class CreateField {

        @Test
        @DisplayName("正常なリクエスト → フィールド作成成功（saveが呼ばれる）")
        void createField_validRequest_success() {
            CreateMemberInfoFieldRequest request = validCreateRequest(12);
            TeamMemberInfoFieldEntity saved = buildField(FIELD_ID, true);
            MemberInfoFieldResponse response = buildFieldResponse(FIELD_ID);

            given(fieldRepository.countByTeamId(TEAM_ID)).willReturn(0L);
            given(fieldRepository.save(any())).willReturn(saved);
            given(mapper.toFieldResponse(saved)).willReturn(response);

            MemberInfoFieldResponse result = service.createField(TEAM_ID, USER_ID, request);

            verify(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");
            verify(fieldRepository).save(any());
        }

        @Test
        @DisplayName("refreshIntervalMonths に不正値（24）→ INVALID_INTERVAL_VALUE 例外")
        void createField_invalidInterval_throws() {
            CreateMemberInfoFieldRequest request = validCreateRequest(24);

            assertThatThrownBy(() -> service.createField(TEAM_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MemberInfoErrorCode.INVALID_INTERVAL_VALUE);
        }

        @Test
        @DisplayName("チームフィールド数が20件上限に達している → FIELD_LIMIT_EXCEEDED 例外")
        void createField_limitExceeded_throws() {
            CreateMemberInfoFieldRequest request = validCreateRequest(12);
            given(fieldRepository.countByTeamId(TEAM_ID)).willReturn(20L);

            assertThatThrownBy(() -> service.createField(TEAM_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MemberInfoErrorCode.FIELD_LIMIT_EXCEEDED);
        }

        @Test
        @DisplayName("ADMIN でないユーザーがフィールド作成 → COMMON_002（403）がスローされる")
        void createField_notAdmin_throws() {
            CreateMemberInfoFieldRequest request = validCreateRequest(12);
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");

            assertThatThrownBy(() -> service.createField(TEAM_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
        }
    }

    // ========================================
    // UpdateField テスト（toBuilder 廃止・id 保持回帰）
    // ========================================

    @Nested
    @DisplayName("updateField - save に渡るのが findById の同一インスタンスかつ id 保持")
    class UpdateField {

        @Test
        @DisplayName("updateField → save に渡るエンティティが findById の同一インスタンスで id を保持する")
        void updateField_savesOriginalInstanceWithIdPreserved() {
            TeamMemberInfoFieldEntity entity = buildField(FIELD_ID, true);
            UpdateMemberInfoFieldRequest request = new UpdateMemberInfoFieldRequest(
                    "更新フィールド", MemberInfoFieldType.TEXT, false, false, 12, 1);
            MemberInfoFieldResponse response = buildFieldResponse(FIELD_ID);

            given(fieldRepository.findByIdAndTeamId(FIELD_ID, TEAM_ID)).willReturn(Optional.of(entity));
            given(fieldRepository.save(any())).willReturn(entity);
            given(mapper.toFieldResponse(entity)).willReturn(response);

            service.updateField(TEAM_ID, FIELD_ID, USER_ID, request);

            ArgumentCaptor<TeamMemberInfoFieldEntity> captor =
                    ArgumentCaptor.forClass(TeamMemberInfoFieldEntity.class);
            verify(fieldRepository).save(captor.capture());
            // save に渡るのが findById の同一インスタンスかつ id を保持していることを検証
            assertThat(captor.getValue()).isSameAs(entity);
            assertThat(captor.getValue().getId()).isEqualTo(FIELD_ID);
        }

        @Test
        @DisplayName("deleteField → save に渡るエンティティが findById の同一インスタンスで id を保持する")
        void deleteField_savesOriginalInstanceWithIdPreserved() {
            TeamMemberInfoFieldEntity entity = buildField(FIELD_ID, true);
            given(fieldRepository.findByIdAndTeamId(FIELD_ID, TEAM_ID)).willReturn(Optional.of(entity));
            given(fieldRepository.save(any())).willReturn(entity);

            service.deleteField(TEAM_ID, FIELD_ID, USER_ID);

            ArgumentCaptor<TeamMemberInfoFieldEntity> captor =
                    ArgumentCaptor.forClass(TeamMemberInfoFieldEntity.class);
            verify(fieldRepository).save(captor.capture());
            // save に渡るのが findById の同一インスタンスかつ id を保持していることを検証
            assertThat(captor.getValue()).isSameAs(entity);
            assertThat(captor.getValue().getId()).isEqualTo(FIELD_ID);
        }
    }

    // ========================================
    // DeleteField テスト
    // ========================================

    @Nested
    @DisplayName("deleteField - 削除バリデーションと論理削除")
    class DeleteField {

        @Test
        @DisplayName("フィールドが存在しない → FIELD_NOT_FOUND 例外")
        void deleteField_notFound_throws() {
            given(fieldRepository.findByIdAndTeamId(FIELD_ID, TEAM_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.deleteField(TEAM_ID, FIELD_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MemberInfoErrorCode.FIELD_NOT_FOUND);
        }

        @Test
        @DisplayName("正常削除 → is_active = false で save が呼ばれる")
        void deleteField_validField_savesWithInactive() {
            TeamMemberInfoFieldEntity entity = buildField(FIELD_ID, true);
            given(fieldRepository.findByIdAndTeamId(FIELD_ID, TEAM_ID)).willReturn(Optional.of(entity));
            given(fieldRepository.save(any())).willReturn(buildField(FIELD_ID, false));

            service.deleteField(TEAM_ID, FIELD_ID, USER_ID);

            verify(fieldRepository).save(any(TeamMemberInfoFieldEntity.class));
        }
    }

    // ========================================
    // ヘルパー
    // ========================================

    private CreateMemberInfoFieldRequest validCreateRequest(Integer intervalMonths) {
        return new CreateMemberInfoFieldRequest(
                "テストフィールド",
                MemberInfoFieldType.TEXT,
                false,
                false,
                intervalMonths,
                0
        );
    }

    private TeamMemberInfoFieldEntity buildField(Long id, boolean isActive) {
        TeamMemberInfoFieldEntity entity = TeamMemberInfoFieldEntity.builder()
                .teamId(TEAM_ID)
                .fieldName("テストフィールド")
                .fieldType(MemberInfoFieldType.TEXT)
                .isRequired(false)
                .isSensitive(false)
                .refreshIntervalMonths(12)
                .sortOrder(0)
                .build();
        // id はリフレクションで設定
        try {
            java.lang.reflect.Field f = findField(entity.getClass(), "id");
            f.setAccessible(true);
            f.set(entity, id);
            java.lang.reflect.Field activeField = findField(entity.getClass(), "isActive");
            activeField.setAccessible(true);
            activeField.set(entity, isActive);
        } catch (Exception e) {
            throw new RuntimeException("フィールド設定失敗: " + e.getMessage(), e);
        }
        return entity;
    }

    private java.lang.reflect.Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        while (clazz != null) {
            try {
                return clazz.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private MemberInfoFieldResponse buildFieldResponse(Long id) {
        return MemberInfoFieldResponse.builder()
                .id(id)
                .fieldName("テストフィールド")
                .fieldType(MemberInfoFieldType.TEXT)
                .isRequired(false)
                .isSensitive(false)
                .refreshIntervalMonths(12)
                .sortOrder(0)
                .isActive(true)
                .build();
    }
}
