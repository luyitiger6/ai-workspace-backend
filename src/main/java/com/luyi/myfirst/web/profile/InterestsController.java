package com.luyi.myfirst.web.profile;

import com.luyi.myfirst.service.profile.ProfileService;
import com.luyi.myfirst.web.profile.dto.InterestListResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 兴趣标签清单端点（GET /api/profile/interests，公开）。
 */
@RestController
@RequestMapping("/api/profile/interests")
public class InterestsController {

    private final ProfileService profileService;

    public InterestsController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping
    InterestListResponse interests() {
        return new InterestListResponse(profileService.interests());
    }
}
