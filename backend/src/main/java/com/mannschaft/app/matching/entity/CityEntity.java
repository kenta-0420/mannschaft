package com.mannschaft.app.matching.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 市区町村マスタエンティティ。
 */
@Entity
@Table(name = "cities")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class CityEntity {

    @Id
    @Column(length = 5)
    private String code;

    @Column(nullable = false, length = 2)
    private String prefectureCode;

    @Column(nullable = false, length = 20)
    private String name;
}
