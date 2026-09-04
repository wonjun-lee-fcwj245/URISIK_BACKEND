package com.urisik.backend.domain.member.service;

import com.urisik.backend.domain.allergy.entity.AllergenAlternative;
import com.urisik.backend.domain.allergy.entity.MemberAllergy;
import com.urisik.backend.domain.allergy.enums.Allergen;
import com.urisik.backend.domain.allergy.repository.AllergenAlternativeRepository;
import com.urisik.backend.domain.allergy.repository.MemberAllergyRepository;
import com.urisik.backend.domain.familyroom.entity.FamilyRoom;
import com.urisik.backend.domain.member.entity.*;
import com.urisik.backend.domain.member.enums.FamilyRole;
import com.urisik.backend.domain.member.converter.FamilyMemberProfileConverter;
import com.urisik.backend.domain.member.dto.req.FamilyMemberProfileRequest;
import com.urisik.backend.domain.member.dto.res.FamilyMemberProfileResponse;
import com.urisik.backend.domain.member.enums.DietPreferenceList;
import com.urisik.backend.domain.member.exception.MemberException;
import com.urisik.backend.domain.member.exception.code.MemberErrorCode;
import com.urisik.backend.domain.member.repo.*;
import com.urisik.backend.domain.recipe.entity.Recipe;
import com.urisik.backend.global.external.s3.S3Remover;
import com.urisik.backend.global.external.s3.S3Uploader;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Service
@RequiredArgsConstructor
public class FamilyMemberProfileService {

    private final FamilyMemberProfileRepository familyMemberProfileRepository;
    private final MemberRepository memberRepository;
    private final MemberAllergyRepository memberAllergyRepository;
    private final DietPreferenceRepository dietPreferenceRepository;
    private final S3Uploader s3Uploader;
    private final S3Remover s3Remover;
    private final AllergenAlternativeRepository allergenAlternativeRepository;
    private final MemberWishListRepository memberWishListRepository;
    private final MemberTransformedRecipeWishRepository transformedRecipeWishRepository;
    private final MemberTransformedRecipeWishRepository memberTransformedRecipeWishRepository;

    //post
    @Caching(evict = {
            @CacheEvict(value = "familyAllergens", key = "#familyRoomId"),
            @CacheEvict(value = "recommendSafe", allEntries = true),
            @CacheEvict(value = "recommendSafeHighScore", allEntries = true)
    })
    @Transactional
    public FamilyMemberProfileResponse.Create create
            (Long familyRoomId, Long memberId, FamilyMemberProfileRequest.Create req)
    {
        Member member = memberRepository.findWithFamilyRoomById(memberId)
                .orElseThrow(() -> new MemberException(MemberErrorCode.NO_MEMBER));

        // 1단계  memberId가 가진 가족방 가져와서 안에 해당 가족방이 있는지 확인. 없다면 오류.
        FamilyRoom familyRoom = member.getFamilyRoom();
        if (familyRoom == null) {
            throw new MemberException(MemberErrorCode.NO_ROOM);
        }
        if (!familyRoom.getId().equals(familyRoomId)) {
            throw new MemberException(MemberErrorCode.FORBIDDEN_ROOM); // 403 성격 에러코드
        }

        boolean exist = familyMemberProfileRepository.existsByMember_Id(memberId);
        if (exist) {
            throw new MemberException(MemberErrorCode.ALREADY_HAVE_PROFILE);
        }


        // 2단계 familyRoom에 속한 FamilyMemberProfile의 리스트를 가져온다. 프로필들중 Mom, Father역할을 식별한다
        //Mom과 father 은 한명밖에 없으니 요청과 현재 부모상태 체크하고 통과되면 프로필을 등록한다.

        List<FamilyMemberProfile> familyMemberProfiles = familyMemberProfileRepository.findAllByFamilyRoom_Id(familyRoom.getId());
        FamilyRole requestedRole = req.getRole();


        // 엄마/아빠/할머니/할아버지 중복 제한
        if (requestedRole == FamilyRole.MOM || requestedRole == FamilyRole.DAD
                || requestedRole == FamilyRole.GRANDMOTHER || requestedRole == FamilyRole.GRANDFATHER ) {

            boolean alreadyExists = familyMemberProfiles.stream()
                    .anyMatch(p -> p.getFamilyRole() == requestedRole);

            if (alreadyExists) {
                throw new MemberException(MemberErrorCode.ALREADY_EXIST_ROLE);
            }
        }
        //3단계 req 정보 저장 FamilyMemberProfile에 저장
        FamilyMemberProfile profile = FamilyMemberProfile.builder()
                .nickname(req.getNickname())
                .member(member)
                .familyRole(req.getRole())
                .profilePicUrl("https://urisik-server-s3.s3.ap-northeast-2.amazonaws.com/family_member_profile/087eb145-8e4c-48c4-872c-a4b178a43639_no_profile_image_first.png")
                .likedIngredients(req.getLikedIngredients())
                .dislikedIngredients(req.getDislikedIngredients())
                .familyRoom(familyRoom)
                .build();

        profile.setMembers(member);


        if (req.getAllergy() != null) {
            for (Allergen allergen : req.getAllergy()) {
                profile.addAllergy(MemberAllergy.of(allergen));
            }
        }


        if (req.getDietPreferences() != null) {
            for (DietPreferenceList diet : req.getDietPreferences()) { // ✅ DTO도 Enum이면 이렇게
                profile.addDietPreference(DietPreference.of(diet));
            }
        }

        FamilyMemberProfile saved = familyMemberProfileRepository.save(profile);



        //4단계 프론트에서 성공응답과 함꼐 저장한 정보 전달.
        return FamilyMemberProfileConverter.toCreate(saved);
    }



