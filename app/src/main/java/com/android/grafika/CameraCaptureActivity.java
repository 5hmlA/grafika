/*
 * Copyright 2013 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.grafika;

import android.opengl.EGL14;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.widget.Toast;

import com.android.grafika.gles.FullFrameRect;
import com.android.grafika.gles.Texture2dProgram;
import com.google.grafika.R;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Shows the camera preview on screen while simultaneously recording it to a .mp4 file.
 * <p>
 * Every time we receive a frame from the camera, we need to:
 * <ul>
 * <li>Render the frame to the SurfaceView, on GLSurfaceView's renderer thread.
 * <li>Render the frame to the mediacodec's input surface, on the encoder thread, if
 *     recording is enabled.
 * </ul>
 * <p>
 * At any given time there are four things in motion:
 * <ol>
 * <li>The UI thread, embodied by this Activity.  We must respect -- or work around -- the
 *     app lifecycle changes.  In particular, we need to release and reacquire the Camera
 *     so that, if the user switches away from us, we're not preventing another app from
 *     using the camera.
 * <li>The Camera, which will busily generate preview frames once we hand it a
 *     SurfaceTexture.  We'll get notifications on the main UI thread unless we define a
 *     Looper on the thread where the SurfaceTexture is created (the GLSurfaceView renderer
 *     thread).
 * <li>The video encoder thread, embodied by TextureMovieEncoder.  This needs to share
 *     the Camera preview external texture with the GLSurfaceView renderer, which means the
 *     EGLContext in this thread must be created with a reference to the renderer thread's
 *     context in hand.
 * <li>The GLSurfaceView renderer thread, embodied by CameraSurfaceRenderer.  The thread
 *     is created for us by GLSurfaceView.  We don't get callbacks for pause/resume or
 *     thread startup/shutdown, though we could generate messages from the Activity for most
 *     of these things.  The EGLContext created on this thread must be shared with the
 *     video encoder, and must be used to create a SurfaceTexture that is used by the
 *     Camera.  As the creator of the SurfaceTexture, it must also be the one to call
 *     updateTexImage().  The renderer thread is thus at the center of a multi-thread nexus,
 *     which is a bit awkward since it's the thread we have the least control over.
 * </ol>
 * <p>
 * GLSurfaceView is fairly painful here.  Ideally we'd create the video encoder, create
 * an EGLContext for it, and pass that into GLSurfaceView to share.  The API doesn't allow
 * this, so we have to do it the other way around.  When GLSurfaceView gets torn down
 * (say, because we rotated the device), the EGLContext gets tossed, which means that when
 * it comes back we have to re-create the EGLContext used by the video encoder.  (And, no,
 * the "preserve EGLContext on pause" feature doesn't help.)
 * <p>
 * We could simplify this quite a bit by using TextureView instead of GLSurfaceView, but that
 * comes with a performance hit.  We could also have the renderer thread drive the video
 * encoder directly, allowing them to work from a single EGLContext, but it's useful to
 * decouple the operations, and it's generally unwise to perform disk I/O on the thread that
 * renders your UI.
 * <p>
 * We want to access Camera from the UI thread (setup, teardown) and the renderer thread
 * (configure SurfaceTexture, start preview), but the API says you can only access the object
 * from a single thread.  So we need to pick one thread to own it, and the other thread has to
 * access it remotely.  Some things are simpler if we let the renderer thread manage it,
 * but we'd really like to be sure that Camera is released before we leave onPause(), which
 * means we need to make a synchronous call from the UI thread into the renderer thread, which
 * we don't really have full control over.  It's less scary to have the UI thread own Camera
 * and have the renderer call back into the UI thread through the standard Handler mechanism.
 * <p>
 * (The <a href="http://developer.android.com/training/camera/cameradirect.html#TaskOpenCamera">
 * camera docs</a> recommend accessing the camera from a non-UI thread to avoid bogging the
 * UI thread down.  Since the GLSurfaceView-managed renderer thread isn't a great choice,
 * we might want to create a dedicated camera thread.  Not doing that here.)
 * <p>
 * With three threads working simultaneously (plus Camera causing periodic events as frames
 * arrive) we have to be very careful when communicating state changes.  In general we want
 * to send a message to the thread, rather than directly accessing state in the object.
 * <p>
 * &nbsp;
 * <p>
 * To exercise the API a bit, the video encoder is required to survive Activity restarts.  In the
 * current implementation it stops recording but doesn't stop time from advancing, so you'll
 * see a pause in the video.  (We could adjust the timer to make it seamless, or output a
 * "paused" message and hold on that in the recording, or leave the Camera running so it
 * continues to generate preview frames while the Activity is paused.)  The video encoder object
 * is managed as a static property of the Activity.
 * 
 * 📷 相机预览和录制Activity
 * 在屏幕上显示相机预览，同时录制为mp4文件
 * 涉及UI线程、相机、编码器线程和渲染线程四个组件的协调
 * 使用GLSurfaceView进行OpenGL渲染
 */
