package com.urisik.backend.domain.recommendation.repository;

import com.urisik.backend.domain.recipe.entity.TransformedRecipe;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface HomeTransformedRecommendationRecipeRepository
        extends JpaRepository<TransformedRecipe, Long> {

    @Query("""
        select tr
        from TransformedRecipe tr
        join fetch tr.baseRecipe br
        left join fetch br.recipeExternalMetadata
        order by tr.wishCount desc
    """)
    List<TransformedRecipe> findTopForHome(Pageable pageable);

    @Query("""
    select tr
    from TransformedRecipe tr
    join fetch tr.baseRecipe br
    left join fetch br.recipeExternalMetadata rem
    order by
        br.avgScore desc,
        br.reviewCount desc,
        tr.wishCount desc
""")
    List<TransformedRecipe> findTopByScore(Pageable pageable);


    @Query("""
    select tr
    from TransformedRecipe tr
    join fetch tr.baseRecipe br
    join fetch br.recipeExternalMetadata rem
    where rem.category in :categories
    order by
        br.avgScore desc,
        br.reviewCount desc,
        tr.wishCount desc
""")
    List<TransformedRecipe> findTopByCategories(
            @Param("categories") List<String> categories,
            Pageable pageable
    );

    @Query("""
        select tr
        from TransformedRecipe tr
        join fetch tr.baseRecipe br
        left join fetch br.recipeExternalMetadata rem
        order by
            tr.wishCount desc,
            br.avgScore desc,
            br.reviewCount desc
    """)
    List<TransformedRecipe> findTopByWish(Pageable pageable);

    @Query("""
        select tr
        from TransformedRecipe tr
        join fetch tr.baseRecipe br
        join fetch br.recipeExternalMetadata rem
        where rem.category in :categories
        order by
            tr.wishCount desc,
            br.avgScore desc,
            br.reviewCount desc
    """)
    List<TransformedRecipe> findTopByWishAndCategories(
            @Param("categories") List<String> categories,
            Pageable pageable
    );

}
