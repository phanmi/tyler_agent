package org.tyler.tool;

import com.openai.models.responses.FunctionTool;

public class GetUserInfo implements ITool{
    @Override
    public String name() {
        return "GetUserInfo";
    }

    @Override
    public String description() {
        return "this tool should be used everytime. "
                + "this allows the AI to understand who the user is";
    }

    @Override
    public String execute(String argumentsJson) {
        return "";
    }

    @Override
    public FunctionTool toFunctionTool() {
        return ITool.super.toFunctionTool();
    }
}
