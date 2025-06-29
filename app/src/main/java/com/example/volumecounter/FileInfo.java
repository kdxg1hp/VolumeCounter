package com.example.volumecounter;

import android.net.Uri;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class FileInfo {
    private final String name;
    private final long dateModified;
    private final long size;
    private final Uri uri;
    private String remark; // 新增备注字段

    public FileInfo(String name, long dateModified, long size, Uri uri, String remark) {
        this.name = name;
        this.dateModified = dateModified;
        this.size = size;
        this.uri = uri;
        this.remark = remark; // 初始化备注
    }

    public String getName() { return name; }
    public long getDateModified() { return dateModified; }
    public long getSize() { return size; }
    public Uri getUri() { return uri; }
    public String getRemark() { return remark; } // 新增备注getter
    // 添加 setter 方法
    public void setRemark(String remark) {
        this.remark = remark;
    }

    public String getFormattedDate() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new Date(dateModified * 1000));
    }

    public String getFormattedSize() {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        return String.format("%.1f MB", size / (1024.0 * 1024));
    }
}