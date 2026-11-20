package com.urisik.backend.domain.recommendation.service;

import com.urisik.backend.domain.recommendation.candidate.HighScoreRecommendationRecipeCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 추천 정렬 비교기의 방향을 고정한다.
 * Comparator.comparingX(a).reversed().thenComparingY(b).reversed()... 처럼 키마다 reversed()를 부르면
 * 직전까지의 전체 비교기가 뒤집혀 2순위 키가 오름차순이 되는 버그가 있었다.
 */
class RecommendationOrderTest {

    private static HighScoreRecommendationRecipeCandidate c(long id, double avg, int review, int wish) {
        return new HighScoreRecommendationRecipeCandidate() {
            public Long getId() { return id; }
            public String getTitle() { return "t" + id; }
            public String getImageUrl() { return null; }
            public String getCategory() { return null; }
            public double getAvgScore() { return avg; }
            public List<String> getIngredients() { return List.of(); }
            public String getDescription() { return null; }
            public int getReviewCount() { return review; }
            public int getWishCount() { return wish; }
        };
    }

    private static List<Long> ids(List<HighScoreRecommendationRecipeCandidate> list) {
        return list.stream().map(HighScoreRecommendationRecipeCandidate::getId).toList();
    }

    @Test
    @DisplayName("고득점 추천: 평균 별점 내림차순, 같으면 리뷰 수 내림차순, 같으면 찜 수 내림차순")
    void highScoreOrder() {
        List<HighScoreRecommendationRecipeCandidate> list = new ArrayList<>(List.of(
                c(1, 4.5, 10, 1),
                c(2, 4.5, 30, 0),   // 평균 같음 → 리뷰 수 많은 쪽이 먼저
                c(3, 4.0, 99, 99),  // 평균이 낮으면 리뷰·찜이 많아도 뒤
                c(4, 4.5, 30, 5)    // 평균·리뷰 수 같음 → 찜 많은 쪽이 먼저
        ));
        list.sort(HighScoreRecommendationService.BY_SCORE_REVIEW_WISH_DESC);
        assertThat(ids(list)).containsExactly(4L, 2L, 1L, 3L);
    }

    @Test
    @DisplayName("안전 고득점 추천도 같은 순서를 쓴다")
    void safeHighScoreOrder() {
        List<HighScoreRecommendationRecipeCandidate> list = new ArrayList<>(List.of(
                c(1, 3.0, 5, 0), c(2, 3.0, 50, 0), c(3, 4.8, 1, 0)));
        list.sort(SafeHighScoreRecommendationService.BY_SCORE_REVIEW_WISH_DESC);
        assertThat(ids(list)).containsExactly(3L, 2L, 1L);
    }

    @Test
    @DisplayName("찜 추천: 찜 수 내림차순, 같으면 평균 별점 내림차순, 같으면 리뷰 수 내림차순")
    void wishOrder() {
        List<HighScoreRecommendationRecipeCandidate> list = new ArrayList<>(List.of(
                c(1, 3.0, 10, 5),
                c(2, 4.5, 1, 5),    // 찜 수 같음 → 평균 높은 쪽이 먼저 (버그일 때는 낮은 쪽이 먼저였다)
                c(3, 1.0, 1, 9),
                c(4, 4.5, 8, 5)     // 찜·평균 같음 → 리뷰 수 많은 쪽이 먼저
        ));
        list.sort(WishRecommendationService.BY_WISH_SCORE_REVIEW_DESC);
        assertThat(ids(list)).containsExactly(3L, 4L, 2L, 1L);
    }
}
