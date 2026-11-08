package com.luyi.myfirst.web.profile;

import com.luyi.myfirst.service.profile.ProfileService;
import com.luyi.myfirst.service.profile.model.ProfileCard;
import com.luyi.myfirst.web.profile.dto.ProfileCardListResponse;
import com.luyi.myfirst.web.profile.dto.ProfileCardResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * 公开名片批量端点（GET /api/profiles?publicIds=a,b）。
 *
 * 逗号分隔串在这里拆成列表；数量与存在性判定在 service，这里只做绑定与映射。
 * 缺失、空值、超过 50 个由 service 判 400，不在控制器里提前抛。
 */
@RestController
@RequestMapping("/api/profiles")
public class ProfileCardsController {

    private final ProfileService profileService;

    public ProfileCardsController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping
    ProfileCardListResponse cards(@RequestParam(required = false) String publicIds) {
        List<ProfileCard> cards = profileService.cards(split(publicIds));
        return new ProfileCardListResponse(cards.stream().map(ProfileCardsController::toCard).toList());
    }

    private static List<String> split(String raw) {
        if (raw == null) {
            return null;
        }
        if (raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(",")).toList();
    }

    private static ProfileCardResponse toCard(ProfileCard card) {
        return new ProfileCardResponse(card.publicId(), card.displayName(), card.avatarUrl());
    }
}
