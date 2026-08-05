package com.mannschaft.app.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.mannschaft.app.auth.entity.AuditLogEntity;
import com.mannschaft.app.auth.repository.AuditLogRepository;
import com.mannschaft.app.auth.service.AuditLogArchiveBatchService;
import com.mannschaft.app.common.storage.StorageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AuditLogArchiveBatchService} の単体テスト。
 *
 * <p>本テストは監査ログの「アーカイブ内容」と「DB からの削除範囲」が
 * 常に一致することを守る番人である。両者がずれると、監査ログが
 * R2 に残らないまま DB から消え、事後追跡が不可能になる。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuditLogArchiveBatchService 単体テスト")
class AuditLogArchiveBatchServiceTest {

    /** 本番実装と同じページサイズ（境界値テスト用）。*/
    private static final int PAGE_SIZE = 1000;

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private StorageService storageService;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private AuditLogArchiveBatchService sut;

    /** 年月 → その月に存在する監査ログ（id 昇順）。{@link #givenLogs} で組み立てる。*/
    private final Map<YearMonth, List<AuditLogEntity>> fixture = new HashMap<>();

    // ─────────────────────────────────────────────
    // archiveOldLogs
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("archiveOldLogs")
    class ArchiveOldLogs {

        @Test
        @DisplayName("AC-0-3: 対象0件_R2に空ファイルを作らず正常終了する")
        void 対象なし_何もしない() {
            // given: 閾値より古いログが1件も無い
            when(auditLogRepository.findOldestCreatedAtBefore(any())).thenReturn(null);

            // when
            sut.archiveOldLogs();

            // then
            verify(storageService, never()).upload(any(), any(byte[].class), any());
            verify(jdbcTemplate, never()).execute(any(String.class));
        }

        @Test
        @DisplayName("対象あり_月ごとにR2アップロードしてパーティションDROPされる")
        void 対象あり_R2にアップロードしてパーティションDROPされる() {
            // given
            givenLogs(YearMonth.of(2020, 1), 1L, 2);
            givenLogs(YearMonth.of(2020, 2), 3L, 1);
            stubRepository();

            // when
            sut.archiveOldLogs();

            // then: 2020-01 と 2020-02 の2つのオブジェクトがR2にアップロードされる
            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            verify(storageService, org.mockito.Mockito.times(2))
                    .upload(keyCaptor.capture(), any(byte[].class), eq("application/json"));

            assertThat(keyCaptor.getAllValues()).containsExactlyInAnyOrder(
                    "audit-archive/2020/01/audit-2020-01.json",
                    "audit-archive/2020/02/audit-2020-02.json"
            );

            // パーティション DROP が月ごとに呼ばれる
            verify(jdbcTemplate).execute(contains("p_2020_01"));
            verify(jdbcTemplate).execute(contains("p_2020_02"));
        }

        @Test
        @DisplayName("AC-0-3: ログが存在しない月のパーティションはDROPも空アップロードもしない")
        void 空の月はDROPもアップロードもしない() {
            // given: 2020-01 のみ存在し、2020-02 は空
            givenLogs(YearMonth.of(2020, 1), 1L, 3);
            stubRepository();

            // when
            sut.archiveOldLogs();

            // then: アップロードは1回だけ、DROP も 2020-01 のみ
            verify(storageService, org.mockito.Mockito.times(1))
                    .upload(any(), any(byte[].class), any());
            verify(jdbcTemplate, org.mockito.Mockito.times(1)).execute(any(String.class));
            verify(jdbcTemplate).execute(contains("p_2020_01"));
        }

        @Test
        @DisplayName("AC-0-1: 1000行超でもR2出力に同一ログIDの重複が0件")
        void 大量件数でもログIDが重複しない() {
            // given: 1ページ (1000) を大きく超える 2500 件
            givenLogs(YearMonth.of(2020, 1), 1L, 2500);
            stubRepository();

            // when
            sut.archiveOldLogs();

            // then
            List<Long> uploadedIds = capturedUploadedIds();
            assertThat(uploadedIds).hasSize(2500);
            assertThat(uploadedIds).doesNotHaveDuplicates();
            assertThat(uploadedIds).containsExactlyInAnyOrderElementsOf(expectedIds(YearMonth.of(2020, 1)));
        }

        @Test
        @DisplayName("AC-0-2: 閾値以前の全対象行をアーカイブしきる（アーカイブ件数＝削除対象件数）")
        void 全対象行をアーカイブしきる() {
            // given: 3ヶ月にまたがる大量データ
            givenLogs(YearMonth.of(2020, 1), 1L, 1500);
            givenLogs(YearMonth.of(2020, 2), 2001L, 700);
            givenLogs(YearMonth.of(2020, 3), 3001L, 1000);
            stubRepository();

            // when
            sut.archiveOldLogs();

            // then: アーカイブされた件数が対象総数と一致し、DROP は3ヶ月分
            List<Long> uploadedIds = capturedUploadedIds();
            assertThat(uploadedIds).hasSize(1500 + 700 + 1000);
            assertThat(uploadedIds).doesNotHaveDuplicates();
            verify(jdbcTemplate).execute(contains("p_2020_01"));
            verify(jdbcTemplate).execute(contains("p_2020_02"));
            verify(jdbcTemplate).execute(contains("p_2020_03"));
        }

