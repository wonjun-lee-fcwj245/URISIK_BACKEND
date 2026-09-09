package com.urisik.backend.domain.recommendation.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class HighScoreRecommendationDTO {

    private String id;
    private String title;
    private String imageUrl;
    private String category;
    private double avgScore;
    private boolean safe;
    private String description;

    private int reviewCount;
    private int wishCount;

    private boolean transformed;


}
