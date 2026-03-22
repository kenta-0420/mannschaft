package com.mannschaft.app.search;

import com.mannschaft.app.search.dto.SavedQueryResponse;
import com.mannschaft.app.search.dto.SearchHistoryResponse;
import com.mannschaft.app.search.entity.SearchHistoryEntity;
import com.mannschaft.app.search.entity.SearchSavedQueryEntity;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-22T15:42:09+0900",
    comments = "version: 1.6.3, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class SearchMapperImpl implements SearchMapper {

    @Override
    public SearchHistoryResponse toHistoryResponse(SearchHistoryEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        String query = null;
        LocalDateTime searchedAt = null;

        id = entity.getId();
        query = entity.getQuery();
        searchedAt = entity.getSearchedAt();

        SearchHistoryResponse searchHistoryResponse = new SearchHistoryResponse( id, query, searchedAt );

        return searchHistoryResponse;
    }

    @Override
    public List<SearchHistoryResponse> toHistoryResponseList(List<SearchHistoryEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<SearchHistoryResponse> list = new ArrayList<SearchHistoryResponse>( entities.size() );
        for ( SearchHistoryEntity searchHistoryEntity : entities ) {
            list.add( toHistoryResponse( searchHistoryEntity ) );
        }

        return list;
    }

    @Override
    public SavedQueryResponse toSavedQueryResponse(SearchSavedQueryEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        String name = null;
        String queryParams = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        name = entity.getName();
        queryParams = entity.getQueryParams();
        createdAt = entity.getCreatedAt();

        SavedQueryResponse savedQueryResponse = new SavedQueryResponse( id, name, queryParams, createdAt );

        return savedQueryResponse;
    }

    @Override
    public List<SavedQueryResponse> toSavedQueryResponseList(List<SearchSavedQueryEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<SavedQueryResponse> list = new ArrayList<SavedQueryResponse>( entities.size() );
        for ( SearchSavedQueryEntity searchSavedQueryEntity : entities ) {
            list.add( toSavedQueryResponse( searchSavedQueryEntity ) );
        }

        return list;
    }
}
