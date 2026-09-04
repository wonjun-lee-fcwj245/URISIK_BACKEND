package com.urisik.backend.domain.recipe.repository;

import com.urisik.backend.domain.recipe.entity.TransformedRecipe;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TransformedRecipeRepository extends JpaRepository<TransformedRecipe, Long> {

    Optional<TransformedRecipe> findById(Long transformedRecipeId);

    @Modifying
    @Query("UPDATE TransformedRecipe r SET r.reviewCount = r.reviewCount + 1 WHERE r.id = :id")
    int incrementReviewCount(@Param("id") Long id);

    @Modifying
    @Query("UPDATE TransformedRecipe r SET r.avgScore = ROUND(((r.avgScore * (r.reviewCount - 1)) + :newScore) / r.reviewCount, 1) WHERE r.id = :id")
    int updateAvgScore(@Param("id") Long id, @Param("newScore") int newScore);

    @Modifying
    @Query("UPDATE TransformedRecipe r SET r.wishCount = r.wishCount + 1 WHERE r.id = :id")
    int incrementWishCount(@Param("id") Long id);

    @Modifying
    @Query("UPDATE TransformedRecipe r SET r.wishCount = r.wishCount - 1 WHERE r.id = :id AND r.wishCount > 0")
    int decrementWishCount(@Param("id") Long id);

    @Query("""
    select tr
    from TransformedRecipe tr
    join fetch tr.baseRecipe
    where lower(tr.title) like lower(concat('%', :keyword, '%'))
""")
    List<TransformedRecipe> findByTitleLike(
            @Param("keyword") String keyword,
            Pageable pageable
    );

    List<TransformedRecipe> findByFamilyRoomId(Long familyRoomId);

    @Query("""
        select tr
        from TransformedRecipe tr
        join fetch tr.baseRecipe br
        where tr.familyRoomId = :familyRoomId
          and tr.id in :ids
    """)
    List<TransformedRecipe> findAllByFamilyRoomIdAndIdIn(
            @Param("familyRoomId") Long familyRoomId,
            @Param("ids") Collection<Long> ids
    );

    List<TransformedRecipe> findByFamilyRoomIdAndBaseRecipe_IdIn(
            Long familyRoomId,
            Collection<Long> recipeIds
    );

    interface TransformedCandidateRow {
        Long getId();
        Long getBaseRecipeId();
        String getIngredientsRaw();
    }

    @Query("""
        select
            tr.id as id,
            br.id as baseRecipeId,
            tr.ingredientsRaw as ingredientsRaw
        from TransformedRecipe tr
        join tr.baseRecipe br
        order by function('rand')
    """)
    List<TransformedCandidateRow> findRandomCandidateRows(Pageable pageable);

}