public class CameraCaptureActivity extends Activity
        implements SurfaceTexture.OnFrameAvailableListener, OnItemSelectedListener {
    private static final String TAG = MainActivity.TAG;
    private static final boolean VERBOSE = false;

    // Camera filters; must match up with cameraFilterNames in strings.xml
    // 🎛️ 相机滤镜常量（必须与strings.xml中的cameraFilterNames匹配）
    static final int FILTER_NONE = 0;           // 🚫 无滤镜
    static final int FILTER_BLACK_WHITE = 1;    // ⬛ 黑白滤镜
    static final int FILTER_BLUR = 2;           // 🌫️ 模糊滤镜
    static final int FILTER_SHARPEN = 3;        // 🔍 锐化滤镜
    static final int FILTER_EDGE_DETECT = 4;    // ✏️ 边缘检测滤镜
    static final int FILTER_EMBOSS = 5;         // 🏛️ 浮雕滤镜

    // 🖥️ GLSurfaceView和渲染器
    private GLSurfaceView mGLView;
    private CameraSurfaceRenderer mRenderer;
    // 📷 相机和Handler
    private Camera mCamera;
    private CameraHandler mCameraHandler;
    private boolean mRecordingEnabled;      // controls button state
    // 🔴 录制状态（控制按钮状态）

    // 📐 相机预览尺寸
    private int mCameraPreviewWidth, mCameraPreviewHeight;

    // this is static so it survives activity restarts
    // 🎥 视频编码器（静态存储，跨Activity重启存活）
    private static TextureMovieEncoder sVideoEncoder = new TextureMovieEncoder();

    // 🎯 Activity创建时初始化相机和GLSurfaceView
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 📱 super.onCreate：调用父类的onCreate方法
        // 🔍 为什么调用：必须首先调用父类初始化
        // 💡 作用：完成Activity的基础初始化
        // ⏰ 使用时机：在任何自定义初始化之前
        super.onCreate(savedInstanceState);
        
        // 🖥️ setContentView：设置Activity的布局文件
        // 🔍 为什么调用：需要指定UI界面
        // 💡 作用：将XML布局文件加载到Activity
        // ⏰ 使用时机：在super.onCreate之后立即调用
        setContentView(R.layout.activity_camera_capture);

        // 📁 outputFile：视频输出文件对象
        // 🔍 为什么定义：需要指定录制视频的保存位置
        // 💡 作用：存储录制的视频文件
        // ⏰ 使用时机：传递给渲染器，录制时写入
        // 📁 设置输出文件路径
        File outputFile = new File(getFilesDir(), "camera-test.mp4");
        
        // 📝 fileText：显示输出文件路径的文本控件
        // 🔍 为什么定义：需要向用户展示文件保存位置
        // 💡 作用：显示视频文件的完整路径
        // ⏰ 使用时机：立即设置文本内容
        TextView fileText = (TextView) findViewById(R.id.cameraOutputFile_text);
        fileText.setText(outputFile.toString());

        // 🎛️ spinner：滤镜选择下拉框控件
        // 🔍 为什么定义：需要让用户选择不同的滤镜效果
        // 💡 作用：显示可用的滤镜选项
        // ⏰ 使用时机：立即设置适配器和监听器
        // 🎛️ 设置滤镜选择下拉框
        Spinner spinner = (Spinner) findViewById(R.id.cameraFilter_spinner);
        
        // 📋 adapter：下拉框的数据适配器
        // 🔍 为什么定义：需要提供下拉框的选项数据
        // 💡 作用：从strings.xml加载滤镜名称列表
        // ⏰ 使用时机：立即设置到spinner
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.cameraFilterNames, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        
        // 📋 setAdapter：设置下拉框的数据源
        // 💡 作用：将滤镜名称列表绑定到下拉框
        spinner.setAdapter(adapter);
        
        // 📧 setOnItemSelectedListener：设置选择事件监听器
        // 💡 作用：当用户选择滤镜时触发回调
        spinner.setOnItemSelectedListener(this);

        // Define a handler that receives camera-control messages from other threads.  All calls
        // to Camera must be made on the same thread.  Note we create this before the renderer
        // thread, so we know the fully-constructed object will be visible.
        // 📬 mCameraHandler：相机操作的消息处理器
        // 🔍 为什么定义：需要在单一线程中处理所有Camera操作
        // 💡 作用：接收其他线程的相机操作请求
        // ⏰ 使用时机：在渲染器线程创建之前创建，确保可见性
        // 📬 创建相机Handler（所有Camera调用必须在同一线程）
        mCameraHandler = new CameraHandler(this);

        // 🎥 mRecordingEnabled：录制是否启用
        // 🔍 为什么检查：需要恢复之前的录制状态
        // 💡 作用：确定当前是否在录制
        // ⏰ 使用时机：在初始化时检查编码器状态
        // 🎥 检查录制状态
        mRecordingEnabled = sVideoEncoder.isRecording();

        // Configure the GLSurfaceView.  This will start the Renderer thread, with an
        // appropriate EGL context.
        // 🖥️ mGLView：OpenGL渲染视图
        // 🔍 为什么定义：需要显示摄像头预览
        // 💡 作用：管理OpenGL渲染的SurfaceView
        // ⏰ 使用时机：立即配置EGL上下文和渲染器
        // 🖥️ 配置GLSurfaceView（GLES 2.0）
        mGLView = (GLSurfaceView) findViewById(R.id.cameraPreview_surfaceView);
        
        // 🎮 setEGLContextClientVersion：设置OpenGL ES版本
        // 💡 参数2：使用OpenGL ES 2.0
        mGLView.setEGLContextClientVersion(2);     // select GLES 2.0
        
        // 🎨 mRenderer：相机预览渲染器
        // 🔍 为什么创建：需要处理摄像头帧的渲染
        // 💡 作用：管理OpenGL绘制和视频编码
        // ⏰ 使用时机：在GLSurfaceView配置后创建
        mRenderer = new CameraSurfaceRenderer(mCameraHandler, sVideoEncoder, outputFile);
        
        // 🖥️ setRenderer：设置GLSurfaceView的渲染器
        // 💡 作用：将渲染器绑定到视图
        mGLView.setRenderer(mRenderer);
        
        // 🔄 setRenderMode：设置渲染模式
        // 💡 RENDERMODE_WHEN_DIRTY：只在需要时渲染（而非持续渲染）
        // 💡 作用：节省电量，只在帧可用时渲染
        mGLView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);

        // 📝 日志输出：记录onCreate完成
        Log.d(TAG, "onCreate complete: " + this);
    }

    // ▶️ Activity恢复时打开相机
    @Override
    protected void onResume() {
        // 📝 日志输出：记录正在获取相机
        Log.d(TAG, "onResume -- acquiring camera");
        
        // 📱 super.onResume：调用父类的onResume方法
        // 🔍 为什么调用：必须首先调用父类的生命周期方法
        // 💡 作用：完成Activity的标准恢复流程
        // ⏰ 使用时机：在任何自定义恢复逻辑之前
        super.onResume();
        
        // 🔄 updateControls：更新界面控件状态
        // 💡 作用：同步录制按钮的显示状态
        // ⏰ 使用时机：在Activity恢复时刷新UI
        updateControls();

        // 🔐 hasCameraPermission：检查相机权限
        // 🔍 为什么检查：需要确保有权限才能打开相机
        // 💡 作用：避免在无权限时尝试打开相机导致异常
        // ⏰ 使用时机：在打开相机之前检查
        // 🔐 检查相机权限
        if (PermissionHelper.hasCameraPermission(this)) {
            // 📷 mCamera：相机对象
            // 🔍 为什么检查：可能在某些情况下相机已被释放
            // 💡 作用：避免重复打开相机
            // ⏰ 使用时机：在打开相机之前检查
            if (mCamera == null) {
                // 📷 openCamera：打开相机并设置预览尺寸
                // 💡 参数(1280, 720)：期望的预览分辨率
                // 💡 作用：初始化相机硬件，开始预览
                openCamera(1280, 720);      // updates mCameraPreviewWidth/Height
            }

        } else {
            // 🔐 requestCameraPermission：请求相机权限
            // 💡 参数false：不显示权限说明对话框
            // 💡 作用：向用户请求相机使用权限
            PermissionHelper.requestCameraPermission(this, false);
        }

        // 🔄 onResume：恢复GLSurfaceView的渲染
        // 🔍 为什么调用：GLSurfaceView在onPause时停止了渲染
        // 💡 作用：重新启动渲染线程
        // ⏰ 使用时机：在检查权限和打开相机之后
        // 🔄 恢复GLSurfaceView并设置预览尺寸
        mGLView.onResume();
        
        // 📨 queueEvent：将任务投递到渲染线程执行
        // 🔍 为什么使用：setCameraPreviewSize必须在渲染线程调用
        // 💡 作用：异步更新渲染器的预览尺寸
        // ⏰ 使用时机：在GLSurfaceView恢复后
        mGLView.queueEvent(new Runnable() {
            @Override public void run() {
                // 📐 setCameraPreviewSize：设置渲染器的预览尺寸
                // 💡 参数：从openCamera获取的实际预览尺寸
                mRenderer.setCameraPreviewSize(mCameraPreviewWidth, mCameraPreviewHeight);
            }
        });
        
        // 📝 日志输出：记录onResume完成
        Log.d(TAG, "onResume complete: " + this);
    }

    // ⏸️ Activity暂停时释放相机
    @Override
    protected void onPause() {
        // 📝 日志输出：记录正在释放相机
        Log.d(TAG, "onPause -- releasing camera");
        
        // 📱 super.onPause：调用父类的onPause方法
        // 🔍 为什么调用：必须首先调用父类的生命周期方法
        // 💡 作用：完成Activity的标准暂停流程
        // ⏰ 使用时机：在任何自定义暂停逻辑之前
        super.onPause();
        
        // 📷 releaseCamera：释放相机资源
        // 🔍 为什么调用：Activity暂停时需要释放相机供其他应用使用
        // 💡 作用：停止预览并释放相机硬件
        // ⏰ 使用时机：在父类onPause之后立即调用
        // 📷 释放相机资源
        releaseCamera();
        
        // 📨 queueEvent：将任务投递到渲染线程执行
        // 🔍 为什么使用：notifyPausing必须在渲染线程调用
        // 💡 作用：异步通知渲染器清理资源
        // ⏰ 使用时机：在释放相机之后
        // 🧹 通知渲染器清理资源
        mGLView.queueEvent(new Runnable() {
            @Override public void run() {
                // Tell the renderer that it's about to be paused so it can clean up.
                // 🧹 notifyPausing：通知渲染器即将暂停
                // 💡 作用：释放SurfaceTexture和渲染器资源
                mRenderer.notifyPausing();
            }
        });
        
        // 🔄 onPause：暂停GLSurfaceView的渲染
        // 🔍 为什么调用：停止渲染线程以节省资源
        // 💡 作用：暂停渲染循环
        // ⏰ 使用时机：在通知渲染器之后
        mGLView.onPause();
        
        // 📝 日志输出：记录onPause完成
        Log.d(TAG, "onPause complete");
    }

    // 💥 Activity销毁时清理Handler
    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy");
        super.onDestroy();
        // 🚫 使Handler失效（防止内存泄漏）
        mCameraHandler.invalidateHandler();     // paranoia
    }

    // 🔐 处理权限请求结果
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        // 📱 super.onRequestPermissionsResult：调用父类的权限结果处理
        // 🔍 为什么调用：必须首先调用父类的处理逻辑
        // 💡 作用：完成权限请求的标准处理流程
        // ⏰ 使用时机：在任何自定义处理之前
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        // 🔐 hasCameraPermission：再次检查相机权限
        // 🔍 为什么检查：需要确认用户是否授予了权限
        // 💡 作用：根据权限状态决定后续操作
        // ⏰ 使用时机：在权限请求回调中检查
        // ❌ 权限被拒绝时提示并关闭
        if (!PermissionHelper.hasCameraPermission(this)) {
            // 🍞 Toast.makeText：显示提示消息
            // 💡 参数：上下文、消息文本、显示时长
            // 💡 作用：告知用户需要相机权限
            Toast.makeText(this,
                    "Camera permission is needed to run this application", Toast.LENGTH_LONG).show();
            
            // ⚙️ launchPermissionSettings：打开系统权限设置页面
            // 💡 作用：让用户可以手动授予权限
            PermissionHelper.launchPermissionSettings(this);
            
            // 🚪 finish：关闭当前Activity
            // 💡 作用：无法使用相机，退出应用
            finish();
        } else {
            // ✅ 权限 granted 后打开相机
            // 📷 openCamera：打开相机并设置预览尺寸
            // 💡 参数(1280, 720)：期望的预览分辨率
            // 💡 作用：用户授予了权限，可以正常使用相机
            openCamera(1280, 720);      // updates mCameraPreviewWidth/Height

        }
    }
    // spinner selected
    // 🎛️ 滤镜选择下拉框选择事件
    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
        // 🎛️ spinner：下拉框控件（从parent参数转换）
        // 🔍 为什么转换：需要访问下拉框的选中状态
        // 💡 作用：获取用户选择的滤镜索引
        // ⏰ 使用时机：在回调方法中立即使用
        Spinner spinner = (Spinner) parent;
        
        // 📊 filterNum：用户选择的滤镜索引
        // 🔍 为什么定义：需要知道用户选择了哪个滤镜
        // 💡 作用：标识滤镜类型（0=无滤镜，1=黑白，2=模糊等）
        // ⏰ 使用时机：立即用于通知渲染器
        final int filterNum = spinner.getSelectedItemPosition();

        // 📝 日志输出：记录用户选择的滤镜
        Log.d(TAG, "onItemSelected: " + filterNum);
        
        // 📨 queueEvent：将任务投递到渲染线程执行
        // 🔍 为什么使用：changeFilterMode必须在渲染线程调用
        // 💡 作用：异步更新渲染器的滤镜模式
        // ⏰ 使用时机：在用户选择滤镜后立即发送
        // 🔄 通知渲染器更改滤镜模式
        mGLView.queueEvent(new Runnable() {
            @Override public void run() {
                // notify the renderer that we want to change the encoder's state
                // 🎛️ changeFilterMode：更改渲染器的滤镜模式
                // 💡 参数filterNum：用户选择的滤镜索引
                // 💡 作用：更新着色器程序和卷积核参数
                mRenderer.changeFilterMode(filterNum);
            }
        });
    }

    // 🚫 未选择任何滤镜时不处理
    @Override public void onNothingSelected(AdapterView<?> parent) {}

    /**
     * Opens a camera, and attempts to establish preview mode at the specified width and height.
     * <p>
     * Sets mCameraPreviewWidth and mCameraPreviewHeight to the actual width/height of the preview.
     * 
     * 📷 打开相机并设置预览尺寸
     * 优先使用前置摄像头，设置录制提示和预览参数
     */
    private void openCamera(int desiredWidth, int desiredHeight) {
        if (mCamera != null) {
            throw new RuntimeException("camera already initialized");
        }

        // 📷 info：相机信息对象
        // 🔍 为什么定义：需要查询每个摄像头的方向、类型等属性
        // 💡 作用：存储单个摄像头的元数据（前置/后置等）
        // ⏰ 使用时机：在遍历摄像头时每次获取一个摄像头的信息
        Camera.CameraInfo info = new Camera.CameraInfo();

        // Try to find a front-facing camera (e.g. for videoconferencing).
        // 🔍 尝试查找前置摄像头
        // 📊 numCameras：设备上可用的摄像头总数
        // 🔍 为什么定义：需要知道循环范围来遍历所有摄像头
        // 💡 作用：控制for循环的迭代次数
        // ⏰ 使用时机：立即用于for循环条件判断
        int numCameras = Camera.getNumberOfCameras();
        for (int i = 0; i < numCameras; i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                mCamera = Camera.open(i);
                break;
            }
        }
        // 📷 如果没有前置摄像头，使用默认摄像头
        if (mCamera == null) {
            Log.d(TAG, "No front-facing camera found; opening default");
            mCamera = Camera.open();    // opens first back-facing camera
        }
        if (mCamera == null) {
            throw new RuntimeException("Unable to open camera");
        }

        // ⚙️ parms：相机参数对象
        // 🔍 为什么定义：需要修改相机的预览尺寸、帧率等运行参数
        // 💡 作用：获取当前参数→修改→设置回去
        // ⏰ 使用时机：在Camera.open()成功后立即获取
        Camera.Parameters parms = mCamera.getParameters();

        // 📐 choosePreviewSize：从可用尺寸中选择最接近期望值的预览尺寸
        // 💡 参数parms：参数对象会被修改，包含选中的尺寸
        CameraUtils.choosePreviewSize(parms, desiredWidth, desiredHeight);

        // Give the camera a hint that we're recording video.  This can have a big
        // impact on frame rate.
        // 🎥 设置录制提示（可提高帧率）
        parms.setRecordingHint(true);

        // leave the frame rate set to default
        mCamera.setParameters(parms);

        // 📊 获取预览尺寸和帧率信息
        // 📊 fpsRange：存储摄像头实际的帧率范围 [min, max]（毫秒单位）
        // 🔍 为什么定义：需要获取摄像头实际支持的帧率范围
        // 💡 作用：存储getPreviewFpsRange的输出结果
        // ⏰ 使用时机：在getPreviewFpsRange调用后立即读取
        int[] fpsRange = new int[2];
        // 📐 mCameraPreviewSize：摄像头实际的预览尺寸对象
        // 🔍 为什么定义：需要获取摄像头实际分配的尺寸（可能与请求值不同）
        // 💡 作用：包含width和height属性，用于后续计算
        Camera.Size mCameraPreviewSize = parms.getPreviewSize();
        // 📊 getPreviewFpsRange：获取摄像头实际的帧率范围
        parms.getPreviewFpsRange(fpsRange);
        // 📝 previewFacts：构建摄像头配置的描述字符串
        // 🔍 为什么定义：需要格式化显示摄像头的实际配置参数
        // 💡 作用：拼接尺寸和帧率信息，用于UI显示
        String previewFacts = mCameraPreviewSize.width + "x" + mCameraPreviewSize.height;
        // 🎬 判断帧率范围：如果min==max表示固定帧率，否则是范围帧率
        if (fpsRange[0] == fpsRange[1]) {
            previewFacts += " @" + (fpsRange[0] / 1000.0) + "fps";
        } else {
            previewFacts += " @[" + (fpsRange[0] / 1000.0) +
                    " - " + (fpsRange[1] / 1000.0) + "] fps";
        }
        // 📝 显示预览参数
        TextView text = (TextView) findViewById(R.id.cameraParams_text);
        text.setText(previewFacts);

        // 📐 保存摄像头实际预览尺寸到成员变量
        // 💡 mCameraPreviewWidth/mCameraPreviewHeight：用于渲染器设置纹理尺寸
        mCameraPreviewWidth = mCameraPreviewSize.width;
        mCameraPreviewHeight = mCameraPreviewSize.height;

        // 📐 layout：宽高比布局控件
        // 🔍 为什么定义：需要根据相机旋转调整预览画面的宽高比
        // 💡 作用：确保预览画面不变形，自动裁剪或留黑边
        // ⏰ 使用时机：在设置相机显示方向后调整
        AspectFrameLayout layout = (AspectFrameLayout) findViewById(R.id.cameraPreview_afl);

        // 📺 display：当前屏幕显示对象
        // 🔍 为什么定义：需要获取屏幕旋转方向来设置相机显示方向
        // 💡 作用：提供屏幕旋转角度等信息
        // ⏰ 使用时机：立即用于判断屏幕旋转
        Display display = ((WindowManager)getSystemService(WINDOW_SERVICE)).getDefaultDisplay();

        // 🔄 根据屏幕旋转设置相机显示方向和宽高比
        if(display.getRotation() == Surface.ROTATION_0) {
            mCamera.setDisplayOrientation(90);
            layout.setAspectRatio((double) mCameraPreviewHeight / mCameraPreviewWidth);
        } else if(display.getRotation() == Surface.ROTATION_270) {
            layout.setAspectRatio((double) mCameraPreviewHeight/ mCameraPreviewWidth);
            mCamera.setDisplayOrientation(180);
        } else {
            // Set the preview aspect ratio.
            layout.setAspectRatio((double) mCameraPreviewWidth / mCameraPreviewHeight);
        }
    }

    /**
     * Stops camera preview, and releases the camera to the system.
     * 
     * 📷 停止相机预览并释放相机资源
     */
    private void releaseCamera() {
        // ⚠️ null检查：确保相机已打开才释放
        // 🔍 为什么检查：可能在相机未打开时调用此方法
        // 💡 作用：避免空指针异常
        // ⏰ 使用时机：在释放资源前检查
        if (mCamera != null) {
            // ⏹️ stopPreview：停止相机预览
            // 🔍 为什么调用：释放相机前必须先停止预览
            // 💡 作用：停止相机捕获帧数据
            // ⏰ 使用时机：在释放相机之前
            mCamera.stopPreview();
            // 🗑️ release：释放相机硬件资源
            // 🔍 为什么调用：将相机归还给系统，其他应用可以使用
            // 💡 作用：释放相机硬件的独占锁
            // ⏰ 使用时机：在停止预览之后
            mCamera.release();
            // 🗑️ 置null：清除相机引用
            // 🔍 为什么置null：标记相机已释放，避免重复释放
            // 💡 作用：允许垃圾回收器回收Camera对象
            // ⏰ 使用时机：在释放后立即置null
            mCamera = null;
            // 📝 日志输出：记录释放完成
            Log.d(TAG, "releaseCamera -- done");
        }
    }

    /**
     * onClick handler for "record" button.
     * 
     * 🔴 录制按钮点击处理
     */
    public void clickToggleRecording(@SuppressWarnings("unused") View unused) {
        // 🔄 mRecordingEnabled：录制状态标志，true表示正在录制，false表示停止录制
        // 🔍 为什么定义：需要记录当前是否在录制状态，用于控制按钮显示和编码器行为
        // 💡 作用：切换录制开关状态，决定是否启用视频编码器
        // ⏰ 使用时机：用户点击录制按钮时立即切换
        // 🔄 切换录制状态
        mRecordingEnabled = !mRecordingEnabled;
        
        // 📨 queueEvent：将任务投递到GLSurfaceView的渲染线程执行
        // 🔍 为什么使用：录制状态变更需要在渲染线程操作编码器，不能在UI线程直接调用
        // 💡 作用：异步通知渲染器更新编码器的录制状态
        // ⏰ 使用时机：录制状态切换后立即发送
        // 📨 通知渲染器更改录制状态
        mGLView.queueEvent(new Runnable() {
            @Override public void run() {
                // notify the renderer that we want to change the encoder's state
                // 📤 mRenderer.changeRecordingState：通知渲染器更新编码器录制状态
                // 💡 参数mRecordingEnabled：新的录制状态，传递给渲染器
                mRenderer.changeRecordingState(mRecordingEnabled);
            }
        });
        
        // 🔄 updateControls：刷新UI控件显示状态
        // 💡 作用：更新录制按钮的文本（显示"开始录制"或"停止录制"）
        // ⏰ 使用时机：录制状态变更后立即调用
        updateControls();
    }

