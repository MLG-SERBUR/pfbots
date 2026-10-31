package com.robomwm.ai.pfbots.markov;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisionOnDemandTest {
    @Test
    void visionModelsFromCodeConfig() {
        // Gemini, Groq qwen3.8, Mistral general models, Ollama gemma4.
        assertTrue(GenerativeAiConfig.supportsVision("gemini-2.5-flash"));
        assertTrue(GenerativeAiConfig.supportsVision("gemini-2.5-flash-lite"));
        assertTrue(GenerativeAiConfig.supportsVision("gemma-4-31b-it"));
        assertTrue(GenerativeAiConfig.supportsVision("qwen/qwen3.8-27b"));
        assertTrue(GenerativeAiConfig.supportsVision("mistral-large-latest"));
        assertTrue(GenerativeAiConfig.supportsVision("mistral-medium-latest"));
        assertTrue(GenerativeAiConfig.supportsVision("magistral-small-latest"));
        assertTrue(GenerativeAiConfig.supportsVision("mistral-small-latest"));
        assertTrue(GenerativeAiConfig.supportsVision("ministral-8b-latest"));
        assertTrue(GenerativeAiConfig.supportsVision("gemma4:31b"));
    }

    @Test
    void textOnlyModelsAreNotVision() {
        assertFalse(GenerativeAiConfig.supportsVision("beam"));
        assertFalse(GenerativeAiConfig.supportsVision("openai/gpt-oss-120b"));
        assertFalse(GenerativeAiConfig.supportsVision("openai/gpt-oss-20b"));
        assertFalse(GenerativeAiConfig.supportsVision("gpt-oss:120b"));
        assertFalse(GenerativeAiConfig.supportsVision("nemotron-3-ultra"));
        assertFalse(GenerativeAiConfig.supportsVision("DeepSeek-V3.1"));
        assertFalse(GenerativeAiConfig.supportsVision("Meta-Llama-3.3-70B-Instruct"));
        assertFalse(GenerativeAiConfig.supportsVision("glm-4.7-flash"));
        assertFalse(GenerativeAiConfig.supportsVision("glm-4.5-flash"));
        assertFalse(GenerativeAiConfig.supportsVision("codestral-latest"));
        assertFalse(GenerativeAiConfig.supportsVision("open-mistral-nemo"));
        assertFalse(GenerativeAiConfig.supportsVision("openrouter/free"));
        assertFalse(GenerativeAiConfig.supportsVision("Qwen3.5-27B-Derestricted"));
        assertFalse(GenerativeAiConfig.supportsVision(null));
    }

    @Test
    void parseVisionNeedWithQuestion() {
        MarkovListener.VisionNeed need = MarkovListener.parseVisionNeed("NEED_VISION: 12345: what color is the car?");
        assertEquals("12345", need.imageId());
        assertEquals("what color is the car?", need.question());
    }

    @Test
    void parseVisionNeedWithoutQuestionDefaultsToDescribe() {
        MarkovListener.VisionNeed need = MarkovListener.parseVisionNeed("NEED_VISION: 12345");
        assertEquals("12345", need.imageId());
        assertEquals("Describe this image in detail", need.question());
    }

    @Test
    void parseVisionNeedRejectsNormalRepliesAndEmbeddedMarkers() {
        assertNull(MarkovListener.parseVisionNeed("hello there"));
        assertNull(MarkovListener.parseVisionNeed("I think NEED_VISION: 123: color? is funny"));
        assertNull(MarkovListener.parseVisionNeed(""));
        assertNull(MarkovListener.parseVisionNeed(null));
    }

    @Test
    void imagePlaceholderCarriesIdForMarker() {
        MarkovListener.PendingImage image = new MarkovListener.PendingImage("12345", "pic.png", "image/png", 100, "https://example.invalid/pic.png");
        assertEquals("[image id=12345 file=pic.png]", MarkovListener.imagePlaceholder(image));
    }
}
