package com.mannschaft.app.service.service;

import com.mannschaft.app.service.dto.ExportResponse;
import com.mannschaft.app.service.entity.ServiceRecordEntity;
import com.mannschaft.app.service.entity.ServiceRecordFieldEntity;
import com.mannschaft.app.service.entity.ServiceRecordValueEntity;
import com.mannschaft.app.service.FieldType;
import com.mannschaft.app.service.ServiceRecordStatus;
import com.mannschaft.app.service.repository.ServiceRecordFieldRepository;
import com.mannschaft.app.service.repository.ServiceRecordRepository;
import com.mannschaft.app.service.repository.ServiceRecordValueRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * {@link ServiceRecordExportService} の単体テスト。
 * CSV エクスポートのストリーミング・非同期判定・サニタイズを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ServiceRecordExportService 単体テスト")
class ServiceRecordExportServiceTest {

    @Mock
    private ServiceRecordRepository recordRepository;

    @Mock
    private ServiceRecordFieldRepository fieldRepository;

    @Mock
    private ServiceRecordValueRepository valueRepository;

    @InjectMocks
    private ServiceRecordExportService serviceRecordExportService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long TEAM_ID = 1L;
    private static final Long MEMBER_USER_ID = 10L;
    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 2, 15);

    private ServiceRecordEntity createRecord(Long id, Long memberUserId, LocalDate serviceDate,
                                              String title, String note) {
        return ServiceRecordEntity.builder()
                .teamId(TEAM_ID)
                .memberUserId(memberUserId)
                .staffUserId(20L)
                .serviceDate(serviceDate)
                .title(title)
                .note(note)
                .durationMinutes(60)
                .build();
    }

    private List<ServiceRecordEntity> createRecords(int count) {
        List<ServiceRecordEntity> records = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            records.add(createRecord((long) (i + 1), MEMBER_USER_ID, SERVICE_DATE.plusDays(i),
                    "サービス" + i, "メモ" + i));
        }
        return records;
    }

    // ========================================
    // exportOrNull
    // ========================================

    @Nested
    @DisplayName("exportOrNull")
    class ExportOrNull {

        @Test
        @DisplayName("正常系: 1000件以下でnullが返る（ストリーミング可能）")
        void exportOrNull_1000件以下_nullが返る() {
            // Given
            given(recordRepository.findConfirmedByTeamId(TEAM_ID)).willReturn(createRecords(500));

            // When
            ExportResponse result = serviceRecordExportService.exportOrNull(TEAM_ID, null, null, null);

            // Then
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("正常系: 1001件以上でExportResponseが返る（非同期）")
        void exportOrNull_1001件以上_ExportResponseが返る() {
            // Given
            given(recordRepository.findConfirmedByTeamId(TEAM_ID)).willReturn(createRecords(1001));

            // When
            ExportResponse result = serviceRecordExportService.exportOrNull(TEAM_ID, null, null, null);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo("PROCESSING");
            assertThat(result.getJobId()).startsWith("export_");
        }

        @Test
        @DisplayName("正常系: memberUserIdフィルタで件数が絞られる")
        void exportOrNull_memberUserIdフィルタ_絞られる() {
            // Given
            List<ServiceRecordEntity> records = new ArrayList<>();
            records.add(createRecord(1L, MEMBER_USER_ID, SERVICE_DATE, "サービスA", null));
            records.add(createRecord(2L, 999L, SERVICE_DATE, "サービスB", null)); // 別ユーザー
            given(recordRepository.findConfirmedByTeamId(TEAM_ID)).willReturn(records);

            // When
            ExportResponse result = serviceRecordExportService.exportOrNull(TEAM_ID, MEMBER_USER_ID, null, null);

            // Then
            assertThat(result).isNull(); // 1件のみなのでストリーミング可能
        }

        @Test
        @DisplayName("正常系: 日付フィルタで件数が絞られる")
        void exportOrNull_日付フィルタ_絞られる() {
            // Given
            List<ServiceRecordEntity> records = new ArrayList<>();
            records.add(createRecord(1L, MEMBER_USER_ID, LocalDate.of(2026, 1, 1), "サービスA", null));
            records.add(createRecord(2L, MEMBER_USER_ID, LocalDate.of(2026, 3, 1), "サービスB", null));
            given(recordRepository.findConfirmedByTeamId(TEAM_ID)).willReturn(records);

            // When
            ExportResponse result = serviceRecordExportService.exportOrNull(
                    TEAM_ID, null, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 4, 1));

            // Then
            assertThat(result).isNull(); // 1件のみ
        }

        @Test
        @DisplayName("境界値: ちょうど1000件でnullが返る")
        void exportOrNull_ちょうど1000件_nullが返る() {
            // Given
            given(recordRepository.findConfirmedByTeamId(TEAM_ID)).willReturn(createRecords(1000));

            // When
            ExportResponse result = serviceRecordExportService.exportOrNull(TEAM_ID, null, null, null);

            // Then
            assertThat(result).isNull();
        }
    }

    // ========================================
    // writeCsv
    // ========================================

    @Nested
    @DisplayName("writeCsv")
    class WriteCsv {

        @Test
        @DisplayName("正常系: CSV にBOM・ヘッダー・データ行が出力される")
        void writeCsv_正常_CSV出力される() {
            // Given
            ServiceRecordEntity record = createRecord(1L, MEMBER_USER_ID, SERVICE_DATE, "サービスA", "備考テスト");
            given(recordRepository.findConfirmedByTeamId(TEAM_ID)).willReturn(List.of(record));
            given(fieldRepository.findByTeamIdOrderBySortOrder(TEAM_ID)).willReturn(List.of());
            given(valueRepository.findByServiceRecordIdIn(any())).willReturn(List.of());

            ByteArrayOutputStream baos = new ByteArrayOutputStream();

            // When
            serviceRecordExportService.writeCsv(TEAM_ID, null, null, null, baos);

            // Then
            String csv = baos.toString(StandardCharsets.UTF_8);
            // BOM check (EF BB BF)
            byte[] bytes = baos.toByteArray();
            assertThat(bytes[0]).isEqualTo((byte) 0xEF);
            assertThat(bytes[1]).isEqualTo((byte) 0xBB);
            assertThat(bytes[2]).isEqualTo((byte) 0xBF);
            assertThat(csv).contains("ID,メンバーID,担当スタッフID,サービス日,タイトル,所要時間(分)");
            assertThat(csv).contains("備考テスト");
        }

        @Test
        @DisplayName("正常系: カスタムフィールドがヘッダーに含まれる")
        void writeCsv_カスタムフィールド_ヘッダーに含まれる() {
            // Given
            given(recordRepository.findConfirmedByTeamId(TEAM_ID)).willReturn(List.of());
            ServiceRecordFieldEntity field = ServiceRecordFieldEntity.builder()
                    .teamId(TEAM_ID)
                    .fieldName("体温")
                    .fieldType(FieldType.NUMBER)
                    .build();
            given(fieldRepository.findByTeamIdOrderBySortOrder(TEAM_ID)).willReturn(List.of(field));

            ByteArrayOutputStream baos = new ByteArrayOutputStream();

            // When
            serviceRecordExportService.writeCsv(TEAM_ID, null, null, null, baos);

            // Then
            String csv = baos.toString(StandardCharsets.UTF_8);
            assertThat(csv).contains("体温");
            assertThat(csv).contains(",備考"); // 末尾に備考
        }

        @Test
        @DisplayName("境界値: CSVインジェクション対策でnoteの先頭危険文字がサニタイズされる")
        void writeCsv_CSVインジェクション_サニタイズ() {
            // Given
            ServiceRecordEntity record = createRecord(1L, MEMBER_USER_ID, SERVICE_DATE,
                    "サービス", "=HYPERLINK(\"evil\")");
            given(recordRepository.findConfirmedByTeamId(TEAM_ID)).willReturn(List.of(record));
            given(fieldRepository.findByTeamIdOrderBySortOrder(TEAM_ID)).willReturn(List.of());
            given(valueRepository.findByServiceRecordIdIn(any())).willReturn(List.of());

            ByteArrayOutputStream baos = new ByteArrayOutputStream();

            // When
            serviceRecordExportService.writeCsv(TEAM_ID, null, null, null, baos);

            // Then
            String csv = baos.toString(StandardCharsets.UTF_8);
            assertThat(csv).contains("'=HYPERLINK");
        }

        @Test
        @DisplayName("正常系: レコード0件はヘッダーのみ")
        void writeCsv_0件_ヘッダーのみ() {
            // Given
            given(recordRepository.findConfirmedByTeamId(TEAM_ID)).willReturn(List.of());
            given(fieldRepository.findByTeamIdOrderBySortOrder(TEAM_ID)).willReturn(List.of());

            ByteArrayOutputStream baos = new ByteArrayOutputStream();

            // When
            serviceRecordExportService.writeCsv(TEAM_ID, null, null, null, baos);

            // Then
            String csv = baos.toString(StandardCharsets.UTF_8);
            assertThat(csv).contains("ID,メンバーID");
        }
    }
}
