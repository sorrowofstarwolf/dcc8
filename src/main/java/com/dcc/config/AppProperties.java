package com.dcc.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dcc")
public class AppProperties {
    private String teamCode = "team16";
    private String datasetPath = "table_data.csv";
    private String outputDir = "/opt/app/dcc/team16/output";
    private String callbackUrl = "";
    private int workerThreads = 3;
    private int queueCapacity = 128;
    private int expectedRows = 300000;
    private int initialRawPoolBytes = 64 * 1024 * 1024;
    private int initialMaskPoolBytes = 16 * 1024 * 1024;
    private int outputBufferBytes = 48 * 1024 * 1024;
    private int asyncWriteQueueSlots = 20;
    private int asyncWriteWorkerThreads = 8;
    private int cacheCapacity = 524288;
    private int requestTimeoutMillis = 120000;
    private boolean baselineValidationEnabled = false;

    public String getTeamCode() { return teamCode; }
    public void setTeamCode(String teamCode) { this.teamCode = teamCode; }
    public String getDatasetPath() { return datasetPath; }
    public void setDatasetPath(String datasetPath) { this.datasetPath = datasetPath; }
    public String getOutputDir() { return outputDir; }
    public void setOutputDir(String outputDir) { this.outputDir = outputDir; }
    public String getCallbackUrl() { return callbackUrl; }
    public void setCallbackUrl(String callbackUrl) { this.callbackUrl = callbackUrl; }
    public int getWorkerThreads() { return workerThreads; }
    public void setWorkerThreads(int workerThreads) { this.workerThreads = workerThreads; }
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
    public int getExpectedRows() { return expectedRows; }
    public void setExpectedRows(int expectedRows) { this.expectedRows = expectedRows; }
    public int getInitialRawPoolBytes() { return initialRawPoolBytes; }
    public void setInitialRawPoolBytes(int initialRawPoolBytes) { this.initialRawPoolBytes = initialRawPoolBytes; }
    public int getInitialMaskPoolBytes() { return initialMaskPoolBytes; }
    public void setInitialMaskPoolBytes(int initialMaskPoolBytes) { this.initialMaskPoolBytes = initialMaskPoolBytes; }
    public int getOutputBufferBytes() { return outputBufferBytes; }
    public void setOutputBufferBytes(int outputBufferBytes) { this.outputBufferBytes = outputBufferBytes; }
    public int getAsyncWriteQueueSlots() { return asyncWriteQueueSlots; }
    public void setAsyncWriteQueueSlots(int asyncWriteQueueSlots) { this.asyncWriteQueueSlots = asyncWriteQueueSlots; }
    public int getAsyncWriteWorkerThreads() { return asyncWriteWorkerThreads; }
    public void setAsyncWriteWorkerThreads(int asyncWriteWorkerThreads) { this.asyncWriteWorkerThreads = asyncWriteWorkerThreads; }
    public int getCacheCapacity() { return cacheCapacity; }
    public void setCacheCapacity(int cacheCapacity) { this.cacheCapacity = cacheCapacity; }
    public int getRequestTimeoutMillis() { return requestTimeoutMillis; }
    public void setRequestTimeoutMillis(int requestTimeoutMillis) { this.requestTimeoutMillis = requestTimeoutMillis; }
    public boolean isBaselineValidationEnabled() { return baselineValidationEnabled; }
    public void setBaselineValidationEnabled(boolean baselineValidationEnabled) { this.baselineValidationEnabled = baselineValidationEnabled; }
}
