package com.mannschaft.app.jobmatching.mapper;

import com.mannschaft.app.jobmatching.controller.dto.JobApplicationResponse;
import com.mannschaft.app.jobmatching.controller.dto.JobContractResponse;
import com.mannschaft.app.jobmatching.controller.dto.JobPostingResponse;
import com.mannschaft.app.jobmatching.controller.dto.JobPostingSummaryResponse;
import com.mannschaft.app.jobmatching.entity.JobApplicationEntity;
import com.mannschaft.app.jobmatching.entity.JobContractEntity;
import com.mannschaft.app.jobmatching.entity.JobPostingEntity;
import org.mapstruct.Mapper;

import java.util.List;

/**
 * 求人マッチング機能の Entity → Response DTO 変換マッパー。
 *
 * <p>MapStruct を用いて Entity のフィールドを同名の Response record フィールドへ自動マッピングする。
 * 既存コード（例: {@code ChatMapper}）と同じく {@code componentModel = "spring"} で Spring Bean として登録する。</p>
 */
@Mapper(componentModel = "spring")
public interface JobMapper {

    // ------------------------------------------------------------
    // 求人投稿
    // ------------------------------------------------------------

    JobPostingResponse toPostingResponse(JobPostingEntity entity);

    List<JobPostingResponse> toPostingResponseList(List<JobPostingEntity> entities);

    JobPostingSummaryResponse toPostingSummaryResponse(JobPostingEntity entity);

    List<JobPostingSummaryResponse> toPostingSummaryResponseList(List<JobPostingEntity> entities);

    // ------------------------------------------------------------
    // 求人応募
    // ------------------------------------------------------------

    JobApplicationResponse toApplicationResponse(JobApplicationEntity entity);

    List<JobApplicationResponse> toApplicationResponseList(List<JobApplicationEntity> entities);

    // ------------------------------------------------------------
    // 求人契約
    // ------------------------------------------------------------

    JobContractResponse toContractResponse(JobContractEntity entity);

    List<JobContractResponse> toContractResponseList(List<JobContractEntity> entities);
}
