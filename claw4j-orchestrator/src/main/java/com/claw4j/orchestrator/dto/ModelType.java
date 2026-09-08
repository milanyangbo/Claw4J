package com.claw4j.orchestrator.dto;

/**
 * Identifies the model family used by the streaming proof path.
 */
public enum ModelType {
    DEEPSEEK,
    QWEN,
    /**
     * Legacy Qwen-family reasoning model alias kept for existing configuration compatibility.
     */
    QWQ,
    GENERIC
}
