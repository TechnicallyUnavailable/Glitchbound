package com.technicallyunavailable.measure;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import com.google.ar.core.Anchor;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.DepthPoint;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.Point;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class MeasureRenderer implements GLSurfaceView.Renderer {
    public enum Mode { DISTANCE, HEIGHT, AREA }

    public interface Listener {
        void onSnapshot(Snapshot snapshot);
        void onPointPlaced();
        void onMessage(String message);
    }

    public static final class ScreenPoint {
        public final float x, y;
        public final int index;
        public final boolean visible;
        ScreenPoint(float x, float y, int index, boolean visible) {
            this.x = x; this.y = y; this.index = index; this.visible = visible;
        }
    }

    public static final class ScreenSegment {
        public final float x1, y1, x2, y2;
        public final String label;
        public final boolean visible;
        ScreenSegment(float x1, float y1, float x2, float y2, String label, boolean visible) {
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2;
            this.label = label; this.visible = visible;
        }
    }

    public static final class Snapshot {
        public final List<ScreenPoint> points;
        public final List<ScreenSegment> segments;
        public final String resultText;
        public final String trackingText;
        public final String lockText;
        public final int pointCount;
        public final double rawTwoPointDistanceMeters;

        Snapshot(List<ScreenPoint> points, List<ScreenSegment> segments, String resultText,
                 String trackingText, String lockText, int pointCount, double rawDistance) {
            this.points = points;
            this.segments = segments;
            this.resultText = resultText;
            this.trackingText = trackingText;
            this.lockText = lockText;
            this.pointCount = pointCount;
            this.rawTwoPointDistanceMeters = rawDistance;
        }
    }

    private static final class HitRequest {
        final float x, y;
        HitRequest(float x, float y) { this.x = x; this.y = y; }
    }

    private final CameraBackgroundRenderer background = new CameraBackgroundRenderer();
    private final ConcurrentLinkedQueue<Runnable> commands = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<HitRequest> hitRequests = new ConcurrentLinkedQueue<>();
    private final ArrayList<Anchor> anchors = new ArrayList<>();
    private final Listener listener;

    private volatile Session session;
    private volatile MeasurementUtils.UnitMode unitMode = MeasurementUtils.UnitMode.FEET_INCHES;
    private volatile Mode mode = Mode.DISTANCE;
    private volatile double calibrationFactor = 1.0;
    private volatile double lastRawTwoPointDistanceMeters = 0.0;

    private int viewportWidth;
    private int viewportHeight;
    private int displayRotation;
    private boolean displayGeometryDirty = true;
    private long lastUiUpdateNs;

    public MeasureRenderer(Listener listener) {
        this.listener = listener;
    }

    public void setSession(Session session) {
        this.session = session;
        displayGeometryDirty = true;
    }

    public void setDisplayRotation(int rotation) {
        this.displayRotation = rotation;
        displayGeometryDirty = true;
    }

    public void setUnitMode(MeasurementUtils.UnitMode unitMode) {
        this.unitMode = unitMode;
    }

    public void setCalibrationFactor(double factor) {
        this.calibrationFactor = factor;
    }

    public double getCalibrationFactor() { return calibrationFactor; }
    public double getLastRawTwoPointDistanceMeters() { return lastRawTwoPointDistanceMeters; }

    public void requestHit(float x, float y) {
        hitRequests.offer(new HitRequest(x, y));
    }

    public void clear() {
        commands.offer(this::clearAnchorsInternal);
    }

    public void undo() {
        commands.offer(() -> {
            if (!anchors.isEmpty()) {
                Anchor a = anchors.remove(anchors.size() - 1);
                a.detach();
            }
        });
    }

    public void setMode(Mode newMode) {
        mode = newMode;
        commands.offer(this::clearAnchorsInternal);
    }

    private void clearAnchorsInternal() {
        for (Anchor a : anchors) a.detach();
        anchors.clear();
        lastRawTwoPointDistanceMeters = 0.0;
    }

    @Override
    public void onSurfaceCreated(javax.microedition.khronos.opengles.GL10 gl,
                                 javax.microedition.khronos.egl.EGLConfig config) {
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        background.createOnGlThread();
    }

    @Override
    public void onSurfaceChanged(javax.microedition.khronos.opengles.GL10 gl, int width, int height) {
        viewportWidth = width;
        viewportHeight = height;
        GLES20.glViewport(0, 0, width, height);
        displayGeometryDirty = true;
    }

    @Override
    public void onDrawFrame(javax.microedition.khronos.opengles.GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        Session localSession = session;
        if (localSession == null || viewportWidth <= 0 || viewportHeight <= 0) return;

        try {
            if (displayGeometryDirty) {
                localSession.setDisplayGeometry(displayRotation, viewportWidth, viewportHeight);
                displayGeometryDirty = false;
            }

            localSession.setCameraTextureName(background.getTextureId());
            Frame frame = localSession.update();
            background.draw(frame);

            Runnable command;
            while ((command = commands.poll()) != null) command.run();

            Camera camera = frame.getCamera();
            TrackingState trackingState = camera.getTrackingState();
            String lockText = trackingState == TrackingState.TRACKING ? probeLock(frame) : "NO LOCK";

            if (trackingState == TrackingState.TRACKING) {
                HitRequest req;
                while ((req = hitRequests.poll()) != null) {
                    placeAnchor(frame, req.x, req.y);
                }
            } else {
                hitRequests.clear();
            }

            maybePublishSnapshot(camera, trackingState, lockText);
        } catch (Throwable t) {
            if (listener != null) listener.onMessage("AR frame error: " + t.getMessage());
        }
    }

    private void placeAnchor(Frame frame, float x, float y) {
        HitResult chosen = chooseReliableHit(frame.hitTest(x, y));
        if (chosen == null) {
            if (listener != null) listener.onMessage("No reliable surface lock. Move the phone slightly and try again.");
            return;
        }

        if ((mode == Mode.DISTANCE || mode == Mode.HEIGHT) && anchors.size() >= 2) {
            clearAnchorsInternal();
        }
        if (mode == Mode.AREA && anchors.size() >= 32) {
            if (listener != null) listener.onMessage("Area mode is limited to 32 points.");
            return;
        }

        anchors.add(chosen.createAnchor());
        if (listener != null) listener.onPointPlaced();
    }

    private HitResult chooseReliableHit(List<HitResult> hits) {
        for (HitResult hit : hits) if (hit.getTrackable() instanceof DepthPoint) return hit;
        for (HitResult hit : hits) {
            if (hit.getTrackable() instanceof Plane) {
                Plane plane = (Plane) hit.getTrackable();
                if (plane.isPoseInPolygon(hit.getHitPose())) return hit;
            }
        }
        for (HitResult hit : hits) {
            if (hit.getTrackable() instanceof Point) {
                Point p = (Point) hit.getTrackable();
                if (p.getOrientationMode() == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL) return hit;
            }
        }
        return null;
    }

    private String probeLock(Frame frame) {
        List<HitResult> hits = frame.hitTest(viewportWidth * 0.5f, viewportHeight * 0.5f);
        for (HitResult h : hits) if (h.getTrackable() instanceof DepthPoint) return "DEPTH LOCK";
        for (HitResult h : hits) {
            if (h.getTrackable() instanceof Plane && ((Plane) h.getTrackable()).isPoseInPolygon(h.getHitPose())) {
                return "PLANE LOCK";
            }
        }
        for (HitResult h : hits) if (h.getTrackable() instanceof Point) return "FEATURE LOCK";
        return "NO SURFACE";
    }

    private void maybePublishSnapshot(Camera camera, TrackingState trackingState, String lockText) {
        long now = System.nanoTime();
        if (now - lastUiUpdateNs < 80_000_000L) return;
        lastUiUpdateNs = now;

        float[] projection = new float[16];
        float[] view = new float[16];
        float[] vp = new float[16];
        camera.getProjectionMatrix(projection, 0, 0.05f, 100f);
        camera.getViewMatrix(view, 0);
        Matrix.multiplyMM(vp, 0, projection, 0, view, 0);

        ArrayList<ScreenPoint> screenPoints = new ArrayList<>();
        ArrayList<float[]> projected = new ArrayList<>();
        for (int i = 0; i < anchors.size(); i++) {
            Pose p = anchors.get(i).getPose();
            float[] sp = project(vp, p.tx(), p.ty(), p.tz());
            projected.add(sp);
            screenPoints.add(new ScreenPoint(sp[0], sp[1], i, sp[2] > 0.5f));
        }

        String result = buildResultText();
        ArrayList<ScreenSegment> segments = buildSegments(projected);
        String tracking = trackingState == TrackingState.TRACKING ? "AR TRACKING" :
                trackingState == TrackingState.PAUSED ? "MOVE PHONE TO SCAN" : "AR STOPPED";

        Snapshot snapshot = new Snapshot(
                Collections.unmodifiableList(screenPoints),
                Collections.unmodifiableList(segments),
                result, tracking, lockText, anchors.size(), lastRawTwoPointDistanceMeters);
        if (listener != null) listener.onSnapshot(snapshot);
    }

    private ArrayList<ScreenSegment> buildSegments(ArrayList<float[]> projected) {
        ArrayList<ScreenSegment> result = new ArrayList<>();
        for (int i = 1; i < anchors.size(); i++) {
            float[] a = projected.get(i - 1);
            float[] b = projected.get(i);
            double d = distance(anchors.get(i - 1).getPose(), anchors.get(i).getPose()) * calibrationFactor;
            result.add(new ScreenSegment(a[0], a[1], b[0], b[1],
                    MeasurementUtils.formatLength(d, unitMode), a[2] > 0.5f && b[2] > 0.5f));
        }
        if (mode == Mode.AREA && anchors.size() >= 3) {
            int last = anchors.size() - 1;
            float[] a = projected.get(last);
            float[] b = projected.get(0);
            double d = distance(anchors.get(last).getPose(), anchors.get(0).getPose()) * calibrationFactor;
            result.add(new ScreenSegment(a[0], a[1], b[0], b[1],
                    MeasurementUtils.formatLength(d, unitMode), a[2] > 0.5f && b[2] > 0.5f));
        }
        return result;
    }

    private String buildResultText() {
        int n = anchors.size();
        if (n == 0) {
            lastRawTwoPointDistanceMeters = 0.0;
            return mode == Mode.AREA ? "AREA · Add at least 3 corner points" : "Aim, then tap ADD POINT";
        }
        if (n == 1) {
            lastRawTwoPointDistanceMeters = 0.0;
            return mode == Mode.AREA ? "AREA · Add 2 more corner points" : "Point 1 locked · Add point 2";
        }

        Pose a = anchors.get(0).getPose();
        Pose b = anchors.get(1).getPose();
        lastRawTwoPointDistanceMeters = distance(a, b);

        if (mode == Mode.DISTANCE) {
            double d = lastRawTwoPointDistanceMeters * calibrationFactor;
            return "DISTANCE  " + MeasurementUtils.formatLength(d, unitMode);
        }
        if (mode == Mode.HEIGHT) {
            double vertical = Math.abs(b.ty() - a.ty()) * calibrationFactor;
            double straight = lastRawTwoPointDistanceMeters * calibrationFactor;
            return "HEIGHT  " + MeasurementUtils.formatLength(vertical, unitMode) +
                    "   ·   LINE  " + MeasurementUtils.formatLength(straight, unitMode);
        }
        if (n < 3) {
            return "AREA · Add 1 more corner point";
        }

        double perimeter = 0.0;
        for (int i = 1; i < n; i++) perimeter += distance(anchors.get(i - 1).getPose(), anchors.get(i).getPose());
        perimeter += distance(anchors.get(n - 1).getPose(), anchors.get(0).getPose());
        perimeter *= calibrationFactor;
        double area = polygonArea3d() * calibrationFactor * calibrationFactor;
        return "AREA  " + MeasurementUtils.formatArea(area, unitMode) +
                "   ·   PERIMETER  " + MeasurementUtils.formatLength(perimeter, unitMode);
    }

    private double polygonArea3d() {
        if (anchors.size() < 3) return 0.0;
        Pose origin = anchors.get(0).getPose();
        double area = 0.0;
        for (int i = 1; i < anchors.size() - 1; i++) {
            Pose p1 = anchors.get(i).getPose();
            Pose p2 = anchors.get(i + 1).getPose();
            double ax = p1.tx() - origin.tx();
            double ay = p1.ty() - origin.ty();
            double az = p1.tz() - origin.tz();
            double bx = p2.tx() - origin.tx();
            double by = p2.ty() - origin.ty();
            double bz = p2.tz() - origin.tz();
            double cx = ay * bz - az * by;
            double cy = az * bx - ax * bz;
            double cz = ax * by - ay * bx;
            area += 0.5 * Math.sqrt(cx * cx + cy * cy + cz * cz);
        }
        return area;
    }

    private float[] project(float[] vp, float x, float y, float z) {
        float[] in = {x, y, z, 1f};
        float[] out = new float[4];
        Matrix.multiplyMV(out, 0, vp, 0, in, 0);
        if (Math.abs(out[3]) < 1e-5f) return new float[]{0f, 0f, 0f};
        float ndcX = out[0] / out[3];
        float ndcY = out[1] / out[3];
        float screenX = (ndcX + 1f) * 0.5f * viewportWidth;
        float screenY = (1f - ndcY) * 0.5f * viewportHeight;
        float visible = out[3] > 0f && ndcX >= -1.2f && ndcX <= 1.2f && ndcY >= -1.2f && ndcY <= 1.2f ? 1f : 0f;
        return new float[]{screenX, screenY, visible};
    }

    private static double distance(Pose a, Pose b) {
        double dx = a.tx() - b.tx();
        double dy = a.ty() - b.ty();
        double dz = a.tz() - b.tz();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
