package com.urisik.backend.domain.recipe.repository;

import com.urisik.backend.domain.recipe.entity.Recipe;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.springframework.data.jpa.repository.EntityGraph;

import java.util.List;
import java.util.Optional;

public interface RecipeRepository extends JpaRepository<Recipe, Long> {

    @EntityGraph(attributePaths = "recipeExternalMetadata")
    @Query("SELECT r FROM Recipe r WHERE r.id IN :ids")
    List<Recipe> findAllByIdWithMetadata(@Param("ids") List<Long> ids);

    Optional<Recipe> findBySourceRef(String sourceRef);

    @Modifying
    @Query("UPDATE Recipe r SET r.reviewCount = r.reviewCount + 1 WHERE r.id = :id")
    int incrementReviewCount(@Param("id") Long id);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Recipe r SET r.avgScore = ROUND(((r.avgScore * (r.reviewCount - 1)) + :newScore) / r.reviewCount, 1) WHERE r.id = :id")
    int updateAvgScore(@Param("id") Long id, @Param("newScore") int newScore);

    @Modifying(clearAutomatically = true)
    // MySQL은 SET 절을 왼쪽부터 평가해 앞에서 바꾼 값을 뒤 식에서 사용한다.
    // reviewCount를 먼저 올리면 평균 계산에 증가된 값이 쓰여 평균이 작게(첫 리뷰는 절반) 저장되므로 avgScore를 먼저 계산한다.
    @Query("UPDATE Recipe r SET r.avgScore = ROUND(((r.avgScore * r.reviewCount) + :newScore) / (r.reviewCount + 1), 1), r.reviewCount = r.reviewCount + 1 WHERE r.id = :id")
    int incrementReviewCountAndUpdateAvgScore(@Param("id") Long id, @Param("newScore") int newScore);

    @Modifying
    @Query("UPDATE Recipe r SET r.wishCount = r.wishCount + 1 WHERE r.id = :id")
    int incrementWishCount(@Param("id") Long id);

    @Modifying
    @Query("UPDATE Recipe r SET r.wishCount = r.wishCount - 1 WHERE r.id = :id AND r.wishCount > 0")
    int decrementWishCount(@Param("id") Long id);

    List<Recipe> findByTitleContainingIgnoreCase(String keyword, Pageable pageable);

    @Query(value = "SELECT * FROM recipe WHERE MATCH(title) AGAINST(:keyword IN BOOLEAN MODE)", nativeQuery = true)
    List<Recipe> findByTitleFulltext(@Param("keyword") String keyword, Pageable pageable);

    /** Lightweight candidate row for meal plan generation (avoid loading full entity graph) */
    interface RecipeCandidateRow {
        Long getId();
        String getIngredientsRaw();
    }

    /** Fetch a limited random-ish slice of recipes for candidate building. */
    @Query("""
        select r.id as id, r.ingredientsRaw as ingredientsRaw
        from Recipe r
        order by function('rand')
    """)
    List<RecipeCandidateRow> findRandomCandidateRows(Pageable pageable);



    @Query("""
        select r
        from Recipe r
        where r.ingredientsRaw like concat('%', :keyword, '%')
          and (:excludeIds is null or r.id not in :excludeIds)
        order by function('rand')
    """)
    List<Recipe> findByIngredientLikeExcludeIdsRandom(
            @Param("keyword") String keyword,
            @Param("excludeIds") List<Long> excludeIds,
            Pageable pageable
    );

}