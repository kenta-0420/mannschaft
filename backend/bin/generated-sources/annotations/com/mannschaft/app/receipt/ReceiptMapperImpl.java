package com.mannschaft.app.receipt;

import com.mannschaft.app.receipt.dto.IssuerSettingsResponse;
import com.mannschaft.app.receipt.dto.PresetResponse;
import com.mannschaft.app.receipt.dto.QueueItemResponse;
import com.mannschaft.app.receipt.dto.ReceiptSummaryResponse;
import com.mannschaft.app.receipt.entity.ReceiptEntity;
import com.mannschaft.app.receipt.entity.ReceiptIssuerSettingsEntity;
import com.mannschaft.app.receipt.entity.ReceiptPresetEntity;
import com.mannschaft.app.receipt.entity.ReceiptQueueEntity;
import java.math.BigDecimal;
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
public class ReceiptMapperImpl implements ReceiptMapper {

    @Override
    public IssuerSettingsResponse toIssuerSettingsResponse(ReceiptIssuerSettingsEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long scopeId = null;
        String issuerName = null;
        String postalCode = null;
        String address = null;
        String phone = null;
        Boolean isQualifiedInvoicer = null;
        String invoiceRegistrationNumber = null;
        Long defaultSealUserId = null;
        String receiptNoteTemplate = null;
        String logoStorageKey = null;
        String customFooter = null;
        Integer nextReceiptNumber = null;
        String receiptNumberPrefix = null;
        Integer fiscalYearStartMonth = null;
        Boolean autoResetNumber = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        scopeId = entity.getScopeId();
        issuerName = entity.getIssuerName();
        postalCode = entity.getPostalCode();
        address = entity.getAddress();
        phone = entity.getPhone();
        isQualifiedInvoicer = entity.getIsQualifiedInvoicer();
        invoiceRegistrationNumber = entity.getInvoiceRegistrationNumber();
        defaultSealUserId = entity.getDefaultSealUserId();
        receiptNoteTemplate = entity.getReceiptNoteTemplate();
        logoStorageKey = entity.getLogoStorageKey();
        customFooter = entity.getCustomFooter();
        nextReceiptNumber = entity.getNextReceiptNumber();
        receiptNumberPrefix = entity.getReceiptNumberPrefix();
        fiscalYearStartMonth = entity.getFiscalYearStartMonth();
        autoResetNumber = entity.getAutoResetNumber();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String scopeType = entity.getScopeType().name();
        String defaultSealVariant = entity.getDefaultSealVariant() != null ? entity.getDefaultSealVariant().name() : null;

        IssuerSettingsResponse issuerSettingsResponse = new IssuerSettingsResponse( id, scopeType, scopeId, issuerName, postalCode, address, phone, isQualifiedInvoicer, invoiceRegistrationNumber, defaultSealUserId, defaultSealVariant, receiptNoteTemplate, logoStorageKey, customFooter, nextReceiptNumber, receiptNumberPrefix, fiscalYearStartMonth, autoResetNumber, createdAt, updatedAt );

        return issuerSettingsResponse;
    }

    @Override
    public PresetResponse toPresetResponse(ReceiptPresetEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long scopeId = null;
        String name = null;
        String description = null;
        BigDecimal amount = null;
        BigDecimal taxRate = null;
        String lineItemsJson = null;
        String paymentMethodLabel = null;
        Boolean sealStamp = null;
        Long createdBy = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        scopeId = entity.getScopeId();
        name = entity.getName();
        description = entity.getDescription();
        amount = entity.getAmount();
        taxRate = entity.getTaxRate();
        lineItemsJson = entity.getLineItemsJson();
        paymentMethodLabel = entity.getPaymentMethodLabel();
        sealStamp = entity.getSealStamp();
        createdBy = entity.getCreatedBy();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String scopeType = entity.getScopeType().name();

        PresetResponse presetResponse = new PresetResponse( id, scopeType, scopeId, name, description, amount, taxRate, lineItemsJson, paymentMethodLabel, sealStamp, createdBy, createdAt, updatedAt );

        return presetResponse;
    }

    @Override
    public List<PresetResponse> toPresetResponseList(List<ReceiptPresetEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<PresetResponse> list = new ArrayList<PresetResponse>( entities.size() );
        for ( ReceiptPresetEntity receiptPresetEntity : entities ) {
            list.add( toPresetResponse( receiptPresetEntity ) );
        }

        return list;
    }

    @Override
    public QueueItemResponse toQueueItemResponse(ReceiptQueueEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long scopeId = null;
        Long memberPaymentId = null;
        Long recipientUserId = null;
        String suggestedDescription = null;
        BigDecimal suggestedAmount = null;
        Long presetId = null;
        Long processedReceiptId = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        scopeId = entity.getScopeId();
        memberPaymentId = entity.getMemberPaymentId();
        recipientUserId = entity.getRecipientUserId();
        suggestedDescription = entity.getSuggestedDescription();
        suggestedAmount = entity.getSuggestedAmount();
        presetId = entity.getPresetId();
        processedReceiptId = entity.getProcessedReceiptId();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String scopeType = entity.getScopeType().name();
        String status = entity.getStatus().name();

        QueueItemResponse queueItemResponse = new QueueItemResponse( id, scopeType, scopeId, memberPaymentId, recipientUserId, suggestedDescription, suggestedAmount, presetId, status, processedReceiptId, createdAt, updatedAt );

        return queueItemResponse;
    }

    @Override
    public List<QueueItemResponse> toQueueItemResponseList(List<ReceiptQueueEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<QueueItemResponse> list = new ArrayList<QueueItemResponse>( entities.size() );
        for ( ReceiptQueueEntity receiptQueueEntity : entities ) {
            list.add( toQueueItemResponse( receiptQueueEntity ) );
        }

        return list;
    }

    @Override
    public ReceiptSummaryResponse toReceiptSummaryResponse(ReceiptEntity entity) {
        if ( entity == null ) {
            return null;
        }

        ReceiptSummaryResponse.ReceiptSummaryResponseBuilder receiptSummaryResponse = ReceiptSummaryResponse.builder();

        receiptSummaryResponse.amount( entity.getAmount() );
        receiptSummaryResponse.description( entity.getDescription() );
        receiptSummaryResponse.id( entity.getId() );
        receiptSummaryResponse.isQualifiedInvoice( entity.getIsQualifiedInvoice() );
        receiptSummaryResponse.issuedAt( entity.getIssuedAt() );
        receiptSummaryResponse.paymentDate( entity.getPaymentDate() );
        receiptSummaryResponse.receiptNumber( entity.getReceiptNumber() );
        receiptSummaryResponse.recipientName( entity.getRecipientName() );

        receiptSummaryResponse.isVoided( entity.isVoided() );
        receiptSummaryResponse.sealStamped( entity.getSealStampLogId() != null );

        return receiptSummaryResponse.build();
    }

    @Override
    public List<ReceiptSummaryResponse> toReceiptSummaryResponseList(List<ReceiptEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<ReceiptSummaryResponse> list = new ArrayList<ReceiptSummaryResponse>( entities.size() );
        for ( ReceiptEntity receiptEntity : entities ) {
            list.add( toReceiptSummaryResponse( receiptEntity ) );
        }

        return list;
    }
}
