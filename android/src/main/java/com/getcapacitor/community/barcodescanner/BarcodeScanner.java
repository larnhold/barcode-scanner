package com.getcapacitor.community.barcodescanner;

import static android.content.Context.MODE_PRIVATE;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Camera;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.activity.result.ActivityResult;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.ResultPoint;
import com.google.zxing.client.android.Intents;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.BarcodeView;
import com.journeyapps.barcodescanner.DefaultDecoderFactory;
import com.journeyapps.barcodescanner.camera.CameraSettings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONException;

@CapacitorPlugin(permissions = { @Permission(strings = { Manifest.permission.CAMERA }, alias = BarcodeScanner.PERMISSION_ALIAS_CAMERA) })
public class BarcodeScanner extends Plugin implements BarcodeCallback {

    public static final String PERMISSION_ALIAS_CAMERA = "camera";
    private static final String TAG = "BarcodeScanner";
    private static final String PREFS_PERMISSION_FIRST_TIME_ASKING = "PREFS_PERMISSION_FIRST_TIME_ASKING";
    private static final String PERMISSION_NAME = Manifest.permission.CAMERA;
    private static final String GRANTED = "granted";
    private static final String DENIED = "denied";
    private static final String ASKED = "asked";
    private static final String NEVER_ASKED = "neverAsked";
    private static final Map<String, BarcodeFormat> SUPPORTED_FORMATS = supportedFormats();
    private BarcodeView mBarcodeView;
    private boolean isScanning = false;
    private boolean shouldRunScan = false;
    private boolean didRunCameraPrepare = false;
    private boolean isBackgroundHidden = false;
    private boolean isTorchOn = false;
    private boolean scanningPaused = false;
    private String lastScanResult = null;
    private JSObject pendingPermissionResult;

    private static Map<String, BarcodeFormat> supportedFormats() {
        Map<String, BarcodeFormat> map = new HashMap<>();
        // 1D Product
        map.put("UPC_A", BarcodeFormat.UPC_A);
        map.put("UPC_E", BarcodeFormat.UPC_E);
        map.put("UPC_EAN_EXTENSION", BarcodeFormat.UPC_EAN_EXTENSION);
        map.put("EAN_8", BarcodeFormat.EAN_8);
        map.put("EAN_13", BarcodeFormat.EAN_13);
        // 1D Industrial
        map.put("CODE_39", BarcodeFormat.CODE_39);
        map.put("CODE_93", BarcodeFormat.CODE_93);
        map.put("CODE_128", BarcodeFormat.CODE_128);
        map.put("CODABAR", BarcodeFormat.CODABAR);
        map.put("ITF", BarcodeFormat.ITF);
        // 2D
        map.put("AZTEC", BarcodeFormat.AZTEC);
        map.put("DATA_MATRIX", BarcodeFormat.DATA_MATRIX);
        map.put("MAXICODE", BarcodeFormat.MAXICODE);
        map.put("PDF_417", BarcodeFormat.PDF_417);
        map.put("QR_CODE", BarcodeFormat.QR_CODE);
        map.put("RSS_14", BarcodeFormat.RSS_14);
        map.put("RSS_EXPANDED", BarcodeFormat.RSS_EXPANDED);
        return Collections.unmodifiableMap(map);
    }

    private boolean hasCamera() {
        return getActivity().getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
    }

