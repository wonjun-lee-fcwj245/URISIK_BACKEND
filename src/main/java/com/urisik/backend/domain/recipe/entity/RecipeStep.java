package com.urisik.backend.domain.recipe.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "recipe_step", indexes = {
        @Index(name = "idx_rs_recipe_id_step_order", columnList = "recipe_id, step_order")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecipeStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipe_id", nullable = false)
    private Recipe recipe;

    private int stepOrder;

    @Column(columnDefinition = "TEXT")
    private String description;

    private String imageUrl;

    public RecipeStep(Recipe recipe, int stepOrder, String description, String imageUrl) {
        this.recipe = recipe;
        this.stepOrder = stepOrder;
        this.description = description;
        this.imageUrl = imageUrl;
    }
}


