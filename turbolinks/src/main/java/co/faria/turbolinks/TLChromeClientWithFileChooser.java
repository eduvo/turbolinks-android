package co.faria.turbolinks;

import static android.Manifest.permission.MODIFY_AUDIO_SETTINGS;
import static android.Manifest.permission.READ_EXTERNAL_STORAGE;
import static android.Manifest.permission.RECORD_AUDIO;
import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;
import static android.Manifest.permission_group.CAMERA;
import static android.app.Activity.RESULT_OK;
import static android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

public class TLChromeClientWithFileChooser extends WebChromeClient implements ActivityResultListener {

    private final int FILE_CHOOSER_REQUEST_CODE = 1001;
    private final int AUDIO_RECORDER_REQUEST_CODE = 1002;
    private final int CAMERA_REQUEST_CODE = 1003;
    private final int VIDEO_REQUEST_CODE = 1004;
    private final int EXTERNAL_REQUEST_CODE = 1005;
    private final int OTHER_PERMISSION_REQUEST_CODE = 1009;

    private Activity activity;
    private final TurbolinksAdapter turbolinksAdapter;
    private ValueCallback<Uri[]> filePathCallback;
    private Uri mediaUri;

    public Boolean openExternalURL(Activity activity, String urlString) {
        try {
            URL url = new URL(urlString);
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://"));
            List<ResolveInfo> activities = activity.getPackageManager().queryIntentActivities(browserIntent, PackageManager.MATCH_DEFAULT_ONLY);
            String packageName = null;
            if (!activities.isEmpty()) {
                packageName = activities.get(0).activityInfo.packageName;
            }
            try {
                Intent openURLIntent = new Intent(Intent.ACTION_VIEW);
                if (packageName != null) {
                    openURLIntent.setPackage(packageName);
                }
                openURLIntent.setData(Uri.parse(urlString));
                activity.startActivity(openURLIntent);
                return true;
            } catch (ActivityNotFoundException ex) {
                Log.e("TLChromeClient", "Could not open browser for: " + urlString);
            }
        } catch (Exception e) {
            Log.e("TLChromeClient", "Invalid URL: " + urlString);
        }
        return false;
    }

