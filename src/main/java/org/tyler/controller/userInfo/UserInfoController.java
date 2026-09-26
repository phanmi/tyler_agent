package org.tyler.controller.userInfo;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tyler.model.userInfo.UserInfo;
import org.tyler.service.userInfo.IUserInfoService;

/**
 * REST controller for user profiles.
 *
 * <p>Maps routes, binds request bodies, and delegates to {@link IUserInfoService}.
 * The service layer handles file access and field validation.
 */
@RestController
@RequestMapping("/api/userinfo")
public class UserInfoController implements IUserInfoController {

    private final IUserInfoService userInfoService;

    public UserInfoController(IUserInfoService userInfoService) {
        this.userInfoService = userInfoService;
    }

    @Override
    @GetMapping
    public UserInfo get() {
        return userInfoService.get();
    }

    @Override
    @PostMapping
    public UserInfo save(@RequestBody UserInfo userInfo) {
        return userInfoService.save(userInfo);
    }
}