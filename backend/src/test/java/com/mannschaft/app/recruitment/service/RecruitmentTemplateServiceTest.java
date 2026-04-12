package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.recruitment.RecruitmentErrorCode;
import com.mannschaft.app.recruitment.RecruitmentParticipationType;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.RecruitmentVisibility;
import com.mannschaft.app.recruitment.dto.RecruitmentTemplateCreateRequest;
import com.mannschaft.app.recruitment.dto.RecruitmentTemplateResponse;
import com.mannschaft.app.recruitment.dto.RecruitmentTemplateUpdateRequest;
import com.mannschaft.app.recruitment.entity.RecruitmentTemplateEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentCancellationPolicyRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentCancellationPolicyTierRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentTemplateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * {@link RecruitmentTemplateService} の単体テスト。
 * F03.11 Phase 3 §5.1.2 テンプレート CRUD を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RecruitmentTemplateService 単体テスト")
class RecruitmentTemplateServiceTest {

    @Mock
    private RecruitmentTemplateRepository templateRepository;

    @Mock
    private RecruitmentCancellationPolicyRepository policyRepository;

    @Mock
    private RecruitmentCancellationPolicyTierRepository tierRepository;

    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private RecruitmentTemplateService service;

    private static final Long SCOPE_ID = 1L;
    private static final Long USER_ID = 99L;
    private static final Long TEMPLATE_ID = 10L;
    private static final RecruitmentScopeType SCOPE_TYPE = RecruitmentScopeType.TEAM;

    // ========================================
    // listByScope
    // ========================================

    @Nested
    @DisplayName("listByScope - テンプレート一覧取得")
    class ListByScope {

        @Test
        @DisplayName("checkMembership 通過後、activeByScopeTypeAndScopeId のページを返す")
        void listByScope_success() {
            // given
            Pageable pageable = PageRequest.of(0, 10);
            RecruitmentTemplateEntity template = buildTemplate();
            Page<RecruitmentTemplateEntity> page = new PageImpl<>(List.of(template), pageable, 1);
            given(templateRepository.findActiveByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID, pageable))
                    .willReturn(page);

            // when
            Page<RecruitmentTemplateResponse> result = service.listByScope(SCOPE_TYPE, SCOPE_ID, USER_ID, pageable);

            // then
            assertThat(result.getTotalElements()).isEqualTo(1);
            verify(accessControlService).checkMembership(USER_ID, SCOPE_ID, SCOPE_TYPE.name());
        }

