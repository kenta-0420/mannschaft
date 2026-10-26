CREATE TABLE test_ym (
    id BIGINT NOT NULL AUTO_INCREMENT,
    `year_month` CHAR(7) NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB;
DROP TABLE test_ym;
SELECT 'OK' AS result;
