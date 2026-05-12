package com.mannschaft.app.repairplan.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.repairplan.RepairPlanErrorCode;
import com.mannschaft.app.repairplan.dto.CsvImportConfirmResponse;
import com.mannschaft.app.repairplan.dto.CsvImportPreviewResponse;
import com.mannschaft.app.repairplan.entity.RepairPlanItem;
import com.mannschaft.app.repairplan.repository.RepairPlanItemRepository;
import com.mannschaft.app.repairplan.repository.RepairPlanTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link RepairPlanItemCsvService} 単体テスト（F08.8 Phase 1）。
 *
 * <p>カバー項目：</p>
 * <ul>
 *   <li>BOM 付き CSV パース（先頭 BOM 自動除去）</li>
 *   <li>異常行のエラーレポート（行番号付き）</li>
 *   <li>preview → confirm の正常系（INSERT 件数 / Valkey 削除確認）</li>
 *   <li>Valkey TTL 切れの 404（REPAIR_PLAN_CSV_001）</li>
 *   <li>5MB 超過の REPAIR_PLAN_CSV_002</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RepairPlanItemCsvServiceTest {

    @Mock
    private RepairPlanItemRepository itemRepository;

    @Mock
    private RepairPlanTemplateRepository templateRepository;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private RepairPlanItemCsvService service;

    /** Valkey の代わりに in-memory Map を使ったスタブ */
    private final Map<String, String> redisStore = new HashMap<>();

    private static final Long USER_ID = 100L;
    private static final Long SCOPE_ID = 200L;
    private static final String SCOPE_TYPE = "TEAM";
    private static final Long ORG_ID = 300L;

    @BeforeEach
    void setUp() {
        redisStore.clear();
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        // set(key, value, duration) のスタブ
        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            String value = invocation.getArgument(1);
            redisStore.put(key, value);
            return null;
        }).when(valueOperations).set(anyString(), anyString(), any(Duration.class));
        // get(key) のスタブ
        given(valueOperations.get(anyString()))
                .willAnswer(invocation -> redisStore.get(invocation.<String>getArgument(0)));
        // delete(key) のスタブ
        given(redisTemplate.delete(anyString()))
                .willAnswer(invocation -> redisStore.remove(invocation.<String>getArgument(0)) != null);
        // テンプレートマスタは空（カテゴリ未登録チェックを実質スキップ）
        given(templateRepository.findBySystemScope()).willReturn(List.of());
        given(templateRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNull(anyString(), any()))
                .willReturn(List.of());
    }

    // ─────────────────────────────────────────────
    // preview 正常系（BOM 付き）
    // ─────────────────────────────────────────────
    @Test
    @DisplayName("BOM 付き UTF-8 CSV をパースし、ヘッダ行をスキップして有効行を抽出する")
    void preview_withBom_parsesCorrectly() {
        String header = "カテゴリ,項目名,説明,計画年度,計画月,見積金額,CPI基準年度,ステータス,タグ\n";
        String row = "外壁塗装,1回目大規模修繕,計画通り,2030,3,15000000,2024,PLANNED,築古\n";
        String csv = "﻿" + header + row;
        MockMultipartFile file = new MockMultipartFile("file", "items.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        CsvImportPreviewResponse response = service.preview(file, USER_ID, SCOPE_ID, SCOPE_TYPE, ORG_ID);

        assertThat(response.totalRows()).isEqualTo(1);
        assertThat(response.validRows()).isEqualTo(1);
        assertThat(response.errorRows()).isZero();
        assertThat(response.importToken()).isNotBlank();
        assertThat(response.expiresAt()).isNotNull();
        assertThat(response.preview()).hasSize(1);
        assertThat(response.preview().get(0).category()).isEqualTo("外壁塗装");
        assertThat(response.preview().get(0).valid()).isTrue();
        // Valkey に保存されたこと
        assertThat(redisStore).hasSize(1);
        // ADMIN/DEPUTY_ADMIN チェックが呼ばれている
        verify(accessControlService).checkAdminOrAbove(USER_ID, SCOPE_ID, SCOPE_TYPE);
    }

    // ─────────────────────────────────────────────
    // 異常行検出（行番号 + フィールド）
    // ─────────────────────────────────────────────
    @Test
    @DisplayName("不正な年度・金額・ステータスを行番号付きで報告する")
    void preview_detectsInvalidRows() {
        String header = "category,title,description,plannedYear,plannedMonth,estimatedAmount,cpiBasis,status,tags\n";
        String validRow = "外壁,1回目,desc,2030,3,15000000,2024,PLANNED,\n";
        String invalidYear = "外壁,2回目,desc,not_a_year,3,15000000,2024,PLANNED,\n";
        String invalidAmount = "外壁,3回目,desc,2030,3,not_a_number,2024,PLANNED,\n";
        String invalidStatus = "外壁,4回目,desc,2030,3,15000000,2024,UNKNOWN_STATUS,\n";
        String csv = header + validRow + invalidYear + invalidAmount + invalidStatus;
        MockMultipartFile file = new MockMultipartFile("file", "items.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        CsvImportPreviewResponse response = service.preview(file, USER_ID, SCOPE_ID, SCOPE_TYPE, ORG_ID);

        assertThat(response.totalRows()).isEqualTo(4);
        assertThat(response.validRows()).isEqualTo(1);
        assertThat(response.errorRows()).isEqualTo(3);
        // エラーは行番号 3,4,5（1=header, 2=valid, 3-5=invalid）
        List<Integer> errorRowNumbers = response.errors().stream().map(e -> e.rowNumber()).distinct().sorted().toList();
        assertThat(errorRowNumbers).containsExactly(3, 4, 5);
        // フィールド名がエラーに含まれる
        assertThat(response.errors()).anyMatch(e -> "planned_year".equals(e.field()));
        assertThat(response.errors()).anyMatch(e -> "estimated_amount".equals(e.field()));
        assertThat(response.errors()).anyMatch(e -> "status".equals(e.field()));
    }

    // ─────────────────────────────────────────────
    // preview → confirm の正常系
    // ─────────────────────────────────────────────
    @Test
    @DisplayName("preview で発行された importToken で confirm すると INSERT 件数が返り Valkey から削除される")
    void preview_and_confirm_happyPath() {
        // tags カラム内に複数値を入れる場合は RFC 4180 通り引用符でくくる
        String csv = "カテゴリ,項目名,説明,計画年度,計画月,見積金額,CPI基準年度,ステータス,タグ\n"
                + "外壁塗装,1回目,大規模1,2030,3,15000000,2024,PLANNED,\"築古,築20年\"\n"
                + "防水工事,屋上防水,12年周期,2032,6,8000000,2024,RESERVED,\n";
        MockMultipartFile file = new MockMultipartFile("file", "items.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        CsvImportPreviewResponse preview = service.preview(file, USER_ID, SCOPE_ID, SCOPE_TYPE, ORG_ID);
        assertThat(preview.validRows()).isEqualTo(2);
        assertThat(redisStore).hasSize(1);

        // saveAll は引数をそのまま返す
        given(itemRepository.saveAll(any())).willAnswer(inv -> inv.<Iterable<RepairPlanItem>>getArgument(0));

        CsvImportConfirmResponse confirm = service.confirm(preview.importToken(), USER_ID, SCOPE_ID, SCOPE_TYPE, ORG_ID);

        assertThat(confirm.totalRows()).isEqualTo(2);
        assertThat(confirm.insertedRows()).isEqualTo(2);
        assertThat(confirm.skippedRows()).isZero();

        // 保存される Entity の中身を検証
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<RepairPlanItem>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(itemRepository).saveAll(captor.capture());
        List<RepairPlanItem> saved = (List<RepairPlanItem>) captor.getValue();
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getCategory()).isEqualTo("外壁塗装");
        assertThat(saved.get(0).getEstimatedAmount()).isEqualTo(15_000_000L);
        assertThat(saved.get(0).getPlannedYear()).isEqualTo(2030);
        assertThat(saved.get(0).getStatus()).isEqualTo("PLANNED");
        assertThat(saved.get(0).getOrganizationId()).isEqualTo(ORG_ID);
        // JSON 配列形式の tags
        assertThat(saved.get(0).getTags()).contains("築古").contains("築20年");

        // 監査ログ
        verify(auditLogService).record(
                eq("PLAN_ITEM_CSV_IMPORTED"),
                eq(USER_ID), any(), any(), eq(ORG_ID), any(), any(), any(), anyString());

        // Valkey から削除されている
        assertThat(redisStore).isEmpty();
    }

    // ─────────────────────────────────────────────
    // Valkey TTL 切れの 404
    // ─────────────────────────────────────────────
    @Test
    @DisplayName("Valkey から消えた importToken で confirm すると REPAIR_PLAN_CSV_001 を返す")
    void confirm_missingPreview_throws001() {
        assertThatThrownBy(() ->
                service.confirm("non-existent-token", USER_ID, SCOPE_ID, SCOPE_TYPE, ORG_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(RepairPlanErrorCode.REPAIR_PLAN_CSV_001);
        verify(itemRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("空文字 importToken で confirm すると REPAIR_PLAN_CSV_001 を返す")
    void confirm_blankToken_throws001() {
        assertThatThrownBy(() ->
                service.confirm("  ", USER_ID, SCOPE_ID, SCOPE_TYPE, ORG_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(RepairPlanErrorCode.REPAIR_PLAN_CSV_001);
    }

    // ─────────────────────────────────────────────
    // 5MB 超過の 413（REPAIR_PLAN_CSV_002）
    // ─────────────────────────────────────────────
    @Test
    @DisplayName("5MB を超える CSV は REPAIR_PLAN_CSV_002 を返す")
    void preview_oversize_throws002() {
        // RepairPlanItemCsvService.MAX_FILE_SIZE_BYTES = 5MB
        byte[] huge = new byte[(int) RepairPlanItemCsvService.MAX_FILE_SIZE_BYTES + 1];
        // 中身はとりあえずヘッダだけにしておく
        byte[] head = "category,title,description,year,month,amount\n".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(head, 0, huge, 0, head.length);
        MockMultipartFile file = new MockMultipartFile("file", "huge.csv", "text/csv", huge);

        assertThatThrownBy(() ->
                service.preview(file, USER_ID, SCOPE_ID, SCOPE_TYPE, ORG_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(RepairPlanErrorCode.REPAIR_PLAN_CSV_002);
    }

    // ─────────────────────────────────────────────
    // 空ファイル
    // ─────────────────────────────────────────────
    @Test
    @DisplayName("空ファイルは REPAIR_PLAN_CSV_003 を返す")
    void preview_emptyFile_throws003() {
        MockMultipartFile file = new MockMultipartFile("file", "empty.csv", "text/csv", new byte[0]);
        assertThatThrownBy(() ->
                service.preview(file, USER_ID, SCOPE_ID, SCOPE_TYPE, ORG_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(RepairPlanErrorCode.REPAIR_PLAN_CSV_003);
    }

    // ─────────────────────────────────────────────
    // 列数不足
    // ─────────────────────────────────────────────
    @Test
    @DisplayName("列数不足の行はエラー扱いになる")
    void preview_tooFewColumns_marksError() {
        String csv = "カテゴリ,項目名,説明,計画年度,計画月,見積金額\n"
                + "外壁,1回目,desc\n"; // 列数 3 で MIN_COLUMNS=6 未満
        MockMultipartFile file = new MockMultipartFile("file", "items.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        CsvImportPreviewResponse response = service.preview(file, USER_ID, SCOPE_ID, SCOPE_TYPE, ORG_ID);

        assertThat(response.errorRows()).isEqualTo(1);
        assertThat(response.validRows()).isZero();
        assertThat(response.errors()).anyMatch(e -> "_row".equals(e.field()));
    }

    // ─────────────────────────────────────────────
    // 引用符付き CSV（カンマを含む値の正しい解釈）
    // ─────────────────────────────────────────────
    @Test
    @DisplayName("引用符で囲まれたフィールド内のカンマは区切り扱いされない")
    void preview_quotedField_handlesEmbeddedComma() {
        String csv = "カテゴリ,項目名,説明,計画年度,計画月,見積金額\n"
                + "外壁塗装,\"1回目, 大規模\",\"説明,with,commas\",2030,3,15000000\n";
        MockMultipartFile file = new MockMultipartFile("file", "items.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        CsvImportPreviewResponse response = service.preview(file, USER_ID, SCOPE_ID, SCOPE_TYPE, ORG_ID);
        assertThat(response.validRows()).isEqualTo(1);
        assertThat(response.preview().get(0).title()).isEqualTo("1回目, 大規模");
        assertThat(response.preview().get(0).description()).isEqualTo("説明,with,commas");
    }
}
