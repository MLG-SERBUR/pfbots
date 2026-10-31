package com.robomwm.ai.pfbots.markov;

/** One downloaded image, ready to send to a vision model as a data URI. */
public record VisionImage(String base64, String mimeType) {
    public VisionImage {
        if (base64 == null || base64.isBlank()) throw new IllegalArgumentException("base64 must not be blank");
        if (mimeType == null || mimeType.isBlank()) throw new IllegalArgumentException("mimeType must not be blank");
    }
}
