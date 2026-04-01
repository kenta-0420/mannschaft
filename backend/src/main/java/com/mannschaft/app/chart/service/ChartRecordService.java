package com.mannschaft.app.chart.service;

import com.mannschaft.app.chart.ChartErrorCode;
import com.mannschaft.app.chart.ChartMapper;
import com.mannschaft.app.chart.ChartPhotoUrlProvider;
import com.mannschaft.app.chart.dto.ChartBodyMarkResponse;
import com.mannschaft.app.chart.dto.ChartFormulaResponse;
import com.mannschaft.app.chart.dto.ChartPhotoResponse;
import com.mannschaft.app.chart.dto.ChartRecordResponse;
import com.mannschaft.app.chart.dto.ChartRecordSummaryResponse;
import com.mannschaft.app.chart.dto.CopyChartRequest;
import com.mannschaft.app.chart.dto.CreateChartRecordRequest;
import com.mannschaft.app.chart.dto.CustomFieldValueResponse;
import com.mannschaft.app.chart.dto.PinResponse;
import com.mannschaft.app.chart.dto.ShareResponse;
import com.mannschaft.app.chart.dto.UpdateChartRecordRequest;
import com.mannschaft.app.chart.entity.ChartBodyMarkEntity;
import com.mannschaft.app.chart.entity.ChartCustomFieldEntity;
import com.mannschaft.app.chart.entity.ChartCustomValueEntity;
import com.mannschaft.app.chart.entity.ChartFormulaEntity;
import com.mannschaft.app.chart.entity.ChartPhotoEntity;
import com.mannschaft.app.chart.entity.ChartRecordEntity;
import com.mannschaft.app.chart.entity.ChartRecordTemplateEntity;
import com.mannschaft.app.chart.entity.ChartSectionSettingEntity;
import com.mannschaft.app.chart.repository.ChartBodyMarkRepository;
import com.mannschaft.app.chart.repository.ChartCustomFieldRepository;
import com.mannschaft.app.chart.repository.ChartCustomValueRepository;
import com.mannschaft.app.chart.repository.ChartFormulaRepository;
import com.mannschaft.app.chart.repository.ChartPhotoRepository;
import com.mannschaft.app.chart.repository.ChartRecordRepository;
import com.mannschaft.app.chart.repository.ChartRecordTemplateRepository;
import com.mannschaft.app.chart.repository.ChartSectionSettingRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * カルテレコードサービス。カルテのCRUD・コピー・共有・ピン留めを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChartRecordService {

    private final ChartRecordRepository recordRepository;
    private final ChartCustomValueRepository customValueRepository;
    private final ChartCustomFieldRepository customFieldRepository;
    private final ChartPhotoRepository photoRepository;
    private final ChartFormulaRepository formulaRepository;
    private final ChartBodyMarkRepository bodyMarkRepository;
    private final ChartSectionSettingRepository sectionSettingRepository;
    private final ChartRecordTemplateRepository recordTemplateRepository;
    private final ChartMapper chartMapper;
    private final ChartPhotoUrlProvider photoUrlProvider;
    private final NameResolverService nameResolverService;
    private final StorageService storageService;

    /**
     * カルテ一覧をフィルタ付きでページング取得する。
     */
    public Page<ChartRecordSummaryResponse> listCharts(Long teamId, Long customerUserId, Long staffUserId,
                                                        LocalDate visitDateFrom, LocalDate visitDateTo,
                                                        Boolean isSharedToCustomer, String keyword,
                                                        Pageable pageable) {
        Page<ChartRecordEntity> page;
        if (keyword != null && !keyword.isBlank()) {
            page = recordRepository.searchByKeyword(teamId, keyword, pageable);
        } else {
            page = recordRepository.findByFilters(teamId, customerUserId, staffUserId,
                    visitDateFrom, visitDateTo, isSharedToCustomer, pageable);
        }
        // ユーザーIDを収集してバッチ取得（N+1回避）
        List<Long> userIds = page.getContent().stream()
                .flatMap(e -> Stream.of(e.getCustomerUserId(), e.getStaffUserId()))
                .filter(id -> id != null)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, String> displayNames = nameResolverService.resolveUserDisplayNames(userIds);

        return page.map(entity -> chartMapper.toSummaryResponse(
                entity,
                displayNames.get(entity.getCustomerUserId()),
                displayNames.get(entity.getStaffUserId()),
                (int) photoRepository.countByChartRecordId(entity.getId())
        ));
    }

    /**
     * カルテ詳細を取得する。
     */
    public ChartRecordResponse getChart(Long teamId, Long chartId) {
        ChartRecordEntity entity = findChartOrThrow(teamId, chartId);
        return buildChartResponse(entity);
    }

    /**
     * カルテを作成する。
     */
    @Transactional
    public ChartRecordResponse createChart(Long teamId, CreateChartRecordRequest request) {
        // テンプレート適用
        String chiefComplaint = request.getChiefComplaint();
        String treatmentNote = request.getTreatmentNote();
        String allergyInfo = request.getAllergyInfo();

        if (request.getTemplateId() != null) {
            ChartRecordTemplateEntity template = recordTemplateRepository.findByIdAndTeamId(
                    request.getTemplateId(), teamId)
                    .orElseThrow(() -> new BusinessException(ChartErrorCode.RECORD_TEMPLATE_NOT_FOUND));
            if (chiefComplaint == null) chiefComplaint = template.getChiefComplaint();
            if (treatmentNote == null) treatmentNote = template.getTreatmentNote();
            if (allergyInfo == null) allergyInfo = template.getAllergyInfo();
        }

        ChartRecordEntity entity = ChartRecordEntity.builder()
                .teamId(teamId)
                .customerUserId(request.getCustomerUserId())
                .staffUserId(request.getStaffUserId())
                .visitDate(request.getVisitDate())
                .chiefComplaint(chiefComplaint)
                .treatmentNote(treatmentNote)
                .nextRecommendation(request.getNextRecommendation())
                .nextVisitRecommendedDate(request.getNextVisitRecommendedDate())
                .allergyInfo(allergyInfo)
                .isSharedToCustomer(request.getIsSharedToCustomer() != null ? request.getIsSharedToCustomer() : false)
                .build();

        ChartRecordEntity saved = recordRepository.save(entity);

        // カスタムフィールド値の保存
        if (request.getCustomFields() != null) {
            request.getCustomFields().forEach(cf -> {
                ChartCustomValueEntity value = ChartCustomValueEntity.builder()
                        .chartRecordId(saved.getId())
                        .fieldId(cf.getFieldId())
                        .value(cf.getValue())
                        .build();
                customValueRepository.save(value);
            });
        }

        log.info("カルテ作成: teamId={}, chartId={}, customerUserId={}", teamId, saved.getId(), request.getCustomerUserId());
        return buildChartResponse(saved);
    }

    /**
     * カルテを更新する。
     */
    @Transactional
    public ChartRecordResponse updateChart(Long teamId, Long chartId, UpdateChartRecordRequest request) {
        ChartRecordEntity entity = findChartOrThrow(teamId, chartId);

        entity.update(
                request.getChiefComplaint(),
                request.getTreatmentNote(),
                request.getNextRecommendation(),
                request.getNextVisitRecommendedDate(),
                request.getAllergyInfo(),
                request.getStaffUserId()
        );

        ChartRecordEntity saved = recordRepository.save(entity);

        // カスタムフィールド値の更新
        if (request.getCustomFields() != null) {
            customValueRepository.deleteByChartRecordId(chartId);
            request.getCustomFields().forEach(cf -> {
                ChartCustomValueEntity value = ChartCustomValueEntity.builder()
                        .chartRecordId(chartId)
                        .fieldId(cf.getFieldId())
                        .value(cf.getValue())
                        .build();
                customValueRepository.save(value);
            });
        }

        log.info("カルテ更新: chartId={}", chartId);
        return buildChartResponse(saved);
    }

    /**
     * カルテを論理削除する。
     */
    @Transactional
    public void deleteChart(Long teamId, Long chartId) {
        ChartRecordEntity entity = findChartOrThrow(teamId, chartId);
        entity.softDelete();
        recordRepository.save(entity);
        log.info("カルテ削除: chartId={}", chartId);
    }

    /**
     * 顧客共有設定を変更する。
     */
    @Transactional
    public ShareResponse updateShareStatus(Long teamId, Long chartId, boolean isSharedToCustomer) {
        ChartRecordEntity entity = findChartOrThrow(teamId, chartId);
        entity.updateShareStatus(isSharedToCustomer);
        ChartRecordEntity saved = recordRepository.save(entity);
        log.info("カルテ共有設定変更: chartId={}, isShared={}", chartId, isSharedToCustomer);
        return new ShareResponse(saved.getId(), saved.getIsSharedToCustomer());
    }

    /**
     * ピン留めを変更する。
     */
    @Transactional
    public PinResponse updatePinStatus(Long teamId, Long chartId, boolean isPinned) {
        ChartRecordEntity entity = findChartOrThrow(teamId, chartId);

        if (isPinned) {
            long pinnedCount = recordRepository.countByTeamIdAndCustomerUserIdAndIsPinnedTrue(
                    teamId, entity.getCustomerUserId());
            if (pinnedCount >= 5) {
                throw new BusinessException(ChartErrorCode.PIN_LIMIT_EXCEEDED);
            }
        }

        entity.updatePinStatus(isPinned);
        ChartRecordEntity saved = recordRepository.save(entity);
        log.info("カルテピン留め変更: chartId={}, isPinned={}", chartId, isPinned);
        return new PinResponse(saved.getId(), saved.getIsPinned());
    }

    /**
     * カルテをコピーする。
     */
    @Transactional
    public ChartRecordResponse copyChart(Long teamId, Long chartId, CopyChartRequest request) {
        ChartRecordEntity source = findChartOrThrow(teamId, chartId);

        LocalDate visitDate = request.getVisitDate() != null ? request.getVisitDate() : LocalDate.now();

        ChartRecordEntity copy = ChartRecordEntity.builder()
                .teamId(teamId)
                .customerUserId(source.getCustomerUserId())
                .staffUserId(request.getStaffUserId())
                .visitDate(visitDate)
                .chiefComplaint(source.getChiefComplaint())
                .allergyInfo(source.getAllergyInfo())
                .build();

        ChartRecordEntity saved = recordRepository.save(copy);

        // 薬剤レシピのコピー
        List<ChartFormulaEntity> sourceFormulas = formulaRepository.findByChartRecordIdOrderBySortOrder(chartId);
        sourceFormulas.forEach(f -> {
            ChartFormulaEntity formulaCopy = ChartFormulaEntity.builder()
                    .chartRecordId(saved.getId())
                    .productName(f.getProductName())
                    .ratio(f.getRatio())
                    .processingTimeMinutes(f.getProcessingTimeMinutes())
                    .temperature(f.getTemperature())
                    .patchTestDate(f.getPatchTestDate())
                    .patchTestResult(f.getPatchTestResult())
                    .note(f.getNote())
                    .sortOrder(f.getSortOrder())
                    .build();
            formulaRepository.save(formulaCopy);
        });

        // カスタムフィールド値のコピー
        List<ChartCustomValueEntity> sourceValues = customValueRepository.findByChartRecordId(chartId);
        sourceValues.forEach(v -> {
            ChartCustomValueEntity valueCopy = ChartCustomValueEntity.builder()
                    .chartRecordId(saved.getId())
                    .fieldId(v.getFieldId())
                    .value(v.getValue())
                    .build();
            customValueRepository.save(valueCopy);
        });

        log.info("カルテコピー: sourceId={}, newId={}", chartId, saved.getId());
        return buildChartResponse(saved);
    }

    /**
     * 特定顧客の全カルテ一覧を取得する。
     */
    public Page<ChartRecordSummaryResponse> listCustomerCharts(Long teamId, Long customerUserId, Pageable pageable) {
        Page<ChartRecordEntity> page = recordRepository.findByTeamIdAndCustomerUserIdOrderByIsPinnedDescVisitDateDesc(
                teamId, customerUserId, pageable);
        return page.map(entity -> chartMapper.toSummaryResponse(
                entity, null, null,
                (int) photoRepository.countByChartRecordId(entity.getId())
        ));
    }

    /**
     * 自分に共有されたカルテを取得する。
     */
    public Page<ChartRecordSummaryResponse> listMyCharts(Long userId, Long teamId, Pageable pageable) {
        Page<ChartRecordEntity> page;
        if (teamId != null) {
            page = recordRepository.findByCustomerUserIdAndTeamIdAndIsSharedToCustomerTrueOrderByVisitDateDesc(
                    userId, teamId, pageable);
        } else {
            page = recordRepository.findByCustomerUserIdAndIsSharedToCustomerTrueOrderByVisitDateDesc(
                    userId, pageable);
        }
        return page.map(entity -> chartMapper.toSummaryResponse(
                entity, null, null,
                (int) photoRepository.countByChartRecordId(entity.getId())
        ));
    }

    /**
     * PDF エクスポート用にカルテデータを取得する。
     * 実際の PDF 生成はコントローラー層で {@link PdfGeneratorService} を使って行う。
     */
    public ChartRecordResponse getChartForPdf(Long teamId, Long chartId) {
        return getChart(teamId, chartId);
    }

    /**
     * PDF埋め込み用に写真をS3からダウンロードしBase64変換したリストを返す。
     * 各要素は contentType, base64Data, originalFilename, photoType, note を持つMapである。
     */
    public List<Map<String, String>> getPhotoBase64List(Long chartId) {
        List<ChartPhotoEntity> photos = photoRepository.findByChartRecordIdOrderBySortOrder(chartId);
        return photos.stream()
                .map(photo -> {
                    try {
                        byte[] data = storageService.download(photo.getS3Key());
                        String base64 = Base64.getEncoder().encodeToString(data);
                        Map<String, String> map = new HashMap<>();
                        map.put("contentType", photo.getContentType());
                        map.put("base64Data", base64);
                        map.put("originalFilename", photo.getOriginalFilename());
                        map.put("photoType", photo.getPhotoType());
                        map.put("note", photo.getNote());
                        return map;
                    } catch (Exception e) {
                        log.warn("写真のダウンロードに失敗しました（PDF生成続行）: s3Key={}", photo.getS3Key(), e);
                        return null;
                    }
                })
                .filter(m -> m != null)
                .collect(Collectors.toList());
    }

    /**
     * カルテエンティティを取得する。存在しない場合は例外をスローする。
     */
    ChartRecordEntity findChartOrThrow(Long teamId, Long chartId) {
        return recordRepository.findByIdAndTeamId(chartId, teamId)
                .orElseThrow(() -> new BusinessException(ChartErrorCode.CHART_NOT_FOUND));
    }

    /**
     * カルテの全セクションデータを組み立てて完全なレスポンスを構築する。
     */
    private ChartRecordResponse buildChartResponse(ChartRecordEntity entity) {
        // セクション設定
        List<ChartSectionSettingEntity> sectionSettings = sectionSettingRepository.findByTeamId(entity.getTeamId());
        Map<String, Boolean> sectionsEnabled = new HashMap<>();
        sectionSettings.forEach(s -> sectionsEnabled.put(s.getSectionType(), s.getIsEnabled()));

        // カスタムフィールド値
        List<ChartCustomValueEntity> values = customValueRepository.findByChartRecordId(entity.getId());
        List<CustomFieldValueResponse> customFieldValues = values.stream()
                .map(v -> {
                    ChartCustomFieldEntity field = customFieldRepository.findById(v.getFieldId()).orElse(null);
                    if (field == null) return null;
                    return chartMapper.toCustomFieldValueResponse(v, field);
                })
                .filter(v -> v != null)
                .collect(Collectors.toList());

        // 写真
        List<ChartPhotoEntity> photos = photoRepository.findByChartRecordIdOrderBySortOrder(entity.getId());
        List<ChartPhotoResponse> photoResponses = photos.stream()
                .map(p -> chartMapper.toPhotoResponse(p,
                        photoUrlProvider.generateSignedUrl(p.getS3Key()),
                        photoUrlProvider.getExpiresAt()))
                .collect(Collectors.toList());

        // 薬剤レシピ
        List<ChartFormulaEntity> formulas = formulaRepository.findByChartRecordIdOrderBySortOrder(entity.getId());
        List<ChartFormulaResponse> formulaResponses = chartMapper.toFormulaResponseList(formulas);

        // 身体チャート
        List<ChartBodyMarkEntity> bodyMarks = bodyMarkRepository.findByChartRecordId(entity.getId());
        List<ChartBodyMarkResponse> bodyMarkResponses = chartMapper.toBodyMarkResponseList(bodyMarks);

        return chartMapper.toChartRecordResponse(
                entity,
                nameResolverService.resolveUserDisplayName(entity.getCustomerUserId()),
                nameResolverService.resolveUserDisplayName(entity.getStaffUserId()),
                sectionsEnabled,
                customFieldValues,
                photoResponses,
                formulaResponses,
                bodyMarkResponses
        );
    }
}
