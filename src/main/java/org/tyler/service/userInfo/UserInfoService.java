package org.tyler.service.userInfo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.tyler.filesandbox.IFileSandboxRead;
import org.tyler.filesandbox.IFileSandboxWrite;
import org.tyler.exceptionHandler.exception.FileWriteException;
import org.tyler.model.userInfo.Other;
import org.tyler.model.userInfo.User;
import org.tyler.model.userInfo.UserInfo;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Set;

/**
 * Service for reading and saving user profiles.
 *
 * <p>Handles JSON conversion and field validation.
 * All file access is delegated to {@link IFileSandboxRead} and {@link IFileSandboxWrite}.
 */
@Service
public class UserInfoService implements IUserInfoService {

    private static final Logger log = LoggerFactory.getLogger(UserInfoService.class);

    /** Accepted gender values; an empty string means unspecified. */
    private static final Set<String> VALID_GENDERS = Set.of("", "Male", "Female", "Other");

    // Create a reusable ObjectMapper directly because this service uses Jackson 2.
    // The configured mapper is thread-safe and requires no injected mapper bean.
    // It handles JSON conversion; FileSandbox handles storage.
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final IFileSandboxRead reader;
    private final IFileSandboxWrite writer;
    private final String relativePath;

    public UserInfoService(
            IFileSandboxRead reader,
            IFileSandboxWrite writer,
            @Value("${userinfo.file-path:userinfo.json}") String relativePath) {
        this.reader = reader;
        this.writer = writer;
        // A relative path within the FileSandbox root.
        this.relativePath = relativePath;
    }

    @Override
    public UserInfo get() {
        // Check existence before reading to avoid an error for a missing file.
        if (!reader.exists(relativePath)) {
            log.info("Profile file is missing; returning an empty profile: {}", relativePath);
            return empty();
        }
        try {
            UserInfo info = objectMapper.readValue(reader.read(relativePath), UserInfo.class);
            log.debug("Loaded profile from {}", relativePath);
            if (info != null && info.user() != null) {
                User user = info.user();
                return new UserInfo(new User(user.name(), normalizeGender(user.gender()),
                        user.age(), user.jobType()), info.other());
            }
            return info;
        } catch (Exception e) {
            // Return an empty profile if the file is unreadable or contains invalid JSON.
            log.warn("Failed to read profile; returning an empty profile: {}", relativePath, e);
            return empty();
        }
    }

    @Override
    public UserInfo save(UserInfo userInfo) {
        User user = userInfo.user();
        Other other = userInfo.other();
        if (user == null || other == null) {
            throw new IllegalArgumentException("User and Other must not be null");
        }

        // Treat a null gender as unspecified.
        String gender = normalizeGender(user.gender());
        if (!VALID_GENDERS.contains(gender)) {
            throw new IllegalArgumentException("Gender must be Male, Female, Other, or left blank");
        }
        // BigDecimal distinguishes integer ages from fractional values
        // so validation can reject fractions without silently truncating them.
        BigDecimal age = user.age();
        if (age != null && age.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("Age must be an integer or left blank");
        }

        // Rebuild the profile with normalized values before saving.
        UserInfo normalized = new UserInfo(
                new User(user.name(), gender, user.age(), user.jobType()),
                other);

        try {
            // Serialize to JSON, then delegate the write to FileSandbox.
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(normalized);
            writer.write(relativePath, json);
            log.info("Saved profile to {}", relativePath);
            return normalized;
        } catch (IOException | FileWriteException e) {
            log.error("Failed to write profile: {}", relativePath, e);
            // GenericExceptionHandler maps IllegalStateException to HTTP 500.
            throw new IllegalStateException("Failed to save profile. Please try again later", e);
        }
    }

    /** Converts legacy profile values to the English values used by the UI. */
    private static String normalizeGender(String gender) {
        if (gender == null) {
            return "";
        }
        return switch (gender) {
            case "\u7537" -> "Male";
            case "\u5973" -> "Female";
            case "\u5176\u4ed6" -> "Other";
            default -> gender;
        };
    }

    /** Empty profile with blank strings and an unspecified age. */
    private static UserInfo empty() {
        return new UserInfo(new User("", "", null, ""), new Other("", ""));
    }
}
