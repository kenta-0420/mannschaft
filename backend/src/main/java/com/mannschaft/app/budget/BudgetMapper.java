package com.mannschaft.app.budget;

import com.mannschaft.app.budget.dto.AllocationResponse;
import com.mannschaft.app.budget.dto.AttachmentResponse;
import com.mannschaft.app.budget.dto.BudgetConfigResponse;
import com.mannschaft.app.budget.dto.CategoryResponse;
import com.mannschaft.app.budget.dto.FiscalYearResponse;
import com.mannschaft.app.budget.dto.ReportResponse;
import com.mannschaft.app.budget.dto.TransactionResponse;
import com.mannschaft.app.budget.dto.UserSummary;
import com.mannschaft.app.budget.entity.BudgetAllocationEntity;
import com.mannschaft.app.budget.entity.BudgetTransactionAttachmentEntity;
import com.mannschaft.app.budget.entity.BudgetCategoryEntity;
import com.mannschaft.app.budget.entity.BudgetConfigEntity;
import com.mannschaft.app.budget.entity.BudgetFiscalYearEntity;
import com.mannschaft.app.budget.entity.BudgetReportEntity;
import com.mannschaft.app.budget.entity.BudgetTransactionEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * 予算・会計機能のMapStructマッパー。
 */
@Mapper(componentModel = "spring")
public interface BudgetMapper {

    /**
     * 会計年度エンティティからレスポンスに変換する。
     */
    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    @Mapping(target = "totalBudget", ignore = true)
    FiscalYearResponse toFiscalYearResponse(BudgetFiscalYearEntity entity);

    /**
     * カテゴリエンティティからレスポンスに変換する。
     */
    @Mapping(target = "categoryType", expression = "java(entity.getCategoryType().name())")
    @Mapping(target = "budgetAmount", ignore = true)
    CategoryResponse toCategoryResponse(BudgetCategoryEntity entity);

    /**
     * 配分エンティティからレスポンスに変換する。
     */
    @Mapping(target = "categoryName", ignore = true)
    @Mapping(target = "month", ignore = true)
    AllocationResponse toAllocationResponse(BudgetAllocationEntity entity);

    /**
     * 取引エンティティからレスポンスに変換する。
     */
    @Mapping(target = "transactionType", expression = "java(entity.getTransactionType().name())")
    @Mapping(target = "approvalStatus", expression = "java(entity.getApprovalStatus().name())")
    @Mapping(target = "paymentMethod", source = "paymentMethod")
    @Mapping(target = "description", source = "title")
    @Mapping(target = "categoryName", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "reference", source = "referenceNumber")
    TransactionResponse toTransactionResponse(BudgetTransactionEntity entity);

    /**
     * 添付ファイルエンティティからレスポンスに変換する。
     */
    @Mapping(target = "fileName", source = "originalFilename")
    @Mapping(target = "fileType", source = "mimeType")
    @Mapping(target = "s3Key", source = "fileKey")
    AttachmentResponse toAttachmentResponse(BudgetTransactionAttachmentEntity entity);

    /**
     * 報告書エンティティからレスポンスに変換する。
     */
    @Mapping(target = "reportType", expression = "java(entity.getReportType().name())")
    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    @Mapping(target = "title", ignore = true)
    @Mapping(target = "targetMonth", ignore = true)
    @Mapping(target = "s3Key", source = "fileKey")
    ReportResponse toReportResponse(BudgetReportEntity entity);

    /**
     * 設定エンティティからレスポンスに変換する。
     */
    @Mapping(target = "warningThresholdPercent", source = "budgetWarningThreshold")
    @Mapping(target = "criticalThresholdPercent", source = "budgetCriticalThreshold")
    @Mapping(target = "autoApproveEnabled", source = "autoRecordPayments")
    BudgetConfigResponse toConfigResponse(BudgetConfigEntity entity);

    /**
     * ユーザーID・表示名からUserSummaryを生成する。
     */
    default UserSummary toUserSummary(Long userId, String displayName) {
        if (userId == null) {
            return null;
        }
        return new UserSummary(userId, displayName);
    }
}
