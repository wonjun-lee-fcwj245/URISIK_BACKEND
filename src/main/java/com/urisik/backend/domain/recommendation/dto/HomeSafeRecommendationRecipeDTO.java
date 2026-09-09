package com.urisik.backend.domain.recommendation.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class HomeSafeRecommendationRecipeDTO {

    private String id;
    private String title;
    private String imageUrl;
    private String description;
    private int wishCount;

    private String category;
    private double avgScore;
    private int reviewCount;
    private boolean transformed;
    private boolean safe;

}

