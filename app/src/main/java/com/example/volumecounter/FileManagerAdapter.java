package com.example.volumecounter;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class FileManagerAdapter extends ListAdapter<FileInfo, FileManagerAdapter.FileViewHolder> {
    private static final String TAG = "FileManagerAdapter";
    private final Context context;
    private final FileActionCallback callback;
    private final RemarkCallback remarkCallback;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);
    private Map<Integer, Boolean> expandStatus = new HashMap<>(); // 存储每个位置的展开状态
    public static final String ACTION_EDIT = "edit";
    public static final String ACTION_SHARE = "share";
    public static final String ACTION_DELETE = "delete";

    // 接口定义
    public interface RemarkCallback {
        String getRemarkForFile(Uri fileUri);
    }

    public interface FileActionCallback {
        void onFileAction(FileInfo fileInfo, String action);
    }

    public FileManagerAdapter(Context context, FileActionCallback callback, RemarkCallback remarkCallback) {
        super(new DiffUtil.ItemCallback<FileInfo>() {
            @Override
            public boolean areItemsTheSame(@NonNull FileInfo oldItem, @NonNull FileInfo newItem) {
                return oldItem.getUri().equals(newItem.getUri());
            }

            @Override
            public boolean areContentsTheSame(@NonNull FileInfo oldItem, @NonNull FileInfo newItem) {
                return oldItem.getName().equals(newItem.getName()) &&
                        oldItem.getDateModified() == newItem.getDateModified();
            }
        });
        this.context = context;
        this.callback = callback;
        this.remarkCallback = remarkCallback;
    }

    @NonNull
    @Override
    public FileViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_file, parent, false);
        return new FileViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull FileViewHolder holder, int position) {
        FileInfo fileInfo = getItem(position);
        holder.fileName.setText(fileInfo.getName());
        holder.fileDate.setText(formatDate(fileInfo.getDateModified()));
        holder.fileSize.setText(formatSize(fileInfo.getSize()));
        Log.d(TAG, "显示文件: " + fileInfo.getName() + ", URI: " + fileInfo.getUri());

        // 处理备注显示
        String remark = fileInfo.getRemark();
        if (!TextUtils.isEmpty(remark)) {
            holder.fileRemark.setText(remark);
            holder.fileRemark.setVisibility(View.VISIBLE);

            // 决定是否显示展开按钮（例如：超过40个字符显示）
            if (remark.length() > 40) {
                holder.expandButton.setVisibility(View.VISIBLE);

                // 获取当前展开状态，默认为折叠
                boolean isExpanded = expandStatus.getOrDefault(position, false);

                // 设置最大行数和箭头图标
                holder.fileRemark.setMaxLines(isExpanded ? Integer.MAX_VALUE : 2);
                holder.expandButton.setImageResource(
                        isExpanded ?
                                android.R.drawable.arrow_up_float :
                                android.R.drawable.arrow_down_float
                );

                // 展开/折叠点击事件
                holder.expandButton.setOnClickListener(v -> {
                    expandStatus.put(position, !isExpanded);
                    notifyItemChanged(position);
                });
            } else {
                holder.expandButton.setVisibility(View.GONE);
                holder.fileRemark.setMaxLines(Integer.MAX_VALUE); // 短备注直接显示全部
            }
        } else {
            holder.fileRemark.setVisibility(View.GONE);
            holder.expandButton.setVisibility(View.GONE);
        }

        // 分享按钮点击事件
        holder.shareButton.setOnClickListener(v -> {
            Log.d(TAG, "触发分享: " + fileInfo.getName());
            callback.onFileAction(fileInfo, ACTION_SHARE);
        });

        // 删除按钮点击事件
        holder.deleteButton.setOnClickListener(v -> {
            Log.d(TAG, "触发删除: " + fileInfo.getName());
            new MaterialAlertDialogBuilder(context)
                    .setTitle("删除文件")
                    .setMessage("确定要删除 " + fileInfo.getName() + " 吗?")
                    .setPositiveButton("删除", (dialog, which) -> callback.onFileAction(fileInfo, ACTION_DELETE))
                    .setNegativeButton("取消", (dialog, which) -> dialog.dismiss())
                    .show();
        });

        // 点击备注区域加载备注（如果未加载）
        holder.fileRemark.setOnClickListener(v -> {
            if (TextUtils.isEmpty(fileInfo.getRemark())) {
                new Thread(() -> {
                    String loadedRemark = remarkCallback.getRemarkForFile(fileInfo.getUri());
                    fileInfo.setRemark(loadedRemark);

                    // 在主线程更新UI
                    holder.itemView.post(() -> {
                        notifyItemChanged(position);
                    });
                }).start();
            }
        });

        // 编辑按钮点击事件 - 修改为使用 callback
        holder.editButton.setOnClickListener(v -> {
            Log.d(TAG, "触发编辑: " + fileInfo.getName());
            callback.onFileAction(fileInfo, ACTION_EDIT);
        });
    }

    // 优化数据更新
    public void setFileList(List<FileInfo> fileList) {
        expandStatus.clear(); // 清空旧状态
        submitList(new ArrayList<>(fileList));
    }

    // 格式化日期
    private String formatDate(long timestampSeconds) {
        try {
            return dateFormat.format(new Date(timestampSeconds * 1000));
        } catch (Exception e) {
            Log.e(TAG, "日期格式化失败", e);
            return "未知日期";
        }
    }

    // 格式化文件大小
    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format("%.2f %sB", bytes / Math.pow(1024, exp), pre);
    }

    static class FileViewHolder extends RecyclerView.ViewHolder {
        TextView fileName, fileDate, fileSize, fileRemark;
        MaterialButton shareButton, deleteButton, editButton;
        ImageView expandButton; // 展开/折叠按钮

        FileViewHolder(@NonNull View itemView) {
            super(itemView);
            fileName = itemView.findViewById(R.id.file_name);
            fileDate = itemView.findViewById(R.id.file_date);
            fileSize = itemView.findViewById(R.id.file_size);
            fileRemark = itemView.findViewById(R.id.file_remark);
            shareButton = itemView.findViewById(R.id.share_button);
            deleteButton = itemView.findViewById(R.id.delete_button);
            expandButton = itemView.findViewById(R.id.expand_button); // 初始化展开按钮
            editButton = itemView.findViewById(R.id.edit_button);
        }
    }
}