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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-22T15:42:11+0900",
    comments = "version: 1.6.3, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class ResidentMapperImpl implements ResidentMapper {

    @Override
    public DwellingUnitResponse toDwellingUnitResponse(DwellingUnitEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        String scopeType = null;
        Long teamId = null;
        Long organizationId = null;
        String unitNumber = null;
        Short floor = null;
        BigDecimal areaSqm = null;
        String layout = null;
        String unitType = null;
        String notes = null;
        Short residentCount = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        scopeType = entity.getScopeType();
        teamId = entity.getTeamId();
        organizationId = entity.getOrganizationId();
        unitNumber = entity.getUnitNumber();
        floor = entity.getFloor();
        areaSqm = entity.getAreaSqm();
        layout = entity.getLayout();
        unitType = entity.getUnitType();
        notes = entity.getNotes();
        residentCount = entity.getResidentCount();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        DwellingUnitResponse dwellingUnitResponse = new DwellingUnitResponse( id, scopeType, teamId, organizationId, unitNumber, floor, areaSqm, layout, unitType, notes, residentCount, createdAt, updatedAt );

        return dwellingUnitResponse;
    }

    @Override
    public List<DwellingUnitResponse> toDwellingUnitResponseList(List<DwellingUnitEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<DwellingUnitResponse> list = new ArrayList<DwellingUnitResponse>( entities.size() );
        for ( DwellingUnitEntity dwellingUnitEntity : entities ) {
            list.add( toDwellingUnitResponse( dwellingUnitEntity ) );
        }

        return list;
    }

    @Override
    public ResidentResponse toResidentResponse(ResidentRegistryEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long dwellingUnitId = null;
        Long userId = null;
        String residentType = null;
        String lastName = null;
        String firstName = null;
        String lastNameKana = null;
        String firstNameKana = null;
        String phone = null;
        String email = null;
        String emergencyContact = null;
        LocalDate moveInDate = null;
        LocalDate moveOutDate = null;
        BigDecimal ownershipRatio = null;
        Boolean isPrimary = null;
        Boolean isVerified = null;
        Long verifiedBy = null;
        LocalDateTime verifiedAt = null;
        String notes = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        dwellingUnitId = entity.getDwellingUnitId();
        userId = entity.getUserId();
        residentType = entity.getResidentType();
        lastName = entity.getLastName();
        firstName = entity.getFirstName();
        lastNameKana = entity.getLastNameKana();
        firstNameKana = entity.getFirstNameKana();
        phone = entity.getPhone();
        email = entity.getEmail();
        emergencyContact = entity.getEmergencyContact();
        moveInDate = entity.getMoveInDate();
        moveOutDate = entity.getMoveOutDate();
        ownershipRatio = entity.getOwnershipRatio();
        isPrimary = entity.getIsPrimary();
        isVerified = entity.getIsVerified();
        verifiedBy = entity.getVerifiedBy();
        verifiedAt = entity.getVerifiedAt();
        notes = entity.getNotes();
        createdAt = entity.getCreatedAt();

        ResidentResponse residentResponse = new ResidentResponse( id, dwellingUnitId, userId, residentType, lastName, firstName, lastNameKana, firstNameKana, phone, email, emergencyContact, moveInDate, moveOutDate, ownershipRatio, isPrimary, isVerified, verifiedBy, verifiedAt, notes, createdAt );

        return residentResponse;
    }

    @Override
    public List<ResidentResponse> toResidentResponseList(List<ResidentRegistryEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<ResidentResponse> list = new ArrayList<ResidentResponse>( entities.size() );
        for ( ResidentRegistryEntity residentRegistryEntity : entities ) {
            list.add( toResidentResponse( residentRegistryEntity ) );
        }

        return list;
    }

    @Override
    public ResidentDocumentResponse toDocumentResponse(ResidentDocumentEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long residentId = null;
        String documentType = null;
        String fileName = null;
        String s3Key = null;
        Integer fileSize = null;
        String contentType = null;
        Long uploadedBy = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        residentId = entity.getResidentId();
        documentType = entity.getDocumentType();
        fileName = entity.getFileName();
        s3Key = entity.getS3Key();
        fileSize = entity.getFileSize();
        contentType = entity.getContentType();
        uploadedBy = entity.getUploadedBy();
        createdAt = entity.getCreatedAt();

        ResidentDocumentResponse residentDocumentResponse = new ResidentDocumentResponse( id, residentId, documentType, fileName, s3Key, fileSize, contentType, uploadedBy, createdAt );

        return residentDocumentResponse;
    }

    @Override
    public List<ResidentDocumentResponse> toDocumentResponseList(List<ResidentDocumentEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<ResidentDocumentResponse> list = new ArrayList<ResidentDocumentResponse>( entities.size() );
        for ( ResidentDocumentEntity residentDocumentEntity : entities ) {
            list.add( toDocumentResponse( residentDocumentEntity ) );
        }

        return list;
    }

    @Override
    public PropertyListingResponse toPropertyListingResponse(PropertyListingEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long dwellingUnitId = null;
        Long listedBy = null;
        String listingType = null;
        String title = null;
        String description = null;
        BigDecimal askingPrice = null;
        BigDecimal monthlyRent = null;
        String status = null;
        LocalDateTime expiresAt = null;
        String imageUrls = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        dwellingUnitId = entity.getDwellingUnitId();
        listedBy = entity.getListedBy();
        listingType = entity.getListingType();
        title = entity.getTitle();
        description = entity.getDescription();
        askingPrice = entity.getAskingPrice();
        monthlyRent = entity.getMonthlyRent();
        status = entity.getStatus();
        expiresAt = entity.getExpiresAt();
        imageUrls = entity.getImageUrls();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        PropertyListingResponse propertyListingResponse = new PropertyListingResponse( id, dwellingUnitId, listedBy, listingType, title, description, askingPrice, monthlyRent, status, expiresAt, imageUrls, createdAt, updatedAt );

        return propertyListingResponse;
    }

    @Override
    public List<PropertyListingResponse> toPropertyListingResponseList(List<PropertyListingEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<PropertyListingResponse> list = new ArrayList<PropertyListingResponse>( entities.size() );
        for ( PropertyListingEntity propertyListingEntity : entities ) {
            list.add( toPropertyListingResponse( propertyListingEntity ) );
        }

        return list;
    }

    @Override
    public InquiryResponse toInquiryResponse(PropertyListingInquiryEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long listingId = null;
        Long userId = null;
        String message = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        listingId = entity.getListingId();
        userId = entity.getUserId();
        message = entity.getMessage();
        createdAt = entity.getCreatedAt();

        InquiryResponse inquiryResponse = new InquiryResponse( id, listingId, userId, message, createdAt );

        return inquiryResponse;
    }

    @Override
    public List<InquiryResponse> toInquiryResponseList(List<PropertyListingInquiryEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<InquiryResponse> list = new ArrayList<InquiryResponse>( entities.size() );
        for ( PropertyListingInquiryEntity propertyListingInquiryEntity : entities ) {
            list.add( toInquiryResponse( propertyListingInquiryEntity ) );
        }

        return list;
    }
}
