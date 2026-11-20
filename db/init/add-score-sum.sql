-- 평균 별점 반올림 누적 오차 해결용 score_sum(점수 합계) 컬럼 추가
-- 평균은 ROUND(score_sum / review_count, 1)로 계산되므로 매번 반올림한 평균을 다시 쓰지 않는다.
-- 실행 전에 백업하고, ddl-auto=update 환경에서는 앱 기동 시 컬럼이 자동 추가되므로 아래 UPDATE만 실행한다.

ALTER TABLE recipe ADD COLUMN score_sum BIGINT NOT NULL DEFAULT 0;
ALTER TABLE transformed_recipe ADD COLUMN score_sum BIGINT NOT NULL DEFAULT 0;

-- 기존 평균·개수로 복원한다(현재 화면에 보이는 평균을 유지한다).
UPDATE recipe SET score_sum = ROUND(avg_score * review_count);
UPDATE transformed_recipe SET score_sum = ROUND(avg_score * review_count);

-- 리뷰 테이블이 기준이면 아래로 정확히 다시 계산할 수 있다(리뷰 수정·삭제가 카운터에 반영되지 않았다면 이쪽이 정확하다).
-- UPDATE recipe r SET r.score_sum = COALESCE((SELECT SUM(rv.score) FROM recipe_review rv WHERE rv.recipe_id = r.id), 0),
--     r.review_count = (SELECT COUNT(*) FROM recipe_review rv WHERE rv.recipe_id = r.id);
-- UPDATE recipe r SET r.avg_score = CASE WHEN r.review_count = 0 THEN 0 ELSE ROUND(r.score_sum / r.review_count, 1) END;
