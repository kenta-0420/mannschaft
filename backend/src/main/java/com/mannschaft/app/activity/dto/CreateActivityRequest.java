package com.mannschaft.app.activity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 活動記録作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateActivityRequest {

    private final Long teamId;
    private final Long organizationId;
    private final Long templateId;

    @NotBlank
    @Size(max = 200)
    private final String title;

    @Size(max = 10000)
    private final String description;

    @NotNull
    private final LocalDate activityDate;

    @Size(max = 200)
    private final String location;

    private final String visibility;

    @Size(max = 500)
    private final String coverImageUrl;

    private final Long scheduleEventId;
    private final List<ParticipantInput> participants;
    private final List<CustomValueInput> customValues;

    /**
     * 参加者入力。
     */
    @Getter
    @RequiredArgsConstructor
    public static class ParticipantInput {
        private final Long userId;
        private final String memberNumber;
        private final String participationType;
        private final Integer minutesPlayed;
        private final String note;
        private final List<CustomValueInput> customValues;
    }

    /**
     * カスタムフィールド値入力。
     */
    @Getter
    @RequiredArgsConstructor
    public static class CustomValueInput {
        private final Long customFieldId;
        private final String value;
    }
}
