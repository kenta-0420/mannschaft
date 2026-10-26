package com.mannschaft.app.tournament.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 連絡スペース公開トグルのリクエスト（F08.7.1 §5.1）。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ContactSpaceVisibilityRequest {

    /** 公開する場合 true（PUBLIC 閲覧可・read-only）。 */
    @NotNull
    private Boolean isPublic;
}
