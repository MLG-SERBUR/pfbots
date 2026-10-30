package com.robomwm.ai.pfbots.markov;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenCalibrationManagerTest {

    @Test
    void familyForModelGroupsQwenAcrossProviders() {
        assertEquals(TokenCalibrationManager.FAMILY_QWEN,
                TokenCalibrationManager.familyForModel("qwen/qwen3.8-27b"));
        assertEquals(TokenCalibrationManager.FAMILY_QWEN,
                TokenCalibrationManager.familyForModel("Qwen3.5-27B-Derestricted"));
        assertEquals(TokenCalibrationManager.FAMILY_QWEN,
                TokenCalibrationManager.familyForModel("qwen-3-235b-a22b-instruct-2507"));
    }

    @Test
    void familyForModelGroupsGpt() {
        assertEquals(TokenCalibrationManager.FAMILY_GPT,
                TokenCalibrationManager.familyForModel("openai/gpt-oss-120b"));
        assertEquals(TokenCalibrationManager.FAMILY_GPT,
                TokenCalibrationManager.familyForModel("openai/gpt-oss-20b"));
        assertEquals(TokenCalibrationManager.FAMILY_GPT,
                TokenCalibrationManager.familyForModel("@cf/openai/gpt-oss-120b"));
    }

    @Test
    void familyForModelDefaultsForOtherAndNull() {
        assertEquals(TokenCalibrationManager.FAMILY_DEFAULT,
                TokenCalibrationManager.familyForModel("llama3.2:3b"));
        assertEquals(TokenCalibrationManager.FAMILY_DEFAULT,
                TokenCalibrationManager.familyForModel("mistral-large-latest"));
        assertEquals(TokenCalibrationManager.FAMILY_DEFAULT,
                TokenCalibrationManager.familyForModel(null));
    }

    @Test
    void groqIptmLimitIsLowerForQwen() {
        assertEquals(7000, RoundRobinGenerativeAiResponder.groqIptmLimitForModel("qwen/qwen3.8-27b"));
        assertEquals(8000, RoundRobinGenerativeAiResponder.groqIptmLimitForModel("openai/gpt-oss-120b"));
        assertEquals(8000, RoundRobinGenerativeAiResponder.groqIptmLimitForModel("openai/gpt-oss-20b"));
        assertEquals(8000, RoundRobinGenerativeAiResponder.groqIptmLimitForModel("something-else"));
    }

    @Test
    void trimTargetsLimitWithHeadroom() {
        // 7000*0.85/8000 = 0.74375 -> 74 of 100
        assertEquals(74, TokenCalibrationManager.computeTrimmedSize(100, 8000, 7000));
        // actual == limit -> 0.85 -> 85
        assertEquals(85, TokenCalibrationManager.computeTrimmedSize(100, 8000, 8000));
    }

    @Test
    void trimClampsExtremeRatiosAndSmallLists() {
        // 7000*0.85/20000 = 0.2975 -> clamped to 0.3 -> 30
        assertEquals(30, TokenCalibrationManager.computeTrimmedSize(100, 20000, 7000));
        // tiny lists never trim
        assertEquals(10, TokenCalibrationManager.computeTrimmedSize(10, 20000, 7000));
        assertEquals(5, TokenCalibrationManager.computeTrimmedSize(5, 20000, 7000));
        // unparseable errors -> no trim
        assertEquals(100, TokenCalibrationManager.computeTrimmedSize(100, null, 7000));
        assertEquals(100, TokenCalibrationManager.computeTrimmedSize(100, 8000, null));
    }

    @Test
    void rawEstimateUsesPerFamilyMaxObservedBase() {
        String gptModel = "openai/gpt-oss-120b";
        String qwenModel = "qwen/qwen3.8-27b";
        assertEquals(2.75, TokenCalibrationManager.baseCharsPerToken(gptModel));
        assertEquals(2.46, TokenCalibrationManager.baseCharsPerToken(qwenModel));
        assertEquals(2.75, TokenCalibrationManager.baseCharsPerToken(null));
        assertEquals(0, TokenCalibrationManager.estimateRaw(""));
        assertEquals(0, TokenCalibrationManager.estimateRaw(null, gptModel));
        assertEquals(1, TokenCalibrationManager.estimateRaw("x", gptModel));
        assertEquals(100, TokenCalibrationManager.estimateRaw("x".repeat(275), gptModel));
        assertEquals(100, TokenCalibrationManager.estimateRaw("x".repeat(246), qwenModel));
        // qwen base denser: same text yields more raw tokens than gpt
        String text = "x".repeat(1000);
        assertTrue(TokenCalibrationManager.estimateRaw(text, qwenModel)
                > TokenCalibrationManager.estimateRaw(text, gptModel));
    }

    @Test
    void gatherBudgetFollowsFirstKnownLimitInChain() {
        var groqGptFirst = new RoundRobinGenerativeAiResponder.Provider(
                "Groq", "https://groq.example/chat", "gk", "openai/gpt-oss-120b", java.util.Map.of(), false);
        var groqQwenFirst = new RoundRobinGenerativeAiResponder.Provider(
                "Groq", "https://groq.example/chat", "gk", "qwen/qwen3.8-27b", java.util.Map.of(), false);
        assertEquals(8000, RoundRobinGenerativeAiResponder.gatherBudgetForProviders(
                List.of(groqGptFirst, groqQwenFirst)));
        assertEquals(7000, RoundRobinGenerativeAiResponder.gatherBudgetForProviders(
                List.of(groqQwenFirst, groqGptFirst)));

        var arli = new ArliAiSecondChanceResponder.Provider(
                "ArliAI", "https://arliai.example/chat", "ak", "Qwen3.5-27B-Derestricted", java.util.Map.of(), false);
        assertEquals(ArliAiSecondChanceResponder.ARLIAI_INPUT_LIMIT,
                ArliAiSecondChanceResponder.gatherBudgetForProviders(List.of(arli)));

        assertEquals(RoundRobinGenerativeAiResponder.MAX_GATHER_BUDGET,
                RoundRobinGenerativeAiResponder.gatherBudgetForProviders(List.of()));
    }

    @Test
    void gatherBudgetUsesFreeTierLimitsCappedAtFetchCeiling() {
        var cerebras = new RoundRobinGenerativeAiResponder.Provider(
                "Cerebras", "https://cerebras.example/chat", "ck", "gpt-oss-120b", java.util.Map.of(), false);
        assertEquals(RoundRobinGenerativeAiResponder.CEREBRAS_INPUT_LIMIT,
                RoundRobinGenerativeAiResponder.gatherBudgetForProviders(List.of(cerebras)));

        // Gemini's 250K limit is below the 800K fetch ceiling.
        var gemini = new RoundRobinGenerativeAiResponder.Provider(
                "Gemini", "https://gemini.example/chat", "gk", "gemini-2.5-flash", java.util.Map.of(), false);
        assertEquals(RoundRobinGenerativeAiResponder.GEMINI_INPUT_LIMIT,
                RoundRobinGenerativeAiResponder.gatherBudgetForProviders(List.of(gemini)));

        // Unknown backends never constrain: fall back to fetch ceiling.
        var mistral = new RoundRobinGenerativeAiResponder.Provider(
                "Mistral", "https://mistral.example/chat", "mk", "mistral-large-latest", java.util.Map.of(), false);
        assertEquals(RoundRobinGenerativeAiResponder.MAX_GATHER_BUDGET,
                RoundRobinGenerativeAiResponder.gatherBudgetForProviders(List.of(mistral)));
    }

    @Test
    void tpmErrorExcludesOutputLimits() {
        String inputTpm = "Rate limit reached for model `qwen/qwen3.8-27b` on tokens per minute (TPM): Limit 7000, Used 5000, Requested 3000.";
        assertTrue(TokenCalibrationManager.isGroqTpmError(inputTpm));
        assertTrue(TokenCalibrationManager.isCalibrationError(inputTpm));
        assertEquals(3000, TokenCalibrationManager.extractGroqRequested(inputTpm));
        assertEquals(7000, TokenCalibrationManager.extractGroqLimit(inputTpm));

        String outputTpm = "Rate limit reached for model `qwen/qwen3.8-27b` on output tokens per minute (OTPM): Limit 1000, Used 900, Requested 200.";
        assertFalse(TokenCalibrationManager.isGroqTpmError(outputTpm));
        assertFalse(TokenCalibrationManager.isCalibrationError(outputTpm));
    }
}
