package org.tyler.controller;

import org.tyler.service.IUserInfoService;

/**
 * 用户信息相关的 REST 契约。
 *
 * <p>只声明 HTTP 接口的方法签名；数据模型（UserInfo / User / Other）已下沉到
 * {@link IUserInfoService}，由 service 层拥有。
 */
public interface IUserInfoController {

    /** 读取当前保存的用户信息。 */
    IUserInfoService.UserInfo get();

    /** 保存用户信息，返回保存后的结构。 */
    IUserInfoService.UserInfo save(IUserInfoService.UserInfo userInfo);
}