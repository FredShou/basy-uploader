package cn.ndctcm.basy.model;

import java.util.List;

/**
 * 接口2 logList 响应模型
 * 示例: {"pageNo":1,"pageSize":1,"rows":[{...}],"total":409}
 */
public class LogListResult {

    private int pageNo;
    private int pageSize;
    private List<LogItem> rows;
    private int total;

    public int getPageNo() {
        return pageNo;
    }

    public void setPageNo(int pageNo) {
        this.pageNo = pageNo;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public List<LogItem> getRows() {
        return rows;
    }

    public void setRows(List<LogItem> rows) {
        this.rows = rows;
    }

    public int getTotal() {
        return total;
    }

    public void setTotal(int total) {
        this.total = total;
    }
}
