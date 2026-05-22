package com.dcc.model;

public class CallbackRequest {
    private final String teamCode;
    private final String requestId;
    private final String ip;

    public CallbackRequest(String teamCode, String requestId, String ip) {
        this.teamCode = teamCode;
        this.requestId = requestId;
        this.ip = ip;
    }

    public String getTeamCode() { return teamCode; }
    public String getRequestId() { return requestId; }
    public String getIp() { return ip; }
}