//    /**
//     * onClick handler for "rebind" checkbox.
//     */
//    public void clickRebindCheckbox(View unused) {
//        CheckBox cb = (CheckBox) findViewById(R.id.rebindHack_checkbox);
//        TextureRender.sWorkAroundContextProblem = cb.isChecked();
//    }

    /**
     * Updates the on-screen controls to reflect the current state of the app.
     *
     * 🎮 更新界面控件状态
     * 同步录制按钮文本
     */
    private void updateControls() {
        // 🔘 toggleRelease：录制按钮控件实例
        // 🔍 为什么定义：需要获取按钮引用才能修改其文本
        // 💡 作用：根据录制状态显示不同的按钮文本
        // ⏰ 使用时机：立即用于设置按钮文本
        Button toggleRelease = (Button) findViewById(R.id.toggleRecording_button);
        
        // 📊 id：字符串资源ID，根据录制状态选择"停止录制"或"开始录制"
        // 🔍 为什么定义：需要根据mRecordingEnabled状态选择对应的字符串资源
        // 💡 作用：决定按钮显示的文本内容
        // ⏰ 使用时机：立即用于setText设置按钮文本
        int id = mRecordingEnabled ?
                R.string.toggleRecordingOff : R.string.toggleRecordingOn;
        
        // 📝 setText：设置按钮显示的文本
        // 💡 作用：用户可以看到当前应该点击"开始"还是"停止"
        toggleRelease.setText(id);

        //CheckBox cb = (CheckBox) findViewById(R.id.rebindHack_checkbox);
        //cb.setChecked(TextureRender.sWorkAroundContextProblem);
    }

    /**
     * Connects the SurfaceTexture to the Camera preview output, and starts the preview.
     *
     * 🔗 连接SurfaceTexture到相机预览输出并开始预览
     * 设置帧可用监听器，配置预览纹理，启动相机预览
     */
    private void handleSetSurfaceTexture(SurfaceTexture st) {
        // 📨 setOnFrameAvailableListener：设置帧可用监听器
        // 🔍 为什么调用：当摄像头有新帧可用时需要收到通知
        // 💡 作用：监听器会在新帧到达时触发onFrameAvailable回调
        // ⏰ 使用时机：在连接SurfaceTexture之前设置
        // 📨 设置帧可用监听器
        st.setOnFrameAvailableListener(this);
        
        try {
            // 🔗 setPreviewTexture：将摄像头预览输出绑定到SurfaceTexture
            // 🔍 为什么调用：建立摄像头→纹理的数据流通道
            // 💡 作用：摄像头捕获的帧会自动写入SurfaceTexture
            // ⏰ 使用时机：在设置监听器之后、开始预览之前
            // 🔗 设置相机预览纹理
            mCamera.setPreviewTexture(st);
        } catch (IOException ioe) {
            // ⚠️ IOException：设置预览纹理失败时抛出运行时异常
            throw new RuntimeException(ioe);
        }
        
        // ▶️ startPreview：开始摄像头预览
        // 🔍 为什么调用：启动摄像头捕获帧数据
        // 💡 作用：摄像头开始工作，通过SurfaceTexture输出帧到OpenGL纹理
        // ⏰ 使用时机：在预览纹理设置完成后立即调用
        // ▶️ 开始预览
        mCamera.startPreview();
    }

    // 🎬 新帧可用时的回调
    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        // The SurfaceTexture uses this to signal the availability of a new frame.  The
        // thread that "owns" the external texture associated with the SurfaceTexture (which,
        // by virtue of the context being shared, *should* be either one) needs to call
        // updateTexImage() to latch the buffer.
        //
        // Once the buffer is latched, the GLSurfaceView thread can signal the encoder thread.
        // This feels backward -- we want recording to be prioritized over rendering -- but
        // since recording is only enabled some of the time it's easier to do it this way.
        //
        // Since GLSurfaceView doesn't establish a Looper, this will *probably* execute on
        // the main UI thread.  Fortunately, requestRender() can be called from any thread,
        // so it doesn't really matter.
        
        // 📊 VERBOSE：详细日志开关，控制是否输出帧可用的调试信息
        // 🔍 为什么判断：避免生产环境输出过多日志
        // 💡 作用：调试时可以看到帧到达的频率
        // ⏰ 使用时机：每次回调时判断
        // 📨 请求GLSurfaceView渲染新帧
        if (VERBOSE) Log.d(TAG, "ST onFrameAvailable");
        
        // 📨 requestRender：请求GLSurfaceView执行一次渲染
        // 🔍 为什么调用：新帧可用时需要触发渲染线程处理
        // 💡 作用：GLSurfaceView会在渲染线程调用onDrawFrame绘制新帧
        // ⏰ 使用时机：收到新帧通知后立即调用
        // 💡 注意：可以从任意线程调用，因为GLSurfaceView会处理线程同步
        mGLView.requestRender();
    }

    /**
     * Handles camera operation requests from other threads.  Necessary because the Camera
     * must only be accessed from one thread.
     * <p>
     * The object is created on the UI thread, and all handlers run there.  Messages are
     * sent from other threads, using sendMessage().
     * 
     * 📬 相机操作Handler
     * 处理其他线程的相机操作请求（Camera只能从单线程访问）
     */
    static class CameraHandler extends Handler {
        public static final int MSG_SET_SURFACE_TEXTURE = 0;

        // Weak reference to the Activity; only access this from the UI thread.
        // 🔗 对Activity的弱引用
        private WeakReference<CameraCaptureActivity> mWeakActivity;

        public CameraHandler(CameraCaptureActivity activity) {
            mWeakActivity = new WeakReference<CameraCaptureActivity>(activity);
        }

        /**
         * Drop the reference to the activity.  Useful as a paranoid measure to ensure that
         * attempts to access a stale Activity through a handler are caught.
         * 
         * 🚫 使Handler失效（清除弱引用）
         */
        public void invalidateHandler() {
            mWeakActivity.clear();
        }

        @Override  // runs on UI thread
        // 🔄 在UI线程处理消息
        public void handleMessage(Message inputMessage) {
            // 📋 what：消息类型标识，决定如何处理该消息
            // 🔍 为什么定义：需要根据不同的消息类型执行不同的操作
            // 💡 作用：消息分发的依据
            // ⏰ 使用时机：立即用于switch判断
            int what = inputMessage.what;
            
            // 📝 日志输出：记录收到的消息类型
            // 💡 作用：调试时可以追踪消息流向
            Log.d(TAG, "CameraHandler [" + this + "]: what=" + what);

            // 📱 activity：从弱引用获取Activity实例
            // 🔍 为什么使用弱引用：防止Handler持有Activity强引用导致内存泄漏
            // 💡 作用：访问Activity的方法来处理消息
            // ⏰ 使用时机：每次收到消息时都需要获取
            CameraCaptureActivity activity = mWeakActivity.get();
            
            // ⚠️ null检查：如果Activity已被垃圾回收，记录警告并返回
            if (activity == null) {
                Log.w(TAG, "CameraHandler.handleMessage: activity is null");
                return;
            }

            // 🎯 根据消息类型分发处理
            switch (what) {
                case MSG_SET_SURFACE_TEXTURE:
                    // 🖼️ 处理设置SurfaceTexture的消息
                    // 📋 inputMessage.obj：SurfaceTexture对象
                    // 💡 作用：将SurfaceTexture传递给Activity连接到相机
                    activity.handleSetSurfaceTexture((SurfaceTexture) inputMessage.obj);
                    break;
                default:
                    // ⚠️ 未知消息类型，抛出异常（防御性编程）
                    throw new RuntimeException("unknown msg " + what);
            }
        }
    }
}

