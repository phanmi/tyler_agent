package org.tyler.service;

import org.tyler.model.userInfo.UserInfo;

/**
 * 用户信息相关的服务契约。
 *
 * <p>数据模型（UserInfo / User / Other）已下沉到 {@link org.tyler.model} 包，
 * 这里只保留业务方法（get / save）定义读写语义。
 */
public interface IUserInfoService {

    /** 读取当前保存的用户信息；文件不存在或损坏时返回空结构。 */
    UserInfo get();

    /** 保存用户信息，返回保存后的结构。 */
    UserInfo save(UserInfo userInfo);
}
