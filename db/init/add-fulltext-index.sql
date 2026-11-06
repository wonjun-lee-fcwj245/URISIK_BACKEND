-- MySQL FULLTEXT(ngram) 인덱스 추가
-- ES와의 비교를 위해 ngram 파서로 한글 검색을 지원한다.
--
-- 사전 조건: ngram_token_size=2 (MySQL 기본값, 변경 필요 시 my.cnf에서 설정)
-- 실행: mysql -u root -p urisik < db/init/add-fulltext-index.sql

-- FULLTEXT 인덱스 추가 (이미 존재하면 무시)
ALTER TABLE recipe ADD FULLTEXT INDEX ft_title (title) WITH PARSER ngram;

-- 인덱스 확인
SHOW INDEX FROM recipe WHERE Key_name = 'ft_title';
