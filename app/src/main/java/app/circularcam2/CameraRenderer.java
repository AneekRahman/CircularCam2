package app.circularcam2;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.Display;
import android.view.Surface;
import android.view.TextureView;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;


import app.circularcam2.filter.CameraFilter;
import app.circularcam2.filter.ChromaticAberrationFilter;

import static android.content.Context.WINDOW_SERVICE;
import static app.circularcam2.CameraUtils.chooseFixedPreviewFps;
import static app.circularcam2.CameraUtils.getBackFacingCameraID;
import static app.circularcam2.CameraUtils.getFrontFacingCameraID;
import static app.circularcam2.CameraUtils.setCameraOrientation;
import static app.circularcam2.CameraUtils.setPreviewSize;

public class CameraRenderer implements TextureView.SurfaceTextureListener, Runnable {

    // Camera orientation variables
    private static final int SENSOR_ORIENTATION_DEFAULT_DEGREES = 90;
    private static final int SENSOR_ORIENTATION_INVERSE_DEGREES = 270;
    private static final SparseIntArray DEFAULT_ORIENTATIONS = new SparseIntArray();
    private static final SparseIntArray INVERSE_ORIENTATIONS = new SparseIntArray();
    static {
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_0, 90);
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_90, 0);
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_180, 270);
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }
    static {
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_0, 270);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_90, 180);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_180, 90);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_270, 0);
    }
    ///////////////////////////////////////////////////////////////////////////////////

    private static final String TAG = "Camera-Renderer::::::";


    private static final int EGL_OPENGL_ES2_BIT = 4;
    private static final int EGL_CONTEXT_CLIENT_VERSION = 0x3098;
    private static final int DRAW_INTERVAL = 1000 / 30;

    private Context mContext;

    private Camera mCamera;
    private int mCameraId;
    private SurfaceTexture mSurfaceTexture, mCameraSurfaceTexture;
    private int mCameraTextureId;
    private int mHeight;
    private int mWidth;

    private Thread mRenderThread;

    private int mSelectedFilterId = 0;
    private CameraFilter mSelectedCameraFilter;
    private SparseArray<CameraFilter> mCameraFilterList = new SparseArray<>();

    private CircularEncoder mCircularEncoder;
    private static int VIDEO_WIDTH = 1080;  // dimensions for 720p video
    private static int VIDEO_HEIGHT = 1920;
    private static final int VIDEO_BIT_RATE = 6000000;
    private static final int DESIRED_PREVIEW_FPS = 20;
    private int mCameraPreviewThousandFps;
    private MainHandler mHandler;

    private WindowSurface mWindowSurface, mInputSurface;
    private EglCore mEGLCore;

    private boolean isRecording = false;


    // Instance of CameraRenderer
    public CameraRenderer(Context context) {
        this.mContext = context;
    }

    public void startEncoding(){

        mCircularEncoder = new CircularEncoder(mWidth, mHeight, VIDEO_BIT_RATE, mCameraPreviewThousandFps / 1000, 20, mHandler);
        mInputSurface = new WindowSurface(mEGLCore, mCircularEncoder.getInputSurface(), true);
        isRecording = true;

    }

    public void stopEncoding(){

        isRecording = false;
        mCircularEncoder.saveVideo(createOutputFile());
        mCircularEncoder.shutdown();
        mInputSurface.release();

    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {

        if (mRenderThread != null && mRenderThread.isAlive()) {
            mRenderThread.interrupt();
        }
        mRenderThread = new Thread(this);

        mSurfaceTexture = surfaceTexture;
        mHeight = -height;
        mWidth = -width;

        mCamera = openCamera(width, height, DESIRED_PREVIEW_FPS);

        mHandler = new MainHandler(this);

        mRenderThread.start();

    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {

        mHeight = -height;
        mWidth = -width;

    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {

        releaseRenderer();

        mWindowSurface.release();
        //mInputSurface.release();
        mEGLCore.release();

        releaseCamera();

        return true;

    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {/* EMPTY */}

    @Override
    public void run() {

        mEGLCore = new EglCore(null);
        mWindowSurface = new WindowSurface(mEGLCore, mSurfaceTexture);
        mWindowSurface.makeCurrent();

        loadAllFilters();

        if(mCamera != null) startCameraPreview();

        // Render loop
        while (!Thread.currentThread().isInterrupted()) {

            if (mWidth < 0 && mWidth < 0) GLES20.glViewport(0, 0, mWidth = -mWidth, mHeight = -mHeight);

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            // Update the camera preview texture
            synchronized (this) {
                mWindowSurface.makeCurrent();
                mCameraSurfaceTexture.updateTexImage();
            }

            // Draw camera preview
            mSelectedCameraFilter.draw(mCameraTextureId, mWidth, mHeight);

            // Flush
            GLES20.glFlush();
            mWindowSurface.swapBuffers();

            if(isRecording){
                synchronized (this) {
                    mInputSurface.makeCurrent();
                    mSelectedCameraFilter.draw(mCameraTextureId, mWidth, mHeight);
                    mCircularEncoder.frameAvailableSoon();
                    mInputSurface.swapBuffers();
                }
            }
        }

        mCameraSurfaceTexture.release();
        GLES20.glDeleteTextures(1, new int[]{mCameraTextureId}, 0);

    }

    private void loadAllFilters(){

        // Setup camera filters map
        mCameraFilterList.append(0, new ChromaticAberrationFilter(mContext));
        setSelectedFilter(mSelectedFilterId);

    }

    private void releaseRenderer(){

        if (mRenderThread != null && mRenderThread.isAlive()) {
            mRenderThread.interrupt();
        }
        CameraFilter.release();

    }

                                 // Camera related defined methods

    private Camera openCamera(int desiredWidth, int desiredHeight, int desiredFps) {

        Camera camera = null;

        if(getFrontFacingCameraID() > 0){
            camera = Camera.open(getFrontFacingCameraID());
        }else if(getBackFacingCameraID() >= 0){
            camera = Camera.open(getBackFacingCameraID());
        }
        if (camera == null) { throw new RuntimeException("Unable to open camera"); }

        setCameraParameters(camera, desiredWidth, desiredHeight, desiredFps);

        Toast.makeText(mContext, "Initialized camera", Toast.LENGTH_SHORT).show();

        return camera;

    }

    private void setCameraParameters(Camera camera, int desiredWidth, int desiredHeight, int desiredFps){

        Camera.Parameters params = camera.getParameters();
        List<String> focusModes = params.getSupportedFocusModes();
        if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
            params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
        }else if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)){
            params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
        }else if(focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)){
            params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
        }
        setPreviewSize(params, desiredWidth, desiredHeight);
        // Try to set the frame rate to a constant value.
        mCameraPreviewThousandFps = chooseFixedPreviewFps(params, desiredFps * 1000);

        params.setRecordingHint(true);

        camera.setParameters(params);

        Camera.Size cameraPreviewSize = params.getPreviewSize();
        Log.i(TAG, "Camera config: " + cameraPreviewSize.width + "x" + cameraPreviewSize.height + " @" + (mCameraPreviewThousandFps / 1000.0f) + "fps");

    }


    // Camera preview init
    private void startCameraPreview(){

        // Create texture for camera preview
        mCameraTextureId = MyGLUtils.genTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);
        mCameraSurfaceTexture = new SurfaceTexture(mCameraTextureId);

        // Start camera preview
        try {
            mCamera.setPreviewTexture(mCameraSurfaceTexture);
            mCamera.startPreview();
        } catch (IOException ioe) {}

    }

    private void releaseCamera(){

        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
        }

    }

    public void setSelectedFilter(@NonNull int id) {
        mSelectedFilterId = id;
        mSelectedCameraFilter = mCameraFilterList.get(id);
        if (mSelectedCameraFilter != null)
            mSelectedCameraFilter.onAttach();
    }

    private File createOutputFile(){

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyymmddhhmmss");
        String date = dateFormat.format(new Date());
        File outFile = new File(Environment.getExternalStorageDirectory(), "circam_" + date + ".mp4");
        if(outFile.exists()) outFile.delete();
        return outFile;

    }

    private static class MainHandler extends Handler implements CircularEncoder.Callback {
        public static final int MSG_BLINK_TEXT = 0;
        public static final int MSG_FRAME_AVAILABLE = 1;
        public static final int MSG_FILE_SAVE_COMPLETE = 2;
        public static final int MSG_BUFFER_STATUS = 3;

        private WeakReference<CameraRenderer> mWeakActivity;

        public MainHandler(CameraRenderer activity) {
            mWeakActivity = new WeakReference<CameraRenderer>(activity);
        }

        // CircularEncoder.Callback, called on encoder thread
        @Override
        public void fileSaveComplete(int status) {
            sendMessage(obtainMessage(MSG_FILE_SAVE_COMPLETE, status, 0, null));
        }

        // CircularEncoder.Callback, called on encoder thread
        @Override
        public void bufferStatus(long totalTimeMsec) {
            sendMessage(obtainMessage(MSG_BUFFER_STATUS,
                    (int) (totalTimeMsec >> 32), (int) totalTimeMsec));
        }


        @Override
        public void handleMessage(Message msg) {
            CameraRenderer activity = mWeakActivity.get();
            if (activity == null) {
                Log.d(TAG, "Got message for dead activity");
                return;
            }

            switch (msg.what) {
                case MSG_BLINK_TEXT: {
                    //TODO Do something
                    break;
                }
                case MSG_FRAME_AVAILABLE: {
                    //TODO Do something
                    break;
                }
                case MSG_FILE_SAVE_COMPLETE: {
                    //TODO Do something
                    break;
                }
                case MSG_BUFFER_STATUS: {
                    //TODO Do something
                    break;
                }
                default:
                    throw new RuntimeException("Unknown message " + msg.what);
            }
        }
    }


}