    private void setupCamera(String cameraDirection) {
        getActivity()
                .runOnUiThread(
                        () -> {
                            if (mBarcodeView != null) {
                                return; // Already setup
                            }
                            // Create BarcodeView
                            mBarcodeView = new BarcodeView(getActivity());

                            // Configure the camera (front/back)
                            CameraSettings settings = new CameraSettings();
                            settings.setRequestedCameraId(
                                    "front".equals(cameraDirection) ? Camera.CameraInfo.CAMERA_FACING_FRONT : Camera.CameraInfo.CAMERA_FACING_BACK
                            );
                            settings.setContinuousFocusEnabled(true);
                            mBarcodeView.setCameraSettings(settings);

                            FrameLayout.LayoutParams cameraPreviewParams = new FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    FrameLayout.LayoutParams.MATCH_PARENT
                            );

                            // Set BarcodeView as sibling View of WebView
                            ((ViewGroup) bridge.getWebView().getParent()).addView(mBarcodeView, cameraPreviewParams);

                            // Bring the WebView in front of the BarcodeView
                            // This allows us to completely style the BarcodeView in HTML/CSS
                            bridge.getWebView().bringToFront();

                            mBarcodeView.resume();
                        }
                );
        didRunCameraSetup = true;
    }

    private void dismantleCamera() {
        getActivity()
                .runOnUiThread(
                        () -> {
                            if (mBarcodeView != null) {
                                mBarcodeView.pause();
                                mBarcodeView.stopDecoding();
                                ((ViewGroup) bridge.getWebView().getParent()).removeView(mBarcodeView);
                                mBarcodeView = null;
                            }
                        }
                );
        isScanning = false;
        didRunCameraSetup = false;
        didRunCameraPrepare = false;

        // If a call is saved and a scan will not run, free the saved call
        if (getSavedCall() != null && !shouldRunScan) {
            freeSavedCall();
        }
    }

    private void _prepare(PluginCall call) {
        // undo previous setup
        // because it may be prepared with a different config
        dismantleCamera();

        // setup camera with new config
        setupCamera(call.getString("cameraDirection", "back"));

        // indicate this method was run
        didRunCameraPrepare = true;

        if (shouldRunScan) {
            scan();
        }
    }

    private void destroy() {
        showBackground();
        dismantleCamera();
        setTorch(false);
    }

    private void configureCamera() {
        getActivity()
                .runOnUiThread(
                        () -> {
                            PluginCall call = getSavedCall();

                            if (call == null || mBarcodeView == null) {
                                Log.d(TAG, "Something went wrong with configuring the BarcodeScanner.");
                                return;
                            }

                            DefaultDecoderFactory defaultDecoderFactory = new DefaultDecoderFactory(null, null, null, Intents.Scan.MIXED_SCAN);

                            if (call.hasOption("targetedFormats")) {
                                JSArray targetedFormats = call.getArray("targetedFormats");
                                ArrayList<BarcodeFormat> formatList = new ArrayList<>();

                                if (targetedFormats != null && targetedFormats.length() > 0) {
                                    for (int i = 0; i < targetedFormats.length(); i++) {
                                        try {
                                            String targetedFormat = targetedFormats.getString(i);
                                            BarcodeFormat targetedBarcodeFormat = SUPPORTED_FORMATS.get(targetedFormat);
                                            if (targetedBarcodeFormat != null) {
                                                formatList.add(targetedBarcodeFormat);
                                            } else {
                                                Log.w(TAG, "Unsupported format: " + targetedFormat);
                                            }
                                        } catch (JSONException e) {
                                            Log.e(TAG, "Error processing targetedFormats: " + e.getMessage());
                                        }
                                    }
                                }

                                if (!formatList.isEmpty()) {
                                    defaultDecoderFactory = new DefaultDecoderFactory(formatList, null, null, Intents.Scan.MIXED_SCAN);
                                } else {
                                    Log.d(TAG, "No valid targetedFormats specified, scanning all supported formats.");
                                }
                            }

                            mBarcodeView.setDecoderFactory(defaultDecoderFactory);
                        }
                );
    }

    private void scan() {
        if (!didRunCameraPrepare) {
            if (hasCamera()) {
                if (getPermissionState(PERMISSION_ALIAS_CAMERA) != PermissionState.GRANTED) {
                    Log.d(TAG, "Camera permission not granted. Requesting permission.");
                    shouldRunScan = true;
                    requestCameraPermission(getSavedCall());
                } else {
                    shouldRunScan = true;
                    _prepare(getSavedCall());
                }
            } else {
                PluginCall call = getSavedCall();
                if (call != null) {
                    call.reject("Device does not have a camera.");
                    freeSavedCall();
                }
            }
        } else {
            didRunCameraPrepare = false;
            shouldRunScan = false;
            configureCamera();

            final BarcodeCallback b = this;
            getActivity()
                    .runOnUiThread(
                            () -> {
                                if (mBarcodeView != null) {
                                    PluginCall call = getSavedCall();
                                    if (call != null && call.isKeptAlive()) {
                                        mBarcodeView.decodeContinuous(b);
                                    } else {
                                        mBarcodeView.decodeSingle(b);
                                    }
                                }
                            }
                    );

            hideBackground();
            isScanning = true;
        }
    }

    private void hideBackground() {
        getActivity()
                .runOnUiThread(
                        () -> {
                            bridge.getWebView().setBackgroundColor(Color.TRANSPARENT);
                            bridge.getWebView().loadUrl("javascript:document.documentElement.style.backgroundColor = 'transparent';void(0);");
                            isBackgroundHidden = true;
                        }
                );
    }

    private void showBackground() {
        getActivity()
                .runOnUiThread(
                        () -> {
                            bridge.getWebView().setBackgroundColor(Color.WHITE);
                            bridge.getWebView().loadUrl("javascript:document.documentElement.style.backgroundColor = '';void(0);");
                            isBackgroundHidden = false;
                        }
                );
    }

    @Override
    public void barcodeResult(BarcodeResult barcodeResult) {
        JSObject jsObject = new JSObject();

        if (barcodeResult.getText() != null) {
            jsObject.put("hasContent", true);
            jsObject.put("content", barcodeResult.getText());
            jsObject.put("format", barcodeResult.getBarcodeFormat().name());
        } else {
            jsObject.put("hasContent", false);
        }

        PluginCall call = getSavedCall();

        if (call != null) {
            if (call.isKeptAlive()) {
                if (!scanningPaused && barcodeResult.getText() != null && !barcodeResult.getText().equals(lastScanResult)) {
                    lastScanResult = barcodeResult.getText();
                    call.resolve(jsObject);
                }
            } else {
                call.resolve(jsObject);
                destroy();
            }
        } else {
            destroy();
        }
    }

    @Override
    public void handleOnPause() {
        if (mBarcodeView != null) {
            mBarcodeView.pause();
        }
    }

    @Override
    public void handleOnResume() {
        if (mBarcodeView != null) {
            mBarcodeView.resume();
        }
    }

    @Override
    public void possibleResultPoints(List<ResultPoint> resultPoints) {}

    @PluginMethod
    public void prepare(PluginCall call) {
        _prepare(call);
        call.resolve();
    }

    @PluginMethod
    public void hideBackground(PluginCall call) {
        hideBackground();
        call.resolve();
    }

    @PluginMethod
    public void showBackground(PluginCall call) {
        showBackground();
        call.resolve();
    }

    @PluginMethod
    public void startScan(PluginCall call) {
        saveCall(call);
        scan();
    }

    @PluginMethod
    public void stopScan(PluginCall call) {
        if (call.hasOption("resolveScan") && getSavedCall() != null) {
            Boolean resolveScan = call.getBoolean("resolveScan", false);
            if (resolveScan != null && resolveScan) {
                JSObject jsObject = new JSObject();
                jsObject.put("hasContent", false);
                getSavedCall().resolve(jsObject);
            }
            freeSavedCall();
        }
        destroy();
        call.resolve();
    }

    @PluginMethod(returnType = PluginMethod.RETURN_CALLBACK)
    public void startScanning(PluginCall call) {
        call.setKeepAlive(true);
        lastScanResult = null; // reset when scanning again
        saveCall(call);
        scanningPaused = false;
        scan();
    }

    @PluginMethod
    public void pauseScanning(PluginCall call) {
        scanningPaused = true;
        call.resolve();
    }

    @PluginMethod
    public void resumeScanning(PluginCall call) {
        lastScanResult = null; // reset when scanning again
        scanningPaused = false;
        call.resolve();
    }

    private void requestCameraPermission(PluginCall call) {
        if (getPermissionState(PERMISSION_ALIAS_CAMERA) != PermissionState.GRANTED) {
            saveCall(call);
            requestPermissionForAlias(PERMISSION_ALIAS_CAMERA, call, "cameraPermissionResult");
        } else {
            call.resolve();
        }
    }

    @PermissionCallback
    private void cameraPermissionResult(PluginCall call) {
        if (getPermissionState(PERMISSION_ALIAS_CAMERA) == PermissionState.GRANTED) {
            // Permission granted, proceed with scanning
            if (shouldRunScan) {
                _prepare(call);
            }
            call.resolve();
        } else {
            // Permission denied
            call.reject("User denied camera permission.");
            freeSavedCall();
            shouldRunScan = false;
            destroy();
        }
    }

    @PluginMethod
    public void checkPermission(PluginCall call) {
        JSObject result = new JSObject();
        PermissionState state = getPermissionState(PERMISSION_ALIAS_CAMERA);
        result.put("granted", state == PermissionState.GRANTED);
        result.put("state", state.toString());
        call.resolve(result);
    }

    @PluginMethod
    public void requestPermissions(PluginCall call) {
        requestCameraPermission(call);
    }

    @PluginMethod
    public void openAppSettings(PluginCall call) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getAppId(), null));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivityForResult(call, intent, "openSettingsResult");
    }

    @ActivityCallback
    private void openSettingsResult(PluginCall call, ActivityResult result) {
        call.resolve();
    }

    private void setTorch(boolean on) {
        if (on != isTorchOn) {
            isTorchOn = on;
            getActivity()
                    .runOnUiThread(
                            () -> {
                                if (mBarcodeView != null) {
                                    mBarcodeView.setTorch(on);
                                }
                            }
                    );
        }
    }

    @PluginMethod
    public void enableTorch(PluginCall call) {
        setTorch(true);
        call.resolve();
    }

    @PluginMethod
    public void disableTorch(PluginCall call) {
        setTorch(false);
        call.resolve();
    }

    @PluginMethod
    public void toggleTorch(PluginCall call) {
        setTorch(!isTorchOn);
        call.resolve();
    }

    @PluginMethod
    public void getTorchState(PluginCall call) {
        JSObject result = new JSObject();
        result.put("isEnabled", isTorchOn);
        call.resolve(result);
    }

    private void setPermissionFirstTimeAsking(String permission, boolean isFirstTime) {
        SharedPreferences sharedPreference = getActivity().getSharedPreferences(PREFS_PERMISSION_FIRST_TIME_ASKING, MODE_PRIVATE);
        sharedPreference.edit().putBoolean(permission, isFirstTime).apply();
    }

    private boolean isPermissionFirstTimeAsking(String permission) {
        return getActivity().getSharedPreferences(PREFS_PERMISSION_FIRST_TIME_ASKING, MODE_PRIVATE).getBoolean(permission, true);
    }
}