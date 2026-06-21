package com.flag.common.dto;

import lombok.Data;

@Data
public class EvaluateResponse {
    private String flagKey;
    private boolean enabled;
    /** Matched rule description for debugging */
    private String matchedRule;
    /** Evaluation duration in NANOSECONDS */
    private long evalCostNs;

    public static EvaluateResponse of(String flagKey, boolean enabled) {
        EvaluateResponse resp = new EvaluateResponse();
        resp.flagKey = flagKey;
        resp.enabled = enabled;
        return resp;
    }
}