        @Test
        @DisplayName("checkMembership が BusinessException をスローしたら上位に伝播")
        void listByScope_noPermission_throws() {
            // given
            doThrow(new BusinessException(com.mannschaft.app.common.CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkMembership(USER_ID, SCOPE_ID, SCOPE_TYPE.name());

            // when / then
            assertThatThrownBy(() ->
                    service.listByScope(SCOPE_TYPE, SCOPE_ID, USER_ID, PageRequest.of(0, 10)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(com.mannschaft.app.common.CommonErrorCode.COMMON_002);
        }
    }

    // ========================================
    // getTemplate
    // ========================================

    @Nested
    @DisplayName("getTemplate - テンプレート単件取得")
    class GetTemplate {

        @Test
        @DisplayName("findActiveById で取得後、checkMembership を通過し DTO を返す")
        void getTemplate_success() throws Exception {
            // given
            RecruitmentTemplateEntity template = buildTemplate();
            setField(template, "id", TEMPLATE_ID);
            given(templateRepository.findActiveById(TEMPLATE_ID)).willReturn(Optional.of(template));

            // when
            RecruitmentTemplateResponse result = service.getTemplate(TEMPLATE_ID, USER_ID);

            // then
            assertThat(result.getScopeType()).isEqualTo(SCOPE_TYPE);
            verify(accessControlService).checkMembership(USER_ID, SCOPE_ID, SCOPE_TYPE.name());
        }

        @Test
        @DisplayName("findActiveById が empty → TEMPLATE_NOT_FOUND")
        void getTemplate_notFound_throws() {
            // given
            given(templateRepository.findActiveById(TEMPLATE_ID)).willReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() -> service.getTemplate(TEMPLATE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RecruitmentErrorCode.TEMPLATE_NOT_FOUND);
        }
    }

    // ========================================
    // create
    // ========================================

    @Nested
    @DisplayName("create - テンプレート作成")
    class Create {

        @Test
        @DisplayName("checkAdminOrAbove 通過後、entity が保存され DTO を返す")
        void create_success() {
            // given
            RecruitmentTemplateCreateRequest request = buildCreateRequest(20, 5, false, null);
            RecruitmentTemplateEntity saved = buildTemplate();
            given(templateRepository.save(any())).willReturn(saved);

            // when
            RecruitmentTemplateResponse result = service.create(SCOPE_TYPE, SCOPE_ID, USER_ID, request);

            // then
            assertThat(result).isNotNull();
            verify(accessControlService).checkAdminOrAbove(USER_ID, SCOPE_ID, SCOPE_TYPE.name());
            verify(templateRepository).save(any(RecruitmentTemplateEntity.class));
        }

        @Test
        @DisplayName("minCapacity > capacity → INVALID_CAPACITY")
        void create_minCapacityExceedsCapacity_throws() {
            // given: capacity=5, minCapacity=10 は不正
            RecruitmentTemplateCreateRequest request = buildCreateRequest(5, 10, false, null);

            // when / then
            assertThatThrownBy(() -> service.create(SCOPE_TYPE, SCOPE_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RecruitmentErrorCode.INVALID_CAPACITY);
        }

        @Test
        @DisplayName("paymentEnabled=true で price=null → PRICE_REQUIRED")
        void create_paymentEnabledWithoutPrice_throws() {
            // given: 決済有効化するが価格未設定
            RecruitmentTemplateCreateRequest request = buildCreateRequest(20, 5, true, null);

            // when / then
            assertThatThrownBy(() -> service.create(SCOPE_TYPE, SCOPE_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RecruitmentErrorCode.PRICE_REQUIRED);
        }
    }

    // ========================================
    // update
    // ========================================

    @Nested
    @DisplayName("update - テンプレート更新")
    class Update {

        @Test
        @DisplayName("findActiveById + checkAdminOrAbove 通過後、entity.update() が呼ばれ DTO を返す")
        void update_success() throws Exception {
            // given
            RecruitmentTemplateEntity template = buildTemplate();
            setField(template, "id", TEMPLATE_ID);
            given(templateRepository.findActiveById(TEMPLATE_ID)).willReturn(Optional.of(template));
            given(templateRepository.save(any())).willReturn(template);

            // 部分更新リクエスト（タイトルのみ変更）
            RecruitmentTemplateUpdateRequest request = new RecruitmentTemplateUpdateRequest(
                    "更新テンプレート名", null, null, "更新タイトル", null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null);

            // when
            RecruitmentTemplateResponse result = service.update(TEMPLATE_ID, USER_ID, request);

            // then
            assertThat(result).isNotNull();
            verify(accessControlService).checkAdminOrAbove(USER_ID, SCOPE_ID, SCOPE_TYPE.name());
            verify(templateRepository).save(template);
        }

        @Test
        @DisplayName("findActiveById が empty → TEMPLATE_NOT_FOUND")
        void update_notFound_throws() {
            // given
            given(templateRepository.findActiveById(TEMPLATE_ID)).willReturn(Optional.empty());

            RecruitmentTemplateUpdateRequest request = new RecruitmentTemplateUpdateRequest(
                    null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null);

            // when / then
            assertThatThrownBy(() -> service.update(TEMPLATE_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RecruitmentErrorCode.TEMPLATE_NOT_FOUND);
        }
    }

    // ========================================
    // archive
    // ========================================

    @Nested
    @DisplayName("archive - テンプレート論理削除")
    class Archive {

        @Test
        @DisplayName("findActiveById + checkAdminOrAbove 通過後、entity.archive() が呼ばれ保存")
        void archive_success() throws Exception {
            // given
            RecruitmentTemplateEntity template = buildTemplate();
            setField(template, "id", TEMPLATE_ID);
            given(templateRepository.findActiveById(TEMPLATE_ID)).willReturn(Optional.of(template));
            given(templateRepository.save(any())).willReturn(template);

            // when
            service.archive(TEMPLATE_ID, USER_ID);

            // then
            // archive() が呼ばれると deletedAt が設定される
            assertThat(template.getDeletedAt()).isNotNull();
            verify(accessControlService).checkAdminOrAbove(USER_ID, SCOPE_ID, SCOPE_TYPE.name());
            verify(templateRepository).save(template);
        }
    }

    // ========================================
    // ヘルパー
    // ========================================

    private RecruitmentTemplateEntity buildTemplate() {
        return RecruitmentTemplateEntity.builder()
                .scopeType(SCOPE_TYPE)
                .scopeId(SCOPE_ID)
                .categoryId(10L)
                .templateName("テスト")
                .title("テスト募集")
                .participationType(RecruitmentParticipationType.INDIVIDUAL)
                .defaultCapacity(20)
                .defaultMinCapacity(5)
                .defaultDurationMinutes(90)
                .defaultPaymentEnabled(false)
                .defaultVisibility(RecruitmentVisibility.SCOPE_ONLY)
                .createdBy(USER_ID)
                .build();
    }

    /**
     * テンプレート作成リクエストを組み立てる。
     *
     * @param capacity    最大定員
     * @param minCapacity 最小定員
     * @param paymentEnabled 決済有効フラグ
     * @param price       料金（null の場合は未設定）
     */
    private RecruitmentTemplateCreateRequest buildCreateRequest(
            int capacity, int minCapacity, boolean paymentEnabled, Integer price) {
        return new RecruitmentTemplateCreateRequest(
                "テンプレート名",
                10L,
                null,
                "テスト募集タイトル",
                null,
                RecruitmentParticipationType.INDIVIDUAL,
                capacity,
                minCapacity,
                90,
                24,
                24,
                paymentEnabled,
                price,
                RecruitmentVisibility.SCOPE_ONLY,
                null,
                null,
                null,
                null
        );
    }

    private void setField(Object entity, String name, Object value) throws Exception {
        Class<?> clazz = entity.getClass();
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField(name);
                f.setAccessible(true);
                f.set(entity, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
