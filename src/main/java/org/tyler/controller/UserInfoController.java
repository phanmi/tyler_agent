package org.tyler.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tyler.service.IUserInfoService;

/**
 * 用户信息相关的 REST 控制器（薄控制器）。
 *
 * <p>只负责 HTTP 层：路由映射 + 请求体绑定 + 委托给 {@link IUserInfoService}。
 * 读写文件、字段校验等业务逻辑全部在 service 层。
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
    public IUserInfoService.UserInfo get() {
        return userInfoService.get();
    }

    @Override
    @PostMapping
    public IUserInfoService.UserInfo save(@RequestBody IUserInfoService.UserInfo userInfo) {
        return userInfoService.save(userInfo);
    }
}