package com.example.volumecounter;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity implements
        FileManagerAdapter.RemarkCallback,
        FileManagerAdapter.FileActionCallback {
    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_STORAGE = 100;
    private static final int REQUEST_MANAGE_ALL_FILES = 101;
    private static final String PREFS_NAME = "ScoreCounterPrefs";
    private static final String KEY_SCORE = "currentScore";
    private static final String REMARK_PREFIX = "#REMARK:"; // 备注前缀
    private static final String ACTION_EDIT = "edit";
    private static final String ACTION_SHARE = "share";
    private static final String ACTION_DELETE = "delete";

    // UI组件
    private TextView scoreTextView, timerTextView;
    private MaterialButton startButton, endButton, fileManagerButton;
    private MaterialButton increaseBtn, decreaseBtn, resetBtn, remarkButton;

    // 数据记录
    private int currentScore = 0;
    private long startTime = 0;         // 记录开始时间（绝对时间）
    private long relativeStartTime = 0; // 记录开始时的相对时间基准
    private boolean isRecording = false;
    private List<KeyPressRecord> keyPressRecords = new ArrayList<>();
    private Handler handler = new Handler();
    private boolean isDebouncing = false;

    // 文件管理
    private FileManagerAdapter fileManagerAdapter;
    private RecyclerView fileRecyclerView;
    private String currentRemark = ""; // 当前备注
    private Map<Uri, String> remarkCache = new HashMap<>(); // 备注缓存

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 检查存储权限
        checkStoragePermission();

        // 针对MIUI系统的特殊处理
        handleMIUIPermission();

        // 初始化组件
        initViews();

        // 恢复分数
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        currentScore = prefs.getInt(KEY_SCORE, 0);
        updateScoreDisplay();
    }

    private void requestLegacyStoragePermission() {
        if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            // 向用户解释为什么需要这个权限
            new MaterialAlertDialogBuilder(this)
                    .setTitle("权限请求")
                    .setMessage("为了保存记录，需要存储权限")
                    .setPositiveButton("确定", (dialog, which) -> {
                        // 请求权限
                        ActivityCompat.requestPermissions(this,
                                new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                                PERMISSION_REQUEST_STORAGE);
                    })
                    .setNegativeButton("取消", (dialog, which) -> dialog.dismiss())
                    .show();
        } else {
            // 直接请求权限
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    PERMISSION_REQUEST_STORAGE);
        }
    }

    private boolean checkStoragePermission() {
        boolean hasPermission = false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ 检查全部文件访问权限
            hasPermission = Environment.isExternalStorageManager();
            if (!hasPermission) {
                showStoragePermissionDialog();
            } else {
                Log.d(TAG, "已获取全部文件访问权限");
                Toast.makeText(this, "存储权限已获取", Toast.LENGTH_SHORT).show();
            }
        } else {
            // Android 10及以下检查传统存储权限
            hasPermission = ContextCompat.checkSelfPermission(this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;

            if (!hasPermission) {
                requestLegacyStoragePermission();
            } else {
                Log.d(TAG, "已获取传统存储权限");
                Toast.makeText(this, "存储权限已获取", Toast.LENGTH_SHORT).show();
            }
        }

        return hasPermission;
    }

    private void showStoragePermissionDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("存储权限")
                .setMessage("为了保存记录，需要授予\"管理所有文件\"权限\n\n" +
                        "操作步骤：\n" +
                        "1. 点击\"前往授权\"\n" +
                        "2. 找到\"" + getString(R.string.app_name) + "\"\n" +
                        "3. 打开\"管理所有文件\"开关")
                .setPositiveButton("前往授权", (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivityForResult(intent, REQUEST_MANAGE_ALL_FILES);
                })
                .setNegativeButton("取消", (dialog, which) -> {
                    dialog.dismiss();
                    Toast.makeText(this, "没有存储权限将无法保存记录", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private boolean isMIUIVersion() {
        String manufacturer = Build.MANUFACTURER.toLowerCase();
        return manufacturer.contains("xiaomi");
    }

    private void handleMIUIPermission() {
        if (isMIUIVersion() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                new MaterialAlertDialogBuilder(this)
                        .setTitle("MIUI系统权限")
                        .setMessage("检测到您使用的是MIUI系统，需要额外的存储权限\n\n" +
                                "1. 点击\"前往设置\"\n" +
                                "2. 打开\"管理所有文件\"权限\n" +
                                "3. 返回应用重试")
                        .setPositiveButton("前往设置", (dialog, which) -> {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                            startActivityForResult(intent, REQUEST_MANAGE_ALL_FILES);
                        })
                        .setNegativeButton("取消", (dialog, which) -> {
                            dialog.dismiss();
                            Toast.makeText(this, "没有存储权限将无法保存记录", Toast.LENGTH_SHORT).show();
                        })
                        .show();
            }
        }
    }

    private void createDirectoryIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            File directory = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOCUMENTS), "ScoreRecords");
            if (!directory.exists()) {
                if (directory.mkdirs()) {
                    Log.d(TAG, "目录创建成功: " + directory.getAbsolutePath());
                } else {
                    Log.e(TAG, "目录创建失败: " + directory.getAbsolutePath());
                }
            }
        }
    }

    private void initViews() {
        scoreTextView = findViewById(R.id.score_text);
        timerTextView = findViewById(R.id.timer_text);
        startButton = findViewById(R.id.start_button);
        endButton = findViewById(R.id.end_button);
        fileManagerButton = findViewById(R.id.file_manager_button);
        increaseBtn = findViewById(R.id.increase_btn);
        decreaseBtn = findViewById(R.id.decrease_btn);
        resetBtn = findViewById(R.id.reset_btn);
        remarkButton = findViewById(R.id.remark_button);

        // 设置按钮监听器
        startButton.setOnClickListener(v -> startRecording());
        endButton.setOnClickListener(v -> endRecording());
        fileManagerButton.setOnClickListener(v -> showFileManager());
        increaseBtn.setOnClickListener(v -> safeIncreaseScore());
        decreaseBtn.setOnClickListener(v -> safeDecreaseScore());
        resetBtn.setOnClickListener(v -> resetScore());
        remarkButton.setOnClickListener(v -> showRemarkDialog());

        // 设置按键监听器
        View rootView = findViewById(android.R.id.content);
        rootView.setFocusableInTouchMode(true);
        rootView.requestFocus();
        rootView.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && !isDebouncing) {
                switch (keyCode) {
                    case KeyEvent.KEYCODE_VOLUME_UP:
                        safeIncreaseScore();
                        return true;
                    case KeyEvent.KEYCODE_VOLUME_DOWN:
                        safeDecreaseScore();
                        return true;
                }
            }
            return false;
        });

        // 更新备注按钮状态
        updateRemarkButtonState();
    }

    private void showRemarkDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle("添加备注");

        // 创建输入框并设置当前备注
        final EditText remarkEditText = new EditText(this);
        remarkEditText.setHint("请输入本次记录的备注信息");
        remarkEditText.setText(currentRemark);
        remarkEditText.setSelection(currentRemark.length()); // 光标放在文本末尾

        builder.setView(remarkEditText);

        // 设置按钮
        builder.setPositiveButton("确定", (dialog, which) -> {
            currentRemark = remarkEditText.getText().toString().trim();
            updateRemarkButtonState();
            Toast.makeText(this, "备注已更新", Toast.LENGTH_SHORT).show();
        });

        builder.setNegativeButton("取消", (dialog, which) -> dialog.dismiss());

        // 显示对话框
        builder.show();
    }

    @Override
    public String getRemarkForFile(Uri fileUri) {
        return extractRemarkFromFile(fileUri);
    }

    private String extractRemarkFromFile(Uri uri) {
        // 先检查缓存
        if (remarkCache.containsKey(uri)) {
            return remarkCache.get(uri);
        }

        // 读取文件
        try (InputStream inputStream = getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {

            String firstLine = reader.readLine();
            if (firstLine != null && firstLine.startsWith(REMARK_PREFIX)) {
                String remark = firstLine.substring(REMARK_PREFIX.length());
                remarkCache.put(uri, remark); // 存入缓存
                return remark;
            }
        } catch (Exception e) {
            Log.e(TAG, "读取备注信息失败: " + e.getMessage());
        }

        remarkCache.put(uri, ""); // 存入空值避免重复读取
        return "";
    }

    private void updateRemarkButtonState() {
        if (remarkButton != null) {
            if (!currentRemark.isEmpty()) {
                remarkButton.setText("已添加备注");
                // 假设存在编辑图标资源
                remarkButton.setIconResource(R.drawable.baseline_edit_note_24);
            } else {
                remarkButton.setText("添加备注");
                // 假设存在添加图标资源
                remarkButton.setIconResource(R.drawable.baseline_note_add_24);
            }
        }
    }

    private void startRecording() {
        if (!isRecording) {
            if (currentRemark.isEmpty()) {
                // 提示用户没有备注
                Toast.makeText(this, "没有设置备注，记录将不包含说明信息", Toast.LENGTH_SHORT).show();
            }

            // 记录开始时间（绝对时间）
            startTime = System.currentTimeMillis();
            // 记录开始时的相对时间基准（从0开始）
            relativeStartTime = SystemClock.elapsedRealtime();

            isRecording = true;
            startButton.setEnabled(false);
            endButton.setEnabled(true);
            updateTimerDisplay(0);

            // 启动计时器
            handler.postDelayed(timerRunnable, 1000);

            // 开始记录时记录当前分数和时间
            recordCurrentScoreAtStart();

            Toast.makeText(this, "开始记录打分", Toast.LENGTH_SHORT).show();
        }
    }

    private void recordCurrentScoreAtStart() {
        if (isRecording) {
            // 使用相对时间（毫秒）
            long relativeTime = SystemClock.elapsedRealtime() - relativeStartTime;
            keyPressRecords.add(new KeyPressRecord(relativeTime, currentScore, "START_RECORD"));
            Log.d(TAG, "开始记录时记录: 分数=" + currentScore + ", 相对时间=" + relativeTime);
        }
    }

    private void endRecording() {
        if (isRecording) {
            isRecording = false;
            startButton.setEnabled(true);
            endButton.setEnabled(false);
            handler.removeCallbacks(timerRunnable);
            timerTextView.setText("已结束");

            // 结束记录时记录当前分数和时间
            if (!keyPressRecords.isEmpty()) {
                long relativeTime = SystemClock.elapsedRealtime() - relativeStartTime;
                keyPressRecords.add(new KeyPressRecord(relativeTime, currentScore, "END_RECORD"));
            }

            // 保存记录
            saveRecordsToCsv();

            Toast.makeText(this, "记录已保存，备注已保留", Toast.LENGTH_SHORT).show();
        }
    }

    private Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (isRecording) {
                long elapsedTime = SystemClock.elapsedRealtime() - relativeStartTime;
                updateTimerDisplay(elapsedTime / 1000);
                handler.postDelayed(this, 1000);
            }
        }
    };

    private void updateTimerDisplay(long seconds) {
        int minutes = (int) (seconds / 60);
        int secs = (int) (seconds % 60);
        timerTextView.setText(String.format(Locale.CHINA, "用时: %02d:%02d", minutes, secs));
    }

    private void safeIncreaseScore() {
        if (!isDebouncing) {
            isDebouncing = true;
            increaseScore();
            handler.postDelayed(() -> isDebouncing = false, 100);
        }
    }

    private void safeDecreaseScore() {
        if (!isDebouncing && currentScore > 0) {
            isDebouncing = true;
            decreaseScore();
            handler.postDelayed(() -> isDebouncing = false, 100);
        }
    }

    private void increaseScore() {
        currentScore++;
        updateScoreDisplay();
        saveState();

        if (isRecording) {
            long relativeTime = SystemClock.elapsedRealtime() - relativeStartTime;
            keyPressRecords.add(new KeyPressRecord(relativeTime, currentScore, "INCREASE"));
        }
    }

    private void decreaseScore() {
        currentScore--;
        updateScoreDisplay();
        saveState();

        if (isRecording) {
            long relativeTime = SystemClock.elapsedRealtime() - relativeStartTime;
            keyPressRecords.add(new KeyPressRecord(relativeTime, currentScore, "DECREASE"));
        }
    }

    private void resetScore() {
        currentScore = 0;
        updateScoreDisplay();
        saveState();

        if (isRecording) {
            long relativeTime = SystemClock.elapsedRealtime() - relativeStartTime;
            keyPressRecords.add(new KeyPressRecord(relativeTime, currentScore, "RESET"));
        }
    }

    private void updateScoreDisplay() {
        scoreTextView.setText("分数: " + currentScore);
    }

    private void saveState() {
        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        editor.putInt(KEY_SCORE, currentScore);
        editor.apply();
    }

    private void saveRecordsToCsv() {
        if (keyPressRecords.isEmpty()) {
            Toast.makeText(this, "没有记录可保存", Toast.LENGTH_SHORT).show();
            return;
        }

        // 强制检查权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            Toast.makeText(this, "请先授予存储权限", Toast.LENGTH_SHORT).show();
            showStoragePermissionDialog();
            return;
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "请先授予存储权限", Toast.LENGTH_SHORT).show();
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    PERMISSION_REQUEST_STORAGE);
            return;
        }

        saveFileWithMediaStore();
    }

    private void saveFileWithMediaStore() {
        createDirectoryIfNeeded(); // 确保目录存在

        ContentResolver contentResolver = getContentResolver();
        ContentValues contentValues = new ContentValues();

        // 设置文件名和MIME类型
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA);
        String fileName = "score_records_" + sdf.format(new Date()) + ".csv";
        Log.d(TAG, "准备保存文件: " + fileName);

        contentValues.put(MediaStore.Files.FileColumns.DISPLAY_NAME, fileName);
        contentValues.put(MediaStore.Files.FileColumns.MIME_TYPE, "text/csv");

        // 根据Android版本选择存储位置
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.put(MediaStore.Files.FileColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOCUMENTS + "/ScoreRecords");
        } else {
            contentValues.put(MediaStore.Files.FileColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/ScoreRecords");
        }

        // 获取内容URI
        Uri uri = contentResolver.insert(
                MediaStore.Files.getContentUri("external"),
                contentValues
        );

        if (uri != null) {
            Log.d(TAG, "生成文件URI: " + uri.toString());

            try (OutputStream outputStream = contentResolver.openOutputStream(uri);
                 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream))) {

                // 写入备注信息（如果有）
                if (!currentRemark.isEmpty()) {
                    writer.write(REMARK_PREFIX + currentRemark);
                    writer.newLine();
                }

                // 写入表头（修改时间戳列为相对时间）
                writer.write("相对时间(毫秒),分数,操作类型,时间(秒)");
                writer.newLine();

                // 写入记录（使用相对时间）
                for (KeyPressRecord record : keyPressRecords) {
                    SimpleDateFormat timeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);
                    String timeStr = timeFormat.format(new Date(startTime + record.relativeTime));
                    writer.write(record.relativeTime + "," + record.score + "," + record.action + "," + timeStr);
                    writer.newLine();
                }

                Log.d(TAG, "文件写入成功");

                // 验证文件是否真的存在
                if (verifyFileExists(uri)) {
                    Toast.makeText(this, "记录已保存至: " + fileName, Toast.LENGTH_LONG).show();
                } else {
                    Log.w(TAG, "文件存在性验证失败，但写入操作未抛出异常");
                    Toast.makeText(this, "文件保存成功，但可能无法立即访问", Toast.LENGTH_SHORT).show();
                }

                // 刷新媒体库（仅针对 Android 9 及以下版本）
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                    mediaScanIntent.setData(uri);
                    sendBroadcast(mediaScanIntent);
                } else {
                    Log.d(TAG, "Android 10+ 无需手动触发媒体扫描，MediaStore 会自动索引文件");
                }

            } catch (FileNotFoundException e) {
                Log.e(TAG, "文件未找到异常: " + e.getMessage(), e);
                Toast.makeText(this, "保存失败: 文件未找到", Toast.LENGTH_SHORT).show();
            } catch (IOException e) {
                Log.e(TAG, "文件写入失败: " + e.getMessage(), e);
                Toast.makeText(this, "保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            } finally {
                keyPressRecords.clear();
                updateRemarkButtonState(); // 更新备注按钮状态
            }
        } else {
            Log.e(TAG, "无法生成文件URI");
            Toast.makeText(this, "无法创建文件", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean verifyFileExists(Uri uri) {
        ContentResolver contentResolver = getContentResolver();
        Cursor cursor = null;

        try {
            // 尝试查询文件
            cursor = contentResolver.query(uri, null, null, null, null);
            boolean exists = cursor != null && cursor.moveToFirst();

            if (exists) {
                Log.d(TAG, "文件存在性验证通过: " + uri);

                // 获取文件大小，进一步验证
                int sizeIndex = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE);
                if (sizeIndex >= 0) {
                    long size = cursor.getLong(sizeIndex);
                    Log.d(TAG, "文件大小: " + size + " 字节");
                }

                // 尝试打开文件流
                try (InputStream inputStream = contentResolver.openInputStream(uri)) {
                    Log.d(TAG, "文件流可打开，验证完成");
                } catch (Exception e) {
                    Log.w(TAG, "文件流打开失败: " + e.getMessage());
                    exists = false;
                }
            } else {
                Log.w(TAG, "文件不存在或无法访问: " + uri);

                // 尝试通过传统文件路径检查
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    String filePath = getRealPathFromUri(uri);
                    if (filePath != null) {
                        File file = new File(filePath);
                        if (file.exists()) {
                            Log.d(TAG, "通过传统路径验证文件存在: " + filePath);
                            exists = true;
                        }
                    }
                }
            }

            return exists;
        } catch (Exception e) {
            Log.e(TAG, "验证文件存在性异常: " + e.getMessage(), e);
            return false;
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private String getRealPathFromUri(Uri uri) {
        if (uri == null) return null;

        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri,
                    new String[]{MediaStore.Files.FileColumns.DATA},
                    null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                // 获取列索引并检查是否有效
                int dataColumnIndex = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA);
                if (dataColumnIndex >= 0) {
                    return cursor.getString(dataColumnIndex);
                } else {
                    Log.w(TAG, "无法找到数据列: MediaStore.Files.FileColumns.DATA");
                    return null;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "获取文件路径失败: " + e.getMessage(), e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return null;
    }

    private void showFileManager() {
        // 创建文件管理对话框
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_file_manager, null);
        builder.setView(dialogView);
        builder.setTitle("文件管理");
        builder.setNegativeButton("关闭", (dialog, which) -> dialog.dismiss());

        // 初始化文件列表
        fileRecyclerView = dialogView.findViewById(R.id.file_recycler_view);
        fileRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        fileManagerAdapter = new FileManagerAdapter(this, this, this);
        fileRecyclerView.setAdapter(fileManagerAdapter);

        // 加载文件
        loadFiles();

        builder.show();
    }

    private void loadFiles() {
        ContentResolver contentResolver = getContentResolver();
        String[] projection = {
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.DATE_MODIFIED,
                MediaStore.Files.FileColumns.SIZE
        };

        // 根据Android版本设置不同的查询条件
        String directory = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ?
                Environment.DIRECTORY_DOCUMENTS :
                Environment.DIRECTORY_DOWNLOADS;

        String selection = MediaStore.Files.FileColumns.RELATIVE_PATH + " LIKE ?";
        String[] selectionArgs = { "%" + directory + "/ScoreRecords%" };

        Cursor cursor = null;
        ArrayList<FileInfo> fileList = new ArrayList<>();

        try {
            // 执行查询
            cursor = contentResolver.query(
                    MediaStore.Files.getContentUri("external"),
                    projection,
                    selection,
                    selectionArgs,
                    MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC"
            );

            if (cursor != null) {
                Log.d(TAG, "查询到 " + cursor.getCount() + " 个文件");

                // 获取列索引（添加有效性检查）
                int idColumn = cursor.getColumnIndex(MediaStore.Files.FileColumns._ID);
                int nameColumn = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME);
                int dateColumn = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED);
                int sizeColumn = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE);

                // 检查所有列索引是否有效
                if (idColumn == -1 || nameColumn == -1 || dateColumn == -1 || sizeColumn == -1) {
                    Log.e(TAG, "查询结果缺少必要的列");
                    return;
                }

                int preloadCount = 0;
                // 遍历结果集
                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idColumn);
                    String name = cursor.getString(nameColumn);
                    long dateModified = cursor.getLong(dateColumn);
                    long size = cursor.getLong(sizeColumn);

                    Uri uri = ContentUris.withAppendedId(
                            MediaStore.Files.getContentUri("external"), id);

                    // 预加载前3个文件的备注
                    String remark = preloadCount < 3 ? extractRemarkFromFile(uri) : "";
                    fileList.add(new FileInfo(name, dateModified, size, uri, remark));

                    preloadCount++;
                }
            } else {
                Log.e(TAG, "查询结果为null，可能权限不足或查询条件错误");
            }
        } catch (Exception e) {
            Log.e(TAG, "加载文件列表失败", e);
            Toast.makeText(this, "无法加载文件列表", Toast.LENGTH_SHORT).show();
        } finally {
            if (cursor != null) cursor.close();
        }

        // 更新适配器
        Log.d(TAG, "更新适配器，文件数量: " + fileList.size());
        fileManagerAdapter.setFileList(fileList);
        // 注意：ListAdapter 不需要手动调用 notifyDataSetChanged()
    }

    @Override
    public void onFileAction(FileInfo fileInfo, String action) {
        Log.d(TAG, "文件操作: " + action + ", 文件: " + fileInfo.getName());
        if (action.equals(ACTION_SHARE)) {
            shareFile(fileInfo.getUri());
        } else if (action.equals(ACTION_DELETE)) {
            deleteFile(fileInfo.getUri());
        } else if (action.equals(ACTION_EDIT)) { // 新增编辑处理
            editFile(fileInfo.getUri());
        }
    }

    private void shareFile(Uri fileUri) {
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/csv");
        shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(shareIntent, "分享文件"));
    }

    private void deleteFile(Uri fileUri) {
        try {
            int rowsDeleted = getContentResolver().delete(fileUri, null, null);
            if (rowsDeleted > 0) {
                Log.d(TAG, "文件删除成功: " + fileUri);
                Toast.makeText(this, "文件已删除", Toast.LENGTH_SHORT).show();
                loadFiles(); // 刷新文件列表
            } else {
                Log.w(TAG, "文件删除失败: " + fileUri);
                Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "删除文件失败", e);
            Toast.makeText(this, "删除失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void editFile(Uri fileUri) {
        // 检查权限
        if (!checkStoragePermission()) {
            return;
        }

        // 读取文件内容
        new Thread(() -> {
            String content = readFileContent(fileUri);
            if (content != null) {
                runOnUiThread(() -> showEditDialog(fileUri, content));
            } else {
                runOnUiThread(() -> Toast.makeText(this, "无法读取文件内容", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private String readFileContent(Uri fileUri) {
        try (InputStream inputStream = getContentResolver().openInputStream(fileUri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {

            StringBuilder content = new StringBuilder();
            String line;
            boolean isFirstLine = true;

            while ((line = reader.readLine()) != null) {
                // 处理备注行
                if (isFirstLine && line.startsWith(REMARK_PREFIX)) {
                    content.append("备注: ").append(line.substring(REMARK_PREFIX.length())).append("\n\n");
                } else {
                    content.append(line).append("\n");
                }
                isFirstLine = false;
            }
            return content.toString();

        } catch (IOException e) {
            Log.e(TAG, "读取文件失败: " + e.getMessage());
            return null;
        }
    }

    private void showEditDialog(Uri fileUri, String content) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle("编辑记录");

        // 创建多行文本编辑框
        final EditText editText = new EditText(this);
        editText.setMinLines(10);
        editText.setMaxLines(20);
        editText.setVerticalScrollBarEnabled(true);
        editText.setHorizontallyScrolling(false);
        editText.setText(content);
        editText.setSelection(content.length()); // 光标放在末尾

        builder.setView(editText);

        builder.setPositiveButton("保存", (dialog, which) -> {
            String newContent = editText.getText().toString();
            saveEditedFile(fileUri, newContent);
        });

        builder.setNegativeButton("取消", (dialog, which) -> dialog.dismiss());

        builder.show();
    }

    private void saveEditedFile(Uri fileUri, String content) {
        new Thread(() -> {
            try (OutputStream outputStream = getContentResolver().openOutputStream(fileUri);
                 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream))) {

                // 解析内容中的备注（第一行以"备注: "开头）
                String[] lines = content.split("\n");
                boolean hasRemark = lines.length > 0 && lines[0].startsWith("备注: ");

                if (hasRemark) {
                    // 写入备注行
                    writer.write(REMARK_PREFIX + lines[0].substring(3));
                    writer.newLine();

                    // 写入剩余内容
                    for (int i = 1; i < lines.length; i++) {
                        writer.write(lines[i]);
                        writer.newLine();
                    }
                } else {
                    // 没有备注，直接写入全部内容
                    writer.write(content);
                }

                writer.flush();

                runOnUiThread(() -> {
                    Toast.makeText(this, "文件已更新", Toast.LENGTH_SHORT).show();
                    // 刷新文件列表
                    if (fileManagerAdapter != null) {
                        loadFiles();
                    }
                });

            } catch (IOException e) {
                Log.e(TAG, "保存文件失败: " + e.getMessage());
                runOnUiThread(() -> Toast.makeText(this, "保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "用户授予了存储权限");
                Toast.makeText(this, "存储权限已授予", Toast.LENGTH_SHORT).show();
                createDirectoryIfNeeded();
            } else {
                Log.w(TAG, "用户拒绝了存储权限");
                Toast.makeText(this, "存储权限被拒绝，无法保存记录", Toast.LENGTH_SHORT).show();

                // 检查用户是否勾选了"不再询问"
                boolean showRationale = ActivityCompat.shouldShowRequestPermissionRationale(
                        this, Manifest.permission.WRITE_EXTERNAL_STORAGE);

                if (!showRationale) {
                    // 用户勾选了"不再询问"，显示设置对话框
                    new MaterialAlertDialogBuilder(this)
                            .setTitle("权限被拒绝")
                            .setMessage("您已拒绝存储权限，需要在设置中手动授予权限才能保存记录")
                            .setPositiveButton("前往设置", (dialog, which) -> {
                                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                                Uri uri = Uri.fromParts("package", getPackageName(), null);
                                intent.setData(uri);
                                startActivity(intent);
                            })
                            .setNegativeButton("取消", (dialog, which) -> dialog.dismiss())
                            .show();
                }
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MANAGE_ALL_FILES) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    Log.d(TAG, "用户授予了所有文件访问权限");
                    Toast.makeText(this, "所有文件访问权限已获取", Toast.LENGTH_SHORT).show();
                    createDirectoryIfNeeded();
                } else {
                    Log.w(TAG, "用户拒绝了所有文件访问权限");
                    Toast.makeText(this, "所有文件访问权限被拒绝，无法保存记录", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    // 修改 KeyPressRecord 类，使用 relativeTime 代替 timestamp
    private static class KeyPressRecord {
        long relativeTime;  // 相对时间（毫秒，从记录开始时计算）
        int score;          // 当前分数
        String action;      // 操作类型（INCREASE/DECREASE/RESET）

        public KeyPressRecord(long relativeTime, int score, String action) {
            this.relativeTime = relativeTime;
            this.score = score;
            this.action = action;
        }

        // Getters
        public long getRelativeTime() {
            return relativeTime;
        }

        public int getScore() {
            return score;
        }

        public String getAction() {
            return action;
        }
    }
}