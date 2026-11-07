package com.mannschaft.app.activity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * 活動記録更新リクエストDTO。
 */
@Getter
public class UpdateActivityRequest {

    @NotBlank
    @Size(max = 200)
    private final String title;

    @NotNull
    private final LocalDate activityDate;

    private final LocalTime activityTimeStart;

    private final LocalTime activityTimeEnd;

    @Size(max = 10000)
    private final String description;

    private final Map<String, Object> fieldValues;

    private final String visibility;

    private final List<Long> participantUserIds;

    private final List<Long> fileIds;

    private final LocalDate activityEndDate;

    private final Long templateId;

    @jakarta.validation.constraints.PositiveOrZero
    private final Long version;

    /** JSON経路の完全コンストラクタを明示し、互換コンストラクタとの曖昧性を防ぐ。 */
    @JsonCreator
    public UpdateActivityRequest(@JsonProperty("title") String title,
            @JsonProperty("activityDate") LocalDate activityDate,
            @JsonProperty("activityTimeStart") LocalTime activityTimeStart,
            @JsonProperty("activityTimeEnd") LocalTime activityTimeEnd,
            @JsonProperty("description") String description,
            @JsonProperty("fieldValues") Map<String, Object> fieldValues,
            @JsonProperty("visibility") String visibility,
            @JsonProperty("participantUserIds") List<Long> participantUserIds,
            @JsonProperty("fileIds") List<Long> fileIds,
            @JsonProperty("activityEndDate") LocalDate activityEndDate,
            @JsonProperty("templateId") Long templateId,
            @JsonProperty("version") Long version) {
        this.title = title;
        this.activityDate = activityDate;
        this.activityTimeStart = activityTimeStart;
        this.activityTimeEnd = activityTimeEnd;
        this.description = description;
        this.fieldValues = fieldValues;
        this.visibility = visibility;
        this.participantUserIds = participantUserIds;
        this.fileIds = fileIds;
        this.activityEndDate = activityEndDate;
        this.templateId = templateId;
        this.version = version;
    }

    /** 既存Java呼び出し元の互換コンストラクタ。 */
    public UpdateActivityRequest(String title, LocalDate activityDate, LocalTime activityTimeStart,
            LocalTime activityTimeEnd, String description, Map<String, Object> fieldValues,
            String visibility, List<Long> participantUserIds, List<Long> fileIds) {
        this(title, activityDate, activityTimeStart, activityTimeEnd, description, fieldValues, visibility,
                participantUserIds, fileIds, null, null, null);
    }
}
