package com.mannschaft.app.equipment;

import com.mannschaft.app.equipment.dto.AssignmentResponse;
import com.mannschaft.app.equipment.dto.EquipmentItemResponse;
import com.mannschaft.app.equipment.dto.QrCodeResponse;
import com.mannschaft.app.equipment.entity.EquipmentAssignmentEntity;
import com.mannschaft.app.equipment.entity.EquipmentItemEntity;
import java.math.BigDecimal;
import java.time.LocalDate;
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
public class EquipmentMapperImpl implements EquipmentMapper {

    @Override
    public EquipmentItemResponse toItemResponse(EquipmentItemEntity entity) {
        if ( entity == null ) {
            return null;
        }

        String imageUrl = null;
        Long id = null;
        Long teamId = null;
        Long organizationId = null;
        String name = null;
        String description = null;
        String category = null;
        Integer quantity = null;
        Integer assignedQuantity = null;
        Boolean isConsumable = null;
        String storageLocation = null;
        LocalDate purchaseDate = null;
        BigDecimal purchasePrice = null;
        String qrCode = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        imageUrl = entity.getS3Key();
        id = entity.getId();
        teamId = entity.getTeamId();
        organizationId = entity.getOrganizationId();
        name = entity.getName();
        description = entity.getDescription();
        category = entity.getCategory();
        quantity = entity.getQuantity();
        assignedQuantity = entity.getAssignedQuantity();
        isConsumable = entity.getIsConsumable();
        storageLocation = entity.getStorageLocation();
        purchaseDate = entity.getPurchaseDate();
        purchasePrice = entity.getPurchasePrice();
        qrCode = entity.getQrCode();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String status = entity.getStatus().name();
        Integer availableQuantity = entity.getAvailableQuantity();

        EquipmentItemResponse equipmentItemResponse = new EquipmentItemResponse( id, teamId, organizationId, name, description, category, quantity, status, availableQuantity, assignedQuantity, isConsumable, storageLocation, purchaseDate, purchasePrice, imageUrl, qrCode, createdAt, updatedAt );

        return equipmentItemResponse;
    }

    @Override
    public List<EquipmentItemResponse> toItemResponseList(List<EquipmentItemEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<EquipmentItemResponse> list = new ArrayList<EquipmentItemResponse>( entities.size() );
        for ( EquipmentItemEntity equipmentItemEntity : entities ) {
            list.add( toItemResponse( equipmentItemEntity ) );
        }

        return list;
    }

    @Override
    public AssignmentResponse toAssignmentResponse(EquipmentAssignmentEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long assignmentId = null;
        Long equipmentItemId = null;
        Long assignedToUserId = null;
        Integer quantity = null;
        LocalDateTime assignedAt = null;
        LocalDate expectedReturnAt = null;
        LocalDateTime returnedAt = null;
        String note = null;

        assignmentId = entity.getId();
        equipmentItemId = entity.getEquipmentItemId();
        assignedToUserId = entity.getAssignedToUserId();
        quantity = entity.getQuantity();
        assignedAt = entity.getAssignedAt();
        expectedReturnAt = entity.getExpectedReturnAt();
        returnedAt = entity.getReturnedAt();
        note = entity.getNote();

        String equipmentName = null;
        String assignedToDisplayName = null;

        AssignmentResponse assignmentResponse = new AssignmentResponse( assignmentId, equipmentItemId, equipmentName, assignedToUserId, assignedToDisplayName, quantity, assignedAt, expectedReturnAt, returnedAt, note );

        return assignmentResponse;
    }

    @Override
    public List<AssignmentResponse> toAssignmentResponseList(List<EquipmentAssignmentEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<AssignmentResponse> list = new ArrayList<AssignmentResponse>( entities.size() );
        for ( EquipmentAssignmentEntity equipmentAssignmentEntity : entities ) {
            list.add( toAssignmentResponse( equipmentAssignmentEntity ) );
        }

        return list;
    }

    @Override
    public QrCodeResponse toQrCodeResponse(EquipmentItemEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        String name = null;
        String category = null;
        String storageLocation = null;
        String qrCode = null;

        id = entity.getId();
        name = entity.getName();
        category = entity.getCategory();
        storageLocation = entity.getStorageLocation();
        qrCode = entity.getQrCode();

        String qrUrl = null;

        QrCodeResponse qrCodeResponse = new QrCodeResponse( id, name, category, storageLocation, qrCode, qrUrl );

        return qrCodeResponse;
    }

    @Override
    public List<QrCodeResponse> toQrCodeResponseList(List<EquipmentItemEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<QrCodeResponse> list = new ArrayList<QrCodeResponse>( entities.size() );
        for ( EquipmentItemEntity equipmentItemEntity : entities ) {
            list.add( toQrCodeResponse( equipmentItemEntity ) );
        }

        return list;
    }
}