/**
 * Renderer object for our GLSurfaceView.
 * <p>
 * Do not call any methods here directly from another thread -- use the
 * GLSurfaceView#queueEvent() call.
 * 
 * 🎨 GLSurfaceView渲染器
 * 处理相机预览帧渲染和录制
 * 请勿从其他线程直接调用方法，使用GLSurfaceView#queueEvent()
 */
class CameraSurfaceRenderer implements GLSurfaceView.Renderer {
    private static final String TAG = MainActivity.TAG;
    private static final boolean VERBOSE = false;

    // 📊 录制状态常量
    private static final int RECORDING_OFF = 0;
    private static final int RECORDING_ON = 1;
    private static final int RECORDING_RESUMED = 2;

    // 📬 相机Handler和视频编码器
    private CameraCaptureActivity.CameraHandler mCameraHandler;
    private TextureMovieEncoder mVideoEncoder;
    private File mOutputFile;

    // 🖼️ 全屏矩形渲染器
    private FullFrameRect mFullScreen;

    // 🆔 纹理矩阵和ID
    private final float[] mSTMatrix = new float[16];
    private int mTextureId;

    // 🎬 SurfaceTexture和录制状态
    private SurfaceTexture mSurfaceTexture;
    private boolean mRecordingEnabled;
    private int mRecordingStatus;
    private int mFrameCount;

