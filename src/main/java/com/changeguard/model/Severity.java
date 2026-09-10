package com.changeguard.model;
public enum Severity {
    INFO(0), LOW(2), MEDIUM(5), HIGH(10), CRITICAL(20);
    private final int penalty;
    Severity(int penalty) { this.penalty = penalty; }
    public int penalty() { return penalty; }
}