    /*
    -----------------------------------------------------------
    patch
     */

    @Caching(evict = {
            @CacheEvict(value = "familyAllergens", key = "#familyRoomId"),
            @CacheEvict(value = "recommendSafe", allEntries = true),
            @CacheEvict(value = "recommendSafeHighScore", allEntries = true)
    })
    @Transactional
    public FamilyMemberProfileResponse.Update update(
            Long familyRoomId,
            Long memberId,
            FamilyMemberProfileRequest.Update req
    ) {
        // 1) "프로필"만 조회 (컬렉션 fetch 금지)
        FamilyMemberProfile profile = familyMemberProfileRepository
                .findByFamilyRoom_IdAndMember_Id(familyRoomId, memberId)
                .orElseThrow(() -> new MemberException(MemberErrorCode.NO_PROFILE_IN_FAMILY));

        Long profileId = profile.getId();

        // 2) 단일 필드 수정
        if (req.getNickname() != null) {
            profile.setNickname(req.getNickname());
        }

        if (req.getLikedIngredients() != null) {
            profile.setLikedIngredients(req.getLikedIngredients());
        }

        if (req.getDislikedIngredients() != null) {
            profile.setDislikedIngredients(req.getDislikedIngredients());
        }

        // 3) role 변경 + (MOM/DAD) 중복 체크
        if (req.getRole() != null) {
            FamilyRole newRole = req.getRole();

            if (newRole == FamilyRole.MOM || newRole == FamilyRole.DAD
                    || newRole == FamilyRole.GRANDMOTHER  || newRole == FamilyRole.GRANDFATHER  ) {
                boolean alreadyExists = familyMemberProfileRepository
                        .existsByFamilyRoom_IdAndFamilyRoleAndIdNot(
                                familyRoomId,
                                newRole,
                                profileId
                        );
                if (alreadyExists) {
                    throw new MemberException(MemberErrorCode.ALREADY_EXIST_ROLE);
                }
            }

            profile.setFamilyRole(newRole);
        }

        // 4) allergy 전체 교체 (요청이 null이면 유지)
        if (req.getAllergy() != null) {
            memberAllergyRepository.deleteAllByFamilyMemberProfile_Id(profileId);

            for (Allergen a : req.getAllergy()) {
                MemberAllergy entity = MemberAllergy.of(a);
                entity.setFamilyMemberProfile(profile); // FK 세팅
                memberAllergyRepository.save(entity);
            }
        }

        // 5) dietPreferences 전체 교체 (요청이 null이면 유지)
        if (req.getDietPreferences() != null) {
            dietPreferenceRepository.deleteAllByFamilyMemberProfile_Id(profileId);

            for (DietPreferenceList d : req.getDietPreferences()) {
                DietPreference entity = DietPreference.of(d);
                entity.setFamilyMemberProfile(profile);
                dietPreferenceRepository.save(entity);
            }
        }

        // 6) 응답 조립을 위해 "컬렉션 2번" 조회 (총 3번 조회)
        List<MemberAllergy> allergies = memberAllergyRepository.findByFamilyMemberProfile_Id(profileId);
        List<DietPreference> diets = dietPreferenceRepository.findAllByFamilyMemberProfile_Id(profileId);

        return FamilyMemberProfileConverter.toUpdate(profile, allergies, diets);
    }