    // width/height of the incoming camera preview frames
    // 📐 相机预览帧尺寸
    private boolean mIncomingSizeUpdated;
    private int mIncomingWidth;
    private int mIncomingHeight;

    // 🎛️ 滤镜模式
    private int mCurrentFilter;
    private int mNewFilter;


    /**
     * Constructs CameraSurfaceRenderer.
     * <p>
     * @param cameraHandler Handler for communicating with UI thread
     * @param movieEncoder video encoder object
     * @param outputFile output file for encoded video; forwarded to movieEncoder
     * 
     * 🏗️ 构造函数，初始化渲染器
     */
    public CameraSurfaceRenderer(CameraCaptureActivity.CameraHandler cameraHandler,
            TextureMovieEncoder movieEncoder, File outputFile) {
        // 📬 mCameraHandler：UI线程的消息处理器
        // 🔍 为什么保存：需要向UI线程发送相机操作请求
        // 💡 作用：跨线程通信的桥梁
        // ⏰ 使用时机：在SurfaceTexture创建后发送消息
        mCameraHandler = cameraHandler;
        
        // 🎥 mVideoEncoder：视频编码器对象
        // 🔍 为什么保存：需要控制视频录制的启停
        // 💡 作用：管理视频编码和文件写入
        // ⏰ 使用时机：在录制状态变化时操作
        mVideoEncoder = movieEncoder;
        
        // 📁 mOutputFile：视频输出文件
        // 🔍 为什么保存：需要传递给编码器指定保存位置
        // 💡 作用：录制视频的存储路径
        // ⏰ 使用时机：开始录制时传递给编码器
        mOutputFile = outputFile;

        // 🆔 mTextureId：OpenGL纹理对象ID
        // 🔍 为什么初始化为-1：表示纹理尚未创建
        // 💡 作用：标识GPU中的纹理资源
        // ⏰ 使用时机：在onSurfaceCreated中创建实际纹理
        // 🆔 初始化纹理ID
        mTextureId = -1;

        // 📊 mRecordingStatus：录制状态
        // 🔍 为什么初始化为-1：表示状态未定义
        // 💡 作用：记录录制的生命周期状态
        // ⏰ 使用时机：在onSurfaceCreated中确定初始状态
        // 🎥 初始化录制状态
        mRecordingStatus = -1;
        
        // 🎥 mRecordingEnabled：录制是否启用
        // 🔍 为什么初始化为false：默认不录制
        // 💡 作用：控制是否启动编码器
        // ⏰ 使用时机：根据用户操作更新
        mRecordingEnabled = false;
        
        // 📊 mFrameCount：帧计数器
        // 🔍 为什么初始化为-1：从0开始计数
        // 💡 作用：控制录制指示框的闪烁频率
        // ⏰ 使用时机：每帧绘制时递增
        mFrameCount = -1;

        // 📐 mIncomingSizeUpdated：预览尺寸是否已更新
        // 🔍 为什么初始化为false：尺寸尚未设置
        // 💡 作用：标记是否需要更新纹理尺寸
        // ⏰ 使用时机：在setCameraPreviewSize中设为true
        // 📐 初始化预览尺寸
        mIncomingSizeUpdated = false;
        
        // 📐 mIncomingWidth/mIncomingHeight：预览帧的宽高
        // 🔍 为什么初始化为-1：表示尺寸尚未设置
        // 💡 作用：存储摄像头预览的实际分辨率
        // ⏰ 使用时机：在setCameraPreviewSize中更新
        mIncomingWidth = mIncomingHeight = -1;

        // We could preserve the old filter mode, but currently not bothering.
        // 🎛️ mCurrentFilter：当前应用的滤镜类型
        // 🔍 为什么初始化为-1：表示滤镜尚未设置
        // 💡 作用：记录当前生效的滤镜
        // ⏰ 使用时机：在updateFilter中更新
        // 🎛️ 初始化滤镜模式
        mCurrentFilter = -1;
        
        // 🎛️ mNewFilter：用户选择的新滤镜类型
        // 🔍 为什么初始化为FILTER_NONE：默认无滤镜
        // 💡 作用：存储用户的选择，等待应用
        // ⏰ 使用时机：在changeFilterMode中更新
        mNewFilter = CameraCaptureActivity.FILTER_NONE;
    }

