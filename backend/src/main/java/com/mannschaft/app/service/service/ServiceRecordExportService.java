package com.mannschaft.app.service.service;

import com.mannschaft.app.service.dto.ExportResponse;
import com.mannschaft.app.service.entity.ServiceRecordEntity;
import com.mannschaft.app.service.entity.ServiceRecordFieldEntity;
import com.mannschaft.app.service.entity.ServiceRecordValueEntity;
import com.mannschaft.app.service.repository.ServiceRecordFieldRepository;
import com.mannschaft.app.service.repository.ServiceRecordRepository;
import com.mannschaft.app.service.repository.ServiceRecordValueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * CSV エクスポートサービス。バッチ処理のため独立した @Service クラスとして実装。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ServiceRecordExportService {

    private final ServiceRecordRepository recordRepository;
    private final ServiceRecordFieldRepository fieldRepository;
    private final ServiceRecordValueRepository valueRepository;

    private static final int STREAMING_THRESHOLD = 1000;
    private static final byte[] UTF8_BOM = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /**
     * CSV エクスポートを実行する。1000件以下はストリーミング、超過は非同期ジョブ。
     *
     * @return ストリーミング可能な場合は null、非同期の場合は ExportResponse
     */
    public ExportResponse exportOrNull(Long teamId, Long memberUserId,
                                        LocalDate serviceDateFrom, LocalDate serviceDateTo) {
        List<ServiceRecordEntity> records = recordRepository.findConfirmedByTeamId(teamId);

        // フィルタ
        if (memberUserId != null) {
            records = records.stream()
                    .filter(r -> r.getMemberUserId().equals(memberUserId))
                    .collect(Collectors.toList());
        }
        if (serviceDateFrom != null) {
            records = records.stream()
                    .filter(r -> !r.getServiceDate().isBefore(serviceDateFrom))
                    .collect(Collectors.toList());
        }
        if (serviceDateTo != null) {
            records = records.stream()
                    .filter(r -> !r.getServiceDate().isAfter(serviceDateTo))
                    .collect(Collectors.toList());
        }

        if (records.size() > STREAMING_THRESHOLD) {
            // 非同期ジョブとして処理
            String jobId = "export_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            log.info("非同期CSVエクスポート開始: teamId={}, jobId={}, count={}", teamId, jobId, records.size());
            // TODO: 非同期ジョブを起動
            return ExportResponse.builder()
                    .jobId(jobId)
                    .status("PROCESSING")
                    .message("エクスポートを開始しました。完了後に通知でお知らせします。")
                    .build();
        }

        return null; // ストリーミング可能
    }

    /**
     * CSV をストリーミング書き出しする。
     */
    public void writeCsv(Long teamId, Long memberUserId,
                          LocalDate serviceDateFrom, LocalDate serviceDateTo,
                          OutputStream outputStream) {
        List<ServiceRecordEntity> records = recordRepository.findConfirmedByTeamId(teamId);

        if (memberUserId != null) {
            records = records.stream()
                    .filter(r -> r.getMemberUserId().equals(memberUserId))
                    .collect(Collectors.toList());
        }
        if (serviceDateFrom != null) {
            records = records.stream()
                    .filter(r -> !r.getServiceDate().isBefore(serviceDateFrom))
                    .collect(Collectors.toList());
        }
        if (serviceDateTo != null) {
            records = records.stream()
                    .filter(r -> !r.getServiceDate().isAfter(serviceDateTo))
                    .collect(Collectors.toList());
        }

        List<ServiceRecordFieldEntity> fields = fieldRepository.findByTeamIdOrderBySortOrder(teamId);

        List<Long> recordIds = records.stream().map(ServiceRecordEntity::getId).collect(Collectors.toList());
        List<ServiceRecordValueEntity> allValues = recordIds.isEmpty()
                ? List.of()
                : valueRepository.findByServiceRecordIdIn(recordIds);
        Map<Long, Map<Long, String>> valuesByRecord = allValues.stream()
                .collect(Collectors.groupingBy(
                        ServiceRecordValueEntity::getServiceRecordId,
                        Collectors.toMap(ServiceRecordValueEntity::getFieldId,
                                v -> v.getValue() != null ? v.getValue() : "")));

        try {
            outputStream.write(UTF8_BOM);
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));

            // ヘッダー行
            StringBuilder header = new StringBuilder("ID,メンバーID,担当スタッフID,サービス日,タイトル,所要時間(分)");
            for (ServiceRecordFieldEntity field : fields) {
                header.append(",").append(escapeCsv(field.getFieldName()));
            }
            header.append(",備考");
            writer.println(header);

            // データ行
            for (ServiceRecordEntity record : records) {
                StringBuilder line = new StringBuilder();
                line.append(record.getId());
                line.append(",").append(record.getMemberUserId());
                line.append(",").append(record.getStaffUserId() != null ? record.getStaffUserId() : "");
                line.append(",").append(record.getServiceDate());
                line.append(",").append(escapeCsv(record.getTitle()));
                line.append(",").append(record.getDurationMinutes() != null ? record.getDurationMinutes() : "");

                Map<Long, String> recordValues = valuesByRecord.getOrDefault(record.getId(), Map.of());
                for (ServiceRecordFieldEntity field : fields) {
                    String val = recordValues.getOrDefault(field.getId(), "");
                    line.append(",").append(escapeCsv(sanitizeCsvValue(val)));
                }

                line.append(",").append(escapeCsv(sanitizeCsvValue(record.getNote() != null ? record.getNote() : "")));
                writer.println(line);
            }

            writer.flush();
        } catch (Exception e) {
            log.error("CSV書き出しエラー: teamId={}", teamId, e);
        }
    }

    /**
     * CSV値のサニタイズ（インジェクション対策）。
     */
    private String sanitizeCsvValue(String value) {
        if (value == null || value.isEmpty()) return value;
        char first = value.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@') {
            return "'" + value;
        }
        return value;
    }

    /**
     * CSV値のエスケープ。
     */
    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
