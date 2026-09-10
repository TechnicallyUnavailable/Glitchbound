package com.technicallyunavailable.measure;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Config;
import com.google.ar.core.Session;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

public final class MainActivity extends Activity implements MeasureRenderer.Listener {
    private static final int CAMERA_PERMISSION_CODE = 901;
    private static final String PREFS = "measure_prefs";

    private GLSurfaceView glView;
    private MeasurementOverlayView overlayView;
    private MeasureRenderer renderer;
    private Session session;
    private boolean installRequested;
    private boolean arResumed;
    private boolean torchOn;

    private TextView resultText;
    private TextView trackingText;
    private TextView lockText;
    private Button modeButton;
    private Button unitButton;
    private Button torchButton;

    private MeasureRenderer.Mode mode = MeasureRenderer.Mode.DISTANCE;
    private MeasurementUtils.UnitMode unitMode = MeasurementUtils.UnitMode.FEET_INCHES;
    private MeasureRenderer.Snapshot latestSnapshot;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        renderer = new MeasureRenderer(this);
        renderer.setCalibrationFactor(prefs.getFloat("calibration", 1.0f));
        buildUi();

        if (!prefs.getBoolean("arcore_disclosure_shown", false)) {
            new Handler(Looper.getMainLooper()).postDelayed(this::showInfoDialog, 350);
            prefs.edit().putBoolean("arcore_disclosure_shown", true).apply();
        }
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);
        glView.setPreserveEGLContextOnPause(true);
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        root.addView(glView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        overlayView = new MeasurementOverlayView(this);
        overlayView.setClickable(false);
        root.addView(overlayView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        glView.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                renderer.requestHit(event.getX(), event.getY());
                return true;
            }
            return true;
        });

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(dp(12), dp(10), dp(12), dp(10));
        top.setBackground(panelBackground(0xC4141414, 14));

        resultText = new TextView(this);
        resultText.setText("Aim, then tap ADD POINT");
        resultText.setTextColor(Color.WHITE);
        resultText.setTextSize(22f);
        resultText.setGravity(Gravity.CENTER);
        resultText.setPadding(dp(6), dp(2), dp(6), dp(5));
        top.addView(resultText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER);
        trackingText = smallStatus("STARTING AR…");
        lockText = smallStatus("NO LOCK");
        statusRow.addView(trackingText);
        TextView divider = smallStatus("   •   ");
        divider.setTextColor(0xFF777777);
        statusRow.addView(divider);
        statusRow.addView(lockText);
        top.addView(statusRow);

        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        topLp.setMargins(dp(12), dp(12), dp(12), 0);
        root.addView(top, topLp);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(8), dp(8), dp(8), dp(8));
        bottom.setBackground(panelBackground(0xDC101010, 16));

        LinearLayout row1 = new LinearLayout(this);
        row1.setGravity(Gravity.CENTER);
        modeButton = createButton("MODE: DISTANCE");
        unitButton = createButton("UNITS: ft/in");
        Button undo = createButton("UNDO");
        Button clear = createButton("CLEAR");
        addWeighted(row1, modeButton, 1.35f);
        addWeighted(row1, unitButton, 1.1f);
        addWeighted(row1, undo, 0.8f);
        addWeighted(row1, clear, 0.8f);
        bottom.addView(row1, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        LinearLayout row2 = new LinearLayout(this);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        Button addPoint = createButton("+ ADD POINT");
        stylePrimary(addPoint);
        torchButton = createButton("TORCH OFF");
        Button calibrate = createButton("CALIBRATE");
        Button save = createButton("SAVE");
        Button history = createButton("HISTORY");
        Button info = createButton("INFO");

        addPoint.setOnClickListener(v -> renderer.requestHit(glView.getWidth() * 0.5f, glView.getHeight() * 0.5f));
        torchButton.setOnClickListener(v -> toggleTorch());
        calibrate.setOnClickListener(v -> showCalibrationDialog());
        save.setOnClickListener(v -> saveMeasurementAndScreenshot());
        history.setOnClickListener(v -> showHistory());
        info.setOnClickListener(v -> showInfoDialog());

        row2.addView(addPoint, new LinearLayout.LayoutParams(dp(138), dp(48)));
        row2.addView(torchButton, new LinearLayout.LayoutParams(dp(108), dp(48)));
        row2.addView(calibrate, new LinearLayout.LayoutParams(dp(116), dp(48)));
        row2.addView(save, new LinearLayout.LayoutParams(dp(88), dp(48)));
        row2.addView(history, new LinearLayout.LayoutParams(dp(100), dp(48)));
        row2.addView(info, new LinearLayout.LayoutParams(dp(76), dp(48)));

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(row2);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        scrollLp.topMargin = dp(6);
        bottom.addView(scroll, scrollLp);

        modeButton.setOnClickListener(v -> cycleMode());
        unitButton.setOnClickListener(v -> cycleUnits());
        undo.setOnClickListener(v -> renderer.undo());
        clear.setOnClickListener(v -> renderer.clear());

        FrameLayout.LayoutParams bottomLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        bottomLp.setMargins(dp(8), 0, dp(8), dp(8));
        root.addView(bottom, bottomLp);

        setContentView(root);
    }

    private TextView smallStatus(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12f);
        tv.setTextColor(0xFFBBBBBB);
        tv.setGravity(Gravity.CENTER);
        return tv;
    }

    private Button createButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(11f);
        b.setAllCaps(false);
        b.setPadding(dp(4), 0, dp(4), 0);
        b.setBackground(panelBackground(0xFF2A2A2A, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.setMargins(dp(3), 0, dp(3), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void stylePrimary(Button b) {
        b.setTextColor(Color.BLACK);
        b.setTextSize(13f);
        b.setBackground(panelBackground(0xFFFF8A00, 10));
    }

    private void addWeighted(LinearLayout row, View view, float weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight);
        lp.setMargins(dp(3), 0, dp(3), 0);
        row.addView(view, lp);
    }

    private GradientDrawable panelBackground(int color, int radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(dp(radiusDp));
        return gd;
    }

    private void cycleMode() {
        switch (mode) {
            case DISTANCE: mode = MeasureRenderer.Mode.HEIGHT; break;
            case HEIGHT: mode = MeasureRenderer.Mode.AREA; break;
            case AREA: default: mode = MeasureRenderer.Mode.DISTANCE; break;
        }
        renderer.setMode(mode);
        modeButton.setText("MODE: " + mode.name());
    }

    private void cycleUnits() {
        MeasurementUtils.UnitMode[] values = MeasurementUtils.UnitMode.values();
        unitMode = values[(unitMode.ordinal() + 1) % values.length];
        renderer.setUnitMode(unitMode);
        unitButton.setText("UNITS: " + unitMode.label);
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderer.setDisplayRotation(getDisplayRotation());
        startArSession();
    }

    private void startArSession() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
            return;
        }

        try {
            if (session == null) {
                ArCoreApk.InstallStatus status = ArCoreApk.getInstance().requestInstall(this, !installRequested);
                if (status == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                    installRequested = true;
                    return;
                }
                session = new Session(this);
                Config config = session.getConfig();
                config.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL);
                config.setFocusMode(Config.FocusMode.AUTO);
                config.setInstantPlacementMode(Config.InstantPlacementMode.DISABLED);
                config.setLightEstimationMode(Config.LightEstimationMode.DISABLED);
                if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                    config.setDepthMode(Config.DepthMode.AUTOMATIC);
                }
                session.configure(config);
                renderer.setSession(session);
            }

            if (!arResumed) {
                session.resume();
                glView.onResume();
                arResumed = true;
            }
        } catch (UnavailableArcoreNotInstalledException | UnavailableUserDeclinedInstallationException e) {
            fatal("Google Play Services for AR is required to measure accurately.");
        } catch (UnavailableApkTooOldException e) {
            fatal("Google Play Services for AR needs to be updated.");
        } catch (UnavailableSdkTooOldException e) {
            fatal("This app build needs a newer ARCore SDK.");
        } catch (UnavailableDeviceNotCompatibleException e) {
            fatal("This device is not compatible with ARCore.");
        } catch (CameraNotAvailableException e) {
            fatal("Camera is not available. Close other camera apps and reopen No-Ad Measure.");
        } catch (Exception e) {
            fatal("Could not start AR: " + e.getMessage());
        }
    }

    @Override
    protected void onPause() {
        if (arResumed) {
            glView.onPause();
            if (session != null) session.pause();
            arResumed = false;
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (session != null) {
            session.close();
            session = null;
        }
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startArSession();
            } else {
                fatal("Camera permission is required for measuring.");
            }
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (renderer != null) {
            renderer.setDisplayRotation(getDisplayRotation());
        }
    }

    private int getDisplayRotation() {
        if (Build.VERSION.SDK_INT >= 30 && getDisplay() != null) return getDisplay().getRotation();
        return getWindowManager().getDefaultDisplay().getRotation();
    }

    private void toggleTorch() {
        if (session == null) return;
        try {
            torchOn = !torchOn;
            Config config = session.getConfig();
            config.setFlashMode(torchOn ? Config.FlashMode.TORCH : Config.FlashMode.OFF);
            session.configure(config);
            torchButton.setText(torchOn ? "TORCH ON" : "TORCH OFF");
        } catch (Exception e) {
            torchOn = false;
            torchButton.setText("TORCH OFF");
            toast("Torch is unavailable in the current camera mode.");
        }
    }

    private void showCalibrationDialog() {
        double rawMeters = renderer.getLastRawTwoPointDistanceMeters();
        if (rawMeters <= 0.0001) {
            toast("Place exactly two points on a known length first.");
            return;
        }

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setHint("Known actual length in " + MeasurementUtils.calibrationPromptUnit(unitMode));
        input.setPadding(dp(18), dp(8), dp(18), dp(8));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Calibrate scale")
                .setMessage("Measure a known reference, then enter its true length. Current correction: " +
                        String.format(Locale.US, "%.4f×", renderer.getCalibrationFactor()))
                .setView(input)
                .setPositiveButton("Apply", null)
                .setNeutralButton("Reset 1.000×", (d, w) -> {
                    renderer.setCalibrationFactor(1.0);
                    prefs.edit().putFloat("calibration", 1.0f).apply();
                    toast("Calibration reset.");
                })
                .setNegativeButton("Cancel", null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                double entered = Double.parseDouble(input.getText().toString().trim());
                double actualMeters = MeasurementUtils.calibrationInputToMeters(entered, unitMode);
                double factor = actualMeters / rawMeters;
                if (factor < 0.5 || factor > 2.0) {
                    input.setError("That would require an implausible correction. Check the units and reference length.");
                    return;
                }
                renderer.setCalibrationFactor(factor);
                prefs.edit().putFloat("calibration", (float) factor).apply();
                toast("Calibration set to " + String.format(Locale.US, "%.4f×", factor));
                dialog.dismiss();
            } catch (Exception ex) {
                input.setError("Enter a numeric length.");
            }
        }));
        dialog.show();
    }

    private void saveMeasurementAndScreenshot() {
        if (latestSnapshot == null || latestSnapshot.pointCount < 2) {
            toast("Finish a measurement first.");
            return;
        }
        saveHistoryRecord(latestSnapshot.resultText);
        captureScreenshot();
    }

    private void saveHistoryRecord(String result) {
        try {
            JSONArray old = new JSONArray(prefs.getString("history", "[]"));
            JSONArray fresh = new JSONArray();
            JSONObject record = new JSONObject();
            record.put("time", System.currentTimeMillis());
            record.put("mode", mode.name());
            record.put("result", result);
            record.put("calibration", renderer.getCalibrationFactor());
            fresh.put(record);
            for (int i = 0; i < old.length() && i < 49; i++) fresh.put(old.get(i));
            prefs.edit().putString("history", fresh.toString()).apply();
        } catch (Exception e) {
            toast("Could not save measurement history.");
        }
    }

    private void showHistory() {
        try {
            JSONArray arr = new JSONArray(prefs.getString("history", "[]"));
            if (arr.length() == 0) {
                toast("No saved measurements yet.");
                return;
            }
            String[] items = new String[arr.length()];
            SimpleDateFormat fmt = new SimpleDateFormat("MMM d, yyyy  h:mm a", Locale.US);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                items[i] = fmt.format(new Date(o.optLong("time"))) + "\n" + o.optString("result");
            }
            new AlertDialog.Builder(this)
                    .setTitle("Measurement history")
                    .setItems(items, null)
                    .setNeutralButton("Clear history", (d, w) -> prefs.edit().remove("history").apply())
                    .setPositiveButton("Close", null)
                    .show();
        } catch (Exception e) {
            toast("History could not be read.");
        }
    }

    private void captureScreenshot() {
        View decor = getWindow().getDecorView();
        int width = decor.getWidth();
        int height = decor.getHeight();
        if (width <= 0 || height <= 0) return;

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        PixelCopy.request(getWindow(), bitmap, result -> {
            if (result == PixelCopy.SUCCESS) saveBitmap(bitmap);
            else toast("Measurement saved, but screenshot capture failed.");
        }, new Handler(Looper.getMainLooper()));
    }

    private void saveBitmap(Bitmap bitmap) {
        String filename = "Measure_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".png";
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/NoAdMeasure");
                Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IllegalStateException("MediaStore insert failed");
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
                toast("Saved measurement + screenshot to Pictures/NoAdMeasure.");
            } else {
                File dir = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "NoAdMeasure");
                if (!dir.exists()) dir.mkdirs();
                File file = new File(dir, filename);
                try (OutputStream out = new FileOutputStream(file)) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
                toast("Saved measurement + screenshot.");
            }
        } catch (Exception e) {
            toast("Measurement saved, but screenshot file could not be written.");
        } finally {
            bitmap.recycle();
        }
    }

    private void showInfoDialog() {
        String message =
                "No-Ad Measure contains no advertising SDK, analytics SDK, account system, or app-level Internet permission. Measurements and history stay on this device.\n\n" +
                "This application runs on Google Play Services for AR (ARCore), which is provided by Google LLC and governed by the Google Privacy Policy.\n\n" +
                "Accuracy tips:\n• Move the phone slowly before measuring so depth can settle.\n• Best depth accuracy is generally at normal room-scale distances.\n• Prefer DEPTH LOCK or PLANE LOCK over FEATURE LOCK.\n• For fabrication-critical work, verify the final dimension with a physical tape or calibrated tool.";
        new AlertDialog.Builder(this)
                .setTitle("No-Ad Measure")
                .setMessage(message)
                .setPositiveButton("Got it", null)
                .show();
    }

    private void fatal(String message) {
        new AlertDialog.Builder(this)
                .setTitle("AR unavailable")
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("Close", (d, w) -> finish())
                .show();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onSnapshot(MeasureRenderer.Snapshot snapshot) {
        runOnUiThread(() -> {
            latestSnapshot = snapshot;
            resultText.setText(snapshot.resultText);
            trackingText.setText(snapshot.trackingText);
            lockText.setText(snapshot.lockText);
            if (snapshot.lockText.startsWith("DEPTH")) lockText.setTextColor(0xFF66BB6A);
            else if (snapshot.lockText.startsWith("PLANE")) lockText.setTextColor(0xFFFFA726);
            else if (snapshot.lockText.startsWith("FEATURE")) lockText.setTextColor(0xFFFFEE58);
            else lockText.setTextColor(0xFFEF5350);
            overlayView.setMeasurementGeometry(snapshot.points, snapshot.segments);
        });
    }

    @Override
    public void onPointPlaced() {
        runOnUiThread(() -> {
            glView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        });
    }

    @Override
    public void onMessage(String message) {
        runOnUiThread(() -> toast(message));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