    /**
     * Notifies the renderer thread that the activity is pausing.
     * <p>
     * For best results, call this *after* disabling Camera preview.
     * 
     * ⏸️ 通知渲染器Activity即将暂停
     * 最好在禁用相机预览后调用
     */
    public void notifyPausing() {
        // 🖼️ mSurfaceTexture：摄像头预览纹理对象
        // 🔍 为什么检查：可能在SurfaceTexture创建前调用此方法
        // 💡 作用：存储摄像头帧数据的纹理
        // ⏰ 使用时机：在释放前检查是否为null
        // 🧹 释放SurfaceTexture
        if (mSurfaceTexture != null) {
            // 📝 日志输出：记录正在释放SurfaceTexture
            Log.d(TAG, "renderer pausing -- releasing SurfaceTexture");
            
            // 🗑️ release：释放SurfaceTexture资源
            // 🔍 为什么调用：Activity暂停时需要释放摄像头资源
            // 💡 作用：停止接收摄像头帧，释放GPU纹理
            // ⏰ 使用时机：在Activity暂停时调用
            mSurfaceTexture.release();
            mSurfaceTexture = null;
        }
        
        // 🖼️ mFullScreen：全屏矩形渲染器对象
        // 🔍 为什么检查：可能在渲染器创建前调用此方法
        // 💡 作用：用于绘制摄像头预览帧到屏幕
        // ⏰ 使用时机：在释放前检查是否为null
        // 🖼️ 释放全屏矩形渲染器
        if (mFullScreen != null) {
            // 🗑️ release：释放渲染器资源
            // 🔍 参数false：假设GLSurfaceView的EGL上下文即将被销毁
            // 💡 作用：释放着色器程序和纹理资源
            // ⏰ 使用时机：在Activity暂停时调用
            mFullScreen.release(false);     // assume the GLSurfaceView EGL context is about
            mFullScreen = null;             //  to be destroyed
        }
        
        // 📐 mIncomingWidth/mIncomingHeight：摄像头预览帧的宽高
        // 🔍 为什么重置：下次恢复时需要重新获取尺寸
        // 💡 作用：标记预览尺寸无效，等待重新设置
        // ⏰ 使用时机：在资源释放后重置
        // 📐 重置预览尺寸
        mIncomingWidth = mIncomingHeight = -1;
    }

    /**
     * Notifies the renderer that we want to stop or start recording.
     * 
     * 🎥 更改录制状态
     */
    public void changeRecordingState(boolean isRecording) {
        // 📝 日志输出：记录录制状态变化
        // 💡 作用：调试时可以追踪录制状态的变更
        Log.d(TAG, "changeRecordingState: was " + mRecordingEnabled + " now " + isRecording);
        
        // 🎥 mRecordingEnabled：录制是否启用
        // 🔍 为什么更新：用户点击了录制按钮
        // 💡 作用：控制是否启动视频编码器
        // ⏰ 使用时机：在onDrawFrame中根据此值决定开始/停止录制
        mRecordingEnabled = isRecording;
    }

    /**
     * Changes the filter that we're applying to the camera preview.
     * 
     * 🎛️ 更改相机预览滤镜
     */
    public void changeFilterMode(int filter) {
        // 🎛️ mNewFilter：用户选择的新滤镜类型
        // 🔍 为什么更新：用户在下拉框中选择了新滤镜
        // 💡 作用：存储用户的选择，等待下次绘制时应用
        // ⏰ 使用时机：在onDrawFrame中检测到mCurrentFilter != mNewFilter时更新
        mNewFilter = filter;
    }

    /**
     * Updates the filter program.
     * 
     * 🎨 更新滤镜着色器程序
     * 根据选择的滤镜类型设置不同的卷积核和参数
     */
    public void updateFilter() {
        // 📋 programType：着色器程序类型，决定使用哪个着色器
        // 🔍 为什么定义：不同滤镜需要不同的着色器程序
        // 💡 作用：标识着色器程序的类型（纹理、黑白、卷积滤镜等）
        // ⏰ 使用时机：在switch语句中根据滤镜类型赋值
        Texture2dProgram.ProgramType programType;
        
        // 📊 kernel：3x3卷积核数组，用于图像滤波
        // 🔍 为什么定义：某些滤镜需要卷积核参数
        // 💡 作用：定义像素与其邻域的加权关系
        // ⏰ 使用时机：模糊、锐化、边缘检测、浮雕滤镜时使用
        float[] kernel = null;
        
        // 🎨 colorAdj：颜色调整值，用于某些滤镜的颜色偏移
        // 🔍 为什么定义：浮雕滤镜需要额外的颜色调整
        // 💡 作用：添加到最终颜色值，实现亮度调整
        // ⏰ 使用时机：浮雕滤镜时设为0.5f
        float colorAdj = 0.0f;

        // 📝 日志输出：记录正在更新到哪个滤镜
        Log.d(TAG, "Updating filter to " + mNewFilter);
        
        // 🎛️ 根据滤镜类型设置着色器程序和卷积核
        switch (mNewFilter) {
            case CameraCaptureActivity.FILTER_NONE:
                // 🚫 无滤镜：使用标准外部纹理着色器
                programType = Texture2dProgram.ProgramType.TEXTURE_EXT;
                break;
            case CameraCaptureActivity.FILTER_BLACK_WHITE:
                // (In a previous version the TEXTURE_EXT_BW variant was enabled by a flag called
                // ROSE_COLORED_GLASSES, because the shader set the red channel to the B&W color
                // and green/blue to zero.)
                // ⬛ 黑白滤镜：使用黑白外部纹理着色器
                programType = Texture2dProgram.ProgramType.TEXTURE_EXT_BW;
                break;
            case CameraCaptureActivity.FILTER_BLUR:
                // 🌫️ 模糊滤镜：使用卷积滤镜着色器
                programType = Texture2dProgram.ProgramType.TEXTURE_EXT_FILT;
                // 🌫️ 模糊滤镜卷积核（高斯模糊）
                // 💡 权重分布：中心最高，周围递减，实现平滑效果
                kernel = new float[] {
                        1f/16f, 2f/16f, 1f/16f,
                        2f/16f, 4f/16f, 2f/16f,
                        1f/16f, 2f/16f, 1f/16f };
                break;
            case CameraCaptureActivity.FILTER_SHARPEN:
                // 🔍 锐化滤镜：使用卷积滤镜着色器
                programType = Texture2dProgram.ProgramType.TEXTURE_EXT_FILT;
                // 🔍 锐化滤镜卷积核
                // 💡 权重分布：中心为正，周围为负，增强边缘对比度
                kernel = new float[] {
                        0f, -1f, 0f,
                        -1f, 5f, -1f,
                        0f, -1f, 0f };
                break;
            case CameraCaptureActivity.FILTER_EDGE_DETECT:
                // ✏️ 边缘检测：使用卷积滤镜着色器
                programType = Texture2dProgram.ProgramType.TEXTURE_EXT_FILT;
                // ✏️ 边缘检测卷积核（拉普拉斯算子）
                // 💡 权重分布：中心为正，周围为负，突出边缘
                kernel = new float[] {
                        -1f, -1f, -1f,
                        -1f, 8f, -1f,
                        -1f, -1f, -1f };
                break;
            case CameraCaptureActivity.FILTER_EMBOSS:
                // 🏛️ 浮雕效果：使用卷积滤镜着色器
                programType = Texture2dProgram.ProgramType.TEXTURE_EXT_FILT;
                // 🏛️ 浮雕效果卷积核
                // 💡 权重分布：对角线方向产生凸起效果
                kernel = new float[] {
                        2f, 0f, 0f,
                        0f, -1f, 0f,
                        0f, 0f, -1f };
                // 🎨 colorAdj：浮雕效果需要亮度调整
                // 💡 0.5f：将颜色值向上偏移，使图像更亮
                colorAdj = 0.5f;
                break;
            default:
                // ⚠️ 未知滤镜类型，抛出异常（防御性编程）
                throw new RuntimeException("Unknown filter mode " + mNewFilter);
        }

        // Do we need a whole new program?  (We want to avoid doing this if we don't have
        // too -- compiling a program could be expensive.)
        // 🔄 检查是否需要重新编译着色器程序
        // 🔍 为什么判断：编译着色器程序开销较大，只在类型变化时才重新编译
        // 💡 作用：优化性能，避免重复编译
        // ⏰ 使用时机：在确定programType后检查
        // 🔄 如果程序类型不同，需要重新编译着色器
        if (programType != mFullScreen.getProgram().getProgramType()) {
            // 🔄 changeProgram：更换着色器程序
            // 💡 参数：新的Texture2dProgram实例，包含新的着色器类型
            // 💡 作用：替换当前的着色器程序，使用新滤镜效果
            mFullScreen.changeProgram(new Texture2dProgram(programType));
            // If we created a new program, we need to initialize the texture width/height.
            // 📐 mIncomingSizeUpdated：标记需要更新纹理尺寸
            // 🔍 为什么设为true：新程序需要重新设置纹理宽高参数
            // 💡 作用：触发onDrawFrame中更新纹理尺寸
            mIncomingSizeUpdated = true;
        }

        // Update the filter kernel (if any).
        // 📊 setKernel：设置卷积核参数
        // 🔍 为什么检查：只有卷积滤镜才有kernel参数
        // 💡 作用：将卷积核传递给着色器程序
        // ⏰ 使用时机：kernel不为null时（模糊、锐化、边缘检测、浮雕）
        // 📦 更新卷积核参数
        if (kernel != null) {
            mFullScreen.getProgram().setKernel(kernel, colorAdj);
        }

        // 📊 mCurrentFilter：当前应用的滤镜类型
        // 🔍 为什么更新：标记滤镜已更新，避免重复更新
        // 💡 作用：记录当前状态，用于判断是否需要再次更新
        // ⏰ 使用时机：在滤镜更新完成后设置
        mCurrentFilter = mNewFilter;
    }

