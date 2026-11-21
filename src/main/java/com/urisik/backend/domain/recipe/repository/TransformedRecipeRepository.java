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

    @Modifying(clearAutomatically = true)
    @Query("UPDATE TransformedRecipe r SET r.avgScore = ROUND(((r.avgScore * (r.reviewCount - 1)) + :newScore) / r.reviewCount, 1) WHERE r.id = :id")
    int updateAvgScore(@Param("id") Long id, @Param("newScore") int newScore);

    @Modifying(clearAutomatically = true)
    // MySQL은 SET 절을 왼쪽부터 평가해 앞에서 바꾼 값을 뒤 식에서 사용한다.
    // reviewCount를 먼저 올리면 평균 계산에 증가된 값이 쓰여 평균이 작게(첫 리뷰는 절반) 저장되므로 avgScore를 먼저 계산한다.
    // SET은 왼쪽부터 평가된다: avgScore는 갱신 전 scoreSum·reviewCount로 계산하고, 그 뒤에 두 값을 갱신한다
    @Query("UPDATE TransformedRecipe r SET r.avgScore = ROUND((r.scoreSum + :newScore) / (r.reviewCount + 1), 1), r.scoreSum = r.scoreSum + :newScore, r.reviewCount = r.reviewCount + 1 WHERE r.id = :id")
    int incrementReviewCountAndUpdateAvgScore(@Param("id") Long id, @Param("newScore") int newScore);

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
