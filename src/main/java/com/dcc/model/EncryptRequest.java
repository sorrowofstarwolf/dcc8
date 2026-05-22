package com.dcc.model;

public class EncryptRequest {
    private String requestId;
    private String sm4Key;
    private String ip;
    private String[] fieldsToEncrypt;

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getSm4Key() { return sm4Key; }
    public void setSm4Key(String sm4Key) { this.sm4Key = sm4Key; }
    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }
    public String[] getFieldsToEncrypt() { return fieldsToEncrypt; }
    public void setFieldsToEncrypt(String[] fieldsToEncrypt) { this.fieldsToEncrypt = fieldsToEncrypt; }
}
