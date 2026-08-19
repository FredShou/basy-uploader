package cn.ndctcm.basy.model;

/**
 * 接口1 uploadFile 响应模型
 * 示例: {"msg":"您的中成药中草药医嘱文件解析成功！...","flag":"1"}
 */
public class UploadResult {

    private String msg;
    private String flag;

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public String getFlag() {
        return flag;
    }

    public void setFlag(String flag) {
        this.flag = flag;
    }

    /**
     * flag=1 表示上传解析成功
     */
    public boolean isSuccess() {
        return "1".equals(flag);
    }

    @Override
    public String toString() {
        return "UploadResult{msg='" + msg + "', flag='" + flag + "'}";
    }
}
