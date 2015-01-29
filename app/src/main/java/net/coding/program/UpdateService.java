package net.coding.program;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DownloadManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.IBinder;
import android.view.WindowManager;
import android.widget.Toast;

import com.loopj.android.http.AsyncHttpClient;
import com.loopj.android.http.JsonHttpResponseHandler;

import net.coding.program.common.CustomDialog;
import net.coding.program.common.Global;
import net.coding.program.common.network.MyAsyncHttpClient;

import org.apache.http.Header;
import org.json.JSONObject;

import java.io.File;

/*
 * 该服务的作用是检查是否有新版本程序，有的话就升级
 */
public class UpdateService extends Service {

    public static final String EXTRA_BACKGROUND = "EXTRA_BACKGROUND";
    public static final String EXTRA_WIFI = "EXTRA_WIFI";
    public static final String EXTRA_DEL_OLD_APK = "EXTRA_DEL_OLD_APK";

    private boolean isChecking = false;

    private CompleteReceiver completeReceiver;

    public UpdateService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);

        if (intent.getBooleanExtra(EXTRA_DEL_OLD_APK, false)) {
            deleteOldApk();
        }

        run(intent);

        return START_REDELIVER_INTENT;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        completeReceiver = new CompleteReceiver();

        downloadManager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        registerReceiver(completeReceiver,
                new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(completeReceiver);
        super.onDestroy();
    }

    private void deleteOldApk() {
        deleteFile(getVersion());

        SharedPreferences share = getSharedPreferences("version", Context.MODE_PRIVATE);
        int jumpVersion = share.getInt("jump", 0);

        if (jumpVersion != 0) {
            deleteFile(jumpVersion);
        }
    }

    private void deleteFile(int version) {
        File path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        String fileName = UpdateInfo.makeFileName(version);
        File file = new File(path, fileName);
        if (file.exists()) {
            file.delete();
        }
    }

    private boolean checkNetwork(Intent intent) {
        if (!Global.isConnected(this)) {
            return false;
        }

        if (intent.getBooleanExtra(EXTRA_WIFI, false) &&
                !Global.isWifiConnected(this)) {
            return false;
        }

        return true;
    }

    private void run(Intent intent) {
        if (isChecking) {
            stopSelf();
            return;
        }
        isChecking = true;

        final boolean background = intent.getBooleanExtra(EXTRA_BACKGROUND, false);
        if (!checkNetwork(intent)) {
            if (!background) {
                Toast.makeText(this, "没有网络连接", Toast.LENGTH_LONG).show();
            }

            isChecking = false;
            stopSelf();
            return;
        }

        AsyncHttpClient client = MyAsyncHttpClient.createClient(this);
        client.get(this, Global.HOST + "/api/update/app", new JsonHttpResponseHandler() {

            @Override
            public void onSuccess(int statusCode, Header[] headers, JSONObject response) {
                try {

                    mUpdateInfo = new UpdateInfo(response);

                    int versionCode = getVersion();

                    if (mUpdateInfo.newVersion > versionCode) {

                        SharedPreferences share = getSharedPreferences("version", Context.MODE_PRIVATE);
                        int jumpVersion = share.getInt("jump", 0);

                        if (mUpdateInfo.newVersion > jumpVersion) {
                            showNoticeDialog();
                        }
                    } else {
                        if (!background) {
                            Toast.makeText(UpdateService.this, "你的软件已经是最新版本", Toast.LENGTH_LONG).show();
                        }
                    }
                } catch (Exception e) {
                    Global.errorLog(e);
                }
            }

            public void onFailure(int statusCode, Header[] headers, Throwable throwable, JSONObject errorResponse) {
                stopSelf();
            }

            @Override
            public void onFinish() {
                super.onFinish();
                isChecking = false;
            }
        });
    }

    private int getVersion() {
        try {
            PackageInfo pInfo = this.getPackageManager().getPackageInfo(this.getPackageName(), 0);
            return pInfo.versionCode;
        } catch (Exception e) {
            Global.errorLog(e);
        }

        return 1;
    }

    class CompleteReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            DownloadManager.Query query = new DownloadManager.Query();
            query.setFilterById(enqueue);
            Cursor cursor = downloadManager.query(query);
            if (cursor.moveToFirst()) {
                int culumnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                if (DownloadManager.STATUS_SUCCESSFUL == cursor.getInt(culumnIndex)) {
                    installApk();
                }
            }

            stopSelf();
        }
    }

    ;


    UpdateService.UpdateInfo mUpdateInfo;

    private Dialog noticeDialog;

    private DownloadManager downloadManager;
    private long enqueue = 0;

    private void showNoticeDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("软件版本更新");
        builder.setMessage(mUpdateInfo.newMessage);

        if (isDownload()) {
            builder.setPositiveButton("安装", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    installApk();
                }
            });
        } else {
            builder.setPositiveButton("下载", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if (noticeDialog.isShowing()) {
                        noticeDialog.cancel();

                        if (enqueue == 0) {

                            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(mUpdateInfo.url))
                                    .setTitle("Coding")
                                    .setDescription("下载Coding" + mUpdateInfo.versionName)
                                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, mUpdateInfo.apkName())
                                    .setVisibleInDownloadsUi(false);
                            enqueue = downloadManager.enqueue(request);
                        }
                    }
                }
            });
        }

        builder.setCancelable(false);

        if (mUpdateInfo.required == 1 || mUpdateInfo.required == 0) {
            builder.setNegativeButton("以后再说", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.dismiss();
                    stopSelf();
                }
            });

            builder.setNeutralButton("跳过", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.dismiss();
                    SharedPreferences share = getSharedPreferences("version", Context.MODE_PRIVATE);
                    SharedPreferences.Editor editor = share.edit();
                    editor.putInt("jump", mUpdateInfo.newVersion);
                    editor.commit();
                    stopSelf();
                }
            });
        }

        noticeDialog = builder.create();
        noticeDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        noticeDialog.show();
        CustomDialog.dialogTitleLineColor(this, noticeDialog);
    }


    private boolean isDownload() {
        return mUpdateInfo.apkFile().exists();
    }

    private void installApk() {
        File apkfile = mUpdateInfo.apkFile();
        if (!apkfile.exists()) {
            stopSelf();
            return;
        }

        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        i.setDataAndType(Uri.parse("file://" + apkfile.toString()), "application/vnd.android.package-archive");
        startActivity(i);

        stopSelf();
    }

    public static class UpdateInfo {
        public int newVersion;
        public String newMessage;
        public int required;
        public String url;
        public String versionName;

        public UpdateInfo(JSONObject response) {
            newVersion = response.optInt("build");
            newMessage = response.optString("message");
            required = response.optInt("required");
            url = response.optString("url");
            versionName = response.optString("version");
        }

        public static String makeFileName(int version) {
            return "coding" + version + ".apk";
        }

        public String apkName() {
            return makeFileName(newVersion);
        }

        public File apkFile() {
            return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), apkName());
        }
    }
}