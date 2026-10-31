package com.robomwm.ai.pfbots.markov;

import java.util.List;

public record GenerativeAiRequest(
        List<String> recentMessages,
        String systemPromptOverride,
        List<VisionImage> images
) {
    public GenerativeAiRequest(List<String> recentMessages) {
        this(recentMessages, null, List.of());
    }

    public GenerativeAiRequest(List<String> recentMessages, String systemPromptOverride) {
        this(recentMessages, systemPromptOverride, List.of());
    }
}
