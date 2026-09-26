package org.tyler.service.userInfo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.tyler.filesandbox.FileSandbox;
import org.tyler.model.userInfo.Other;
import org.tyler.model.userInfo.User;
import org.tyler.model.userInfo.UserInfo;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Tests English profile values and compatibility with previously saved profiles. */
class UserInfoServiceTest {

    @TempDir
    Path tempDir;

    private static UserInfo profile(String gender) {
        return new UserInfo(new User("Taylor", gender, new BigDecimal("30"), "Developer"),
                new Other("Enjoys running", "Help track nutrition"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Male", "Female", "Other"})
    void englishGenderRoundTrips(String gender) throws IOException {
        FileSandbox sandbox = new FileSandbox(tempDir.toString());
        UserInfoService service = new UserInfoService(sandbox, sandbox, "userinfo.json");
        UserInfo expected = profile(gender);

        assertEquals(expected, service.save(expected));
        assertEquals(expected, service.get());
        assertEquals(expected, new ObjectMapper().readValue(sandbox.read("userinfo.json"), UserInfo.class));
    }

    @ParameterizedTest
    @CsvSource({"\u7537,Male", "\u5973,Female", "\u5176\u4ed6,Other"})
    void legacyGenderLoadsAsEnglishWithoutChangingOtherFields(String legacyGender, String englishGender)
            throws IOException {
        FileSandbox sandbox = new FileSandbox(tempDir.toString());
        UserInfoService service = new UserInfoService(sandbox, sandbox, "userinfo.json");
        String original = new ObjectMapper().writeValueAsString(profile(legacyGender));
        sandbox.write("userinfo.json", original);

        assertEquals(profile(englishGender), service.get());
        assertEquals(original, sandbox.read("userinfo.json"));
    }

    @ParameterizedTest
    @CsvSource({"\u7537,Male", "\u5973,Female", "\u5176\u4ed6,Other"})
    void legacyGenderIsSavedInEnglish(String legacyGender, String englishGender) throws IOException {
        FileSandbox sandbox = new FileSandbox(tempDir.toString());
        UserInfoService service = new UserInfoService(sandbox, sandbox, "userinfo.json");

        assertEquals(profile(englishGender), service.save(profile(legacyGender)));
        UserInfo stored = new ObjectMapper().readValue(sandbox.read("userinfo.json"), UserInfo.class);
        assertEquals(profile(englishGender), stored);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void unspecifiedGenderRemainsBlank(String gender) throws IOException {
        FileSandbox sandbox = new FileSandbox(tempDir.toString());
        UserInfoService service = new UserInfoService(sandbox, sandbox, "userinfo.json");

        assertEquals(profile(""), service.save(profile(gender)));
        assertEquals(profile(""), service.get());
    }

    @Test
    void invalidGenderReturnsEnglishValidationMessage() throws IOException {
        FileSandbox sandbox = new FileSandbox(tempDir.toString());
        UserInfoService service = new UserInfoService(sandbox, sandbox, "userinfo.json");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.save(profile("invalid")));

        assertEquals("Gender must be Male, Female, Other, or left blank", exception.getMessage());
    }
}
