package com.urisik.backend.domain.review.entity;

import com.urisik.backend.domain.member.entity.FamilyMemberProfile;
import com.urisik.backend.domain.recipe.entity.Recipe;
import com.urisik.backend.global.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "recipe_review", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"family_member_id", "recipe_id"})
})
@Builder
@AllArgsConstructor
public class Review extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 별점
    @Column(name = "score", nullable = false)
    private Integer score;   // 1 ~ 5 범위의 정수

    // 취향
    @Column(name = "isFavorite")
    private boolean isFavorite;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "family_member_id")
    private FamilyMemberProfile familyMemberProfile;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipe_id")
    private Recipe recipe;

}
