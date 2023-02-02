package info.belluco.cordova.bbscanner;

import android.Manifest;
import android.content.Intent;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.hardware.Camera;
import android.net.Uri;
import android.provider.Settings;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.ResultPoint;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.BarcodeView;
import com.journeyapps.barcodescanner.DefaultDecoderFactory;
import com.journeyapps.barcodescanner.SourceData;
import com.journeyapps.barcodescanner.camera.CameraInstance;
import com.journeyapps.barcodescanner.camera.CameraSettings;
import com.journeyapps.barcodescanner.camera.PreviewCallback;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PermissionHelper;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class BBScanner extends CordovaPlugin implements BarcodeCallback {

    /**
     * This variable stores the callback context.
     */
    private CallbackContext callbackContext;
    /**
     * This variable stores the state of the flash. true if flash is available,
     * false otherwise.
     */
    private Boolean flashAvailable = null;
    /**
     * This variable stores the state of the flash light. true if the flash light is
     * on, false otherwise.
     */
    private Boolean lightOn = false;
    private boolean showing = false;
    private boolean prepared = false;
    private int currentCameraId = Camera.CameraInfo.CAMERA_FACING_BACK;
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 33;
    // Preview started or paused
    private boolean previewing = false;
    private BarcodeView mBarcodeView;
    private boolean cameraPreviewing;
    private boolean scanning = false;
    private CallbackContext nextScanCallback;
    private boolean shouldScanAgain;
    private boolean denied;
    private boolean authorized;
    private boolean restricted;
    private boolean oneTime = true;
    private boolean keepDenied = false;
    private boolean appPausedWithActivePreview = false;
    private BarcodeFormat scanType = null;
    private boolean multipleScan = false;
    private final Object LOCK = new Object();

    static class BBScannerError {
        private static final int UNEXPECTED_ERROR = 0, CAMERA_ACCESS_DENIED = 1, CAMERA_ACCESS_RESTRICTED = 2,
                BACK_CAMERA_UNAVAILABLE = 3, FRONT_CAMERA_UNAVAILABLE = 4, CAMERA_UNAVAILABLE = 5, SCAN_CANCELED = 6,
                LIGHT_UNAVAILABLE = 7, OPEN_SETTINGS_UNAVAILABLE = 8;
    }

    @Override
    public boolean execute(final String action, final JSONArray args, final CallbackContext callbackContext)
            throws JSONException {
        this.callbackContext = callbackContext;
        try {
            switch (action) {
                case "show":
                    cordova.getThreadPool().execute(() -> show(callbackContext));
                    return true;
                case "scan":
                    cordova.getThreadPool().execute(() -> {
                        JSONObject data = (JSONObject) args.opt(0);
                        // Any format will be accepted by default
                        scanType = getBarcodeFormatFromString(data.optString("format", ""));
                        multipleScan = data.optBoolean("multipleScan", false);
                        scan(callbackContext);
                    });
                    return true;
                case "pause":
                    cordova.getThreadPool().execute(() -> pauseScan(callbackContext));
                    return true;
                case "resume":
                    cordova.getThreadPool().execute(() -> resumeScan(callbackContext));
                    return true;
                case "snap":
                    cordova.getThreadPool().execute(() -> snap(callbackContext));
                    return true;
                case "stop":
                    cordova.getThreadPool().execute(this::stop);
                    return true;
                case "openSettings":
                    cordova.getThreadPool().execute(() -> openSettings(callbackContext));
                    return true;
                case "pausePreview":
                    cordova.getThreadPool().execute(() -> pausePreview(callbackContext));
                    return true;
                case "useCamera":
                    cordova.getThreadPool().execute(() -> switchCamera(callbackContext, args));
                    return true;
                case "resumePreview":
                    cordova.getThreadPool().execute(() -> resumePreview(callbackContext));
                    return true;
                case "hide":
                    cordova.getThreadPool().execute(() -> hide(callbackContext));
                    return true;
                case "enableLight":
                    cordova.getThreadPool().execute(this::enableLight);
                    return true;
                case "disableLight":
                    cordova.getThreadPool().execute(this::disableLight);
                    return true;
                case "prepare":
                    cordova.getThreadPool().execute(() -> cordova.getActivity().runOnUiThread(() -> {
                        try {
                            currentCameraId = args.getInt(0);
                        } catch (JSONException e) {
                        }
                        prepare(callbackContext);
                    }));
                    return true;
                case "destroy":
                    cordova.getThreadPool().execute(() -> destroy(callbackContext));
                    return true;
                case "getStatus":
                    cordova.getThreadPool().execute(() -> getStatus(callbackContext));
                    return true;
                default:
                    return false;
            }
        } catch (Exception e) {
            callbackContext.error(BBScannerError.UNEXPECTED_ERROR);
            return false;
        }
    }

    @Override
    public void onPause(boolean multitasking) {
        if (previewing) {
            this.appPausedWithActivePreview = true;
            this.pausePreview(null);
        }
    }

    @Override
    public void onResume(boolean multitasking) {
        if (this.appPausedWithActivePreview) {
            this.appPausedWithActivePreview = false;
            this.resumePreview(null);
        }
    }

    /**
     *
     * Check if the device has a camera flash
     *
     * @return true if the device has a camera flash, false otherwise
     */
    private boolean hasFlash() {
        if (flashAvailable == null) {
            flashAvailable = false;
            final PackageManager packageManager = cordova.getActivity().getPackageManager();
            for (final FeatureInfo feature : packageManager.getSystemAvailableFeatures()) {
                if (PackageManager.FEATURE_CAMERA_FLASH.equalsIgnoreCase(feature.name)) {
                    flashAvailable = true;
                    break;
                }
            }
        }
        return flashAvailable;
    }

    /**
     *
     * Switch the camera flash on or off
     *
     * @param toggleLight     true to turn the flash on, false to turn it off
     * @param callbackContext the callback context to handle the result
     */
    private void switchFlash(boolean toggleLight, CallbackContext callbackContext) {
        this.callbackContext = callbackContext;
        try {
            if (hasFlash()) {
                doSwitchFlash(toggleLight);
            } else {
                callbackContext.error(BBScannerError.LIGHT_UNAVAILABLE);
            }
        } catch (Exception e) {
            lightOn = false;
            callbackContext.error(BBScannerError.LIGHT_UNAVAILABLE);
        }
    }

    /**
     *
     * Perform the flash switching. Only supports back camera
     *
     * @param toggleLight true to turn the flash on, false to turn it off
     */
    private void doSwitchFlash(final boolean toggleLight) {
        if (getCurrentCameraId() == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            callbackContext.error(BBScannerError.LIGHT_UNAVAILABLE);
            return;
        }
        cordova.getActivity().runOnUiThread(() -> {
            if (mBarcodeView != null) {
                mBarcodeView.setTorch(toggleLight);
                lightOn = toggleLight;
            }
            getStatus(callbackContext);
        });
    }

    /**
     *
     * Enable the camera flash
     */
    private void enableLight() {
        if (hasPermission()) {
            switchFlash(true, this.callbackContext);
        } else {
            callbackContext.error(BBScannerError.CAMERA_ACCESS_DENIED);
        }
    }

    /**
     *
     * Disable the camera flash
     */
    private void disableLight() {
        if (hasPermission()) {
            switchFlash(false, this.callbackContext);
        } else {
            callbackContext.error(BBScannerError.CAMERA_ACCESS_DENIED);
        }
    }

    private String boolToNumberString(Boolean bool) {
        return bool ? "1" : "0";
    }

    private BarcodeFormat getBarcodeFormatFromString(String format) {
        BarcodeFormat aFormat = null;
        switch (format) {
            case "QR_CODE":
                aFormat = BarcodeFormat.QR_CODE;
                break;
            case "UPC_E":
                aFormat = BarcodeFormat.UPC_E;
                break;
            case "EAN_13":
                aFormat = BarcodeFormat.EAN_13;
                break;
            case "EAN_8":
                aFormat = BarcodeFormat.EAN_8;
                break;
            case "CODE_39":
                aFormat = BarcodeFormat.CODE_39;
                break;
            case "CODE_93":
                aFormat = BarcodeFormat.CODE_93;
                break;
            case "CODE_128":
                aFormat = BarcodeFormat.CODE_128;
                break;
            case "PDF417":
                aFormat = BarcodeFormat.PDF_417;
                break;
            case "ITF":
                aFormat = BarcodeFormat.ITF;
                break;
            case "DATA_MATRIX":
                aFormat = BarcodeFormat.DATA_MATRIX;
                break;
            case "CODABAR":
                aFormat = BarcodeFormat.CODABAR;
                break;
            case "RSS_14":
                aFormat = BarcodeFormat.RSS_14;
                break;
            case "RSS_EXPANDED":
                aFormat = BarcodeFormat.RSS_EXPANDED;
                break;
        }
        return aFormat;
    }

    public int getCurrentCameraId() {
        return this.currentCameraId;
    }

    private boolean canChangeCamera() {
        int numCameras = Camera.getNumberOfCameras();
        for (int i = 0; i < numCameras; i++) {
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(i, info);
            if (Camera.CameraInfo.CAMERA_FACING_FRONT == info.facing) {
                return true;
            }
        }
        return false;
    }

    public void switchCamera(CallbackContext callbackContext, JSONArray args) {
        int cameraId = 0;

        try {
            cameraId = args.getInt(0);
        } catch (JSONException d) {
            callbackContext.error(BBScannerError.UNEXPECTED_ERROR);
        }
        currentCameraId = cameraId;
        if (scanning) {
            scanning = false;
            prepared = false;
            if (cameraPreviewing) {
                this.cordova.getActivity().runOnUiThread(() -> {
                    ((ViewGroup) mBarcodeView.getParent()).removeView(mBarcodeView);
                    cameraPreviewing = false;
                });
            }
            closeCamera();
            prepare(callbackContext);
            scan(this.nextScanCallback);
        } else
            prepare(callbackContext);
    }

    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) {
        oneTime = false;
        if (requestCode == 33) {
            // for each permission check if the user granted/denied them
            // you may want to group the rationale in a single dialog,
            // this is just an example
            for (int i = 0; i < permissions.length; i++) {
                String permission = permissions[i];
                if (grantResults[i] == PackageManager.PERMISSION_DENIED) {
                    boolean showRationale = ActivityCompat.shouldShowRequestPermissionRationale(cordova.getActivity(),
                            permission);
                    if (!showRationale) {
                        // user denied flagging NEVER ASK AGAIN
                        denied = true;
                        authorized = false;
                    } else {
                        authorized = false;
                        denied = false;
                    }
                    callbackContext.error(BBScannerError.CAMERA_ACCESS_DENIED);
                    return;
                } else if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    authorized = true;
                    denied = false;
                    prepare(callbackContext);
                } else {
                    authorized = false;
                    denied = false;
                    restricted = false;
                }
            }
        }
    }

    public boolean hasPermission() {
        return PermissionHelper.hasPermission(this, Manifest.permission.CAMERA );
    }

    private void requestPermission() {
        PermissionHelper.requestPermissions(this, CAMERA_PERMISSION_REQUEST_CODE, new String[] { Manifest.permission.CAMERA });
    }

    private void closeCamera() {
        cordova.getActivity().runOnUiThread(() -> {
            if (mBarcodeView != null) {
                mBarcodeView.pause();
            }
        });
    }

    private void makeOpaque() {
        this.cordova.getActivity().runOnUiThread(() -> {
            webView.getView().setBackgroundColor(Color.WHITE);
            if (mBarcodeView != null)
                mBarcodeView.setVisibility(View.INVISIBLE);
        });
        showing = false;
    }

    private boolean hasCamera() {
        return this.cordova.getActivity().getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
    }

    private boolean hasFrontCamera() {
        return this.cordova.getActivity().getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT);
    }

    private void setupCamera(CallbackContext callbackContext) {
        cordova.getActivity().runOnUiThread(() -> {
            // Create our Preview view and set it as the content of our activity.
            mBarcodeView = new BarcodeView(cordova.getActivity());

            // Configure the decoder
            ArrayList<BarcodeFormat> formatList = new ArrayList<>();
            formatList.add(BarcodeFormat.QR_CODE);
            formatList.add(BarcodeFormat.DATA_MATRIX);
            formatList.add(BarcodeFormat.UPC_A);
            formatList.add(BarcodeFormat.UPC_E);
            formatList.add(BarcodeFormat.EAN_8);
            formatList.add(BarcodeFormat.EAN_13);
            formatList.add(BarcodeFormat.CODE_39);
            formatList.add(BarcodeFormat.CODE_93);
            formatList.add(BarcodeFormat.CODE_128);
            formatList.add(BarcodeFormat.CODABAR);
            formatList.add(BarcodeFormat.ITF);
            formatList.add(BarcodeFormat.RSS_14);
            formatList.add(BarcodeFormat.PDF_417);
            formatList.add(BarcodeFormat.RSS_EXPANDED);
            mBarcodeView.setDecoderFactory(new DefaultDecoderFactory(formatList));

            // Configure the camera (front/back)
            CameraSettings settings = new CameraSettings();
            mBarcodeView.setCameraSettings(settings);
            settings.setRequestedCameraId(getCurrentCameraId());

            FrameLayout.LayoutParams cameraPreviewParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            ((ViewGroup) webView.getView().getParent()).addView(mBarcodeView, cameraPreviewParams);

            cameraPreviewing = true;
            webView.getView().bringToFront();

            mBarcodeView.resume();
        });
        prepared = true;
        previewing = true;
        if (shouldScanAgain)
            scan(callbackContext);

    }

    @Override
    public void barcodeResult(BarcodeResult barcodeResult) {
        if (!this.scanning || this.nextScanCallback == null) {
            return;
        }

        if (this.scanType != null) {
            if (barcodeResult.getBarcodeFormat() != this.scanType) {
                // Log.d("BBScan", "====== NOOOO");
                return;
            }
        }

        if (barcodeResult.getText() != null) {
            // Log.d("BBScan", "====== Ooook: "+barcodeResult.getText());
            PluginResult result = new PluginResult(PluginResult.Status.OK, barcodeResult.getText());

            if (this.multipleScan) {
                result.setKeepCallback(true);
                this.nextScanCallback.sendPluginResult(result);
            } else {
                scanning = false;
                mBarcodeView.stopDecoding();
                this.nextScanCallback.sendPluginResult(result);
                this.scanType = null;
                stop();
            }
        } else {
            scan(this.nextScanCallback);
        }
    }

    @Override
    public void possibleResultPoints(List<ResultPoint> list) {
    }

    // ---- BEGIN EXTERNAL API ----
    private void prepare(final CallbackContext callbackContext) {
        if (!prepared) {
            if (currentCameraId == Camera.CameraInfo.CAMERA_FACING_BACK) {
                if (hasCamera()) {
                    if (!hasPermission()) {
                        requestPermission();
                    } else {
                        setupCamera(callbackContext);
                        if (!scanning)
                            getStatus(callbackContext);
                    }
                } else {
                    callbackContext.error(BBScannerError.BACK_CAMERA_UNAVAILABLE);
                }
            } else if (currentCameraId == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                if (hasFrontCamera()) {
                    if (!hasPermission()) {
                        requestPermission();
                    } else {
                        setupCamera(callbackContext);
                        if (!scanning)
                            getStatus(callbackContext);
                    }
                } else {
                    callbackContext.error(BBScannerError.FRONT_CAMERA_UNAVAILABLE);
                }
            } else {
                callbackContext.error(BBScannerError.CAMERA_UNAVAILABLE);
            }
        } else {
            prepared = false;
            this.cordova.getActivity().runOnUiThread(() -> mBarcodeView.pause());
            if (cameraPreviewing) {
                this.cordova.getActivity().runOnUiThread(() -> {
                    ((ViewGroup) mBarcodeView.getParent()).removeView(mBarcodeView);
                    cameraPreviewing = false;
                });

                previewing = true;
                lightOn = false;
            }
            setupCamera(callbackContext);
            getStatus(callbackContext);
        }
    }

    private void scan(final CallbackContext callbackContext) {
        scanning = true;
        if (!prepared) {
            shouldScanAgain = true;
            if (hasCamera()) {
                if (!hasPermission()) {
                    requestPermission();
                } else {
                    setupCamera(callbackContext);
                }
            }
        } else {
            if (!previewing) {
                this.cordova.getActivity().runOnUiThread(() -> {
                    if (mBarcodeView != null) {
                        mBarcodeView.resume();
                        previewing = true;
                    }
                });
            }
            shouldScanAgain = false;
            this.nextScanCallback = callbackContext;
            final BarcodeCallback b = this;
            this.cordova.getActivity().runOnUiThread(() -> {
                webView.getView().setBackgroundColor(Color.argb(1, 0, 0, 0));
                showing = true;
                mBarcodeView.setVisibility(View.VISIBLE);
                if (mBarcodeView != null) {
                    // mBarcodeView.decodeSingle(b);
                    mBarcodeView.decodeContinuous(b);
                }
            });
        }
    }

    private void stop() {
        this.cordova.getActivity().runOnUiThread(() -> {
            makeOpaque();
            scanning = false;
            if (mBarcodeView != null) {
                mBarcodeView.stopDecoding();
            }
        });
        if (this.nextScanCallback != null)
            this.nextScanCallback.error(BBScannerError.SCAN_CANCELED);
        this.nextScanCallback = null;
    }

    private void show(final CallbackContext callbackContext) {
        this.cordova.getActivity().runOnUiThread(() -> {
            webView.getView().setBackgroundColor(Color.argb(1, 0, 0, 0));
            showing = true;
            mBarcodeView.setVisibility(View.VISIBLE);
            getStatus(callbackContext);
        });
    }

    private void hide(final CallbackContext callbackContext) {
        makeOpaque();
        getStatus(callbackContext);
    }

    private void pausePreview(final CallbackContext callbackContext) {
        this.cordova.getActivity().runOnUiThread(() -> {
            if (mBarcodeView != null) {
                mBarcodeView.pause();
                previewing = false;
                if (lightOn)
                    lightOn = false;
            }

            if (callbackContext != null)
                getStatus(callbackContext);
        });

    }

    private void resumePreview(final CallbackContext callbackContext) {
        this.cordova.getActivity().runOnUiThread(() -> {
            if (mBarcodeView != null) {
                mBarcodeView.resume();
                previewing = true;
            }

            if (callbackContext != null)
                getStatus(callbackContext);
        });
    }

    private void openSettings(CallbackContext callbackContext) {
        oneTime = true;
        if (denied)
            keepDenied = true;
        try {
            denied = false;
            authorized = false;
            boolean shouldPrepare = prepared;
            boolean shouldFlash = lightOn;
            boolean shouldShow = showing;
            if (prepared)
                destroy(callbackContext);
            lightOn = false;
            Intent intent = new Intent();
            intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Uri uri = Uri.fromParts("package", this.cordova.getActivity().getPackageName(), null);
            intent.setData(uri);
            this.cordova.getActivity().getApplicationContext().startActivity(intent);
            getStatus(callbackContext);
            if (shouldPrepare)
                prepare(callbackContext);
            if (shouldFlash)
                enableLight();
            if (shouldShow)
                show(callbackContext);
        } catch (Exception e) {
            callbackContext.error(BBScannerError.OPEN_SETTINGS_UNAVAILABLE);
        }

    }

    private void getStatus(CallbackContext callbackContext) {

        if (oneTime) {

            authorized = hasPermission();
            denied = keepDenied && !authorized;

            // No applicable API
            restricted = false;
        }
        boolean canOpenSettings = true;

        boolean canEnableLight = hasFlash();

        if (currentCameraId == Camera.CameraInfo.CAMERA_FACING_FRONT)
            canEnableLight = false;

        HashMap<String, String> status = new HashMap<>();
        status.put("authorized", boolToNumberString(authorized));
        status.put("denied", boolToNumberString(denied));
        status.put("restricted", boolToNumberString(restricted));
        status.put("prepared", boolToNumberString(prepared));
        status.put("scanning", boolToNumberString(scanning));
        status.put("previewing", boolToNumberString(previewing));
        status.put("showing", boolToNumberString(showing));
        status.put("lightEnabled", boolToNumberString(lightOn));
        status.put("canOpenSettings", boolToNumberString(canOpenSettings));
        status.put("canEnableLight", boolToNumberString(canEnableLight));
        status.put("canChangeCamera", boolToNumberString(canChangeCamera()));
        status.put("currentCamera", Integer.toString(getCurrentCameraId()));

        JSONObject obj = new JSONObject(status);
        PluginResult result = new PluginResult(PluginResult.Status.OK, obj);
        callbackContext.sendPluginResult(result);
    }

    private void destroy(CallbackContext callbackContext) {
        prepared = false;
        makeOpaque();
        previewing = false;
        if (scanning) {
            this.cordova.getActivity().runOnUiThread(() -> {
                scanning = false;
                if (mBarcodeView != null) {
                    mBarcodeView.stopDecoding();
                }
            });
            this.nextScanCallback = null;
        }

        if (cameraPreviewing) {
            this.cordova.getActivity().runOnUiThread(() -> {
                ViewGroup parent = (ViewGroup) mBarcodeView.getParent();
                if (parent != null) {
                    parent.removeView(mBarcodeView);
                }
            });
        }
        if (currentCameraId != Camera.CameraInfo.CAMERA_FACING_FRONT) {
            if (lightOn)
                switchFlash(false, callbackContext);
        }
        closeCamera();
        currentCameraId = 0;
        getStatus(callbackContext);
    }

    private void pauseScan(CallbackContext callbackContext) {
        if (!scanning) {
            callbackContext.success();
            return;
        }
        scanning = false;
        this.cordova.getActivity().runOnUiThread(() -> {
            mBarcodeView.stopDecoding();
            PluginResult result = new PluginResult(PluginResult.Status.OK);
            callbackContext.sendPluginResult(result);
        });
    }

    private void resumeScan(CallbackContext callbackContext) {
        if (scanning) {
            callbackContext.success();
            return;
        }
        final BarcodeCallback barcodeCallback = this;
        scanning = true;
        cordova.getActivity().runOnUiThread(() -> {
            mBarcodeView.decodeContinuous(barcodeCallback);
            PluginResult result = new PluginResult(PluginResult.Status.OK);
            callbackContext.sendPluginResult(result);
        });
    }

    private void snap(CallbackContext callbackContext) {
        Rect rect = mBarcodeView.getPreviewFramingRect();

        CameraInstance camera = mBarcodeView.getCameraInstance();
        camera.requestPreview(new PreviewCallback() {
            @Override
            public void onPreview(SourceData sourceData) {
                synchronized (LOCK) {
                    sourceData.setCropRect(rect);
                    Bitmap image = sourceData.getBitmap();

                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    image.compress(Bitmap.CompressFormat.PNG, 100, baos);
                    byte[] b = baos.toByteArray();
                    String imageEncoded = Base64.encodeToString(b, Base64.DEFAULT);
                    PluginResult result = new PluginResult(PluginResult.Status.OK, imageEncoded);
                    callbackContext.sendPluginResult(result);
                }
            }

            @Override
            public void onPreviewError(Exception e) {
                synchronized (LOCK) {
                    PluginResult result = new PluginResult(PluginResult.Status.ERROR);
                    callbackContext.sendPluginResult(result);
                }
            }
        });
    }
}