        @Test
        @DisplayName("AC-0-4: ちょうどPAGE_SIZE件でも重複なく全件アーカイブされる")
        void 境界値_ちょうど1ページ() {
            // given
            givenLogs(YearMonth.of(2020, 1), 1L, PAGE_SIZE);
            stubRepository();

            // when
            sut.archiveOldLogs();

            // then
            List<Long> uploadedIds = capturedUploadedIds();
            assertThat(uploadedIds).hasSize(PAGE_SIZE);
            assertThat(uploadedIds).doesNotHaveDuplicates();
            verify(jdbcTemplate).execute(contains("p_2020_01"));
        }

        @Test
        @DisplayName("AC-0-4: PAGE_SIZE+1件でも重複なく全件アーカイブされる")
        void 境界値_1ページ超過1件() {
            // given
            givenLogs(YearMonth.of(2020, 1), 1L, PAGE_SIZE + 1);
            stubRepository();

            // when
            sut.archiveOldLogs();

            // then
            List<Long> uploadedIds = capturedUploadedIds();
            assertThat(uploadedIds).hasSize(PAGE_SIZE + 1);
            assertThat(uploadedIds).doesNotHaveDuplicates();
            verify(jdbcTemplate).execute(contains("p_2020_01"));
        }

        @Test
        @DisplayName("AC-0-5: R2アップロード失敗_パーティションDROPを実行せず失敗を呼び手へ伝える")
        void R2アップロード失敗_DB削除を実行しない() {
            // given
            givenLogs(YearMonth.of(2020, 1), 1L, 1);
            stubRepository();
            org.mockito.Mockito.doThrow(new RuntimeException("R2接続失敗"))
                    .when(storageService).upload(any(), any(byte[].class), any());

            // when / then: 失敗を握り潰して正常終了させない
            //（手動起動でも失敗が呼び手へ伝わらなければ、アーカイブ未完了に運用が気付けない）
            assertThatThrownBy(() -> sut.archiveOldLogs())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("2020-01");

            // パーティション DROP は実行されない
            verify(jdbcTemplate, never()).execute(any(String.class));
        }

        @Test
        @DisplayName("AC-0-5: 複数ページの途中でR2アップロードが失敗_パーティションDROPを実行しない")
        void R2アップロード途中失敗_DB削除を実行しない() {
            // given: 2ページ分。2回目のアップロードで失敗させる
            givenLogs(YearMonth.of(2020, 1), 1L, PAGE_SIZE + 10);
            stubRepository();
            org.mockito.Mockito.doNothing().doThrow(new RuntimeException("R2接続失敗"))
                    .when(storageService).upload(any(), any(byte[].class), any());

            // when / then
            assertThatThrownBy(() -> sut.archiveOldLogs())
                    .isInstanceOf(IllegalStateException.class);

            // 一部しかアップロードできていないのでパーティションを落としてはならない
            verify(jdbcTemplate, never()).execute(any(String.class));
        }

        @Test
        @DisplayName("ある月の失敗で後続の月まで打ち切らず、成功した月は削除まで進む")
        void 失敗月があっても後続の月は処理される() {
            // given: 3ヶ月分。最初の月のアップロードだけ失敗させる
            givenLogs(YearMonth.of(2020, 1), 1L, 1);
            givenLogs(YearMonth.of(2020, 2), 11L, 1);
            givenLogs(YearMonth.of(2020, 3), 21L, 1);
            stubRepository();
            org.mockito.Mockito.doThrow(new RuntimeException("R2接続失敗"))
                    .doNothing()
                    .when(storageService).upload(any(), any(byte[].class), any());

            // when / then: 失敗月は集約例外で報告される
            assertThatThrownBy(() -> sut.archiveOldLogs())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("2020-01")
                    .hasMessageNotContaining("2020-02")
                    .hasMessageNotContaining("2020-03");

            // 失敗月は DROP せず、後続の成功月は DROP まで到達する
            verify(jdbcTemplate, never()).execute(contains("p_2020_01"));
            verify(jdbcTemplate).execute(contains("p_2020_02"));
            verify(jdbcTemplate).execute(contains("p_2020_03"));
        }

