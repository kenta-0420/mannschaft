package com.mannschaft.app.resident.mapper;

import com.mannschaft.app.resident.dto.DwellingUnitResponse;
import com.mannschaft.app.resident.dto.InquiryResponse;
import com.mannschaft.app.resident.dto.PropertyListingResponse;
import com.mannschaft.app.resident.dto.ResidentDocumentResponse;
import com.mannschaft.app.resident.dto.ResidentResponse;
import com.mannschaft.app.resident.entity.DwellingUnitEntity;
import com.mannschaft.app.resident.entity.PropertyListingEntity;
import com.mannschaft.app.resident.entity.PropertyListingInquiryEntity;
import com.mannschaft.app.resident.entity.ResidentDocumentEntity;
import com.mannschaft.app.resident.entity.ResidentRegistryEntity;
import org.mapstruct.Mapper;

import java.util.List;

/**
 * 住民台帳の Entity → DTO 変換マッパー。
 */
@Mapper(componentModel = "spring")
public interface ResidentMapper {

    DwellingUnitResponse toDwellingUnitResponse(DwellingUnitEntity entity);

    List<DwellingUnitResponse> toDwellingUnitResponseList(List<DwellingUnitEntity> entities);

    ResidentResponse toResidentResponse(ResidentRegistryEntity entity);

    List<ResidentResponse> toResidentResponseList(List<ResidentRegistryEntity> entities);

    ResidentDocumentResponse toDocumentResponse(ResidentDocumentEntity entity);

    List<ResidentDocumentResponse> toDocumentResponseList(List<ResidentDocumentEntity> entities);

    PropertyListingResponse toPropertyListingResponse(PropertyListingEntity entity);

    List<PropertyListingResponse> toPropertyListingResponseList(List<PropertyListingEntity> entities);

    InquiryResponse toInquiryResponse(PropertyListingInquiryEntity entity);

    List<InquiryResponse> toInquiryResponseList(List<PropertyListingInquiryEntity> entities);
}
