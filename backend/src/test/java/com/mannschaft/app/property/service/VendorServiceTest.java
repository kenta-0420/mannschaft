package com.mannschaft.app.property.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.property.PropertyHistoryErrorCode;
import com.mannschaft.app.property.VendorCategory;
import com.mannschaft.app.property.entity.VendorEntity;
import com.mannschaft.app.property.repository.VendorRepository;
import com.mannschaft.app.property.service.VendorService.VendorUpsertRequest;
import org.junit.jupiter.api.BeforeEach;
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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link VendorService} 単体テスト（F09.13 Phase 1-ζ-A）。
 *
 * <p>設計書 §3 vendors / §4 業者マスタ API / IDOR 修正（PR #433）の検証範囲:</p>
 * <ul>
 *   <li>createVendor: 正常系・名称未指定/長さ超過 PROPERTY_004・重複 PROPERTY_006</li>
 *   <li>updateVendor: 正常系・他スコープ vendor 更新時 PROPERTY_005（IDOR）</li>
 *   <li>getVendor: 正常系・他スコープ vendor 取得時 PROPERTY_005（IDOR）</li>
 *   <li>softDelete: 正常系・他スコープ削除時 PROPERTY_005（IDOR）</li>
 *   <li>listActiveVendors: ページング動作</li>
 *   <li>suggestByName: 上限 10 件・空クエリで空リスト</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VendorService 単体テスト（F09.13 Phase 1-ζ-A）")
class VendorServiceTest {

    @Mock
    private VendorRepository vendorRepository;

    @InjectMocks
    private VendorService vendorService;

    private static final String SCOPE_TEAM = "TEAM";
    private static final String SCOPE_ORG = "ORGANIZATION";
    private static final Long TEAM_ID = 100L;
    private static final Long OTHER_TEAM_ID = 999L;
    private static final Long CREATED_BY = 7L;
    private static final Long VENDOR_ID = 555L;

    private VendorUpsertRequest baseRequest;

    @BeforeEach
    void setUp() {
        baseRequest = new VendorUpsertRequest(
                "○○塗装工業",
                "マルマルトソウコウギョウ",
                VendorCategory.CONSTRUCTION,
                "03-1234-5678",
                "info@example.jp",
                "https://example.jp",
                "100-0001",
                "東京都千代田区1-2-3",
                "代表 太郎",
                "担当 花子",
                "東京都建設業許可123456",
                java.time.LocalDate.of(2030, 12, 31),
                "備考なし");
    }

    private VendorEntity stubVendor(Long id, String scopeType, Long scopeId, String name) {
        VendorEntity v = VendorEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .name(name)
                .nameKana("カナ")
                .category(VendorCategory.CONSTRUCTION)
                .isActive(true)
                .createdBy(CREATED_BY)
                .build();
        org.springframework.test.util.ReflectionTestUtils.setField(v, "id", id);
        return v;
    }

    // =========================================================================
    // createVendor
    // =========================================================================

    @Nested
    @DisplayName("createVendor")
    class CreateVendor {

