package org.tyler.service.userInfo;

import org.tyler.model.userInfo.UserInfo;

/**
 * Service contract for user profiles.
 *
 * <p>UserInfo, User, and Other data records live in {@link org.tyler.model}.
 * This interface defines the get and save operations.
 */
public interface IUserInfoService {

    /** Returns the saved profile, or an empty profile if the file is missing or invalid. */
    UserInfo get();

    /** Saves a profile and returns the stored values. */
    UserInfo save(UserInfo userInfo);
}