    /**
     * Records the size of the incoming camera preview frames.
     * <p>
     * It's not clear whether this is guaranteed to execute before or after onSurfaceCreated(),
     * so we assume it could go either way.  (Fortunately they both run on the same thread,
     * so we at least know that they won't execute concurrently.)
     * 
     * 📐 设置相机预览帧尺寸
     */
    public void setCameraPreviewSize(int width, int height) {
        // 📝 日志输出：记录设置预览尺寸
        Log.d(TAG, "setCameraPreviewSize");
        
        // 📐 mIncomingWidth：摄像头预览帧的宽度
        // 🔍 为什么更新：摄像头实际预览尺寸可能与请求值不同
        // 💡 作用：存储实际的预览宽度，用于滤镜和渲染
        // ⏰ 使用时机：在onDrawFrame中使用
        mIncomingWidth = width;
        
        // 📐 mIncomingHeight：摄像头预览帧的高度
        // 🔍 为什么更新：摄像头实际预览尺寸可能与请求值不同
        // 💡 作用：存储实际的预览高度，用于滤镜和渲染
        // ⏰ 使用时机：在onDrawFrame中使用
        mIncomingHeight = height;
        
        // 📊 mIncomingSizeUpdated：预览尺寸是否已更新
        // 🔍 为什么设为true：标记尺寸已更新，需要应用到着色器
        // 💡 作用：触发onDrawFrame中更新纹理尺寸
        // ⏰ 使用时机：在设置尺寸后立即标记
        mIncomingSizeUpdated = true;
    }

    // 🎨 Surface创建时初始化OpenGL资源
    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        // 📝 日志输出：记录Surface创建事件
        Log.d(TAG, "onSurfaceCreated");

        // We're starting up or coming back.  Either way we've got a new EGLContext that will
        // need to be shared with the video encoder, so figure out if a recording is already
        // in progress.
        // 🎥 mRecordingEnabled：当前录制是否启用
        // 🔍 为什么检查：需要恢复之前的录制状态
        // 💡 作用：确定是否需要继续之前的录制
        // ⏰ 使用时机：在Surface创建时检查
        // 🔄 检查是否已有录制在进行中
        mRecordingEnabled = mVideoEncoder.isRecording();
        if (mRecordingEnabled) {
            // 📊 RECORDING_RESUMED：录制恢复状态
            // 💡 作用：标记需要恢复录制，更新共享EGL上下文
            mRecordingStatus = RECORDING_RESUMED;
        } else {
            // 📊 RECORDING_OFF：录制关闭状态
            // 💡 作用：标记录制未启用
            mRecordingStatus = RECORDING_OFF;
        }

