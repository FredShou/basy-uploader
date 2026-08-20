package cn.ndctcm.basy.model;

/**
 * 接口2 logList 返回的单条日志记录
 * 对应响应 rows 数组中的元素
 */
public class LogItem {

    private String logId;
    private int qcCount;
    private int sjkQcCount;
    private int rkCount;
    private int tgljjyCount;
    private int ljjyerrorCount;
    private int errorCount;
    private String errorLogs;
    private double fileSize;
    private String isAnalysis;
    private String isSucceed;
    private String newFileName;
    private String oldFileName;
    private String orgCode;
    private String serverFlag;
    private int totalCount;
    private String uploadDate;
    private String uploadUserId;
    private int veriErrorCount;
    private String veriErrorLogs;
    private String jkdbErrorLogs;
    private int jkdbCount;
    private int jkdbErrorCount;

    public String getLogId() {
        return logId;
    }

    public void setLogId(String logId) {
        this.logId = logId;
    }

    public int getErrorCount() {
        return errorCount;
    }

    public void setErrorCount(int errorCount) {
        this.errorCount = errorCount;
    }

    public String getIsAnalysis() {
        return isAnalysis;
    }

    public void setIsAnalysis(String isAnalysis) {
        this.isAnalysis = isAnalysis;
    }

    public String getIsSucceed() {
        return isSucceed;
    }

    public void setIsSucceed(String isSucceed) {
        this.isSucceed = isSucceed;
    }

    public String getOldFileName() {
        return oldFileName;
    }

    public void setOldFileName(String oldFileName) {
        this.oldFileName = oldFileName;
    }

    public String getNewFileName() {
        return newFileName;
    }

    public void setNewFileName(String newFileName) {
        this.newFileName = newFileName;
    }

    public int getTotalCount() {
        return totalCount;
    }

    public void setTotalCount(int totalCount) {
        this.totalCount = totalCount;
    }

    public String getUploadDate() {
        return uploadDate;
    }

    public void setUploadDate(String uploadDate) {
        this.uploadDate = uploadDate;
    }

    public int getVeriErrorCount() {
        return veriErrorCount;
    }

    public void setVeriErrorCount(int veriErrorCount) {
        this.veriErrorCount = veriErrorCount;
    }

    public String getOrgCode() {
        return orgCode;
    }

    public void setOrgCode(String orgCode) {
        this.orgCode = orgCode;
    }

    public double getFileSize() {
        return fileSize;
    }

    public void setFileSize(double fileSize) {
        this.fileSize = fileSize;
    }

    public int getJkdbCount() {
        return jkdbCount;
    }

    public void setJkdbCount(int jkdbCount) {
        this.jkdbCount = jkdbCount;
    }

    public int getJkdbErrorCount() {
        return jkdbErrorCount;
    }

    public void setJkdbErrorCount(int jkdbErrorCount) {
        this.jkdbErrorCount = jkdbErrorCount;
    }

    @Override
    public String toString() {
        return "LogItem{logId='" + logId + "', oldFileName='" + oldFileName
                + "', isAnalysis='" + isAnalysis + "', isSucceed='" + isSucceed
                + "', errorCount=" + errorCount + ", totalCount=" + totalCount
                + ", uploadDate='" + uploadDate + "'}";
    }
}
