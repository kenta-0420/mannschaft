-- F08.1: 都道府県マスタ（JIS X 0401）
CREATE TABLE prefectures (
    code    CHAR(2)     NOT NULL,
    name    VARCHAR(10) NOT NULL,
    PRIMARY KEY (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- F08.1: 市区町村マスタ（JIS X 0402）
CREATE TABLE cities (
    code            CHAR(5)     NOT NULL,
    prefecture_code CHAR(2)     NOT NULL,
    name            VARCHAR(20) NOT NULL,
    PRIMARY KEY (code),
    CONSTRAINT fk_cities_prefecture FOREIGN KEY (prefecture_code) REFERENCES prefectures(code),
    INDEX idx_cities_prefecture (prefecture_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