        @Test
        @DisplayName("正常系: 同名重複が無ければ業者を保存して返す")
        void create_success() {
            given(vendorRepository.findByScopeTypeAndScopeIdAndNameAndDeletedAtIsNull(
                    SCOPE_TEAM, TEAM_ID, baseRequest.name())).willReturn(Optional.empty());
            given(vendorRepository.save(any(VendorEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            VendorEntity saved = vendorService.createVendor(SCOPE_TEAM, TEAM_ID, CREATED_BY, baseRequest);

            assertThat(saved.getName()).isEqualTo(baseRequest.name());
            assertThat(saved.getScopeType()).isEqualTo(SCOPE_TEAM);
            assertThat(saved.getScopeId()).isEqualTo(TEAM_ID);
            assertThat(saved.getCategory()).isEqualTo(VendorCategory.CONSTRUCTION);
            assertThat(saved.getIsActive()).isTrue();
            assertThat(saved.getCreatedBy()).isEqualTo(CREATED_BY);
            verify(vendorRepository).save(any(VendorEntity.class));
        }

        @Test
        @DisplayName("異常系: 同一スコープ内で同名業者が既存なら PROPERTY_006")
        void create_duplicate_throwsPROPERTY_006() {
            VendorEntity existing = stubVendor(1L, SCOPE_TEAM, TEAM_ID, baseRequest.name());
            given(vendorRepository.findByScopeTypeAndScopeIdAndNameAndDeletedAtIsNull(
                    SCOPE_TEAM, TEAM_ID, baseRequest.name())).willReturn(Optional.of(existing));

            assertThatThrownBy(() ->
                    vendorService.createVendor(SCOPE_TEAM, TEAM_ID, CREATED_BY, baseRequest))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", PropertyHistoryErrorCode.PROPERTY_006);
            verify(vendorRepository, never()).save(any());
        }

        @Test
        @DisplayName("異常系: scopeType が許可外なら PROPERTY_004")
        void create_invalidScope_throwsPROPERTY_004() {
            assertThatThrownBy(() ->
                    vendorService.createVendor("PERSONAL", TEAM_ID, CREATED_BY, baseRequest))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", PropertyHistoryErrorCode.PROPERTY_004);
        }

        @Test
        @DisplayName("異常系: name が空白なら PROPERTY_004")
        void create_blankName_throwsPROPERTY_004() {
            VendorUpsertRequest blank = new VendorUpsertRequest(
                    "  ", null, null, null, null, null, null, null, null, null, null, null, null);
            assertThatThrownBy(() ->
                    vendorService.createVendor(SCOPE_TEAM, TEAM_ID, CREATED_BY, blank))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", PropertyHistoryErrorCode.PROPERTY_004);
        }

        @Test
        @DisplayName("異常系: name が 150 文字超過なら PROPERTY_004")
        void create_tooLongName_throwsPROPERTY_004() {
            String longName = "あ".repeat(151);
            VendorUpsertRequest req = new VendorUpsertRequest(
                    longName, null, null, null, null, null, null, null, null, null, null, null, null);
            assertThatThrownBy(() ->
                    vendorService.createVendor(SCOPE_TEAM, TEAM_ID, CREATED_BY, req))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", PropertyHistoryErrorCode.PROPERTY_004);
        }
    }

    // =========================================================================
    // updateVendor
    // =========================================================================

    @Nested
    @DisplayName("updateVendor")
    class UpdateVendor {

        @Test
        @DisplayName("正常系: 同名のまま更新は重複チェックをスキップしつつ保存")
        void update_sameName_success() {
            VendorEntity existing = stubVendor(VENDOR_ID, SCOPE_TEAM, TEAM_ID, baseRequest.name());
            given(vendorRepository.findByIdAndDeletedAtIsNull(VENDOR_ID)).willReturn(Optional.of(existing));
            given(vendorRepository.save(any(VendorEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            VendorEntity updated = vendorService.updateVendor(SCOPE_TEAM, TEAM_ID, VENDOR_ID, baseRequest);

            assertThat(updated.getName()).isEqualTo(baseRequest.name());
            verify(vendorRepository, never()).findByScopeTypeAndScopeIdAndNameAndDeletedAtIsNull(
                    anyString(), anyLong(), anyString());
        }

        @Test
        @DisplayName("正常系: 名称変更時は重複チェックを行い問題なければ保存")
        void update_renameNoDuplicate_success() {
            VendorEntity existing = stubVendor(VENDOR_ID, SCOPE_TEAM, TEAM_ID, "旧名");
            given(vendorRepository.findByIdAndDeletedAtIsNull(VENDOR_ID)).willReturn(Optional.of(existing));
            given(vendorRepository.findByScopeTypeAndScopeIdAndNameAndDeletedAtIsNull(
                    SCOPE_TEAM, TEAM_ID, baseRequest.name())).willReturn(Optional.empty());
            given(vendorRepository.save(any(VendorEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            VendorEntity updated = vendorService.updateVendor(SCOPE_TEAM, TEAM_ID, VENDOR_ID, baseRequest);
            assertThat(updated.getName()).isEqualTo(baseRequest.name());
        }

        @Test
        @DisplayName("異常系: IDOR — 他スコープ vendor を更新しようとすると PROPERTY_005")
        void update_idor_throwsPROPERTY_005() {
            VendorEntity otherScope = stubVendor(VENDOR_ID, SCOPE_TEAM, OTHER_TEAM_ID, "他チームの業者");
            given(vendorRepository.findByIdAndDeletedAtIsNull(VENDOR_ID))
                    .willReturn(Optional.of(otherScope));

            assertThatThrownBy(() ->
                    vendorService.updateVendor(SCOPE_TEAM, TEAM_ID, VENDOR_ID, baseRequest))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", PropertyHistoryErrorCode.PROPERTY_005);
            verify(vendorRepository, never()).save(any());
        }

        @Test
        @DisplayName("異常系: IDOR — scope_type が違う vendor の更新も PROPERTY_005")
        void update_idor_differentScopeType_throwsPROPERTY_005() {
            VendorEntity orgVendor = stubVendor(VENDOR_ID, SCOPE_ORG, TEAM_ID, "組織業者");
            given(vendorRepository.findByIdAndDeletedAtIsNull(VENDOR_ID))
                    .willReturn(Optional.of(orgVendor));

            assertThatThrownBy(() ->
                    vendorService.updateVendor(SCOPE_TEAM, TEAM_ID, VENDOR_ID, baseRequest))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", PropertyHistoryErrorCode.PROPERTY_005);
        }

        @Test
        @DisplayName("異常系: 名称変更時に同名業者が既に存在すれば PROPERTY_006")
        void update_renameDuplicate_throwsPROPERTY_006() {
            VendorEntity existing = stubVendor(VENDOR_ID, SCOPE_TEAM, TEAM_ID, "旧名");
            VendorEntity dup = stubVendor(VENDOR_ID + 1, SCOPE_TEAM, TEAM_ID, baseRequest.name());
            given(vendorRepository.findByIdAndDeletedAtIsNull(VENDOR_ID)).willReturn(Optional.of(existing));
            given(vendorRepository.findByScopeTypeAndScopeIdAndNameAndDeletedAtIsNull(
                    SCOPE_TEAM, TEAM_ID, baseRequest.name())).willReturn(Optional.of(dup));

            assertThatThrownBy(() ->
                    vendorService.updateVendor(SCOPE_TEAM, TEAM_ID, VENDOR_ID, baseRequest))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", PropertyHistoryErrorCode.PROPERTY_006);
        }
    }

    // =========================================================================
    // getVendor
    // =========================================================================

    @Nested
    @DisplayName("getVendor")
    class GetVendor {

        @Test
        @DisplayName("正常系: scope 一致で vendor を返す")
        void get_success() {
            VendorEntity vendor = stubVendor(VENDOR_ID, SCOPE_TEAM, TEAM_ID, "業者A");
            given(vendorRepository.findByIdAndDeletedAtIsNull(VENDOR_ID)).willReturn(Optional.of(vendor));

            VendorEntity got = vendorService.getVendor(SCOPE_TEAM, TEAM_ID, VENDOR_ID);
            assertThat(got).isSameAs(vendor);
        }

        @Test
        @DisplayName("異常系: 不在 ID は PROPERTY_005")
        void get_notFound_throwsPROPERTY_005() {
            given(vendorRepository.findByIdAndDeletedAtIsNull(VENDOR_ID)).willReturn(Optional.empty());
            assertThatThrownBy(() -> vendorService.getVendor(SCOPE_TEAM, TEAM_ID, VENDOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", PropertyHistoryErrorCode.PROPERTY_005);
        }

        @Test
        @DisplayName("異常系: IDOR — 他スコープ vendor の取得は PROPERTY_005（404 で他存在を漏らさない）")
        void get_idor_throwsPROPERTY_005() {
            VendorEntity otherScope = stubVendor(VENDOR_ID, SCOPE_TEAM, OTHER_TEAM_ID, "他人の業者");
            given(vendorRepository.findByIdAndDeletedAtIsNull(VENDOR_ID))
                    .willReturn(Optional.of(otherScope));

            assertThatThrownBy(() -> vendorService.getVendor(SCOPE_TEAM, TEAM_ID, VENDOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", PropertyHistoryErrorCode.PROPERTY_005);
        }
    }

    // =========================================================================
    // softDelete
    // =========================================================================

    @Nested
    @DisplayName("softDelete")
    class SoftDelete {

        @Test
        @DisplayName("正常系: scope 一致で論理削除して保存")
        void softDelete_success() {
            VendorEntity vendor = stubVendor(VENDOR_ID, SCOPE_TEAM, TEAM_ID, "業者A");
            given(vendorRepository.findByIdAndDeletedAtIsNull(VENDOR_ID)).willReturn(Optional.of(vendor));
            given(vendorRepository.save(any(VendorEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            vendorService.softDelete(SCOPE_TEAM, TEAM_ID, VENDOR_ID);

            assertThat(vendor.getDeletedAt()).isNotNull();
            verify(vendorRepository).save(vendor);
        }

        @Test
        @DisplayName("異常系: IDOR — 他スコープ vendor の削除は PROPERTY_005")
        void softDelete_idor_throwsPROPERTY_005() {
            VendorEntity otherScope = stubVendor(VENDOR_ID, SCOPE_TEAM, OTHER_TEAM_ID, "他人の業者");
            given(vendorRepository.findByIdAndDeletedAtIsNull(VENDOR_ID))
                    .willReturn(Optional.of(otherScope));

            assertThatThrownBy(() -> vendorService.softDelete(SCOPE_TEAM, TEAM_ID, VENDOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", PropertyHistoryErrorCode.PROPERTY_005);
            verify(vendorRepository, never()).save(any());
        }
    }

    // =========================================================================
    // listActiveVendors
    // =========================================================================

    @Nested
    @DisplayName("listActiveVendors")
    class ListActive {

        @Test
        @DisplayName("ページングで Repository の結果をそのまま返す")
        void listActive_paginated() {
            Pageable pageable = PageRequest.of(1, 5);
            VendorEntity v1 = stubVendor(1L, SCOPE_TEAM, TEAM_ID, "業者1");
            VendorEntity v2 = stubVendor(2L, SCOPE_TEAM, TEAM_ID, "業者2");
            Page<VendorEntity> page = new PageImpl<>(List.of(v1, v2), pageable, 12);
            given(vendorRepository.findByScopeTypeAndScopeIdAndIsActiveTrueAndDeletedAtIsNull(
                    SCOPE_TEAM, TEAM_ID, pageable)).willReturn(page);

            Page<VendorEntity> result = vendorService.listActiveVendors(SCOPE_TEAM, TEAM_ID, pageable);

            assertThat(result.getContent()).hasSize(2);
            assertThat(result.getTotalElements()).isEqualTo(12);
            assertThat(result.getNumber()).isEqualTo(1);
            assertThat(result.getSize()).isEqualTo(5);
        }

        @Test
        @DisplayName("scope 不正なら PROPERTY_004（ページング前検証）")
        void listActive_invalidScope_throwsPROPERTY_004() {
            assertThatThrownBy(() -> vendorService.listActiveVendors(
                    "BAD", TEAM_ID, PageRequest.of(0, 10)))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", PropertyHistoryErrorCode.PROPERTY_004);
        }
    }

    // =========================================================================
    // suggestByName
    // =========================================================================

    @Nested
    @DisplayName("suggestByName")
    class Suggest {

        @Test
        @DisplayName("空クエリは空リストを即返す（Repository を呼ばない）")
        void suggest_blankQuery_returnsEmpty() {
            assertThat(vendorService.suggestByName(SCOPE_TEAM, TEAM_ID, "  ")).isEmpty();
            assertThat(vendorService.suggestByName(SCOPE_TEAM, TEAM_ID, null)).isEmpty();
            verify(vendorRepository, never()).searchByKeyword(anyString(), anyLong(), anyString(), any());
        }

        @Test
        @DisplayName("Pageable は size=10 で Repository を呼ぶ（サジェスト上限）")
        void suggest_passesPageable10() {
            given(vendorRepository.searchByKeyword(eq(SCOPE_TEAM), eq(TEAM_ID), eq("塗装"),
                    any(Pageable.class)))
                    .willAnswer(inv -> {
                        Pageable p = inv.getArgument(3);
                        // 上限は 10
                        assertThat(p.getPageSize()).isEqualTo(10);
                        assertThat(p.getPageNumber()).isEqualTo(0);
                        return List.of(stubVendor(1L, SCOPE_TEAM, TEAM_ID, "○○塗装"));
                    });

            List<VendorEntity> result = vendorService.suggestByName(SCOPE_TEAM, TEAM_ID, "塗装");
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getName()).isEqualTo("○○塗装");
        }
    }
}
