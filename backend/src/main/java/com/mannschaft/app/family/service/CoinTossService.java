package com.mannschaft.app.family.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.family.CoinTossMode;
import com.mannschaft.app.family.FamilyErrorCode;
import com.mannschaft.app.family.dto.CoinTossRequest;
import com.mannschaft.app.family.dto.CoinTossResponse;
import com.mannschaft.app.family.entity.CoinTossResultEntity;
import com.mannschaft.app.family.repository.CoinTossResultRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CoinTossService {

    private static final int RATE_LIMIT_WINDOW_MINUTES = 1;
    private static final int RATE_LIMIT_MAX = 10;
    private static final List<String> COIN_OPTIONS = List.of("表", "裏");
    private static final int MIN_CUSTOM_OPTIONS = 2;
    private static final int MAX_CUSTOM_OPTIONS = 6;
    private static final int MAX_OPTION_LENGTH = 50;

    private final CoinTossResultRepository coinTossResultRepository;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public ApiResponse<CoinTossResponse> toss(Long teamId, Long userId, CoinTossRequest request) {
        long recentCount = coinTossResultRepository.countByTeamIdAndUserIdAndCreatedAtAfter(
                teamId, userId, LocalDateTime.now().minusMinutes(RATE_LIMIT_WINDOW_MINUTES));
        if (recentCount >= RATE_LIMIT_MAX) { throw new BusinessException(FamilyErrorCode.FAMILY_007); }

        CoinTossMode mode = "CUSTOM".equalsIgnoreCase(request.getMode()) ? CoinTossMode.CUSTOM : CoinTossMode.COIN;
        List<String> options;
        if (mode == CoinTossMode.COIN) { options = COIN_OPTIONS; }
        else { options = request.getOptions(); validateCustomOptions(options); }

        if (request.getQuestion() != null && request.getQuestion().length() > 200) {
            throw new BusinessException(FamilyErrorCode.FAMILY_023);
        }

        int resultIndex = secureRandom.nextInt(options.size());
        CoinTossResultEntity entity = CoinTossResultEntity.builder()
                .teamId(teamId).userId(userId).mode(mode).options(toJson(options))
                .resultIndex(resultIndex).question(request.getQuestion()).build();
        return ApiResponse.of(toResponse(coinTossResultRepository.save(entity), options));
    }

    @Transactional
    public ApiResponse<CoinTossResponse> share(Long teamId, Long id, Long userId) {
        CoinTossResultEntity entity = coinTossResultRepository.findById(id)
                .orElseThrow(() -> new BusinessException(FamilyErrorCode.FAMILY_008));
        if (!entity.getUserId().equals(userId)) { throw new BusinessException(FamilyErrorCode.FAMILY_010); }
        if (Boolean.TRUE.equals(entity.getSharedToChat())) { throw new BusinessException(FamilyErrorCode.FAMILY_009); }
        entity.markShared();
        return ApiResponse.of(toResponse(entity, fromJson(entity.getOptions())));
    }

    public CursorPagedResponse<CoinTossResponse> getHistory(Long teamId, Long cursor, int limit) {
        List<CoinTossResultEntity> results = coinTossResultRepository.findHistory(teamId, cursor, PageRequest.of(0, limit + 1));
        boolean hasNext = results.size() > limit;
        List<CoinTossResultEntity> page = hasNext ? results.subList(0, limit) : results;
        List<CoinTossResponse> responses = page.stream().map(e -> toResponse(e, fromJson(e.getOptions()))).toList();
        String nextCursor = hasNext ? String.valueOf(page.get(page.size() - 1).getId()) : null;
        return CursorPagedResponse.of(responses, new CursorPagedResponse.CursorMeta(nextCursor, hasNext, limit));
    }

    private void validateCustomOptions(List<String> options) {
        if (options == null || options.size() < MIN_CUSTOM_OPTIONS || options.size() > MAX_CUSTOM_OPTIONS) {
            throw new BusinessException(FamilyErrorCode.FAMILY_005);
        }
        for (String option : options) {
            if (option != null && option.length() > MAX_OPTION_LENGTH) { throw new BusinessException(FamilyErrorCode.FAMILY_006); }
        }
    }

    private CoinTossResponse toResponse(CoinTossResultEntity entity, List<String> options) {
        String result = (entity.getResultIndex() >= 0 && entity.getResultIndex() < options.size()) ? options.get(entity.getResultIndex()) : "";
        return new CoinTossResponse(entity.getId(), entity.getMode().name(), entity.getQuestion(),
                options, entity.getResultIndex(), result, Boolean.TRUE.equals(entity.getSharedToChat()), entity.getCreatedAt());
    }

    private String toJson(List<String> list) {
        try { return objectMapper.writeValueAsString(list); }
        catch (JsonProcessingException e) { throw new IllegalStateException("JSON変換に失敗しました", e); }
    }

    private List<String> fromJson(String json) {
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (JsonProcessingException e) { throw new IllegalStateException("JSONパースに失敗しました", e); }
    }
}
