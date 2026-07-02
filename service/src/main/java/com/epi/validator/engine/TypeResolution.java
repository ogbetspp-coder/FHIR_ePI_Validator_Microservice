package com.epi.validator.engine;

import com.epi.validator.model.EpiType;

/**
 * The type-resolution model of the gate:
 *
 * <pre>
 * requestedEpiType = caller's contract intent ("1"|"2"|"3"|"auto")
 * detectedEpiType  = inferred from bundle contents (diagnostic, always reported)
 * effectiveEpiType = requested unless requested=auto, then detected
 * </pre>
 *
 * The validation target (profile + type rules) follows {@code effective}; auto-detection can
 * never select a weaker gate than an explicitly requested type.
 */
public record TypeResolution(String requested, EpiType detected, EpiType effective) {

    public static final String AUTO = "auto";

    public static TypeResolution resolve(String requestedParam, EpiType detected) {
        String requested = requestedParam == null || requestedParam.isBlank() ? AUTO : requestedParam;
        if (AUTO.equalsIgnoreCase(requested)) {
            return new TypeResolution(AUTO, detected, detected);
        }
        return new TypeResolution(requested, detected, EpiType.fromWire(requested));
    }

    public boolean isAuto() {
        return AUTO.equalsIgnoreCase(requested);
    }
}
