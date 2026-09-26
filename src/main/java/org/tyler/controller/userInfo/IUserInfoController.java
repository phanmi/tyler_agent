package org.tyler.controller.userInfo;

import org.tyler.model.userInfo.UserInfo;

/**
 * REST contract for user profiles.
 *
 * <p>Declares HTTP operations; UserInfo, User, and Other data records live in
 * the {@link org.tyler.model} package.
 */
public interface IUserInfoController {

    /** Returns the saved user profile. */
    UserInfo get();

    /** Saves a profile and returns the stored values. */
    UserInfo save(UserInfo userInfo);
}