        @Test
        @DisplayName("閾値と同じ月のパーティションはDROPしない（未経過分を巻き込むため）")
        void 閾値の月はDROPしない() {
            // given: 閾値（now-2年）と同じ月にログがある
            YearMonth cutoffMonth = YearMonth.from(LocalDate.now().minusYears(2));
            givenLogs(cutoffMonth, 1L, 5);
            stubRepository();

            // when
            sut.archiveOldLogs();

            // then: 当該月は次回以降に持ち越すため、アップロードも DROP もしない
            verify(storageService, never()).upload(any(), any(byte[].class), any());
            verify(jdbcTemplate, never()).execute(any(String.class));
        }
    }

    // ─────────────────────────────────────────────
    // buildR2Key
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("buildR2Key")
    class BuildR2Key {

        @Test
        @DisplayName("年月からR2キーが正しく生成される_1桁月")
        void 年月からR2キーが正しく生成される_1桁月() {
            String key = AuditLogArchiveBatchService.buildR2Key(YearMonth.of(2024, 3), 0);
            assertThat(key).isEqualTo("audit-archive/2024/03/audit-2024-03.json");
        }

        @Test
        @DisplayName("年月からR2キーが正しく生成される_2桁月")
        void 年月からR2キーが正しく生成される_2桁月() {
            String key = AuditLogArchiveBatchService.buildR2Key(YearMonth.of(2025, 12), 0);
            assertThat(key).isEqualTo("audit-archive/2025/12/audit-2025-12.json");
        }

        @Test
        @DisplayName("2ページ目以降は part 付きキーになり先頭ページを上書きしない")
        void パート付きキーが生成される() {
            assertThat(AuditLogArchiveBatchService.buildR2Key(YearMonth.of(2024, 3), 0))
                    .isEqualTo("audit-archive/2024/03/audit-2024-03.json");
            assertThat(AuditLogArchiveBatchService.buildR2Key(YearMonth.of(2024, 3), 1))
                    .isEqualTo("audit-archive/2024/03/audit-2024-03.part1.json");
        }
    }

    // ─────────────────────────────────────────────
    // ヘルパー
    // ─────────────────────────────────────────────

    /**
     * 指定年月に {@code count} 件のログを（id 昇順で）用意する。
     */
    private void givenLogs(YearMonth ym, long startId, int count) {
        List<AuditLogEntity> logs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            // 月内に収まるよう日付は 1〜28 日で循環させる
            LocalDateTime createdAt = ym.atDay(1 + (i % 28)).atTime(10, 0);
            logs.add(createAuditLog(startId + i, createdAt));
        }
        fixture.put(ym, logs);
    }

    /**
     * {@link #fixture} を正とする「本物らしい」リポジトリのふるまいを仕込む。
     * キーセットページング（{@code id > cursor}）を忠実に模倣する。
     */
    private void stubRepository() {
        LocalDateTime oldest = fixture.keySet().stream()
                .min(YearMonth::compareTo)
                .map(ym -> fixture.get(ym).get(0).getCreatedAt())
                .orElse(null);
        lenient().when(auditLogRepository.findOldestCreatedAtBefore(any())).thenReturn(oldest);

        lenient().when(auditLogRepository.findMonthSliceAfterId(any(), any(), anyLong(), any()))
                .thenAnswer(invocation -> {
                    LocalDateTime from = invocation.getArgument(0);
                    Long cursor = invocation.getArgument(2);
                    Pageable pageable = invocation.getArgument(3);
                    List<AuditLogEntity> monthLogs =
                            fixture.getOrDefault(YearMonth.from(from.toLocalDate()), List.of());
                    return monthLogs.stream()
                            .filter(e -> e.getId() > cursor)
                            .limit(pageable.getPageSize())
                            .toList();
                });
    }

    /**
     * R2 にアップロードされた JSON を全て読み戻し、含まれるログ ID を連結して返す。
     */
    private List<Long> capturedUploadedIds() {
        ArgumentCaptor<byte[]> bodyCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(storageService, org.mockito.Mockito.atLeastOnce())
                .upload(any(), bodyCaptor.capture(), any());

        List<Long> ids = new ArrayList<>();
        for (byte[] body : bodyCaptor.getAllValues()) {
            try {
                List<Map<String, Object>> records =
                        objectMapper.readValue(body, new TypeReference<List<Map<String, Object>>>() { });
                records.forEach(r -> ids.add(((Number) r.get("id")).longValue()));
            } catch (Exception e) {
                throw new IllegalStateException("アップロードされた JSON の読み戻しに失敗", e);
            }
        }
        return ids;
    }

    private List<Long> expectedIds(YearMonth ym) {
        return fixture.get(ym).stream().map(AuditLogEntity::getId).toList();
    }

    private AuditLogEntity createAuditLog(Long id, LocalDateTime createdAt) {
        AuditLogEntity entity = AuditLogEntity.builder()
                .eventType("LOGIN_SUCCESS")
                .userId(42L)
                .build();
        ReflectionTestUtils.setField(entity, "id", id);
        ReflectionTestUtils.setField(entity, "createdAt", createdAt);
        return entity;
    }
}
