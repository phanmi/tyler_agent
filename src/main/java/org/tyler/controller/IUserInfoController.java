package org.tyler.controller;

import org.tyler.model.userInfo.UserInfo;

/**
 * 用户信息相关的 REST 契约。
 *
 * <p>只声明 HTTP 接口的方法签名；数据模型（UserInfo / User / Other）已下沉到
 * {@link org.tyler.model} 包。
 */
public interface IUserInfoController {

    /** 读取当前保存的用户信息。 */
    UserInfo get();

    /** 保存用户信息，返回保存后的结构。 */
    UserInfo save(UserInfo userInfo);
}