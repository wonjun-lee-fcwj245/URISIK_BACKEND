package com.urisik.backend.domain.recipe.document;

import com.urisik.backend.domain.recipe.entity.Recipe;
import com.urisik.backend.domain.recipe.entity.RecipeExternalMetadata;
import com.urisik.backend.domain.recipe.entity.TransformedRecipe;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.Setting;

@Document(indexName = "recipes")
@Setting(settingPath = "elasticsearch/recipes-settings.json")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecipeDocument {

    @Id
    private String id;

    @Field(type = FieldType.Keyword)
    private String type;

    @Field(type = FieldType.Long)
    private Long recipeId;

    @Field(type = FieldType.Text, analyzer = "nori")
    private String title;

    @Field(type = FieldType.Text, analyzer = "nori")
    private String ingredientsRaw;

    @Field(type = FieldType.Keyword)
    private String category;

    @Field(type = FieldType.Double)
    private double avgScore;

    @Field(type = FieldType.Integer)
    private int reviewCount;

    @Field(type = FieldType.Integer)
    private int wishCount;

    @Field(type = FieldType.Keyword)
    private String imageUrl;

    @Field(type = FieldType.Long)
    private Long familyRoomId;

    @Field(type = FieldType.Long)
    private Long baseRecipeId;

    public static RecipeDocument from(Recipe recipe, RecipeExternalMetadata metadata) {
        return RecipeDocument.builder()
                .id("RECIPE-" + recipe.getId())
                .type("RECIPE")
                .recipeId(recipe.getId())
                .title(recipe.getTitle())
                .ingredientsRaw(recipe.getIngredientsRaw())
                .category(metadata != null ? metadata.getCategory() : null)
                .avgScore(recipe.getAvgScore())
                .reviewCount(recipe.getReviewCount())
                .wishCount(recipe.getWishCount())
                .imageUrl(metadata != null ? metadata.getThumbnailImageUrl() : null)
                .build();
    }

    public static RecipeDocument from(TransformedRecipe tr, RecipeExternalMetadata metadata) {
        return RecipeDocument.builder()
                .id("TRANSFORMED-" + tr.getId())
                .type("TRANSFORMED")
                .recipeId(tr.getId())
                .title(tr.getTitle())
                .ingredientsRaw(tr.getIngredientsRaw())
                .category(metadata != null ? metadata.getCategory() : null)
                .avgScore(tr.getAvgScore())
                .reviewCount(tr.getReviewCount())
                .wishCount(tr.getWishCount())
                .imageUrl(tr.getImageUrl())
                .familyRoomId(tr.getFamilyRoomId())
                .baseRecipeId(tr.getBaseRecipe().getId())
                .build();
    }
}