    /*
    프론트는 multipart/form-data로:
    key: file
    value: 이미지 파일
     */
    @Transactional
    public FamilyMemberProfileResponse.UpdatePic updatePic(
            Long familyRoomId,
            Long memberId,
            MultipartFile file
    ) {
        String DEFAULT_URL = "https://urisik-server-s3.s3.ap-northeast-2.amazonaws.com/family_member_profile/087eb145-8e4c-48c4-872c-a4b178a43639_no_profile_image_first.png";
        // 1) 프로필 조회 (memberId + familyRoomId 조건으로 찾는 걸 추천)
        FamilyMemberProfile profile = familyMemberProfileRepository
                .findByFamilyRoom_IdAndMember_Id(familyRoomId, memberId)
                .orElseThrow(() -> new MemberException(MemberErrorCode.NO_PROFILE_IN_FAMILY));


        if (file == null || file.isEmpty()) {
            throw new MemberException(MemberErrorCode.INVALID_FILE);
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new MemberException(MemberErrorCode.INVALID_FILE_TYPE);
        }

        String oldUrl = profile.getProfilePicUrl();

        String newUrl = s3Uploader.uploadByFile(file, "family_member_profile"); // 폴더명 추천
        profile.setProfilePicUrl(newUrl);

        if (oldUrl != null && !oldUrl.isBlank() && !oldUrl.equals(DEFAULT_URL)) {
            s3Remover.remove(oldUrl);
        }

        return FamilyMemberProfileResponse.UpdatePic.builder()
                .profilePicUrl(newUrl)
                .build();
    }

    /*
    --------------------------------------------------------------------
    get
     */


    public FamilyMemberProfileResponse.Detail getMemberProfile
            (Long familyRoomId, Long profileId, Long memberId) {

        // 요청자가 자신의 프로필을 요청하는 경우
        if (profileId == -1) {
            FamilyMemberProfile mine = familyMemberProfileRepository.findByMember_Id(memberId).orElseThrow(
                    () -> new MemberException(MemberErrorCode.NO_PROFILE_IN_FAMILY)
            );
            profileId = mine.getId();
        }


        // 요청 대상의 프로필이 있는가?
        boolean exists_req_pro = familyMemberProfileRepository.existsById(profileId);
        if (!exists_req_pro) {
            throw new MemberException(MemberErrorCode.NO_PROFILE_IN_FAMILY);
        }

        // 요청자가 방안에 있는지?
        boolean exists = familyMemberProfileRepository.existsByFamilyRoom_IdAndMember_Id(familyRoomId, memberId);
        if (!exists) {
            throw new MemberException(MemberErrorCode.NOT_YOUR_ROOM);
        }

        // 방안에 있는 맴버 찾기.
        FamilyMemberProfile profile = familyMemberProfileRepository
                .findByFamilyRoom_IdAndId(familyRoomId,profileId)
                .orElseThrow(() -> new MemberException(MemberErrorCode.NO_PROFILE_IN_FAMILY));

        Long targetProfileId = profile.getId();

        // 2) 알러지 목록 조회
        List<MemberAllergy> allergies = memberAllergyRepository.findByFamilyMemberProfile_Id(targetProfileId);

        // 3) 식단선호 목록 조회
        List<DietPreference> diets = dietPreferenceRepository.findAllByFamilyMemberProfile_Id(targetProfileId);

        // 4) 대체 식재료 조회
        List<Allergen> allergyEnums = allergies.stream()
                .map(MemberAllergy::getAllergen)
                .distinct()
                .toList();

        List<AllergenAlternative> alternatives = allergyEnums.isEmpty()
                ? List.of()
                : allergenAlternativeRepository.findByAllergenIn(allergyEnums);


        return FamilyMemberProfileConverter.toDetail(profile, allergies, diets, alternatives);

    }


