/*
 * Copyright 2014 Google Inc. All rights reserved.
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

import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.opengl.GLES20;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import androidx.annotation.NonNull;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.app.Activity;
import com.android.grafika.gles.EglCore;
import com.android.grafika.gles.FullFrameRect;
import com.android.grafika.gles.Texture2dProgram;
import com.android.grafika.gles.WindowSurface;
import com.google.grafika.R;
import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;

/**
 * Demonstrates capturing video into a ring buffer.  When the "capture" button is clicked,
 * the buffered video is saved.
 * <p>
 * Capturing and storing raw frames would be slow and require lots of memory.  Instead, we
 * feed the frames into the video encoder and buffer the output.
 * 
 * 📸 连续捕获演示：将视频捕获到环形缓冲区
 * 💡 点击"捕获"按钮时，保存缓冲的视频
 * 💡 不存储原始帧（慢且占内存），而是将帧送入编码器并缓冲输出
 */
public class ContinuousCaptureActivity extends Activity implements SurfaceHolder.Callback,
        SurfaceTexture.OnFrameAvailableListener {
    private static final String TAG = MainActivity.TAG;

    private static final int VIDEO_WIDTH = 1280;      // 📐 720p视频宽度
    private static final int VIDEO_HEIGHT = 720;      // 📐 720p视频高度
    private static final int DESIRED_PREVIEW_FPS = 15; // 🎬 期望预览帧率

    private EglCore mEglCore;                         // 🎮 EGL核心
    private WindowSurface mDisplaySurface;            // 🖼️ 显示Surface
    private SurfaceTexture mCameraTexture;            // 📷 摄像头纹理
    private FullFrameRect mFullFrameBlit;             // 🖼️ 全屏矩形
    private final float[] mTmpMatrix = new float[16]; // 📐 临时矩阵
    private int mTextureId;                           // 🖼️ 纹理ID
    private int mFrameNum;                            // 🔢 帧号

    private Camera mCamera;                           // 📷 摄像头
    private int mCameraPreviewThousandFps;            // 🎬 预览帧率（千分之一）

    private File mOutputFile;                         // 📁 输出文件
    private CircularEncoder mCircEncoder;             // 🎬 环形编码器
    private WindowSurface mEncoderSurface;            // 🖼️ 编码器Surface
    private boolean mFileSaveInProgress;              // 💾 文件保存中

    private MainHandler mHandler;                     // 📬 主线程Handler
    private float mSecondsOfVideo;                    // ⏱️ 视频秒数

    /**
     * Custom message handler for main UI thread.
     * 
     * 📬 主线程消息处理器
     */
    private static class MainHandler extends Handler implements CircularEncoder.Callback {
        public static final int MSG_BLINK_TEXT = 0;
        public static final int MSG_FRAME_AVAILABLE = 1;
        public static final int MSG_FILE_SAVE_COMPLETE = 2;
        public static final int MSG_BUFFER_STATUS = 3;

        private WeakReference<ContinuousCaptureActivity> mWeakActivity;

        public MainHandler(ContinuousCaptureActivity activity) {
            mWeakActivity = new WeakReference<>(activity);
        }

        @Override public void fileSaveComplete(int status) {
            sendMessage(obtainMessage(MSG_FILE_SAVE_COMPLETE, status, 0, null));
        }

        @Override public void bufferStatus(long totalTimeMsec) {
            sendMessage(obtainMessage(MSG_BUFFER_STATUS,
                    (int) (totalTimeMsec >> 32), (int) totalTimeMsec));
        }

        // 📬 消息处理回调（共22行，需逐行注释）
        // 🔧 为什么：处理主线程消息，更新UI和调度帧绘制
        // 📍 时机：Handler收到消息时由系统调用
        @Override
        public void handleMessage(Message msg) {
            // 📦 activity变量作用：持有Activity弱引用，避免内存泄漏
            // 🔍 为什么使用弱引用：防止Handler长期持有Activity导致无法回收
            // 📦 activity变量：持有Activity弱引用，避免内存泄漏
            // 🔍 为什么使用弱引用：防止Handler长期持有Activity导致无法回收
            // 📍 作用：从弱引用中取出Activity实例，用于访问UI控件
            // ⏰ 使用时机：每收到一条消息时都需要检查Activity是否还存活
            ContinuousCaptureActivity activity = mWeakActivity.get(); // 📞 获取Activity实例
            // 🔍 为什么检查null：Activity可能已被系统回收，继续操作会导致崩溃
            // 📍 作用：安全检查，防止对已销毁的Activity进行UI操作
            if (activity == null) return; // ❌ Activity已被回收，直接返回

            // 🔀 根据消息类型分发处理
            // 💡 msg.what变量：消息类型标识符，决定执行哪个case分支
            // 🔍 为什么需要：Handler可能收到多种类型的消息，需要区分处理
            // 📍 作用：switch的判断条件，匹配到对应的消息常量
            // ⏰ 使用时机：switch语句执行时自动读取
            switch (msg.what) {
                case MSG_BLINK_TEXT: { // 💡 闪烁文本消息（录制中视觉提示）
                    // 📺 tv变量：录制状态文本控件（TextView）
                    // 🔍 为什么定义：需要通过它来切换文字可见性，实现闪烁效果
                    // 📍 作用：持有R.id.recording_text的引用，用于getVisibility和setVisibility
                    // ⏰ 使用时机：获取当前可见性并切换时使用
                    TextView tv = (TextView) activity.findViewById(R.id.recording_text); // 🔍 获取录制文本控件
                    // 👁️ visibility变量：记录TextView当前的可见性状态
                    // 🔍 为什么定义：需要知道当前状态才能决定切换方向
                    // 📍 作用：存储View.VISIBLE或View.INVISIBLE，作为切换的判断依据
                    // ⏰ 使用时机：紧接获取后，用于三元运算符判断
                    int visibility = tv.getVisibility(); // 📋 获取当前可见性状态
                    // 🔄 切换可见性：VISIBLE→INVISIBLE，INVISIBLE→VISIBLE
                    // 🔍 为什么切换：交替显示/隐藏文字，产生闪烁效果，提示用户正在录制
                    tv.setVisibility(visibility == View.VISIBLE ? View.INVISIBLE : View.VISIBLE); // 🔀 切换可见性
                    // ⏱️ delay变量：下次发送闪烁消息的延迟毫秒数
                    // 🔍 为什么定义：可见时停留1秒（让用户看到），不可见时仅200毫秒（快速闪烁）
                    // 📍 作用：控制闪烁节奏——显示1秒，隐藏0.2秒，形成呼吸灯效果
                    // ⏰ 使用时机：立即传给sendEmptyMessageDelayed()作为延迟参数
                    int delay = (visibility == View.VISIBLE) ? 1000 : 200; // ⏱️ 计算下次闪烁延迟
                    sendEmptyMessageDelayed(MSG_BLINK_TEXT, delay); // 📬 延迟发送下次闪烁消息
                    break; // 🛑 跳出switch，处理下一条消息
                }
                case MSG_FRAME_AVAILABLE: activity.drawFrame(); break; // 🖼️ 新帧可用，调用drawFrame绘制一帧到屏幕和编码器
                case MSG_FILE_SAVE_COMPLETE: activity.fileSaveComplete(msg.arg1); break; // 💾 文件保存完成，传入状态码（0=成功）
                case MSG_BUFFER_STATUS: { // 📊 缓冲状态消息（视频缓冲时长更新）
                    // ⏱️ duration变量：视频缓冲总时长（微秒），从msg.arg1和msg.arg2重建
                    // 🔍 为什么需要：64位long值无法放入单个int，需拆分为高低32位传输
                    // 📍 作用：存储环形缓冲区中视频的总时长，用于更新UI显示秒数
                    // ⏰ 使用时机：立即传给activity.updateBufferStatus()更新UI
                    long duration = (((long) msg.arg1) << 32) | (((long) msg.arg2) & 0xffffffffL); // 🔢 从arg1和arg2重建64位时长
                    activity.updateBufferStatus(duration); // 🔄 更新缓冲状态显示
                    break; // 🛑 跳出switch
                }
                default: throw new RuntimeException("Unknown message " + msg.what); // ❓ 未知消息类型，抛出异常
            }
        }
    }

    // 🎬 Activity创建回调（共16行，需逐行注释）
    // 🔧 为什么：初始化UI布局、SurfaceView回调、Handler和输出文件路径
    // 📍 时机：Activity首次创建时由系统调用
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState); // 📞 调用父类onCreate，完成基础Activity初始化
        setContentView(R.layout.activity_continuous_capture); // 🖥️ 设置布局文件，加载UI界面

        // 🖥️ 获取SurfaceView并注册回调
        // 🔍 为什么：SurfaceView是渲染相机预览的容器，回调用于监听Surface生命周期
        // 💡 sv变量作用：持有SurfaceView引用，用于注册回调和获取渲染尺寸
        SurfaceView sv = (SurfaceView) findViewById(R.id.continuousCapture_surfaceView); // 🔍 通过ID获取SurfaceView控件
        sv.getHolder().addCallback(this); // 📌 注册SurfaceHolder.Callback，监听Surface创建/变化/销毁事件

        // 📬 创建Handler并延迟发送闪烁文本消息
        // 🔍 为什么：Handler用于在主线程处理消息，实现UI更新和帧绘制调度
        // 💡 mHandler变量作用：主线程消息处理器，接收帧可用、文件保存完成等消息
        mHandler = new MainHandler(this); // 📬 创建Handler实例，绑定当前Activity
        mHandler.sendEmptyMessageDelayed(MainHandler.MSG_BLINK_TEXT, 1500); // ⏱️ 延迟1.5秒后发送闪烁文本消息，提供视觉反馈

        // 📁 设置输出文件路径
        // 🔍 为什么：录制的MP4文件需要保存到应用内部存储
        // 💡 mOutputFile变量作用：存储环形编码器输出的视频文件路径
        mOutputFile = new File(getFilesDir(), "continuous-capture.mp4"); // 📂 创建输出文件对象，保存到应用files目录
        mSecondsOfVideo = 0.0f; // ⏱️ 初始化视频秒数为0，后续由缓冲状态更新
        updateControls(); // 🔄 更新UI控件状态（按钮启用状态和文本显示）
    }

    // ▶️ Activity恢复回调（共8行，需逐行注释）
    // 🔧 为什么：检查相机权限，打开相机并启动预览
    // 📍 时机：Activity从后台回到前台时由系统调用
    @Override
    protected void onResume() {
        // 📞 super.onResume(): 调用父类onResume
        // 🔍 为什么调用：必须执行系统级恢复逻辑（恢复UI状态等）
        // 📍 作用：完成标准的Activity恢复流程
        // ⏰ 时机：自定义恢复逻辑前必须调用
        super.onResume(); // 📞 调用父类onResume，完成基础恢复逻辑

        // 🔐 检查相机权限
        // 🔍 为什么检查：相机是敏感权限，Android 6.0+需要运行时动态申请
        // 📍 作用：判断当前Activity是否已获得CAMERA权限
        // ⏰ 时机：Activity每次恢复时都需要检查（权限可能被用户在设置中撤销）
        if (!PermissionHelper.hasCameraPermission(this)) { // ❌ 没有相机权限
            // 🙏 请求相机权限，弹出系统权限对话框
            // 🔍 为什么请求：没有相机权限则无法打开摄像头，核心功能无法使用
            // 📍 参数false：非强制要求，用户拒绝后仍可进入Activity（但功能受限）
            PermissionHelper.requestCameraPermission(this, false); // 🙏 请求相机权限，false表示非强制要求
        } else { // ✅ 已有相机权限
            // 📷 mCamera变量：Camera实例引用
            // 🔍 为什么检查null：相机在onPause()中被releaseCamera()释放，恢复时需要重新打开
            // 📍 作用：持有Camera对象，用于设置预览参数和启动预览
            // ⏰ 时机：onResume中权限已授予时检查并打开
            if (mCamera == null) openCamera(VIDEO_WIDTH, VIDEO_HEIGHT, DESIRED_PREVIEW_FPS); // 📷 打开相机，设置720p预览
            // 🎮 mEglCore变量：EGL核心上下文管理器
            // 🔍 为什么检查null：EGL核心在surfaceCreated()中创建，恢复时可能还未创建
            // 📍 作用：持有EGL上下文，startPreview()需要它来创建编码器Surface
            // ⏰ 时机：相机已打开时检查，确保渲染环境已就绪
            if (mEglCore != null) startPreview(); // ▶️ 启动相机预览和环形编码器
        }
    }

    // ⏸️ Activity暂停回调（共9行，需逐行注释）
    // 🔧 为什么：释放相机、编码器、纹理等资源，避免内存泄漏
    // 📍 时机：Activity进入后台时由系统调用
    @Override
    protected void onPause() {
        // 📞 super.onPause(): 调用父类onPause
        // 🔍 为什么调用：必须执行系统级暂停逻辑
        // 📍 作用：完成标准的Activity暂停流程
        // ⏰ 时机：自定义暂停逻辑前必须调用
        super.onPause(); // 📞 调用父类onPause，完成基础暂停逻辑

        // 📷 releaseCamera(): 释放相机硬件资源
        // 🔍 为什么调用：相机是独占资源，不释放会导致其他应用无法使用
        // 📍 作用：停止预览并释放Camera对象
        // ⏰ 时机：Activity暂停时最先释放相机
        releaseCamera(); // 📷 释放相机资源，停止预览

        // 🧹 清理编码器、纹理、Surface和EGL资源
        // 🔍 为什么：这些资源占用GPU和系统内存，必须及时释放避免内存泄漏

        // 🎬 mCircEncoder变量：环形编码器实例
        // 🔍 为什么检查null：编码器在startPreview()中创建，可能还未创建
        // 📍 作用：负责视频编码和环形缓冲，shutdown()会释放编码线程和缓冲区
        // ⏰ 时机：相机释放后，依次释放编码相关资源
        if (mCircEncoder != null) { mCircEncoder.shutdown(); mCircEncoder = null; } // 🎬 关闭环形编码器，释放编码资源

        // 🖼️ mCameraTexture变量：相机预览的SurfaceTexture
        // 🔍 为什么检查null：纹理在surfaceCreated()中创建，可能还未创建
        // 📍 作用：接收相机预览帧，release()会释放OpenGL纹理和native资源
        // ⏰ 时机：编码器释放后释放纹理
        if (mCameraTexture != null) { mCameraTexture.release(); mCameraTexture = null; } // 🖼️ 释放摄像头纹理

        // 🪟 mDisplaySurface变量：显示窗口Surface（EGL WindowSurface）
        // 🔍 为什么检查null：窗口Surface在surfaceCreated()中创建
        // 📍 作用：将OpenGL渲染结果输出到屏幕，release()释放EGL Surface资源
        // ⏰ 时机：纹理释放后释放显示Surface
        if (mDisplaySurface != null) { mDisplaySurface.release(); mDisplaySurface = null; } // 🪟 释放显示窗口Surface

        // 🖼️ mFullFrameBlit变量：全屏矩形渲染器（FullFrameRect）
        // 🔍 为什么检查null：渲染器在surfaceCreated()中创建
        // 📍 作用：绘制全屏纹理，release(false)释放着色器程序但不释放纹理（纹理已单独释放）
        // ⏰ 时机：显示Surface释放后释放渲染器
        if (mFullFrameBlit != null) { mFullFrameBlit.release(false); mFullFrameBlit = null; } // 🖼️ 释放全屏矩形渲染器

        // 🎮 mEglCore变量：EGL上下文核心管理器
        // 🔍 为什么检查null：EGL核心在surfaceCreated()中创建
        // 📍 作用：管理OpenGL ES与原生窗口系统的连接，release()终止EGL上下文
        // ⏰ 时机：所有OpenGL资源释放后，最后释放EGL核心
        if (mEglCore != null) { mEglCore.release(); mEglCore = null; } // 🎮 释放EGL核心
    }

    /**
     * Opens a camera, and attempts to establish preview mode at the specified width and height.
     *
     * 📷 打开相机并设置预览参数
     * 优先使用前置摄像头，设置录制提示和预览尺寸
     * 根据屏幕旋转设置显示方向和宽高比
     *
     * @param desiredWidth  期望的预览宽度（像素），通常为1280
     * @param desiredHeight 期望的预览高度（像素），通常为720
     * @param desiredFps    期望的预览帧率，如15
     */
    // 📷 打开摄像头并配置预览参数（共约40行，超过30行阈值，需逐行注释）
    private void openCamera(int desiredWidth, int desiredHeight, int desiredFps) {
        // ⚠️ 安全检查：防止重复初始化摄像头，避免资源泄漏
        if (mCamera != null) throw new RuntimeException("camera already initialized");

        // 🔍 创建CameraInfo对象，用于获取摄像头信息
        // 💡 作用：存储摄像头朝向、方向等元数据
        // 💡 使用时机：遍历所有摄像头时，判断是否为前置摄像头
        Camera.CameraInfo info = new Camera.CameraInfo();
        // 🔢 获取设备上摄像头总数
        // 💡 作用：决定循环遍历的次数
        // 💡 使用时机：for循环开始前，确定搜索范围
        int numCameras = Camera.getNumberOfCameras();
        // 🔁 遍历所有摄像头，优先查找前置摄像头
        // 💡 作用：找到前置摄像头并打开，提升用户体验（自拍场景）
        // 💡 使用时机：设备有多个摄像头时，优先选择前置
        for (int i = 0; i < numCameras; i++) {
            // 📋 获取第i个摄像头的信息，填充到info对象
            Camera.getCameraInfo(i, info);
            // 🔍 判断当前摄像头是否为前置摄像头
            // 💡 CameraInfo.CAMERA_FACING_FRONT = 前置摄像头
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                // 📷 打开第i个摄像头并赋值给mCamera
                // 💡 作用：获取摄像头实例，后续用于设置参数和启动预览
                mCamera = Camera.open(i);
                // 🛑 找到前置摄像头后立即跳出循环，不再继续搜索
                break;
            }
        }
        // 📷 如果没有找到前置摄像头（或设备只有后置），使用默认摄像头
        // 💡 作用：兜底策略，确保摄像头一定能打开
        if (mCamera == null) mCamera = Camera.open();
        // ❌ 最终安全检查：如果仍然无法打开摄像头，抛出异常
        if (mCamera == null) throw new RuntimeException("Unable to open camera");

        // ⚙️ 获取摄像头当前参数对象，用于修改预览尺寸和帧率
        // 💡 作用：获取可修改的参数容器
        // 💡 使用时机：打开摄像头后、启动预览前
        Camera.Parameters parms = mCamera.getParameters();
        // 📐 根据期望尺寸选择最接近的预览尺寸
        // 💡 作用：确保预览尺寸与编码器要求匹配
        // 💡 参数：parms会被修改，包含选中的预览尺寸
        CameraUtils.choosePreviewSize(parms, desiredWidth, desiredHeight);
        // 🎬 设置固定预览帧率（单位：千分之一fps）
        // 💡 作用：将期望fps转换为千分之一单位，设置到参数中
        // 💡 赋值给mCameraPreviewThousandFps：后续创建编码器时需要此值
        // 💡 使用时机：创建CircularEncoder时，需要知道实际预览帧率
        mCameraPreviewThousandFps = CameraUtils.chooseFixedPreviewFps(parms, desiredFps * 1000);
        // 🎥 设置录制提示标志（可帮助系统优化帧率和性能）
        // 💡 作用：告诉系统接下来要录制视频，可能触发帧率优化
        parms.setRecordingHint(true);  // 🎥 设置录制提示（可提高帧率）
        // 📝 将修改后的参数应用到摄像头
        // 💡 作用：使预览尺寸和帧率设置生效
        mCamera.setParameters(parms);

        // 📐 获取实际生效的预览尺寸（可能与期望值略有不同）
        // 💡 作用：用于计算正确的宽高比
        // 💡 使用时机：设置AspectFrameLayout的宽高比时
        Camera.Size cameraPreviewSize = parms.getPreviewSize();
        // 🖥️ 获取AspectFrameLayout控件，用于设置预览画面的宽高比
        // 💡 作用：确保预览画面不变形，正确填充屏幕
        // 💡 使用时机：根据屏幕旋转方向设置不同的宽高比
        AspectFrameLayout layout = (AspectFrameLayout) findViewById(R.id.continuousCapture_afl);
        // 📱 获取屏幕显示对象，用于判断当前屏幕旋转方向
        // 💡 作用：获取屏幕方向，决定是否需要旋转相机预览画面
        // 💡 使用时机：设置相机显示方向和宽高比前
        Display display = ((WindowManager)getSystemService(WINDOW_SERVICE)).getDefaultDisplay();

        // 🔄 根据屏幕旋转方向设置相机显示方向和预览画面宽高比
        // 🔍 为什么需要：摄像头传感器默认方向是横向，而手机屏幕可能旋转
        // 📍 作用：无论手机如何旋转，都确保预览画面方向正确、比例不变形
        if(display.getRotation() == Surface.ROTATION_0) {
            // 📐 ROTATION_0：屏幕竖直（portrait模式），旋转90度
            // 🔍 为什么旋转90度：摄像头传感器横向安装，竖屏时需要旋转90度对齐
            // 📍 作用：设置Camera预览画面旋转角度，使画面方向与屏幕一致
            mCamera.setDisplayOrientation(90);
            // 📐 设置宽高比：交换宽高（因为旋转了90度）
            // 🔍 为什么交换：旋转90度后，原来的宽度变成了高度，需要反向设置
            // 📍 作用：保持画面比例正确，避免预览被拉伸变形
            // ⏰ 参数：height/width而非width/height，适应旋转后的实际方向
            layout.setAspectRatio((double) cameraPreviewSize.height / cameraPreviewSize.width);
        } else if(display.getRotation() == Surface.ROTATION_270) {
            // 📐 ROTATION_270：屏幕反向竖直（倒置portrait），交换宽高比
            // 🔍 为什么交换：同样需要适应旋转方向
            // 📍 作用：设置AspectFrameLayout的宽高比为height/width
            layout.setAspectRatio((double) cameraPreviewSize.height / cameraPreviewSize.width);
            // 📐 旋转180度：修正反向竖直时的画面方向
            // 🔍 为什么旋转180度：倒置portrait时画面需要额外旋转180度
            // 📍 作用：使相机画面与屏幕方向完全对齐
            mCamera.setDisplayOrientation(180);
        } else {
            // 📐 ROTATION_90/ROTATION_180：横屏或其他方向，直接使用原始宽高比
            // 🔍 为什么不需要交换：横屏时摄像头传感器与屏幕方向自然对齐
            // 📍 作用：使用width/height的原始比例，无需交换
            layout.setAspectRatio((double) cameraPreviewSize.width / cameraPreviewSize.height);
        }
    }

    /**
     * 📷 释放摄像头资源
     * 💡 作用：停止预览并释放Camera对象，避免资源泄漏
     * 💡 使用时机：onPause()中Activity暂停时调用
     */
    private void releaseCamera() {
        // 🔍 检查Camera是否已初始化
        // 💡 为什么检查：避免对null对象调用方法导致NullPointerException
        if (mCamera != null) {
            // ⏹️ 停止相机预览
            // 💡 作用：停止摄像头输出预览帧，解除与SurfaceTexture的绑定
            // 💡 使用时机：释放Camera之前必须先停止预览
            mCamera.stopPreview();
            // 🗑️ 释放Camera硬件资源
            // 💡 作用：释放摄像头独占锁，允许其他应用使用摄像头
            // 💡 使用时机：停止预览后调用，彻底释放摄像头
            mCamera.release();
            // 🔚 置空引用，方便GC回收
            // 💡 作用：解除对Camera对象的引用，便于垃圾回收
            // 💡 使用时机：释放后立即置空，防止重复释放
            mCamera = null;
        }
    }

    /** 🔄 更新控件状态（共7行，需逐行注释）
     *  🔧 为什么：同步UI与业务状态，显示视频秒数和控制按钮启用
     *  📍 时机：缓冲状态更新、文件保存开始/完成时调用
     */
    private void updateControls() {
        // 📝 str变量作用：格式化后的视频秒数字符串，用于显示
        // 🔍 为什么：将浮点数秒数转换为本地化字符串
        String str = getString(R.string.secondsOfVideo, mSecondsOfVideo); // 📋 获取格式化字符串，包含视频秒数
        // 📝 tv变量作用：视频描述文本控件，显示录制时长
        TextView tv = (TextView) findViewById(R.id.capturedVideoDesc_text); // 🔍 获取视频描述文本控件
        tv.setText(str); // ✍️ 设置文本显示视频秒数
        // 📊 wantEnabled变量作用：捕获按钮是否应启用
        // 🔍 为什么：编码器存在且不在保存中时才允许捕获
        boolean wantEnabled = (mCircEncoder != null) && !mFileSaveInProgress; // 🔀 计算按钮启用条件
        // 🔘 button变量作用：捕获按钮控件
        Button button = (Button) findViewById(R.id.capture_button); // 🔍 获取捕获按钮
        button.setEnabled(wantEnabled); // 🔒 设置按钮启用状态
    }

    /** 📸 捕获按钮点击处理（共7行，需逐行注释）
     *  🔧 为什么：触发环形缓冲区视频保存，防止重复点击
     *  📍 时机：用户点击捕获按钮时由XML onClick属性调用
     */
    public void clickCapture(@SuppressWarnings("unused") View unused) {
        // ⚠️ 检查是否正在保存，防止重复触发
        // 🔍 为什么：视频保存是异步操作，重复点击会导致并发问题
        if (mFileSaveInProgress) return; // 🛑 正在保存中，直接返回
        mFileSaveInProgress = true; // 🔒 标记为保存中，阻止后续点击
        updateControls(); // 🔄 更新UI，禁用捕获按钮
        // 📝 tv变量作用：录制状态文本控件
        TextView tv = (TextView) findViewById(R.id.recording_text); // 🔍 获取录制文本控件
        tv.setText(getString(R.string.nowSaving)); // ✍️ 设置文本为"正在保存"
        // 💾 调用环形编码器保存视频
        // 🔍 为什么：将缓冲区中的视频数据写入文件
        // 💡 mCircEncoder变量作用：环形编码器，包含缓冲的视频数据
        // 💡 mOutputFile变量作用：输出文件路径
        mCircEncoder.saveVideo(mOutputFile); // 💾 保存环形缓冲区视频到文件
    }

    /** ✅ 文件保存完成处理（共7行，需逐行注释）
     *  🔧 为什么：重置保存状态，更新UI，显示保存结果
     *  📍 时机：环形编码器视频保存完成后由Handler回调调用
     *  @param status 保存状态码，0表示成功，非0表示失败
     */
    private void fileSaveComplete(int status) {
        mFileSaveInProgress = false; // 🔓 重置保存标志，允许再次捕获
        updateControls(); // 🔄 更新UI，启用捕获按钮
        // 📝 tv变量作用：录制状态文本控件
        TextView tv = (TextView) findViewById(R.id.recording_text); // 🔍 获取录制文本控件
        tv.setText(getString(R.string.nowRecording)); // ✍️ 恢复文本为"正在录制"
        // 📝 str变量作用：保存结果提示信息
        // 🔍 为什么：根据状态码选择成功或失败提示
        String str = (status == 0) ? getString(R.string.recordingSucceeded) : getString(R.string.recordingFailed, status); // 🔀 生成结果消息
        Toast.makeText(this, str, Toast.LENGTH_SHORT).show(); // 💬 显示Toast提示保存结果
    }

    /** 
     * 🔄 更新缓冲状态
     * 💡 作用：将微秒时长转换为秒数，更新UI显示
     * 💡 使用时机：环形编码器缓冲状态更新时由Handler回调调用
     * 
     * @param durationUsec 视频缓冲时长（微秒）
     */
    private void updateBufferStatus(long durationUsec) {
        // 📊 mSecondsOfVideo: 视频秒数（float类型）
        //    为什么定义：需要在UI上显示用户友好的秒数（如"3.5秒"）
        //    作用：存储转换后的秒数值，供updateControls()格式化显示
        //    使用时机：updateControls()中通过getString(R.string.secondsOfVideo, mSecondsOfVideo)显示
        // 🔢 durationUsec / 1000000.0f: 微秒转秒的计算
        //    为什么除以1000000：1秒 = 1000000微秒
        //    为什么用.0f：确保浮点除法，保留小数部分
        mSecondsOfVideo = durationUsec / 1000000.0f; // 🔢 微秒转秒
        // 🔄 updateControls(): 更新UI控件状态
        //    为什么调用：秒数变化后需要刷新界面显示
        //    作用：更新视频秒数文本和捕获按钮启用状态
        //    使用时机：秒数更新后立即调用
        updateControls(); // 🔄 更新UI控件状态
    }

    // 🎨 Surface创建回调（共14行，需逐行注释）
    // 🔧 为什么：初始化EGL环境、创建渲染资源、启动相机预览
    // 📍 时机：SurfaceView的Surface首次创建时由系统调用
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        // 🎬 创建EGL核心（支持录制）
        // 🔍 为什么：EGL是OpenGL ES与原生窗口系统的桥梁，FLAG_RECORDABLE支持编码器录制
        // 💡 mEglCore变量作用：EGL上下文管理器，协调OpenGL ES渲染
        mEglCore = new EglCore(null, EglCore.FLAG_RECORDABLE); // 🎮 创建EGL核心，共享上下文为null，启用录制标志
        // 🪟 创建显示窗口Surface
        // 🔍 为什么：WindowSurface将EGL渲染输出到屏幕Surface
        // 💡 mDisplaySurface变量作用：显示窗口，用于渲染到屏幕
        // 💡 holder.getSurface()获取SurfaceView的Surface，false表示不托管Surface生命周期
        mDisplaySurface = new WindowSurface(mEglCore, holder.getSurface(), false); // 🪟 创建显示窗口Surface
        mDisplaySurface.makeCurrent(); // 🔗 将EGL上下文绑定到当前线程
        // 🖼️ 创建全屏矩形渲染器（用于外部纹理）
        // 🔍 为什么：FullFrameRect用于绘制全屏纹理，ProgramType.TEXTURE_EXT支持外部纹理（相机）
        // 💡 mFullFrameBlit变量作用：全屏矩形渲染器，绘制相机纹理到屏幕
        mFullFrameBlit = new FullFrameRect(new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_EXT)); // 🖼️ 创建全屏矩形渲染器
        // 🆔 创建纹理对象并绑定SurfaceTexture
        // 🔍 为什么：纹理ID用于OpenGL ES标识纹理，SurfaceTexture将纹理绑定到相机
        // 💡 mTextureId变量作用：OpenGL纹理ID，用于绑定和绘制纹理
        mTextureId = mFullFrameBlit.createTextureObject(); // 🆔 创建纹理对象
        // 💡 mCameraTexture变量作用：相机纹理，接收相机预览帧
        mCameraTexture = new SurfaceTexture(mTextureId); // 🖼️ 创建SurfaceTexture，绑定纹理ID
        mCameraTexture.setOnFrameAvailableListener(this); // 📌 注册帧可用监听器，新帧到达时回调onFrameAvailable
        // ▶️ 开始预览
        // 🔍 为什么：Surface创建完成后才能启动相机预览
        startPreview(); // ▶️ 启动相机预览和环形编码器
    }

    // ▶️ 开始相机预览并创建环形编码器（共12行，需逐行注释）
    // 🔧 为什么：将相机预览绑定到SurfaceTexture，创建环形编码器用于视频缓冲
    // 📍 时机：surfaceCreated中Surface创建完成后调用
    private void startPreview() {
        // 📷 检查相机是否已打开
        // 🔍 为什么：相机在onResume中可能未打开，需要检查
        if (mCamera != null) {
            // 🖼️ 设置相机预览纹理
            // 🔍 为什么：将相机预览输出到SurfaceTexture，用于OpenGL ES渲染
            // 💡 mCameraTexture变量作用：相机纹理，接收预览帧
            try { mCamera.setPreviewTexture(mCameraTexture); } catch (IOException ioe) { throw new RuntimeException(ioe); } // 🖼️ 设置预览纹理
            mCamera.startPreview(); // ▶️ 启动相机预览，开始输出帧到SurfaceTexture
        }
        // 🎬 创建环形编码器（6Mbps，7秒缓冲）
        // 🔍 为什么：环形编码器持续编码并缓冲视频，点击保存时输出最近7秒
        // 💡 mCircEncoder变量作用：环形编码器，负责视频编码和缓冲
        // 💡 参数说明：VIDEO_WIDTH=1280, VIDEO_HEIGHT=720, 比特率6Mbps, 帧率, 缓冲秒数7秒, Handler回调
        try {
            mCircEncoder = new CircularEncoder(VIDEO_WIDTH, VIDEO_HEIGHT, 6000000, mCameraPreviewThousandFps / 1000, 7, mHandler); // 🎬 创建环形编码器
        } catch (IOException ioe) { throw new RuntimeException(ioe); } // ❌ 编码器创建失败
        // 🖼️ 创建编码器输入Surface
        // 🔍 为什么：编码器需要输入Surface来接收渲染的帧
        // 💡 mEncoderSurface变量作用：编码器输入窗口Surface
        // 💡 true参数表示托管Surface生命周期
        mEncoderSurface = new WindowSurface(mEglCore, mCircEncoder.getInputSurface(), true); // 🖼️ 创建编码器输入Surface
        updateControls(); // 🔄 更新UI控件状态
    }

    // 📐 Surface尺寸变化回调（无需处理）
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
    // 💥 Surface销毁回调（无需处理，资源在onPause中释放）
    @Override public void surfaceDestroyed(SurfaceHolder holder) {}
    // 🎬 新帧可用时发送消息到主线程
    @Override public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        mHandler.sendEmptyMessage(MainHandler.MSG_FRAME_AVAILABLE);
    }

    // 🔐 处理相机权限请求结果（共11行，需逐行注释）
    // 🔧 为什么：处理用户对相机权限请求的响应，决定是否打开相机
    // 📍 时机：用户从权限对话框返回后由系统调用
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults); // 📞 调用父类处理
        // ❌ 权限被拒绝时提示并关闭
        // 🔍 为什么：相机是核心功能，没有权限无法继续
        // 💡 this变量作用：当前Activity上下文
        if (!PermissionHelper.hasCameraPermission(this)) { // ❌ 仍然没有相机权限
            Toast.makeText(this, "Camera permission is needed", Toast.LENGTH_LONG).show(); // 💬 显示权限被拒提示
            PermissionHelper.launchPermissionSettings(this); // ⚙️ 打开系统权限设置页面
            finish(); // 🚪 关闭Activity，因为无法使用相机
        } else { // ✅ 权限已授予
            // ✅ 权限 granted 后打开相机
            // 🔍 为什么：获得权限后才能安全使用相机API
            openCamera(VIDEO_WIDTH, VIDEO_HEIGHT, DESIRED_PREVIEW_FPS); // 📷 打开相机，设置720p预览
        }
    }

    /**
     * 绘制一帧（共26行，需逐行注释）
     * 🔧 为什么：从相机获取帧，先绘制到屏幕，再绘制到编码器（录制中）
     * 📍 时机：新帧可用时由Handler消息触发
     *
     * 🖼️ 绘制流程：更新纹理→设置视口→绘制全屏矩形→绘制额外效果→交换缓冲区
     */
    private void drawFrame() {
        // ⚠️ 安全检查：EGL核心未初始化时直接返回
        // 🔍 为什么：surfaceCreated可能未完成，EGL资源未就绪
        if (mEglCore == null) return; // 🛑 EGL未初始化，跳过绘制

        // 🖥️ 绘制到显示Surface
        // 🔍 为什么：首先将相机画面渲染到屏幕，用户可以看到预览
        // 💡 mDisplaySurface变量作用：显示窗口Surface
        mDisplaySurface.makeCurrent(); // 🔗 绑定显示Surface的EGL上下文
        // 📷 更新摄像头纹理
        // 🔍 为什么：SurfaceTexture有新帧时，必须调用此方法更新纹理内容
        // 💡 mCameraTexture变量作用：相机纹理，接收预览帧
        mCameraTexture.updateTexImage();                // 📷 更新摄像头纹理
        // 📐 获取纹理变换矩阵
        // 🔍 为什么：相机可能有旋转或裁剪，需要变换矩阵校正
        // 💡 mTmpMatrix变量作用：4x4纹理变换矩阵
        mCameraTexture.getTransformMatrix(mTmpMatrix);  // 📐 获取纹理变换矩阵

        // 🖥️ 设置视口为SurfaceView尺寸并绘制
        // 🔍 为什么：视口决定渲染区域大小，需与SurfaceView匹配
        SurfaceView sv = (SurfaceView) findViewById(R.id.continuousCapture_surfaceView); // 🔍 获取SurfaceView
        GLES20.glViewport(0, 0, sv.getWidth(), sv.getHeight()); // 📐 设置视口为SurfaceView尺寸
        // 🖼️ 绘制全屏矩形
        // 🔍 为什么：将相机纹理绘制到整个屏幕
        // 💡 mFullFrameBlit变量作用：全屏矩形渲染器
        // 💡 mTextureId变量作用：纹理ID
        mFullFrameBlit.drawFrame(mTextureId, mTmpMatrix); // 🖼️ 绘制全屏纹理
        drawExtra(mFrameNum, sv.getWidth(), sv.getHeight()); // 🎨 绘制额外效果（闪烁方块）
        mDisplaySurface.swapBuffers(); // 🔄 交换缓冲区，显示绘制结果

        // 🎥 如果不在保存文件，绘制到编码器Surface
        // 🔍 为什么：保存期间不编码新帧，避免并发问题
        // 💡 mFileSaveInProgress变量作用：文件保存中标志
        if (!mFileSaveInProgress) {
            // 💡 mEncoderSurface变量作用：编码器输入Surface
            mEncoderSurface.makeCurrent(); // 🔗 绑定编码器Surface的EGL上下文
            GLES20.glViewport(0, 0, VIDEO_WIDTH, VIDEO_HEIGHT); // 📐 设置视口为视频尺寸（1280x720）
            mFullFrameBlit.drawFrame(mTextureId, mTmpMatrix); // 🖼️ 绘制全屏纹理到编码器
            drawExtra(mFrameNum, VIDEO_WIDTH, VIDEO_HEIGHT); // 🎨 绘制额外效果
            // 💡 mCircEncoder变量作用：环形编码器
            mCircEncoder.frameAvailableSoon(); // 📬 通知编码器有新帧可用
            // ⏱️ 设置呈现时间
            // 🔍 为什么：编码器需要时间戳来同步音视频（本例仅视频）
            mEncoderSurface.setPresentationTime(mCameraTexture.getTimestamp()); // ⏱️ 设置帧时间戳
            mEncoderSurface.swapBuffers(); // 🔄 交换缓冲区，提交帧到编码器
        }
        mFrameNum++; // 🔢 递增帧号，用于额外效果的动画
    }

    /**
     * 绘制额外效果（共14行，需逐行注释）
     * 🔧 为什么：在相机设置期间提供视觉反馈，显示录制正在进行
     * 📍 时机：每帧绘制时由drawFrame调用
     *
     * 🎨 效果说明：根据帧号交替显示红/绿/蓝色，并在屏幕一角绘制移动方块
     * @param frameNum 帧号，用于计算颜色和位置
     * @param width 渲染区域宽度
     * @param height 渲染区域高度
     */
    private static void drawExtra(int frameNum, int width, int height) {
        // 🎨 根据帧号交替设置清除颜色
        // 🔍 为什么：模3取余，循环显示红、绿、蓝三色
        // 💡 val变量作用：颜色索引（0=红，1=绿，2=蓝）
        int val = frameNum % 3; // 🔢 计算颜色索引
        // 🔀 根据颜色索引设置清除颜色
        switch (val) {
            case 0: GLES20.glClearColor(1.0f, 0.0f, 0.0f, 1.0f); break;  // 🔴 红色：RGB(255,0,0)
            case 1: GLES20.glClearColor(0.0f, 1.0f, 0.0f, 1.0f); break;  // 🟢 绿色：RGB(0,255,0)
            case 2: GLES20.glClearColor(0.0f, 0.0f, 1.0f, 1.0f); break;  // 🔵 蓝色：RGB(0,0,255)
        }
        // 📍 在屏幕一角绘制小方块
        // 🔍 为什么：方块从左向右移动，提供动态视觉反馈
        // 💡 xpos变量作用：方块水平位置，每100帧循环一次
        int xpos = (int) (width * ((frameNum % 100) / 100.0f)); // 📐 计算方块x坐标（0~width）
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST); // ✂️ 启用剪裁测试，限制绘制区域
        GLES20.glScissor(xpos, 0, width / 32, height / 32); // ✂️ 设置剪裁区域（小方块）
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT); // 🧹 清除剪裁区域（填充当前清除颜色）
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST); // ✂️ 禁用剪裁测试
    }
}
