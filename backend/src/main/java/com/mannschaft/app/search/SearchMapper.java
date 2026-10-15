package com.mannschaft.app.search;

import com.mannschaft.app.search.dto.SavedQueryResponse;
import com.mannschaft.app.search.dto.SearchHistoryResponse;
import com.mannschaft.app.search.entity.SearchHistoryEntity;
import com.mannschaft.app.search.entity.SearchSavedQueryEntity;
import org.mapstruct.Mapper;

import java.util.List;

/**
 * 検索機能の Entity → DTO 変換マッパー。
 */
@Mapper(componentModel = "spring")
public interface SearchMapper {

    SearchHistoryResponse toHistoryResponse(SearchHistoryEntity entity);

    List<SearchHistoryResponse> toHistoryResponseList(List<SearchHistoryEntity> entities);

    SavedQueryResponse toSavedQueryResponse(SearchSavedQueryEntity entity);

    List<SavedQueryResponse> toSavedQueryResponseList(List<SearchSavedQueryEntity> entities);
}
