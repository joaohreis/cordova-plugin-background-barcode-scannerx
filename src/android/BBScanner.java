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
import java.util.Arrays;
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
    /**
     * This variable stores the state if the camera is currently showing
     */
    private boolean showing = false;
    /**
     * This variable stores the state if the camera has been prepared for scanning
     */
    private boolean prepared = false;
    /**
     * This variable stores an integer representing the currently selected camera (either the front or back camera)
     * CAMERA_FACING_BACK by default
     */
    private int currentCameraId = Camera.CameraInfo.CAMERA_FACING_BACK;
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 33;
    /**
     * This variable stores the camera preview state
     */
    private boolean previewing = false;
    /**
     * This variable stores a BarcodeView object used to display the camera preview and scan barcodes
     */
    private BarcodeView mBarcodeView;
    /**
     * This variable stores the state if the camera is currently in preview mode
     */
    private boolean cameraPreviewing;
    /**
     * This variable stores the state if the camera is currently scanning
     */
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
                        currentCameraId = Camera.CameraInfo.CAMERA_FACING_BACK;
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
            final PackageManager packageManager = this.cordova.getActivity().getPackageManager();
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
                runOnUiThread(() -> {
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
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            // for each permission check if the user granted/denied them
            // you may want to group the rationale in a single dialog,
            // this is just an example
            for (int i = 0; i < permissions.length; i++) {
                String permission = permissions[i];

                authorized = false;
                denied = false;
                restricted = false;
                if (grantResults[i] == PackageManager.PERMISSION_DENIED) {

                    //If false the user has pressed NEVER ASK AGAIN
                    boolean showRationale = ActivityCompat.shouldShowRequestPermissionRationale(cordova.getActivity(),
                            permission);

                    // user denied flagging NEVER ASK AGAIN
                    denied = !showRationale;

                    callbackContext.error(BBScannerError.CAMERA_ACCESS_DENIED);
                    return;
                } else if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    authorized = true;
                    //If true, it's called by scan method
                    if(shouldScanAgain){
                        setupCamera(callbackContext);
                    } else {
                        //Will return the state
                        prepare(callbackContext);
                    }
                }
            }
        }
    }

    public boolean hasPermission() {
        return PermissionHelper.hasPermission(this, Manifest.permission.CAMERA);
    }

    private void requestPermission() {
        PermissionHelper.requestPermissions(this, CAMERA_PERMISSION_REQUEST_CODE,
                new String[] { Manifest.permission.CAMERA });
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

    private void setupCamera(CallbackContext callbackContext) {

        cordova.getActivity().runOnUiThread(() -> {
            // Create our Preview view and set it as the content of our activity.
            mBarcodeView = new BarcodeView(cordova.getActivity());

            // Configure the decoder
            ArrayList<BarcodeFormat> formatList = new ArrayList<>(
                    Arrays.asList(BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX, BarcodeFormat.UPC_A,
                            BarcodeFormat.UPC_E, BarcodeFormat.EAN_8, BarcodeFormat.EAN_13, BarcodeFormat.CODE_39,
                            BarcodeFormat.CODE_93, BarcodeFormat.CODE_128, BarcodeFormat.CODABAR, BarcodeFormat.ITF,
                            BarcodeFormat.RSS_14, BarcodeFormat.PDF_417, BarcodeFormat.RSS_EXPANDED));

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
            prepared = true;
            previewing = true;

            if (shouldScanAgain) {
                scan(callbackContext);
            }
        });
    }

    @Override
    public void barcodeResult(BarcodeResult barcodeResult) {
        if (!this.scanning || this.nextScanCallback == null ||
                (this.scanType != null && barcodeResult.getBarcodeFormat() != this.scanType)) {
            return;
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
                destroy(callbackContext);
            }
        } else {
            scan(this.nextScanCallback);
        }
    }

    @Override
    public void possibleResultPoints(List<ResultPoint> list) {
    }

    /**
     * Checks if the device has a camera.
     * @return True if the device has a camera, false otherwise.
     */
    private boolean hasCamera() {
        return this.cordova.getActivity().getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
    }

    /**
     * Prepares the camera for use.
     * @param callbackContext The callback context to return the results.
     */
    private void prepare(final CallbackContext callbackContext) {
        // Check if the camera has been prepared
        if (!prepared) {
            // Check if camera is available
            if (!hasCamera()) {
                callbackContext.error(BBScannerError.BACK_CAMERA_UNAVAILABLE);
                return;
            }
            // Check for permission
            if (!hasPermission()) {
                requestPermission();
                scanning = false;
                return;
            }

        } else {
            // Reset the camera state
            prepared = false;
            runOnUiThread(() -> {
                if (mBarcodeView != null) {
                    mBarcodeView.pause();
                }
            });
            if (cameraPreviewing) {
                this.cordova.getActivity().runOnUiThread(() -> {
                    removeFromParent(mBarcodeView);
                    cameraPreviewing = false;
                });
                previewing = true;
                lightOn = false;
            }
        }

        // Setup the camera and start scanning
        setupCamera(callbackContext);
        if (!scanning) {
            //Will return the status to app
            getStatus(callbackContext);
        }
    }

    /**
     * Scans barcodes using the camera and returns the result to the provided callback.
     *
     * @param callbackContext The callback to receive the scan result.
     */
    private void scan(final CallbackContext callbackContext) {
        scanning = true;

        // Check if the scanning preparation has been done
        if (!prepared) {
            // Set flag for scanning again after preparation is done
            shouldScanAgain = true;
            prepare(callbackContext);
        } else {
            // Check if the camera preview is not already running
            if (!previewing && mBarcodeView != null) {
                runOnUiThread(() -> {
                    mBarcodeView.resume();
                    previewing = true;
                });
            }

            // Reset flag for scanning again
            shouldScanAgain = false;

            // Store the callback for the next scan
            this.nextScanCallback = callbackContext;

            // Create a barcode callback instance
            final BarcodeCallback b = this;

            // Start the barcode scanning
            if (mBarcodeView != null && webView.getView() != null) {
                runOnUiThread(() -> {
                    webView.getView().setBackgroundColor(Color.argb(1, 0, 0, 0));
                    showing = true;
                    mBarcodeView.setVisibility(View.VISIBLE);
                    mBarcodeView.decodeContinuous(b);
                });
            }
        }
    }

    /**
     * Runs a task on the UI thread.
     *
     * @param runnable The task to run on the UI thread.
     */
    private void runOnUiThread(Runnable runnable) {
        this.cordova.getActivity().runOnUiThread(runnable);
    }

    /**
     * Stops the scanning process and cancels the current scan if any.
     */
    private void stop() {
        runOnUiThread(() -> {
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

    /**
     * Shows the camera view on the screen.
     *
     * @param callbackContext The callback context to return the results.
     */
    private void show(final CallbackContext callbackContext) {
        runOnUiThread(() -> {
            webView.getView().setBackgroundColor(Color.argb(1, 0, 0, 0));
            showing = true;
            mBarcodeView.setVisibility(View.VISIBLE);
            if (callbackContext != null)
                getStatus(callbackContext);
        });
    }

    /**
     * Hides the camera view from the screen.
     *
     * @param callbackContext The callback context to return the results.
     */
    private void hide(final CallbackContext callbackContext) {
        makeOpaque();
        if (callbackContext != null)
            getStatus(callbackContext);
    }

    /**
     * Pauses the camera preview.
     *
     * @param callbackContext The callback context to return the results.
     */
    private void pausePreview(final CallbackContext callbackContext) {
        runOnUiThread(() -> {
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

    /**
     * Resumes the camera preview.
     *
     * @param callbackContext The callback context to return the results.
     */
    private void resumePreview(final CallbackContext callbackContext) {
        runOnUiThread(() -> {
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

        boolean canEnableLight = hasFlash() && currentCameraId == Camera.CameraInfo.CAMERA_FACING_BACK;

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

    /**
     * Destroys the barcode scanning process.
     *
     * @param callbackContext The callback to receive the status after destruction.
     */
    private void destroy(CallbackContext callbackContext) {
        // Reset the preparation flag
        prepared = false;

        // Make the camera preview opaque
        makeOpaque();

        // Reset the preview flag
        previewing = false;

        // Check if scanning is in progress
        if (scanning) {
            // Stop the scanning
            runOnUiThread(() -> {
                scanning = false;
                if (mBarcodeView != null) {
                    mBarcodeView.stopDecoding();
                }
            });

            // Reset the next scan callback
            this.nextScanCallback = null;
        }

        // Check if the camera preview is running
        if (cameraPreviewing) {
            runOnUiThread(() -> removeFromParent(mBarcodeView));
        }

        // Turn off the flashlight if it is on
        if (lightOn && currentCameraId != Camera.CameraInfo.CAMERA_FACING_FRONT) {
            switchFlash(false, callbackContext);
        }

        // Close the camera
        closeCamera();

        // Reset the camera ID
        currentCameraId = Camera.CameraInfo.CAMERA_FACING_BACK;

        // Get the status after destruction
        getStatus(callbackContext);
    }


    /**
     * Removes a view from its parent.
     *
     * @param view The view to remove.
     */
    private void removeFromParent(View view) {
        if(view != null){
            ViewGroup parent = (ViewGroup) view.getParent();
            if (parent != null) {
                parent.removeView(view);
            }
        }
    }

    /**
     * Pause the scan process
     *
     * @param callbackContext The callback context to return the results.
     */
    private void pauseScan(CallbackContext callbackContext) {
        if (callbackContext == null) {
            return;
        }

        // Check if scanning is already paused
        if (!scanning) {
            callbackContext.success();
            return;
        }
        // Set scanning to false
        scanning = false;
        // Stop decoding barcodes on the UI thread
        runOnUiThread(() -> {
            if (mBarcodeView != null) {
                mBarcodeView.stopDecoding();
                // Return success
                PluginResult result = new PluginResult(PluginResult.Status.OK);
                callbackContext.sendPluginResult(result);
            }
        });
    }

    /**
     * Resume the scan process
     *
     * @param callbackContext The callback context to return the results.
     */
    private void resumeScan(CallbackContext callbackContext) {
        if (callbackContext == null) {
            return;
        }

        // Check if scanning is already resumed
        if (scanning) {
            // Return success if scanning is already resumed
            callbackContext.success();
            return;
        }

        // Get the barcode callback
        final BarcodeCallback barcodeCallback = this;
        // Set scanning to true
        scanning = true;
        // Start decoding barcodes continuously on the UI thread
        runOnUiThread(() -> {
            if (mBarcodeView != null) {
                mBarcodeView.decodeContinuous(barcodeCallback);
                // Return success
                PluginResult result = new PluginResult(PluginResult.Status.OK);
                callbackContext.sendPluginResult(result);
            }
        });
    }

    /**

     Snap the camera to take a picture

     @param callbackContext The callback context to return the results.
     */
    private void snap(CallbackContext callbackContext) {
        // Check if the callback context and barcode view are not null
        if (callbackContext == null || mBarcodeView == null) {
            return;
        }

        // Check if camera is available
        if (!hasCamera()) {
            // Return error if camera is unavailable
            callbackContext.error(BBScannerError.CAMERA_UNAVAILABLE);
            return;
        }

        // Get the preview framing rect and camera instance
        Rect rect = mBarcodeView.getPreviewFramingRect();
        CameraInstance camera = mBarcodeView.getCameraInstance();
        if (camera == null) {
            // Return error if camera is unavailable
            callbackContext.error(BBScannerError.CAMERA_UNAVAILABLE);
            return;
        }

        // Request preview from the camera
        camera.requestPreview(new PreviewCallback() {
            @Override
            public void onPreview(SourceData sourceData) {
                synchronized (LOCK) {
                    // Check if source data is not null
                    if (sourceData == null) {
                        // Return error if source data is null
                        callbackContext.error(BBScannerError.SCAN_CANCELED);
                        return;
                    }
                    // Set the crop rect
                    sourceData.setCropRect(rect);
                    Bitmap image = sourceData.getBitmap();
                    // Check if the bitmap is not null
                    if (image == null) {
                        callbackContext.error(BBScannerError.SCAN_CANCELED);
                        return;
                    }

                    // Compress the image to PNG and encode to base64 string
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    image.compress(Bitmap.CompressFormat.PNG, 100, baos);
                    byte[] b = baos.toByteArray();
                    String imageEncoded = Base64.encodeToString(b, Base64.DEFAULT);

                    // Send the result with the encoded image
                    PluginResult result = new PluginResult(PluginResult.Status.OK, imageEncoded);
                    callbackContext.sendPluginResult(result);
                }
            }

            @Override
            public void onPreviewError(Exception e) {
                synchronized (LOCK) {
                    // Return error if there is any exception during the preview
                    PluginResult result = new PluginResult(PluginResult.Status.ERROR);
                    callbackContext.sendPluginResult(result);
                }
            }
        });
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
}
