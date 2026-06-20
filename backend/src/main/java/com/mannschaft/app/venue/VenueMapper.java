package com.mannschaft.app.venue;

import com.mannschaft.app.venue.dto.RegisterVenueRequest;
import com.mannschaft.app.venue.dto.VenueResponse;
import com.mannschaft.app.venue.dto.VenueSuggestionResponse;
import com.mannschaft.app.venue.entity.VenueEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * 施設機能のMapStructマッパー。
 */
@Mapper(componentModel = "spring")
public interface VenueMapper {

    VenueResponse toResponse(VenueEntity entity);

    @Mapping(target = "venueId", source = "id")
    @Mapping(target = "source", constant = "DB")
    VenueSuggestionResponse toSuggestion(VenueEntity entity);

    @Mapping(target = "usageCount", constant = "0")
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    VenueEntity toEntity(RegisterVenueRequest request);
}
