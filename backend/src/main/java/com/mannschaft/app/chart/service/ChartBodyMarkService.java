package com.mannschaft.app.chart.service;

import com.mannschaft.app.chart.BodyPart;
import com.mannschaft.app.chart.ChartErrorCode;
import com.mannschaft.app.chart.ChartMapper;
import com.mannschaft.app.chart.MarkType;
import com.mannschaft.app.chart.dto.BodyMarksResponse;
import com.mannschaft.app.chart.dto.ChartBodyMarkResponse;
import com.mannschaft.app.chart.dto.UpdateBodyMarksRequest;
import com.mannschaft.app.chart.entity.ChartBodyMarkEntity;
import com.mannschaft.app.chart.repository.ChartBodyMarkRepository;
import com.mannschaft.app.chart.repository.ChartRecordRepository;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 身体チャートサービス。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChartBodyMarkService {

    private static final int MAX_MARKS_PER_CHART = 50;

    private final ChartBodyMarkRepository bodyMarkRepository;
    private final ChartRecordRepository recordRepository;
    private final ChartMapper chartMapper;

    /**
     * 身体チャートのマークを一括更新する（全件置換）。
     */
    @Transactional
    public BodyMarksResponse updateBodyMarks(Long teamId, Long chartId, UpdateBodyMarksRequest request) {
        recordRepository.findByIdAndTeamId(chartId, teamId)
                .orElseThrow(() -> new BusinessException(ChartErrorCode.CHART_NOT_FOUND));

        if (request.getMarks().size() > MAX_MARKS_PER_CHART) {
            throw new BusinessException(ChartErrorCode.BODY_MARK_LIMIT_EXCEEDED);
        }

        // 既存マークを全削除
        bodyMarkRepository.deleteByChartRecordId(chartId);

        // 新しいマークを一括INSERT
        List<ChartBodyMarkEntity> entities = request.getMarks().stream()
                .map(mark -> {
                    // enum バリデーション
                    BodyPart.valueOf(mark.getBodyPart());
                    MarkType.valueOf(mark.getMarkType());

                    return ChartBodyMarkEntity.builder()
                            .chartRecordId(chartId)
                            .bodyPart(mark.getBodyPart())
                            .xPosition(mark.getXPosition())
                            .yPosition(mark.getYPosition())
                            .markType(mark.getMarkType())
                            .severity(mark.getSeverity())
                            .note(mark.getNote())
                            .build();
                })
                .toList();

        List<ChartBodyMarkEntity> saved = bodyMarkRepository.saveAll(entities);
        List<ChartBodyMarkResponse> responses = chartMapper.toBodyMarkResponseList(saved);

        log.info("身体チャート更新: chartId={}, marksCount={}", chartId, saved.size());
        return new BodyMarksResponse(chartId, saved.size(), responses);
    }
}