    @Override
    public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
        final WebView sourceWebView = view;
        WebView targetWebView = new WebView(this.activity); // pass a context
        targetWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (url != null && view.getUrl() != null) {
                    Uri current = Uri.parse(view.getUrl());
                    if (url.getHost() != null && (url.getHost().compareToIgnoreCase(current.getHost()) == 0) && turbolinksAdapter != null) {
                        turbolinksAdapter.visitProposedToLocationWithAction(url.toString(), "advance");
                    } else {
                        openExternalURL(activity, url.toString());
                    }
                }
                return true; // return false if you want the load to continue
            }
        });
        WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
        transport.setWebView(targetWebView);
        resultMsg.sendToTarget();
        return true;
    }

    public TLChromeClientWithFileChooser(Activity activity, TurbolinksAdapter turbolinksAdapter) {
        this.activity = activity;
        this.turbolinksAdapter = turbolinksAdapter;

        if ((this.activity != null) && (this.activity instanceof ActivityResultListenerRegistry)) {
            ((ActivityResultListenerRegistry) this.activity).registerActivityResultListener(this);
        }
    }

    @Override
    public boolean onJsConfirm(WebView view, String url, String message, final JsResult result) {
        new AlertDialog.Builder(view.getContext())
                .setMessage(message)
                .setPositiveButton(android.R.string.ok,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                result.confirm();
                            }
                        })
                .setNegativeButton(android.R.string.cancel,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                result.cancel();
                            }
                        })
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialog) {
                        result.cancel();
                    }
                })
                .create()
                .show();
        return true;
    }

    @Override
    public void onPermissionRequest(PermissionRequest request) {
        for (String permission : request.getResources()) {
            // for audio also request modify audio settings
            if (permission == PermissionRequest.RESOURCE_AUDIO_CAPTURE) {
                if ((this.activity.checkSelfPermission(RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                        || (this.activity.checkSelfPermission(MODIFY_AUDIO_SETTINGS) != PackageManager.PERMISSION_GRANTED)) {
                    this.activity.requestPermissions(new String[]{RECORD_AUDIO, MODIFY_AUDIO_SETTINGS}, AUDIO_RECORDER_REQUEST_CODE);
                    request.deny();
                    return;
                }
            } else {
                if (this.activity.checkSelfPermission(RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    this.activity.requestPermissions(new String[]{permission}, OTHER_PERMISSION_REQUEST_CODE);
                    request.deny();
                    return;
                }
            }
        }
        // we have successfully checked all permissions
        request.grant(request.getResources());
    }

    @Override
    protected void finalize() throws Throwable {
        clearReferences();
        super.finalize();
    }

    public void clearReferences() {
        this.activity = null;

        if ((this.activity != null) && (this.activity instanceof ActivityResultListenerRegistry)) {
            ((ActivityResultListenerRegistry) this.activity).unregisterActivityResultListener(this);
        }
    }

    @Override
    public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
        String type;
        if (fileChooserParams != null && fileChooserParams.getAcceptTypes() != null && fileChooserParams.getAcceptTypes().length > 0) {
            type = !TextUtils.isEmpty(fileChooserParams.getAcceptTypes()[0]) ? fileChooserParams.getAcceptTypes()[0] : "*/*";
            this.filePathCallback = filePathCallback;
        } else {
            return false;
        }
        Boolean skipDefaultChooser = false;
        if (activity instanceof TLFileChooserInterceptor) {
            skipDefaultChooser = ((TLFileChooserInterceptor) activity).handleShowFileChooser(this, type, fileChooserParams.isCaptureEnabled(), fileChooserParams.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
        }

        if (!skipDefaultChooser) {
            proceedOnType(type, fileChooserParams.getMode());
        }
        return true;
    }

    private void proceedOnType(String type, int mode) {
        if (type.toLowerCase().contains("image")) {
            openDefaultChooser("image/*", mode);
        } else if (type.toLowerCase().contains("video")) {
            String[] permissions = getPermissionType(TypeForPermission.VIDEO);
            if (permissions.length > 0) {
                this.activity.requestPermissions(permissions, VIDEO_REQUEST_CODE);
            }
            // need to make sure you have "android.permission.CAMERA", "android.permission.WRITE_EXTERNAL_STORAGE", "android.permission.RECORD_AUDIO" permission
            try {
                makeVideo();
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            String[] permissions = getPermissionType(TypeForPermission.FILE);
            if (permissions.length > 0) {
                this.activity.requestPermissions(permissions, EXTERNAL_REQUEST_CODE);
            }
            openDefaultChooser(type, mode); // need to make sure you have "android.permission.WRITE_EXTERNAL_STORAGE" permission
        }
    }

    private String[] getPermissionType(@NonNull TypeForPermission type) {
        ArrayList<String> list = new ArrayList();
        switch (type) {
            case IMAGE:
                list.add(CAMERA);
                break;
            case VIDEO:
                list.add(CAMERA);
                list.add(WRITE_EXTERNAL_STORAGE);
                list.add(RECORD_AUDIO);
                break;
            default:
                list.add(READ_EXTERNAL_STORAGE);
                list.add(WRITE_EXTERNAL_STORAGE);
                break;
        }
        return filterStoragePermissions(Arrays.copyOf(list.toArray(), list.size(), String[].class));
    }

    private String[] filterStoragePermissions(String[] permissions) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return permissions;
        }
        ArrayList<String> list = new ArrayList();
        for (String permission : permissions) {
            if (!permission.equals(READ_EXTERNAL_STORAGE) && !permission.equals(WRITE_EXTERNAL_STORAGE)) {
                list.add(permission);
            }
        }
        return Arrays.copyOf(list.toArray(), list.size(), String[].class);
    }

    private void openDefaultChooser(String type, int mode) {
        if (!hasExternalPermission()) {
            clearFileCallback();
            return; // sorry no access
        }

        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setFlags(FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_FORWARD_RESULT);
        if (mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(type);
        activity.startActivityForResult(Intent.createChooser(i, "File Chooser"), FILE_CHOOSER_REQUEST_CODE);
    }

    private boolean hasExternalPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return (ContextCompat.checkSelfPermission(activity, READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) ||
                    (ContextCompat.checkSelfPermission(activity, WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED);
        } else {
            return true;
        }
    }

    private void makeVideo() throws IOException {
        boolean hasPermission =
                (ContextCompat.checkSelfPermission(this.activity, RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) ||
                        (ContextCompat.checkSelfPermission(this.activity, WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) ||
                        (ContextCompat.checkSelfPermission(this.activity, CAMERA) == PackageManager.PERMISSION_GRANTED);
        if (!hasPermission) {
            clearFileCallback();
            return; // sorry no access
        }
        Intent iImageCapture = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
        iImageCapture.setFlags(FLAG_ACTIVITY_SINGLE_TOP);

        File videoFile = getVideoCaptureCachePath();
        mediaUri = Uri.fromFile(videoFile);

        iImageCapture.putExtra(MediaStore.EXTRA_OUTPUT, getProvidedImageUri(videoFile));
        activity.startActivityForResult(Intent.createChooser(iImageCapture, "Image Chooser"), FILE_CHOOSER_REQUEST_CODE);
    }

    private Uri getProvidedImageUri(File imageFile) {
        String packageName = activity.getPackageName();

        return FileProvider.getUriForFile(activity, packageName + ".fileprovider", imageFile);
    }

    private File getImageCaptureCacheFile() throws IOException {
        // Create an image file name
        String imageFileName = "temp_photo" + System.currentTimeMillis();
        File storageDir = activity.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES);

        return File.createTempFile(imageFileName, ".jpg", storageDir);
    }

    private File getVideoCaptureCachePath() throws IOException {
        String videoFileName = "temp_video" + System.currentTimeMillis();
        File storageDir = activity.getExternalFilesDir(Environment.DIRECTORY_MOVIES);

        return File.createTempFile(videoFileName, ".mp4", storageDir);
    }

    private void cleanCacheUri() {
        filePathCallback = null;
        mediaUri = null;
    }

    @Override
    public int activityResultType() {
        return FILE_CHOOSER_REQUEST_CODE;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (filePathCallback == null) {
            return;
        }

        if (resultCode != RESULT_OK) {
            filePathCallback.onReceiveValue(null);
            cleanCacheUri();
            return;
        }

        Uri[] resValue = null;
        // if mediaUri exists, we have generated content => data is null
        if (data != null) {
            ClipData clipData = data.getClipData();
            // multiple files selected
            if (clipData != null) {
                resValue = new Uri[clipData.getItemCount()];
                for (int i = 0; i < clipData.getItemCount(); i++) {
                    resValue[i] = clipData.getItemAt(i).getUri();
                }
            } else {
                resValue = new Uri[]{data.getData()};
            }
        }
        if (resValue == null && mediaUri != null) {
            File file = new File(mediaUri.getPath());
            if (file.exists()) {
                resValue = new Uri[]{mediaUri};
            }
        }

        execFileCallback(resValue);
    }

    public void clearFileCallback() {
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
        }
        cleanCacheUri();
    }

    public void execFileCallback(Uri resValue) {
        execFileCallback(new Uri[]{resValue});
    }

    public void execFileCallback(Uri[] resValue) {
        if (resValue == null) {
            clearFileCallback();
            return;
        }

        Handler mHandler = new Handler();
        final Uri[] finalResValue = resValue;
        final ProgressDialog progress = willCopyToCachedDir(finalResValue) ? new ProgressDialog(activity) : null;
        if (progress != null) {
            Resources resources = activity.getResources();
            progress.setTitle(resources.getText(R.string.downloading));
            progress.setMessage(resources.getText(R.string.please_wait));
            progress.setCancelable(false); // disable dismiss by tapping outside of the dialog
            progress.show();
        }

        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (filePathCallback != null) {
                        Uri[] resValue = copyFileToCachedDir(finalResValue);
                        filePathCallback.onReceiveValue(resValue);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
                cleanCacheUri();
                if (progress != null) {
                    progress.dismiss();
                }
            }
        }, (progress != null) ? 250 : 0);
    }

    Boolean willCopyToCachedDir(Uri[] sourceUris) {
        for (Uri sourceUri : sourceUris) {
            if (sourceUri != null && shouldCopyToCache(sourceUri)) {
                return true;
            }
        }
        return false;
    }

    Uri[] copyFileToCachedDir(Uri[] sourceUris) throws IOException {
        ArrayList<Uri> outputUris = new ArrayList();
        File outputDir = activity.getCacheDir(); // context being the Activity pointer
        for (Uri sourceUri : sourceUris) {
            String fileName = getFileName(sourceUri);
            String extension = "";
            String prefix = fileName;
            if (fileName.lastIndexOf(".") != -1) {
                prefix = fileName.substring(0, fileName.lastIndexOf("."));
                extension = fileName.substring(fileName.lastIndexOf("."));
            }
            // is file available on storage?
            if (shouldCopyToCache(sourceUri)) {
                try {
                    File tmpDirectory = generateTempFile("tmpDir-", "", outputDir);
                    tmpDirectory.mkdir();

                    File outputFile = new File(tmpDirectory, fileName);
                    copyFile(sourceUri, outputFile);
                    outputUris.add(Uri.fromFile(outputFile));
                } catch (Exception e) {
                    Toast.makeText(this.activity, "Could not create temporary file.", Toast.LENGTH_LONG).show();
                }
            } else {
                outputUris.add(sourceUri);
            }
        }
        return outputUris.toArray(new Uri[outputUris.size()]);
    }

    private static final class RandomNumberGeneratorHolder {
        static final Random randomNumberGenerator = new Random();
    }

    static File generateTempFile(String prefix, String suffix, File dir)
            throws IOException {
        Long n = RandomNumberGeneratorHolder.randomNumberGenerator.nextLong();
        if (n == Long.MIN_VALUE) {
            n = 0L;      // corner case
        } else {
            n = Math.abs(n);
        }

        String name = prefix + n + suffix;
        File f = new File(dir, name);
        if (!name.equals(f.getName())) {
            if (System.getSecurityManager() != null)
                throw new IOException("Unable to create temporary file");
            else
                throw new IOException("Unable to create temporary file, " + f);
        }
        return f;
    }

    boolean shouldCopyToCache(Uri uri) {
        return (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())
                && !isAndroidProviderDocument(uri)
                && !isExternalStorageDocument(uri)
                && !uri.getAuthority().startsWith(activity.getPackageName()));

    }

    void copyFile(Uri src, File dst) throws IOException {
        FileInputStream inputStream = (FileInputStream) activity.getContentResolver().openInputStream(src);
        FileChannel inChannel = inputStream.getChannel();
        FileChannel outChannel = new FileOutputStream(dst).getChannel();
        try {
            inChannel.transferTo(0, inChannel.size(), outChannel);
        } finally {
            if (inChannel != null)
                inChannel.close();
            if (outChannel != null)
                outChannel.close();
        }
    }

    @SuppressLint("Range")
    public String getFileName(Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            Cursor cursor = activity.getContentResolver().query(uri, null, null, null, null);
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    result = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME));
                }
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) {
                result = result.substring(cut + 1);
            }
        }
        return result;
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is ExternalStorageProvider.
     */
    public static boolean isAndroidProviderDocument(Uri uri) {
        return uri.getAuthority().startsWith("com.android.providers");
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is ExternalStorageProvider.
     */
    public static boolean isExternalStorageDocument(Uri uri) {
        return "com.android.externalstorage.documents".equals(uri.getAuthority());
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is DownloadsProvider.
     */
    public static boolean isDownloadsDocument(Uri uri) {
        return "com.android.providers.downloads.documents".equals(uri.getAuthority());
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is MediaProvider.
     */
    public static boolean isMediaDocument(Uri uri) {
        return "com.android.providers.media.documents".equals(uri.getAuthority());
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is Google Photos.
     */
    public static boolean isGooglePhotosUri(Uri uri) {
        return "com.google.android.apps.photos.content".equals(uri.getAuthority());
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is Google Drive.
     */
    public static boolean isGoogleDriveUri(Uri uri) {
        return "com.google.android.apps.docs.storage".equals(uri.getAuthority());
    }

    enum TypeForPermission {IMAGE, VIDEO, FILE}
}