    public FamilyMemberProfileResponse.getFamilyProfilesResponse getFamilyProfiles
            (Long familyRoomId, Long memberId) {


        // 프로필이 존재하는지 여부
        boolean exists_mem = familyMemberProfileRepository.existsByMember_Id(memberId);
        if (!exists_mem) {
            throw new MemberException(MemberErrorCode.NO_PROFILE_IN_FAMILY);
        }

        // 방안에 있는 맴버 찾기.

        boolean exists = familyMemberProfileRepository.existsByFamilyRoom_IdAndMember_Id(familyRoomId, memberId);
        if (!exists) {
            throw new MemberException(MemberErrorCode.NOT_YOUR_ROOM);
        }

        // 방안에 모든 맴버들 반환
        List<FamilyMemberProfile> profiles =
                familyMemberProfileRepository.findAllByFamilyRoomId(familyRoomId);

        //각 맴버 들의 id, 프로필 url, 이름, 역할
        List<FamilyMemberProfileResponse.familyDetail> details = profiles.stream()
                .map(p -> FamilyMemberProfileResponse.familyDetail.builder()
                        .profileId(p.getId())
                        .nickname(p.getNickname())
                        .role(p.getFamilyRole())          // 네 DTO는 FamilyRole role
                        .profilePicUrl(p.getProfilePicUrl())
                        .build())
                .toList();

        return FamilyMemberProfileResponse.getFamilyProfilesResponse.builder()
                .familyDetails(details)
                .build();

    }



    /*
    --------------------------------------------------------------------
    delete
     */


    @Transactional
    public FamilyMemberProfileResponse.Delete quitFamilyRoom(Long familyRoomId, Long profileId , Long memberId) {

        FamilyMemberProfile targetProfile = familyMemberProfileRepository
                .findByFamilyRoom_IdAndId(familyRoomId, profileId)
                .orElseThrow(() -> new MemberException(MemberErrorCode.NO_PROFILE_IN_FAMILY));

        FamilyMemberProfile requesterProfile = familyMemberProfileRepository
                .findByFamilyRoom_IdAndMember_Id(familyRoomId, memberId)
                .orElseThrow(() -> new MemberException(MemberErrorCode.NO_PROFILE_IN_FAMILY));


        //자기 프로필이 거나, 권한이 있을때 삭제
        boolean isSelf = targetProfile.getMember().getId().equals(memberId);

        // 4) 리더 권한 여부 (FamilyPolicy 기준)
        boolean isLeader = requesterProfile.getFamilyRoom()
                .getFamilyPolicy()
                .isLeaderRole(requesterProfile.getFamilyRole());

        // 5) 본인이거나 리더면 삭제 가능
        if (!isSelf && !isLeader) {
            throw new MemberException(MemberErrorCode.FORBIDDEN_MEMBER);
        }

        List<MemberWishList> memberWishLists =
                memberWishListRepository.findMemberWishListsByFamilyMemberProfile_Id(targetProfile.getId());

        List<Long> recipeIds = memberWishLists.stream()
                .map(w -> w.getRecipe().getId())
                .toList();

        List<MemberTransformedRecipeWish> transformedWishes =
                transformedRecipeWishRepository.findMemberWishListsByFamilyMemberProfile_Id(targetProfile.getId());

        List<Long> transformedRecipeIds = transformedWishes.stream()
                .map(w -> w.getRecipe().getId())
                .toList();

        if (!recipeIds.isEmpty()) {
            int a = memberWishListRepository.decreaseWishCount(recipeIds);
        }

        if (!transformedRecipeIds.isEmpty()) {
            int b = memberTransformedRecipeWishRepository.decreaseWishCount(transformedRecipeIds);
        }

        // 6) 방 탈퇴
        targetProfile.getMember().setFamilyRoom(null);
        familyMemberProfileRepository.delete(targetProfile);

        return FamilyMemberProfileResponse.Delete.builder().isDeleted(true).build();

    }





}
