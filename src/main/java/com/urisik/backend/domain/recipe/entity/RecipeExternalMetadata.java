package com.urisik.backend.domain.recipe.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "recipe_external_metadata", indexes = {
        @Index(name = "idx_rem_category", columnList = "category")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecipeExternalMetadata {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 1:1로 두고, recipe가 주인(FK를 metadata가 가지는 형태)
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipe_id", nullable = false, unique = true)
    private Recipe recipe;

    @Column(nullable = true)
    private String category;

    @Column(nullable = true)
    private String servingWeight;

    @Column(nullable = true)
    private Integer calorie;

    @Column(nullable = true)
    private Integer carbohydrate;

    @Column(nullable = true)
    private Integer protein;

    @Column(nullable = true)
    private Integer fat;

    @Column(nullable = true)
    private Integer sodium;

    @Column(nullable = true)
    private String imageSmallUrl;

    @Column(nullable = true)
    private String imageLargeUrl;

    public RecipeExternalMetadata(
            Recipe recipe,
            String category,
            String servingWeight,
            Integer calorie,
            Integer carbohydrate,
            Integer protein,
            Integer fat,
            Integer sodium,
            String imageSmallUrl,
            String imageLargeUrl
    ) {
        this.recipe = recipe;
        this.category = category;
        this.servingWeight = servingWeight;
        this.calorie = calorie;
        this.carbohydrate = carbohydrate;
        this.protein = protein;
        this.fat = fat;
        this.sodium = sodium;
        this.imageSmallUrl = imageSmallUrl;
        this.imageLargeUrl = imageLargeUrl;
    }

    public String getThumbnailImageUrl() {
        if (imageSmallUrl != null && !imageSmallUrl.isBlank()) {
            return imageSmallUrl;
        }
        return imageLargeUrl;
    }

}
