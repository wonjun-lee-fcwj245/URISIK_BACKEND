package com.urisik.backend.domain.member.entity;

import com.urisik.backend.domain.recipe.entity.TransformedRecipe;
import com.urisik.backend.global.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;


@Entity
@Table(name = "personal-transformed-recipe-wishlist", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"family_member_profile_id", "transformed_recipe_id"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberTransformedRecipeWish extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "family_member_profile_id", nullable = false)
    private FamilyMemberProfile familyMemberProfile;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transformed_recipe_id", nullable = false)
    private TransformedRecipe recipe;



    public static MemberTransformedRecipeWish of(TransformedRecipe recipe) {
        MemberTransformedRecipeWish w = new MemberTransformedRecipeWish();
        w.recipe = recipe;
        return w;
    }

    public void setFamilyMemberProfile(FamilyMemberProfile profile) {
        this.familyMemberProfile = profile;
    }
}