        // Set up the texture blitter that will be used for on-screen display.  This
        // is *not* applied to the recording, because that uses a separate shader.
        // 🖼️ mFullScreen：全屏矩形渲染器
        // 🔍 为什么创建：需要渲染摄像头帧到屏幕
        // 💡 作用：管理纹理绘制的OpenGL资源
        // ⏰ 使用时机：在Surface创建时初始化
        // 🖼️ 创建全屏矩形渲染器（用于屏幕显示，录制使用单独的着色器）
        mFullScreen = new FullFrameRect(
                new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_EXT));

        // 🆔 mTextureId：OpenGL纹理对象ID
        // 🔍 为什么创建：需要纹理对象来接收摄像头帧
        // 💡 作用：标识GPU中的纹理资源
        // ⏰ 使用时机：在创建SurfaceTexture之前
        // 🆔 创建纹理对象
        mTextureId = mFullScreen.createTextureObject();

        // Create a SurfaceTexture, with an external texture, in this EGL context.  We don't
        // have a Looper in this thread -- GLSurfaceView doesn't create one -- so the frame
        // available messages will arrive on the main thread.
        // 🎬 mSurfaceTexture：摄像头预览纹理
        // 🔍 为什么创建：需要接收摄像头输出的帧数据
        // 💡 作用：将摄像头帧绑定到OpenGL纹理
        // ⏰ 使用时机：在纹理ID创建后立即创建
        // 🎬 创建SurfaceTexture（帧可用消息将到达主线程）
        mSurfaceTexture = new SurfaceTexture(mTextureId);

        // Tell the UI thread to enable the camera preview.
        // 📨 sendMessage：向UI线程发送消息
        // 🔍 为什么发送：需要在UI线程设置摄像头预览
        // 💡 作用：通知UI线程将SurfaceTexture连接到摄像头
        // ⏰ 使用时机：在SurfaceTexture创建后立即发送
        // 📨 通知UI线程启用相机预览
        mCameraHandler.sendMessage(mCameraHandler.obtainMessage(
                CameraCaptureActivity.CameraHandler.MSG_SET_SURFACE_TEXTURE, mSurfaceTexture));
    }

    // 📐 Surface尺寸变化回调
    @Override
    public void onSurfaceChanged(GL10 unused, int width, int height) {
        Log.d(TAG, "onSurfaceChanged " + width + "x" + height);
    }

    // 🎬 每帧绘制回调
    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    @Override
    public void onDrawFrame(GL10 unused) {
        // 📊 VERBOSE：详细日志开关
        // 🔍 为什么判断：避免生产环境输出过多日志
        // 💡 作用：调试时可以看到每帧绘制的信息
        // ⏰ 使用时机：每帧绘制时判断
        if (VERBOSE) Log.d(TAG, "onDrawFrame tex=" + mTextureId);
        
        // 🔲 showBox：是否显示录制指示框
        // 🔍 为什么定义：需要根据录制状态决定是否显示指示
        // 💡 作用：控制红色指示框的显示
        // ⏰ 使用时机：在绘制流程中判断
        boolean showBox = false;

        // Latch the latest frame.  If there isn't anything new, we'll just re-use whatever
        // was there before.
        // 🖼️ updateTexImage：更新纹理图像（获取最新帧）
        // 🔍 为什么调用：需要获取摄像头的最新帧数据
        // 💡 作用：将SurfaceTexture中的帧数据更新到OpenGL纹理
        // ⏰ 使用时机：在绘制帧之前调用
        // 🔄 更新纹理图像（获取最新帧）
        mSurfaceTexture.updateTexImage();

        // If the recording state is changing, take care of it here.  Ideally we wouldn't
        // be doing all this in onDrawFrame(), but the EGLContext sharing with GLSurfaceView
        // makes it hard to do elsewhere.
        // 🎥 mRecordingEnabled：录制是否启用
        // 🔍 为什么判断：需要处理录制状态变化
        // 💡 作用：决定是否需要开始、恢复或停止录制
        // ⏰ 使用时机：每帧绘制时判断
        // 🎥 处理录制状态变化
        if (mRecordingEnabled) {
            // 📊 mRecordingStatus：录制状态（OFF/ON/RESUMED）
            // 🔍 为什么切换：不同状态需要不同的处理
            // 💡 作用：管理录制的生命周期
            // ⏰ 使用时机：根据当前状态执行对应操作
            switch (mRecordingStatus) {
                case RECORDING_OFF:
                    // ▶️ 开始录制
                    Log.d(TAG, "START recording");
                    // start recording
                    // 🎥 startRecording：启动视频编码器
                    // 🔍 为什么调用：用户开启了录制功能
                    // 💡 作用：开始编码视频帧并写入文件
                    // ⏰ 使用时机：录制状态从OFF变为ON时
                    mVideoEncoder.startRecording(new TextureMovieEncoder.EncoderConfig(
                            mOutputFile, 640, 480, 1000000, EGL14.eglGetCurrentContext()));
                    // 📊 RECORDING_ON：录制进行中状态
                    mRecordingStatus = RECORDING_ON;
                    break;
                case RECORDING_RESUMED:
                    // 🔄 恢复录制（更新共享上下文）
                    Log.d(TAG, "RESUME recording");
                    // 🔄 updateSharedContext：更新编码器的EGL共享上下文
                    // 🔍 为什么调用：Activity恢复后EGL上下文可能变化
                    // 💡 作用：确保编码器与渲染器共享正确的EGL上下文
                    // ⏰ 使用时机：录制状态为RESUMED时
                    mVideoEncoder.updateSharedContext(EGL14.eglGetCurrentContext());
                    mRecordingStatus = RECORDING_ON;
                    break;
                case RECORDING_ON:
                    // yay
                    // 📊 录制正常进行中，无需特殊处理
                    break;
                default:
                    // ⚠️ 未知录制状态，抛出异常（防御性编程）
                    throw new RuntimeException("unknown status " + mRecordingStatus);
            }
        } else {
            // 📊 mRecordingStatus：当前录制状态
            switch (mRecordingStatus) {
                case RECORDING_ON:
                case RECORDING_RESUMED:
                    // ⏹️ 停止录制
                    Log.d(TAG, "STOP recording");
                    // ⏹️ stopRecording：停止视频编码器
                    // 🔍 为什么调用：用户关闭了录制功能
                    // 💡 作用：停止编码并保存视频文件
                    // ⏰ 使用时机：录制状态从ON/RESUMED变为OFF时
                    mVideoEncoder.stopRecording();
                    mRecordingStatus = RECORDING_OFF;
                    break;
                case RECORDING_OFF:
                    // yay
                    // 📊 录制已关闭，无需特殊处理
                    break;
                default:
                    // ⚠️ 未知录制状态，抛出异常（防御性编程）
                    throw new RuntimeException("unknown status " + mRecordingStatus);
            }
        }

        // Set the video encoder's texture name.  We only need to do this once, but in the
        // current implementation it has to happen after the video encoder is started, so
        // we just do it here.
        //
        // TODO: be less lame.
        // 🆔 setTextureId：设置编码器使用的纹理ID
        // 🔍 为什么调用：编码器需要知道从哪个纹理读取帧数据
        // 💡 作用：建立渲染器与编码器的纹理关联
        // ⏰ 使用时机：在编码器启动后设置
        // 🆔 设置编码器纹理ID
        mVideoEncoder.setTextureId(mTextureId);

        // Tell the video encoder thread that a new frame is available.
        // This will be ignored if we're not actually recording.
        // 📨 frameAvailable：通知编码器有新帧可用
        // 🔍 为什么调用：编码器需要知道有新帧可以编码
        // 💡 作用：触发编码器处理新帧
        // ⏰ 使用时机：每帧绘制时调用（编码器会忽略非录制状态的调用）
        // 📨 通知编码器有新帧可用
        mVideoEncoder.frameAvailable(mSurfaceTexture);

        // ⏳ mIncomingWidth/mIncomingHeight：摄像头预览帧的宽高
        // 🔍 为什么检查：需要确保纹理尺寸已设置
        // 💡 作用：滤镜效果需要知道纹理尺寸
        // ⏰ 使用时机：在绘制前检查
        // ⏳ 等待纹理尺寸设置完成
        if (mIncomingWidth <= 0 || mIncomingHeight <= 0) {
            // Texture size isn't set yet.  This is only used for the filters, but to be
            // safe we can just skip drawing while we wait for the various races to resolve.
            // (This seems to happen if you toggle the screen off/on with power button.)
            Log.i(TAG, "Drawing before incoming texture size set; skipping");
            return;
        }
        
        // 🎛️ mCurrentFilter/mNewFilter：当前滤镜和新滤镜
        // 🔍 为什么判断：需要检测用户是否切换了滤镜
        // 💡 作用：只在滤镜变化时更新，避免重复操作
        // ⏰ 使用时机：每帧绘制时判断
        // 🎨 更新滤镜（如果需要）
        if (mCurrentFilter != mNewFilter) {
            // 🔄 updateFilter：更新滤镜着色器程序
            // 💡 作用：编译新的着色器或更新卷积核参数
            updateFilter();
        }
        
        // 📐 mIncomingSizeUpdated：纹理尺寸是否已更新
        // 🔍 为什么判断：需要检测纹理尺寸是否变化
        // 💡 作用：只在尺寸变化时更新，避免重复设置
        // ⏰ 使用时机：每帧绘制时判断
        // 📐 更新纹理尺寸
        if (mIncomingSizeUpdated) {
            // 📐 setTexSize：设置着色器程序的纹理尺寸
            // 💡 作用：某些滤镜效果需要知道纹理尺寸
            mFullScreen.getProgram().setTexSize(mIncomingWidth, mIncomingHeight);
            mIncomingSizeUpdated = false;
        }

        // Draw the video frame.
        // 📐 getTransformMatrix：获取SurfaceTexture的变换矩阵
        // 🔍 为什么调用：需要将纹理坐标正确映射到屏幕
        // 💡 作用：处理摄像头方向、缩放等变换
        // ⏰ 使用时机：在绘制帧之前
        // 🖼️ 绘制视频帧
        mSurfaceTexture.getTransformMatrix(mSTMatrix);
        
        // 🖼️ drawFrame：绘制视频帧到屏幕
        // 🔍 为什么调用：需要显示摄像头预览
        // 💡 作用：使用全屏矩形渲染器绘制纹理
        // ⏰ 使用时机：在获取变换矩阵后
        mFullScreen.drawFrame(mTextureId, mSTMatrix);

        // Draw a flashing box if we're recording.  This only appears on screen.
        // 🔲 showBox：是否显示录制指示框
        // 🔍 为什么判断：只有录制时才显示指示
        // 💡 作用：让用户知道正在录制
        // ⏰ 使用时机：在绘制完帧后判断
        // 🔴 录制时显示闪烁的指示框
        showBox = (mRecordingStatus == RECORDING_ON);
        
        // 📊 mFrameCount：帧计数器
        // 🔍 为什么递增：用于控制闪烁频率
        // 💡 作用：每4帧切换一次显示状态，产生闪烁效果
        // ⏰ 使用时机：每帧绘制时递增
        if (showBox && (++mFrameCount & 0x04) == 0) {
            // 🔴 drawBox：绘制红色指示框
            // 💡 作用：在屏幕角落显示录制指示
            drawBox();
        }
    }

    /**
     * Draws a red box in the corner.
     * 
     * 🔴 在角落绘制红色指示框（录制状态指示）
     */
    private void drawBox() {
        // 🎨 glEnable：启用OpenGL功能
        // 🔍 GL_SCISSOR_TEST：裁剪测试
        // 💡 作用：限制绘制区域到指定矩形
        // ⏰ 使用时机：在绘制指示框之前
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
        
        // 📐 glScissor：设置裁剪区域
        // 🔍 参数(0, 0, 100, 100)：从左下角(0,0)到右上角(100,100)
        // 💡 作用：只绘制左下角100x100像素的区域
        // ⏰ 使用时机：在启用裁剪测试后
        GLES20.glScissor(0, 0, 100, 100);
        
        // 🎨 glClearColor：设置清除颜色
        // 🔍 参数(1.0f, 0.0f, 0.0f, 1.0f)：红色（不透明）
        // 💡 作用：指定清除颜色为红色
        // ⏰ 使用时机：在清除之前设置
        GLES20.glClearColor(1.0f, 0.0f, 0.0f, 1.0f);
        
        // 🎨 glClear：清除颜色缓冲区
        // 🔍 GL_COLOR_BUFFER_BIT：只清除颜色
        // 💡 作用：用红色填充裁剪区域
        // ⏰ 使用时机：在设置清除颜色后
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        
        // 🎨 glDisable：禁用OpenGL功能
        // 🔍 GL_SCISSOR_TEST：裁剪测试
        // 💡 作用：恢复正常的绘制区域
        // ⏰ 使用时机：在绘制完成后
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
    }
}
