package org.tyler.tool;

import com.openai.models.responses.FunctionTool;
import org.springframework.stereotype.Component;
import org.tyler.service.IUserInfoService;

@Component
public class GetUserInfo implements ITool{
    IUserInfoService userInfoService;

    public GetUserInfo(IUserInfoService userInfoService) {
        this.userInfoService = userInfoService;
    }

    @Override
    public String name() {
        return "GetUserInfo";
    }

    @Override
    public String description() {
        return "Get the saved information about the current user, including basic profile information, additional information, and expectations from the AI."
                + "this allows the AI to understand who the user is and what to expect. ";
    }

    @Override
    public String execute(String argumentsJson) {
        return userInfoService.get().toString();
    }

    @Override
    public FunctionTool toFunctionTool() {
        return ITool.super.toFunctionTool();
    }
}
