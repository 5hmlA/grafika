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

// 📚 导入需要的类库
import android.graphics.SurfaceTexture;      // 🖼️ SurfaceTexture：用于将图像流转换为OpenGL纹理
import android.hardware.Camera;              // 📷 Camera：摄像头类
import android.opengl.GLES20;                // 🎮 GLES20：OpenGL ES 2.0 API
import android.opengl.Matrix;                // 📐 Matrix：矩阵变换类
import android.os.Bundle;                    // 🎁 Bundle：用于在Activity之间传递数据
import android.os.Handler;                   // 🔧 Handler：消息处理器
import android.os.Looper;                    // 🔄 Looper：消息循环
import android.os.Message;                   // 📨 Message：消息对象
import android.util.Log;                     // 📝 Log：日志工具
import android.view.MotionEvent;            // 👆 MotionEvent：触摸事件
import android.view.Surface;                 // 🖼️ Surface：显示表面
import android.view.SurfaceHolder;           // 🖼️ SurfaceHolder：Surface的持有者
import android.view.SurfaceView;             // 🖼️ SurfaceView：Surface视图
import android.widget.SeekBar;              // 🎚️ SeekBar：拖动条
import android.widget.TextView;             // 📝 TextView：文本视图
import android.app.Activity;                // 📱 Activity：Android活动基类

import com.android.grafika.gles.Drawable2d;  // 🎨 Drawable2d：2D可绘制对象
import com.android.grafika.gles.EglCore;     // 🎮 EglCore：EGL核心类
import com.android.grafika.gles.GlUtil;      // 🔧 GlUtil：OpenGL工具类
import com.android.grafika.gles.Sprite2d;    // 🎨 Sprite2d：2D精灵
import com.android.grafika.gles.Texture2dProgram;  // 🎨 Texture2dProgram：2D纹理程序
import com.android.grafika.gles.WindowSurface;     // 🖼️ WindowSurface：窗口Surface
import com.google.grafika.R;                 // 🎨 R：资源文件

import java.io.IOException;                  // ⚠️ IOException：IO异常
import java.lang.ref.WeakReference;          // 🔗 WeakReference：弱引用

/**
 * Direct the Camera preview to a GLES texture and manipulate it.
 * <p>
 * We manage the Camera and GLES rendering from a dedicated thread.  We don't animate anything,
 * so we don't need a Choreographer heartbeat -- just redraw when we get a new frame from the
 * camera or the user has caused a change in size or position.
 * <p>
 * The Camera needs to follow the activity pause/resume cycle so we don't keep it locked
 * while we're in the background.  Also, for power reasons, we don't want to keep getting
 * frames when the screen is off.  As noted in
 * http://source.android.com/devices/graphics/architecture.html#activity
 * the Surface lifecycle isn't quite the same as the activity's.  We follow approach #1.
 * <p>
 * The tricky part about the lifecycle is that our SurfaceView's Surface can outlive the
 * Activity, and we can get surface callbacks while paused, so we need to keep track of it
 * in a static variable and be prepared for calls at odd times.
 * <p>
 * The zoom, size, and rotate values are determined by the values stored in the "seek bars"
 * (sliders).  When the device is rotated, the Activity is paused and resumed, but the
 * controls retain their value, which is kind of nice.  The position, set by touch, is lost
 * on rotation.
 * <p>
 * The UI updates go through a multi-stage process:
 * <ol>
 * <li> The user updates a slider.
 * <li> The new value is passed as a percent to the render thread.
 * <li> The render thread converts the percent to something concrete (e.g. size in pixels).
 *      The rect geometry is updated.
 * <li> (For most things) The values computed by the render thread are sent back to the main
 *      UI thread.
 * <li> (For most things) The UI thread updates some text views.
 * </ol>
 * 
 * 📷 将摄像头预览定向到GLES纹理并进行操作
 * 💡 从专用线程管理摄像头和GLES渲染
 * 💡 不需要动画，所以不需要Choreographer心跳——只在收到新帧或用户改变大小/位置时重绘
 * 
 * ⚠️ 生命周期说明：
 *    摄像头需要遵循Activity的暂停/恢复周期，以免在后台保持锁定
 *    SurfaceView的Surface可能比Activity寿命更长
 *    静态变量跟踪Surface，准备好在奇怪的时间收到回调
 * 
 * 🎚️ 用户控件说明：
 *    缩放、大小和旋转值由拖动条（滑块）决定
 *    设备旋转时，Activity暂停和恢复，但控件保留其值
 *    触摸设置的位置在旋转时丢失
 */
public class TextureFromCameraActivity extends Activity implements SurfaceHolder.Callback,
        SeekBar.OnSeekBarChangeListener {
    // 🏷️ TAG：日志标签
    private static final String TAG = MainActivity.TAG;

    // 🎚️ 默认值常量
    private static final int DEFAULT_ZOOM_PERCENT = 0;      // 0-100   # 🔍 默认缩放百分比
    private static final int DEFAULT_SIZE_PERCENT = 50;     // 0-100   # 📐 默认大小百分比
    private static final int DEFAULT_ROTATE_PERCENT = 0;    // 0-100   # 🔄 默认旋转百分比

    // 📷 Requested values; actual may differ.
    // 📷 请求的摄像头参数值；实际值可能不同
    private static final int REQ_CAMERA_WIDTH = 1280;       //# 📐 请求的摄像头宽度
    private static final int REQ_CAMERA_HEIGHT = 720;       //# 📐 请求的摄像头高度
    private static final int REQ_CAMERA_FPS = 30;           //# 🎬 请求的摄像头帧率

    /**
     * The holder for our SurfaceView.  The Surface can outlive the Activity (e.g. when
     * the screen is turned off and back on with the power button).
     *
     * This becomes non-null after the surfaceCreated() callback is called, and gets set
     * to null when surfaceDestroyed() is called.
     * 
     * 🖼️ SurfaceView的持有者
     * 💡 Surface可能比Activity寿命更长（例如屏幕关闭再打开时）
     * 💡 在surfaceCreated()回调后变为非空，surfaceDestroyed()时设为null
     */
    private static SurfaceHolder sSurfaceHolder;

    // 🧵 mRenderThread：处理渲染和控制摄像头的线程
    // 💡 在onResume()中启动，在onPause()中停止
    // Thread that handles rendering and controls the camera.  Started in onResume(),
    // stopped in onPause().
    private RenderThread mRenderThread;

    // 📬 mHandler：接收渲染线程消息的处理器
    // Receives messages from renderer thread.
    private MainHandler mHandler;

    // 🎚️ 用户控件
    // User controls.
    private SeekBar mZoomBar;     // 🔍 缩放拖动条
    private SeekBar mSizeBar;     // 📐 大小拖动条
    private SeekBar mRotateBar;   // 🔄 旋转拖动条

    // 📊 These values are passed to us by the camera/render thread, and displayed in the UI.
    //    We could also just peek at the values in the RenderThread object, but we'd need to
    //    synchronize access carefully.
    // 📊 这些值由摄像头/渲染线程传递给我们，并在UI中显示
    // 💡 也可以直接查看RenderThread对象中的值，但需要仔细同步访问
    private int mCameraPreviewWidth, mCameraPreviewHeight;  // 📐 摄像头预览宽高
    private float mCameraPreviewFps;                        // 🎬 摄像头预览帧率
    private int mRectWidth, mRectHeight;                    // 📐 矩形宽高
    private int mZoomWidth, mZoomHeight;                    // 🔍 缩放区域宽高
    private int mRotateDeg;                                 // 🔄 旋转角度


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
        setContentView(R.layout.activity_texture_from_camera);

        // 📬 mHandler：主线程消息处理器
        // 🔍 为什么创建：需要接收渲染线程的UI更新消息
        // 💡 作用：处理摄像头参数、矩形尺寸等UI更新
        // ⏰ 使用时机：在onResume中传递给渲染线程
        // 📬 创建主线程消息处理器
        mHandler = new MainHandler(this);

        // 🖼️ sv：SurfaceView控件实例
        // 🔍 为什么定义：需要获取SurfaceView来显示摄像头预览
        // 💡 作用：提供显示摄像头帧的Surface
        // ⏰ 使用时机：立即用于获取SurfaceHolder
        // 🖼️ 获取SurfaceView并注册回调
        SurfaceView sv = (SurfaceView) findViewById(R.id.cameraOnTexture_surfaceView);
        
        // 🖼️ sh：SurfaceHolder对象
        // 🔍 为什么定义：需要注册Surface生命周期回调
        // 💡 作用：管理Surface的创建、变化、销毁事件
        // ⏰ 使用时机：立即用于注册回调
        SurfaceHolder sh = sv.getHolder();
        
        // 📧 addCallback：注册Surface生命周期回调
        // 💡 作用：当Surface状态变化时收到通知
        sh.addCallback(this);

        // 🎚️ mZoomBar：缩放拖动条控件
        // 🔍 为什么定义：需要让用户调整纹理的缩放级别
        // 💡 作用：控制纹理采样区域的大小
        // ⏰ 使用时机：在onProgressChanged中读取值
        // 🎚️ 初始化拖动条控件
        mZoomBar = (SeekBar) findViewById(R.id.tfcZoom_seekbar);
        
        // 🎚️ mSizeBar：大小拖动条控件
        // 🔍 为什么定义：需要让用户调整纹理矩形的显示大小
        // 💡 作用：控制纹理矩形的缩放比例
        // ⏰ 使用时机：在onProgressChanged中读取值
        mSizeBar = (SeekBar) findViewById(R.id.tfcSize_seekbar);
        
        // 🎚️ mRotateBar：旋转拖动条控件
        // 🔍 为什么定义：需要让用户调整纹理的旋转角度
        // 💡 作用：控制纹理矩形的旋转
        // ⏰ 使用时机：在onProgressChanged中读取值
        mRotateBar = (SeekBar) findViewById(R.id.tfcRotate_seekbar);
        
        // 📊 setProgress：设置拖动条的初始值
        // 💡 作用：为每个拖动条设置默认位置
        // 📊 设置默认值
        mZoomBar.setProgress(DEFAULT_ZOOM_PERCENT);
        mSizeBar.setProgress(DEFAULT_SIZE_PERCENT);
        mRotateBar.setProgress(DEFAULT_ROTATE_PERCENT);
        
        // 📧 setOnSeekBarChangeListener：设置拖动条变化监听器
        // 💡 作用：当用户拖动时触发onProgressChanged回调
        // 🎧 设置拖动条变化监听器
        mZoomBar.setOnSeekBarChangeListener(this);
        mSizeBar.setOnSeekBarChangeListener(this);
        mRotateBar.setOnSeekBarChangeListener(this);

        // 🔄 updateControls：更新UI控件显示
        // 💡 作用：刷新摄像头参数、矩形尺寸等文本显示
        // ⏰ 使用时机：在初始化完成后调用
        // 🔄 更新控件显示
        updateControls();
    }

    @Override
    protected void onResume() {
        // 📝 日志输出：记录onResume开始
        Log.d(TAG, "onResume BEGIN");
        
        // 📱 super.onResume：调用父类的onResume方法
        // 🔍 为什么调用：必须首先调用父类的生命周期方法
        // 💡 作用：完成Activity的标准恢复流程
        // ⏰ 使用时机：在任何自定义恢复逻辑之前
        super.onResume();

        // 🔐 hasCameraPermission：检查摄像头权限
        // 🔍 为什么检查：需要确保有权限才能打开摄像头
        // 💡 作用：避免在无权限时尝试打开摄像头导致异常
        // ⏰ 使用时机：在创建渲染线程之前检查
        // 🔐 检查摄像头权限
        if (!PermissionHelper.hasCameraPermission(this)) {
            // 🔐 requestCameraPermission：请求摄像头权限
            // 💡 参数false：不显示权限说明对话框
            // 💡 作用：向用户请求摄像头使用权限
            PermissionHelper.requestCameraPermission(this, false);
            return;
        }
        
        // 🧵 mRenderThread：渲染线程实例
        // 🔍 为什么创建：需要在独立线程中处理摄像头和OpenGL渲染
        // 💡 作用：管理摄像头预览和纹理渲染
        // ⏰ 使用时机：在Activity恢复时创建
        // 🧵 创建并启动渲染线程
        mRenderThread = new RenderThread(mHandler);
        
        // 🏷️ setName：设置线程名称
        // 💡 作用：便于调试时识别线程
        mRenderThread.setName("TexFromCam Render");
        
        // ▶️ start：启动渲染线程
        // 💡 作用：开始执行渲染线程的run方法
        mRenderThread.start();
        
        // ⏳ waitUntilReady：等待渲染线程就绪
        // 🔍 为什么调用：需要确保渲染线程的Handler已创建
        // 💡 作用：阻塞UI线程直到渲染线程准备就绪
        // ⏰ 使用时机：在线程启动后立即调用
        mRenderThread.waitUntilReady();

        // 📬 rh：渲染线程的消息处理器
        // 🔍 为什么获取：需要向渲染线程发送控制消息
        // 💡 作用：发送缩放、大小、旋转等控制消息
        // ⏰ 使用时机：在线程就绪后获取
        // 📤 发送当前拖动条值到渲染线程
        RenderHandler rh = mRenderThread.getHandler();
        
        // 📤 sendZoomValue：发送缩放值
        // 💡 参数：从拖动条获取的进度值（0-100）
        // 💡 作用：设置纹理的缩放级别
        rh.sendZoomValue(mZoomBar.getProgress());
        
        // 📤 sendSizeValue：发送大小值
        // 💡 参数：从拖动条获取的进度值（0-100）
        // 💡 作用：设置纹理矩形的显示大小
        rh.sendSizeValue(mSizeBar.getProgress());
        
        // 📤 sendRotateValue：发送旋转值
        // 💡 参数：从拖动条获取的进度值（0-100）
        // 💡 作用：设置纹理矩形的旋转角度
        rh.sendRotateValue(mRotateBar.getProgress());

        // 🖼️ sSurfaceHolder：静态SurfaceHolder引用
        // 🔍 为什么检查：Surface可能在Activity暂停期间仍然存在
        // 💡 作用：恢复之前的Surface显示
        // ⏰ 使用时机：在渲染线程就绪后检查
        // 🖼️ 如果有之前的Surface，发送给渲染线程
        if (sSurfaceHolder != null) {
            // 📝 日志输出：记录正在发送之前的Surface
            Log.d(TAG, "Sending previous surface");
            
            // 📤 sendSurfaceAvailable：发送Surface可用消息
            // 💡 参数false：表示这不是新创建的Surface
            // 💡 作用：通知渲染线程使用现有的Surface
            rh.sendSurfaceAvailable(sSurfaceHolder, false);
        } else {
            // 📝 日志输出：记录没有之前的Surface
            Log.d(TAG, "No previous surface");
        }
        
        // 📝 日志输出：记录onResume结束
        Log.d(TAG, "onResume END");
    }

    @Override
    protected void onPause() {
        // 📝 日志输出：记录onPause开始
        Log.d(TAG, "onPause BEGIN");
        
        // 📱 super.onPause：调用父类的onPause方法
        // 🔍 为什么调用：必须首先调用父类的生命周期方法
        // 💡 作用：完成Activity的标准暂停流程
        // ⏰ 使用时机：在任何自定义暂停逻辑之前
        super.onPause();

        // 🧵 mRenderThread：渲染线程实例
        // 🔍 为什么检查：可能在某些情况下渲染线程未创建
        // 💡 作用：避免空指针异常
        // ⏰ 使用时机：在操作渲染线程之前检查
        // 🔍 检查渲染线程是否存在
        if (mRenderThread == null) {
            return;
        }
        
        // 📬 rh：渲染线程的消息处理器
        // 🔍 为什么获取：需要向渲染线程发送关闭消息
        // 💡 作用：通知渲染线程退出Looper循环
        // ⏰ 使用时机：在暂停时发送
        // 📤 发送关闭消息
        RenderHandler rh = mRenderThread.getHandler();
        
        // 📤 sendShutdown：发送关闭消息
        // 💡 作用：通知渲染线程退出消息循环
        rh.sendShutdown();
        
        try {
            // ⏳ join：等待渲染线程结束
            // 🔍 为什么调用：需要确保渲染线程完全退出
            // 💡 作用：阻塞UI线程直到渲染线程结束
            // ⏰ 使用时机：在发送关闭消息后
            // ⏳ 等待渲染线程结束
            mRenderThread.join();
        } catch (InterruptedException ie) {
            // not expected
            // ⚠️ InterruptedException：等待被中断时抛出异常
            // 💡 作用：处理意外的中断情况
            throw new RuntimeException("join was interrupted", ie);
        }
        
        // 🗑️ mRenderThread：渲染线程引用
        // 🔍 为什么设为null：释放引用，允许垃圾回收
        // 💡 作用：避免内存泄漏
        // ⏰ 使用时机：在线程结束后
        mRenderThread = null;
        
        // 📝 日志输出：记录onPause结束
        Log.d(TAG, "onPause END");
    }

    /**
     * 🖼️ Surface创建时调用
     * 💡 保存SurfaceHolder并通知渲染线程
     */
    @Override   // SurfaceHolder.Callback
    public void surfaceCreated(SurfaceHolder holder) {
        // 📝 日志输出：记录Surface创建事件和SurfaceHolder信息
        Log.d(TAG, "surfaceCreated holder=" + holder + " (static=" + sSurfaceHolder + ")");
        
        // ⚠️ sSurfaceHolder：静态SurfaceHolder引用
        // 🔍 为什么检查：确保不会重复设置SurfaceHolder
        // 💡 作用：防止状态混乱
        // ⏰ 使用时机：在设置之前检查
        // ⚠️ 检查是否已有SurfaceHolder
        if (sSurfaceHolder != null) {
            throw new RuntimeException("sSurfaceHolder is already set");
        }

        // 🖼️ sSurfaceHolder：静态SurfaceHolder引用
        // 🔍 为什么保存：Surface可能在Activity暂停期间仍然存在
        // 💡 作用：跟踪Surface状态，用于恢复
        // ⏰ 使用时机：在Surface创建时保存
        // 🖼️ 保存SurfaceHolder到静态变量
        sSurfaceHolder = holder;

        // 🧵 mRenderThread：渲染线程实例
        // 🔍 为什么检查：可能在某些情况下渲染线程未创建
        // 💡 作用：避免空指针异常
        // ⏰ 使用时机：在通知渲染线程之前检查
        if (mRenderThread != null) {
            // Normal case -- render thread is running, tell it about the new surface.
            // 📬 rh：渲染线程的消息处理器
            // 🔍 为什么获取：需要向渲染线程发送Surface可用消息
            // 💡 作用：通知渲染线程Surface已创建
            // ⏰ 使用时机：在Surface创建后立即发送
            // 📤 正常情况：渲染线程正在运行，通知它有新的Surface
            RenderHandler rh = mRenderThread.getHandler();
            
            // 📤 sendSurfaceAvailable：发送Surface可用消息
            // 💡 参数true：表示这是新创建的Surface
            // 💡 作用：通知渲染线程创建窗口Surface并初始化纹理
            rh.sendSurfaceAvailable(holder, true);
        } else {
            // Sometimes see this on 4.4.x N5: power off, power on, unlock, with device in
            // landscape and a lock screen that requires portrait.  The surface-created
            // message is showing up after onPause().
            //
            // Chances are good that the surface will be destroyed before the activity is
            // unpaused, but we track it anyway.  If the activity is un-paused and we start
            // the RenderThread, the SurfaceHolder will be passed in right after the thread
            // is created.
            // 📝 特殊情况：在4.4.x设备上可能出现surfaceCreated在onPause()之后调用
            // 💡 Surface可能会在Activity恢复前被销毁，但我们仍然跟踪它
            Log.d(TAG, "render thread not running");
        }
    }

    /**
     * 🖼️ Surface尺寸变化时调用
     * 💡 通知渲染线程新的尺寸
     */
    @Override   // SurfaceHolder.Callback
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // 📝 日志输出：记录Surface尺寸变化信息
        Log.d(TAG, "surfaceChanged fmt=" + format + " size=" + width + "x" + height +
                " holder=" + holder);

        // 🧵 mRenderThread：渲染线程实例
        // 🔍 为什么检查：可能在某些情况下渲染线程未创建
        // 💡 作用：避免空指针异常
        // ⏰ 使用时机：在通知渲染线程之前检查
        if (mRenderThread != null) {
            // 📬 rh：渲染线程的消息处理器
            // 🔍 为什么获取：需要向渲染线程发送Surface变化消息
            // 💡 作用：通知渲染线程更新窗口尺寸
            // ⏰ 使用时机：在Surface尺寸变化后立即发送
            // 📤 发送Surface变化消息
            RenderHandler rh = mRenderThread.getHandler();
            
            // 📤 sendSurfaceChanged：发送Surface变化消息
            // 💡 参数：格式、宽度、高度
            // 💡 作用：通知渲染线程更新投影矩阵和视口
            rh.sendSurfaceChanged(format, width, height);
        } else {
            // 📝 渲染线程未运行，忽略此消息
            Log.d(TAG, "Ignoring surfaceChanged");
            return;
        }
    }

    /**
     * 🖼️ Surface销毁时调用
     * 💡 通知渲染线程并清除静态SurfaceHolder引用
     */
    @Override   // SurfaceHolder.Callback
    public void surfaceDestroyed(SurfaceHolder holder) {
        // In theory we should tell the RenderThread that the surface has been destroyed.
        // 🧵 mRenderThread：渲染线程实例
        // 🔍 为什么检查：可能在某些情况下渲染线程未创建
        // 💡 作用：避免空指针异常
        // ⏰ 使用时机：在通知渲染线程之前检查
        // 📤 通知渲染线程Surface已销毁
        if (mRenderThread != null) {
            // 📬 rh：渲染线程的消息处理器
            // 🔍 为什么获取：需要向渲染线程发送Surface销毁消息
            // 💡 作用：通知渲染线程释放GL资源
            // ⏰ 使用时机：在Surface销毁后立即发送
            RenderHandler rh = mRenderThread.getHandler();
            
            // 📤 sendSurfaceDestroyed：发送Surface销毁消息
            // 💡 作用：通知渲染线程释放窗口Surface和纹理资源
            rh.sendSurfaceDestroyed();
        }
        
        // 📝 日志输出：记录Surface销毁事件
        Log.d(TAG, "surfaceDestroyed holder=" + holder);
        
        // 🗑️ sSurfaceHolder：静态SurfaceHolder引用
        // 🔍 为什么设为null：Surface已销毁，不再需要跟踪
        // 💡 作用：释放引用，避免使用已销毁的Surface
        // ⏰ 使用时机：在Surface销毁后
        // 🗑️ 清除静态引用
        sSurfaceHolder = null;
    }

    /**
     * 🎚️ 拖动条进度变化时调用
     * 💡 将新的进度值发送到渲染线程
     *
     * @param seekBar   发生变化的拖动条控件实例，用于判断是哪个拖动条触发了变化 🔍
     * @param progress  新的进度值，范围0-100，表示百分比 📊
     * @param fromUser  是否由用户手动拖动触发，true表示用户操作，false表示程序设置 ⚙️
     */
    @Override   // SeekBar.OnSeekBarChangeListener
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        // 🧵 mRenderThread：渲染线程引用，负责处理摄像头和OpenGL渲染
        // ⚠️ 为什么检查：在渲染线程启动前或应用切换时可能收到回调，需要防止空指针异常
        // 💡 使用时机：每次进度变化时都需要检查，确保渲染线程已就绪
        if (mRenderThread == null) {
            // Could happen if we programmatically update the values after setting a listener
            // but before starting the thread.  Also, easy to cause this by scrubbing the seek
            // bar with one finger then tapping "recents" with another.
            // 📝 可能在渲染线程启动前收到此回调，或在拖动时切换应用
            Log.w(TAG, "Ignoring onProgressChanged received w/o RT running");
            return;
        }

        // 📬 rh：渲染线程的消息处理器，用于向渲染线程发送各种控制消息
        // 🔍 为什么定义：需要通过Handler机制与渲染线程通信，实现跨线程消息传递
        // 💡 作用：发送缩放、大小、旋转等控制消息给渲染线程
        // ⏰ 使用时机：获取到后立即用于发送对应的进度值消息
        RenderHandler rh = mRenderThread.getHandler();

        // "progress" ranges from 0 to 100
        // 📊 progress：用户拖动的进度值，范围0-100
        // 🔍 为什么判断：需要根据不同的拖动条发送不同的消息类型
        if (seekBar == mZoomBar) {
            //Log.v(TAG, "zoom: " + progress);
            // 🔍 缩放拖动条变化 → 发送缩放值消息
            // 📤 sendZoomValue：将缩放百分比发送到渲染线程，用于计算纹理采样区域
            rh.sendZoomValue(progress);
        } else if (seekBar == mSizeBar) {
            //Log.v(TAG, "size: " + progress);
            // 📐 大小拖动条变化 → 发送大小值消息
            // 📤 sendSizeValue：将大小百分比发送到渲染线程，用于计算矩形显示尺寸
            rh.sendSizeValue(progress);
        } else if (seekBar == mRotateBar) {
            //Log.v(TAG, "rotate: " + progress);
            // 🔄 旋转拖动条变化 → 发送旋转值消息
            // 📤 sendRotateValue：将旋转百分比发送到渲染线程，用于计算旋转角度
            rh.sendRotateValue(progress);
        } else {
            // ⚠️ 未知拖动条，抛出异常（防御性编程）
            throw new RuntimeException("unknown seek bar");
        }

        // If we're getting preview frames quickly enough we don't really need this, but
        // we don't want to have chunky-looking resize movement if the camera is slow.
        // OTOH, if we get the updates too quickly (60fps camera?), this could jam us
        // up and cause us to run behind.  So use with caution.
        // 📤 sendRedraw：强制渲染线程立即重绘，避免摄像头响应慢时出现卡顿
        // ⚠️ 警告：如果更新太快（如60fps摄像头）可能导致性能问题，需要谨慎使用
        rh.sendRedraw();
    }

    @Override   // SeekBar.OnSeekBarChangeListener
    public void onStartTrackingTouch(SeekBar seekBar) {}
    @Override   // SeekBar.OnSeekBarChangeListener
    public void onStopTrackingTouch(SeekBar seekBar) {}
    @Override

    /**
     * 👆 处理未被控件捕获的触摸事件
     * 💡 将触摸位置发送到渲染线程，用于移动纹理位置
     */
    public boolean onTouchEvent(MotionEvent e) {
        // 📍 x：触摸点的X坐标（像素）
        // 🔍 为什么定义：需要知道用户触摸的水平位置
        // 💡 作用：传递给渲染线程设置纹理位置
        // ⏰ 使用时机：在触摸事件处理时立即获取
        float x = e.getX();
        
        // 📍 y：触摸点的Y坐标（像素）
        // 🔍 为什么定义：需要知道用户触摸的垂直位置
        // 💡 作用：传递给渲染线程设置纹理位置
        // ⏰ 使用时机：在触摸事件处理时立即获取
        float y = e.getY();

        // 🎯 e.getAction()：触摸事件类型
        // 🔍 为什么判断：只处理移动和按下事件
        // 💡 作用：决定是否更新纹理位置
        // ⏰ 使用时机：在switch语句中判断
        switch (e.getAction()) {
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_DOWN:
                //Log.v(TAG, "onTouchEvent act=" + e.getAction() + " x=" + x + " y=" + y);
                // 🧵 mRenderThread：渲染线程实例
                // 🔍 为什么检查：可能在某些情况下渲染线程未创建
                // 💡 作用：避免空指针异常
                // ⏰ 使用时机：在通知渲染线程之前检查
                if (mRenderThread != null) {
                    // 📬 rh：渲染线程的消息处理器
                    // 🔍 为什么获取：需要向渲染线程发送触摸位置
                    // 💡 作用：通知渲染线程更新纹理位置
                    // ⏰ 使用时机：在触摸事件发生后立即发送
                    // 📤 发送触摸位置到渲染线程
                    RenderHandler rh = mRenderThread.getHandler();
                    
                    // 📤 sendPosition：发送触摸位置
                    // 💡 参数：触摸点的X和Y坐标
                    // 💡 作用：设置纹理矩形的显示位置
                    rh.sendPosition((int) x, (int) y);

                    // Forcing a redraw can cause sluggish-looking behavior if the touch
                    // events arrive quickly.
                    //rh.sendRedraw();
                }
                break;
            default:
                break;
        }

        // 📊 返回true：表示事件已处理
        // 💡 作用：阻止事件继续传递
        return true;
    }

    /**
     * 🔄 更新UI控件显示
     * 💡 显示摄像头参数、矩形尺寸和缩放区域
     */
    private void updateControls() {
        // 📷 str：格式化后的摄像头参数字符串
        // 🔍 为什么定义：需要将摄像头参数格式化为可读文本
        // 💡 作用：显示摄像头的分辨率和帧率
        // ⏰ 使用时机：立即设置到TextView
        // 📷 显示摄像头参数
        String str = getString(R.string.tfcCameraParams, mCameraPreviewWidth,
                mCameraPreviewHeight, mCameraPreviewFps);
        
        // 📝 tv：显示摄像头参数的文本控件
        // 🔍 为什么定义：需要获取控件引用才能设置文本
        // 💡 作用：显示摄像头参数信息
        // ⏰ 使用时机：立即设置文本
        TextView tv = (TextView) findViewById(R.id.tfcCameraParams_text);
        tv.setText(str);

        // 📐 str：格式化后的矩形尺寸字符串
        // 🔍 为什么更新：需要显示纹理矩形的当前尺寸
        // 💡 作用：显示纹理矩形的宽度和高度
        // ⏰ 使用时机：立即设置到TextView
        // 📐 显示矩形尺寸
        str = getString(R.string.tfcRectSize, mRectWidth, mRectHeight);
        
        // 📝 tv：显示矩形尺寸的文本控件
        tv = (TextView) findViewById(R.id.tfcRectSize_text);
        tv.setText(str);

        // 🔍 str：格式化后的缩放区域字符串
        // 🔍 为什么更新：需要显示纹理采样区域的当前尺寸
        // 💡 作用：显示缩放区域的宽度和高度
        // ⏰ 使用时机：立即设置到TextView
        // 🔍 显示缩放区域
        str = getString(R.string.tfcZoomArea, mZoomWidth, mZoomHeight);
        
        // 📝 tv：显示缩放区域的文本控件
        tv = (TextView) findViewById(R.id.tfcZoomArea_text);
        tv.setText(str);
    }

    /**
     * 📬 主线程自定义消息处理器
     * 💡 接收渲染线程的UI相关更新消息
     * 💡 使用弱引用避免内存泄漏
     */
    private static class MainHandler extends Handler {
        // 📨 消息类型常量
        private static final int MSG_SEND_CAMERA_PARAMS0 = 0;  // 📷 摄像头参数（宽高）
        private static final int MSG_SEND_CAMERA_PARAMS1 = 1;  // 📷 摄像头参数（帧率）
        private static final int MSG_SEND_RECT_SIZE = 2;       // 📐 矩形尺寸
        private static final int MSG_SEND_ZOOM_AREA = 3;       // 🔍 缩放区域
        private static final int MSG_SEND_ROTATE_DEG = 4;      // 🔄 旋转角度

        // 🔗 对Activity的弱引用，避免内存泄漏
        private WeakReference<TextureFromCameraActivity> mWeakActivity;

        public MainHandler(TextureFromCameraActivity activity) {
            mWeakActivity = new WeakReference<TextureFromCameraActivity>(activity);
        }

        /**
         * Sends the updated camera parameters to the main thread.
         * <p>
         * Call from render thread.
         * 
         * 📤 发送更新的摄像头参数到主线程（从渲染线程调用）
         * 
         * @param width 摄像头预览宽度
         * @param height 摄像头预览高度
         * @param fps 摄像头帧率
         */
        public void sendCameraParams(int width, int height, float fps) {
            // The right way to do this is to bundle them up into an object.  The lazy
            // way is to send two messages.
            // 📝 正确做法是打包成一个对象，懒惰做法是发送两条消息
            sendMessage(obtainMessage(MSG_SEND_CAMERA_PARAMS0, width, height));
            sendMessage(obtainMessage(MSG_SEND_CAMERA_PARAMS1, (int) (fps * 1000), 0));
        }

        /**
         * Sends the updated rect size to the main thread.
         * <p>
         * Call from render thread.
         * 
         * 📤 发送更新的矩形尺寸到主线程（从渲染线程调用）
         */
        public void sendRectSize(int width, int height) {
            sendMessage(obtainMessage(MSG_SEND_RECT_SIZE, width, height));
        }

        /**
         * Sends the updated zoom area to the main thread.
         * <p>
         * Call from render thread.
         * 
         * 📤 发送更新的缩放区域到主线程（从渲染线程调用）
         */
        public void sendZoomArea(int width, int height) {
            sendMessage(obtainMessage(MSG_SEND_ZOOM_AREA, width, height));
        }

        /**
         * Sends the updated rotation degree to the main thread.
         * <p>
         * Call from render thread.
         * 
         * 📤 发送更新的旋转角度到主线程（从渲染线程调用）
         */
        public void sendRotateDeg(int rot) {
            sendMessage(obtainMessage(MSG_SEND_ROTATE_DEG, rot, 0));
        }

        /**
         * 📬 处理消息
         * 💡 更新Activity中的UI数据并刷新显示
         *
         * @param msg 从渲染线程发送过来的消息对象，包含更新的参数数据 📨
         */
        @Override
        public void handleMessage(Message msg) {
            // 📱 activity：从弱引用获取Activity实例，防止内存泄漏
            // 🔍 为什么使用弱引用：Handler是静态内部类，如果持有Activity强引用会导致内存泄漏
            // 💡 作用：访问Activity的成员变量和方法来更新UI
            // ⏰ 使用时机：每次收到消息时都需要获取，可能为null（Activity已被回收）
            TextureFromCameraActivity activity = mWeakActivity.get();

            // ⚠️ null检查：如果Activity已被垃圾回收，忽略该消息
            if (activity == null) {
                Log.d(TAG, "Got message for dead activity");
                return;
            }

            // 🎯 msg.what：消息类型标识，决定如何处理该消息
            // 🔍 为什么需要：不同消息携带不同的更新数据，需要分别处理
            switch (msg.what) {
                case MSG_SEND_CAMERA_PARAMS0: {
                    // 📷 更新摄像头预览尺寸
                    // 📐 msg.arg1：摄像头预览宽度（像素），存储到Activity的mCameraPreviewWidth
                    // 📐 msg.arg2：摄像头预览高度（像素），存储到Activity的mCameraPreviewHeight
                    // 💡 作用：保存摄像头参数，用于后续计算纹理显示比例
                    activity.mCameraPreviewWidth = msg.arg1;
                    activity.mCameraPreviewHeight = msg.arg2;
                    break;
                }
                case MSG_SEND_CAMERA_PARAMS1: {
                    // 📷 更新摄像头帧率并刷新UI
                    // 🎬 msg.arg1：帧率×1000的整数值，需要除以1000.0f还原为浮点数
                    // 💡 作用：保存帧率用于UI显示，让用户知道摄像头当前运行状态
                    activity.mCameraPreviewFps = msg.arg1 / 1000.0f;
                    // 🔄 updateControls：刷新UI界面上的所有参数显示
                    activity.updateControls();
                    break;
                }
                case MSG_SEND_RECT_SIZE: {
                    // 📐 更新矩形尺寸并刷新UI
                    // 📐 msg.arg1：矩形宽度（像素），存储到mRectWidth
                    // 📐 msg.arg2：矩形高度（像素），存储到mRectHeight
                    // 💡 作用：显示纹理矩形的实际渲染尺寸
                    activity.mRectWidth = msg.arg1;
                    activity.mRectHeight = msg.arg2;
                    // 🔄 updateControls：刷新UI显示
                    activity.updateControls();
                    break;
                }
                case MSG_SEND_ZOOM_AREA: {
                    // 🔍 更新缩放区域并刷新UI
                    // 🔍 msg.arg1：缩放区域宽度（像素），存储到mZoomWidth
                    // 🔍 msg.arg2：缩放区域高度（像素），存储到mZoomHeight
                    // 💡 作用：显示纹理采样区域的实际大小
                    activity.mZoomWidth = msg.arg1;
                    activity.mZoomHeight = msg.arg2;
                    // 🔄 updateControls：刷新UI显示
                    activity.updateControls();
                    break;
                }
                case MSG_SEND_ROTATE_DEG: {
                    // 🔄 更新旋转角度并刷新UI
                    // 🔄 msg.arg1：旋转角度（0-360度），存储到mRotateDeg
                    // 💡 作用：显示纹理当前的旋转角度
                    activity.mRotateDeg = msg.arg1;
                    // 🔄 updateControls：刷新UI显示
                    activity.updateControls();
                    break;
                }
                default:
                    // ⚠️ 未知消息类型，抛出异常（防御性编程）
                    throw new RuntimeException("Unknown message " + msg.what);
            }
        }
    }


    /**
     * 🧵 处理所有渲染和摄像头操作的线程
     * 💡 运行自己的Looper循环，接收消息处理各种操作
     */
    private static class RenderThread extends Thread implements
            SurfaceTexture.OnFrameAvailableListener {
        // 📬 渲染线程的Handler，必须声明为volatile确保UI线程看到完整对象
        // Object must be created on render thread to get correct Looper, but is used from
        // UI thread, so we need to declare it volatile to ensure the UI thread sees a fully
        // constructed object.
        private volatile RenderHandler mHandler;

        // 🔒 用于等待线程启动的锁
        // Used to wait for the thread to start.
        private Object mStartLock = new Object();
        private boolean mReady = false;

        // 📬 主线程Handler，用于发送消息回Activity
        private MainHandler mMainHandler;

        // 📷 摄像头相关
        private Camera mCamera;
        private int mCameraPreviewWidth, mCameraPreviewHeight;

        // 🎮 EGL相关
        private EglCore mEglCore;
        private WindowSurface mWindowSurface;
        private int mWindowSurfaceWidth;
        private int mWindowSurfaceHeight;

        // Receives the output from the camera preview.
        // 🖼️ 接收摄像头预览输出的SurfaceTexture
        private SurfaceTexture mCameraTexture;

        // Orthographic projection matrix.
        // 📐 正交投影矩阵（16个float）
        private float[] mDisplayProjectionMatrix = new float[16];

        // 🎨 纹理程序和可绘制对象
        private Texture2dProgram mTexProgram;
        private final ScaledDrawable2d mRectDrawable =
                new ScaledDrawable2d(Drawable2d.Prefab.RECTANGLE);
        private final Sprite2d mRect = new Sprite2d(mRectDrawable);

        // 🎚️ 用户控件的百分比值
        private int mZoomPercent = DEFAULT_ZOOM_PERCENT;    // 🔍 缩放百分比
        private int mSizePercent = DEFAULT_SIZE_PERCENT;    // 📐 大小百分比
        private int mRotatePercent = DEFAULT_ROTATE_PERCENT;  // 🔄 旋转百分比
        private float mPosX, mPosY;                         // 📍 位置坐标


        /**
         * Constructor.  Pass in the MainHandler, which allows us to send stuff back to the
         * Activity.
         * 
         * 🏗️ 构造函数
         * @param handler 主线程Handler，用于发送消息回Activity
         */
        public RenderThread(MainHandler handler) {
            mMainHandler = handler;
        }

        /**
         * Thread entry point.
         * 
         * 🧵 线程入口点
         * 💡 准备Looper，创建Handler，初始化EGL和摄像头
         */
        @Override
        public void run() {
            // 🔄 Looper.prepare：准备消息循环
            // 🔍 为什么调用：需要为当前线程创建Looper
            // 💡 作用：允许线程接收和处理消息
            // ⏰ 使用时机：在线程启动时立即调用
            Looper.prepare();

            // We need to create the Handler before reporting ready.
            // 📬 mHandler：渲染线程的消息处理器
            // 🔍 为什么创建：需要接收UI线程的控制消息
            // 💡 作用：处理缩放、大小、旋转等控制消息
            // ⏰ 使用时机：在Looper准备后创建
            // 📬 创建Handler（必须在报告就绪前创建）
            mHandler = new RenderHandler(this);
            
            // 🔒 mStartLock：同步锁对象
            // 🔍 为什么同步：需要确保UI线程等待渲染线程就绪
            // 💡 作用：协调UI线程和渲染线程的启动顺序
            // ⏰ 使用时机：在Handler创建后通知
            synchronized (mStartLock) {
                // 📊 mReady：渲染线程就绪标志
                // 🔍 为什么设为true：表示Handler已创建，可以接收消息
                // 💡 作用：通知UI线程可以继续
                mReady = true;
                
                // 📢 notify：通知等待的UI线程
                // 💡 作用：唤醒在waitUntilReady中等待的UI线程
                mStartLock.notify();    // signal waitUntilReady()
            }

            // Prepare EGL and open the camera before we start handling messages.
            // 🎮 mEglCore：EGL核心对象
            // 🔍 为什么创建：需要管理OpenGL ES的上下文和表面
            // 💡 作用：提供OpenGL渲染的基础设施
            // ⏰ 使用时机：在消息循环开始前创建
            // 🎮 准备EGL并打开摄像头
            mEglCore = new EglCore(null, 0);
            
            // 📷 openCamera：打开摄像头
            // 💡 参数：期望的宽度、高度、帧率
            // 💡 作用：初始化摄像头硬件，设置预览参数
            // ⏰ 使用时机：在EGL初始化后调用
            openCamera(REQ_CAMERA_WIDTH, REQ_CAMERA_HEIGHT, REQ_CAMERA_FPS);

            // 🔄 Looper.loop：开始消息循环
            // 🔍 为什么调用：开始处理消息队列中的消息
            // 💡 作用：线程进入阻塞状态，等待消息
            // ⏰ 使用时机：在初始化完成后开始
            // 🔄 开始消息循环
            Looper.loop();

            // 🧹 循环结束后清理资源
            // 📝 日志输出：记录Looper退出
            Log.d(TAG, "looper quit");
            
            // 📷 releaseCamera：释放摄像头资源
            // 💡 作用：停止预览并释放摄像头硬件
            releaseCamera();
            
            // 🎮 releaseGl：释放OpenGL资源
            // 💡 作用：释放纹理、着色器等GPU资源
            releaseGl();
            
            // 🎮 mEglCore.release：释放EGL核心对象
            // 💡 作用：释放EGL上下文和显示连接
            mEglCore.release();

            // 🔒 同步块：更新就绪状态
            synchronized (mStartLock) {
                // 📊 mReady：设为false表示线程已退出
                mReady = false;
            }
        }

        /**
         * Waits until the render thread is ready to receive messages.
         * <p>
         * Call from the UI thread.
         * 
         * ⏳ 等待渲染线程就绪（在UI线程调用）
         */
        public void waitUntilReady() {
            // 🔒 mStartLock：同步锁对象
            // 🔍 为什么同步：需要等待渲染线程通知就绪
            // 💡 作用：阻塞UI线程直到渲染线程准备就绪
            // ⏰ 使用时机：在渲染线程启动后立即调用
            synchronized (mStartLock) {
                // 📊 mReady：渲染线程就绪标志
                // 🔍 为什么循环检查：防止虚假唤醒
                // 💡 作用：确保渲染线程确实已就绪
                // ⏰ 使用时机：在等待前检查
                while (!mReady) {
                    try {
                        // ⏳ wait：等待渲染线程通知
                        // 💡 作用：释放锁并进入等待状态
                        // ⏰ 使用时机：在mReady为false时等待
                        mStartLock.wait();
                    } catch (InterruptedException ie) { /* not expected */ }
                }
            }
        }

        /**
         * Shuts everything down.
         * 
         * 🛑 关闭所有资源并退出Looper循环
         */
        private void shutdown() {
            // 📝 日志输出：记录正在关闭
            Log.d(TAG, "shutdown");
            
            // 🔄 Looper.myLooper().quit()：退出消息循环
            // 🔍 为什么调用：需要停止渲染线程的消息处理
            // 💡 作用：终止Looper.loop()的阻塞，使线程继续执行
            // ⏰ 使用时机：在UI线程发送关闭消息后
            Looper.myLooper().quit();
        }

        /**
         * Returns the render thread's Handler.  This may be called from any thread.
         * 
         * 📬 获取渲染线程的Handler（可从任意线程调用）
         */
        public RenderHandler getHandler() {
            return mHandler;
        }

        /**
         * Handles the surface-created callback from SurfaceView.  Prepares GLES and the Surface.
         * 
         * 🖼️ 处理SurfaceView的surface-created回调，准备GLES和Surface
         */
        private void surfaceAvailable(SurfaceHolder holder, boolean newSurface) {
            // 🖼️ surface：从SurfaceHolder获取的Surface对象
            // 🔍 为什么获取：需要创建窗口Surface
            // 💡 作用：提供渲染的显示表面
            // ⏰ 使用时机：立即用于创建WindowSurface
            Surface surface = holder.getSurface();
            
            // 🎮 mWindowSurface：窗口Surface对象
            // 🔍 为什么创建：需要将OpenGL渲染结果显示到屏幕
            // 💡 作用：管理EGL表面和窗口的绑定
            // ⏰ 使用时机：在Surface可用后立即创建
            // 🎮 创建窗口Surface并设为当前上下文
            mWindowSurface = new WindowSurface(mEglCore, surface, false);
            
            // 🎮 makeCurrent：设置当前EGL上下文
            // 💡 作用：后续的OpenGL调用将使用此上下文
            mWindowSurface.makeCurrent();

            // Create and configure the SurfaceTexture, which will receive frames from the
            // camera.  We set the textured rect's program to render from it.
            // 🎨 mTexProgram：纹理着色器程序
            // 🔍 为什么创建：需要渲染外部纹理（摄像头帧）
            // 💡 作用：管理纹理采样和着色器
            // ⏰ 使用时机：在创建SurfaceTexture之前
            // 🖼️ 创建并配置SurfaceTexture，用于接收摄像头帧
            mTexProgram = new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_EXT);
            
            // 🆔 textureId：OpenGL纹理对象ID
            // 🔍 为什么创建：需要纹理对象来接收摄像头帧
            // 💡 作用：标识GPU中的纹理资源
            // ⏰ 使用时机：立即用于创建SurfaceTexture
            int textureId = mTexProgram.createTextureObject();
            
            // 🎬 mCameraTexture：摄像头预览纹理
            // 🔍 为什么创建：需要接收摄像头输出的帧数据
            // 💡 作用：将摄像头帧绑定到OpenGL纹理
            // ⏰ 使用时机：在纹理ID创建后立即创建
            mCameraTexture = new SurfaceTexture(textureId);
            
            // 🖼️ setTexture：设置纹理ID到矩形对象
            // 💡 作用：告诉矩形使用哪个纹理进行渲染
            mRect.setTexture(textureId);

            // 📊 newSurface：是否是新创建的Surface
            // 🔍 为什么判断：新Surface会收到surfaceChanged()消息，旧Surface不会
            // 💡 作用：决定是否需要立即完成Surface设置
            // ⏰ 使用时机：在创建纹理后判断
            if (!newSurface) {
                // This Surface was established on a previous run, so no surfaceChanged()
                // message is forthcoming.  Finish the surface setup now.
                //
                // We could also just call this unconditionally, and perhaps do an unnecessary
                // bit of reallocating if a surface-changed message arrives.
                // 📝 这个Surface是之前创建的，不会有surfaceChanged()消息
                // 💡 直接完成Surface设置
                // 📐 mWindowSurfaceWidth/mWindowSurfaceHeight：窗口Surface的尺寸
                // 🔍 为什么获取：需要知道渲染区域的大小
                // 💡 作用：设置视口和投影矩阵
                // ⏰ 使用时机：在finishSurfaceSetup之前
                mWindowSurfaceWidth = mWindowSurface.getWidth();
                mWindowSurfaceHeight = mWindowSurface.getHeight();
                
                // 🔧 finishSurfaceSetup：完成Surface设置
                // 💡 作用：设置视口、投影矩阵、启动摄像头预览
                finishSurfaceSetup();
            }

            // 🎧 setOnFrameAvailableListener：设置帧可用监听器
            // 💡 作用：当有新帧可用时收到通知
            // 🎧 设置帧可用监听器
            mCameraTexture.setOnFrameAvailableListener(this);
        }

        /**
         * Releases most of the GL resources we currently hold (anything allocated by
         * surfaceAvailable()).
         * <p>
         * Does not release EglCore.
         * 
         * 🗑️ 释放大部分GL资源（不包括EglCore）
         */
        private void releaseGl() {
            // 🔍 GlUtil.checkGlError：检查OpenGL错误
            // 💡 作用：调试时检测是否有OpenGL错误
            // ⏰ 使用时机：在释放资源前后检查
            GlUtil.checkGlError("releaseGl start");

            // 🖼️ mWindowSurface：窗口Surface对象
            // 🔍 为什么检查：可能在Surface创建前调用此方法
            // 💡 作用：避免空指针异常
            // ⏰ 使用时机：在释放前检查
            // 🪟 释放窗口Surface
            if (mWindowSurface != null) {
                // 🗑️ release：释放窗口Surface资源
                // 💡 作用：释放EGL表面
                mWindowSurface.release();
                mWindowSurface = null;
            }
            
            // 🎨 mTexProgram：纹理着色器程序
            // 🔍 为什么检查：可能在程序创建前调用此方法
            // 💡 作用：避免空指针异常
            // ⏰ 使用时机：在释放前检查
            // 🎨 释放纹理程序
            if (mTexProgram != null) {
                // 🗑️ release：释放着色器程序资源
                // 💡 作用：释放着色器和纹理资源
                mTexProgram.release();
                mTexProgram = null;
            }
            
            // 🔍 GlUtil.checkGlError：检查OpenGL错误
            GlUtil.checkGlError("releaseGl done");

            // 🎮 makeNothingCurrent：解除当前EGL上下文
            // 💡 作用：释放当前线程的EGL上下文绑定
            // 🔌 解绑当前上下文
            mEglCore.makeNothingCurrent();
        }

        /**
         * Handles the surfaceChanged message.
         * <p>
         * We always receive surfaceChanged() after surfaceCreated(), but surfaceAvailable()
         * could also be called with a Surface created on a previous run.  So this may not
         * be called.
         * 
         * 📐 处理Surface尺寸变化消息
         * 💡 surfaceCreated()之后总是会收到surfaceChanged()
         * 💡 但surfaceAvailable()可能使用之前创建的Surface，此时不会调用此方法
         */
        private void surfaceChanged(int width, int height) {
            // 📝 日志输出：记录Surface尺寸变化
            Log.d(TAG, "RenderThread surfaceChanged " + width + "x" + height);

            // 📐 mWindowSurfaceWidth：窗口Surface的宽度
            // 🔍 为什么更新：Surface尺寸可能变化
            // 💡 作用：存储当前窗口宽度
            // ⏰ 使用时机：在finishSurfaceSetup中使用
            mWindowSurfaceWidth = width;
            
            // 📐 mWindowSurfaceHeight：窗口Surface的高度
            // 🔍 为什么更新：Surface尺寸可能变化
            // 💡 作用：存储当前窗口高度
            // ⏰ 使用时机：在finishSurfaceSetup中使用
            mWindowSurfaceHeight = height;
            
            // 🔧 finishSurfaceSetup：完成Surface设置
            // 💡 作用：更新视口、投影矩阵、摄像头预览
            finishSurfaceSetup();
        }

        /**
         * Handles the surfaceDestroyed message.
         * 
         * 🗑️ 处理Surface销毁消息
         */
        private void surfaceDestroyed() {
            // In practice this never appears to be called -- the activity is always paused
            // before the surface is destroyed.  In theory it could be called though.
            // 📝 日志输出：记录Surface销毁事件
            // 📝 实际上这个方法很少被调用——Activity总是在Surface销毁前暂停
            Log.d(TAG, "RenderThread surfaceDestroyed");
            
            // 🗑️ releaseGl：释放OpenGL资源
            // 💡 作用：释放窗口Surface和纹理程序
            releaseGl();
        }

        /**
         * Sets up anything that depends on the window size.
         * <p>
         * Open the camera (to set mCameraAspectRatio) before calling here.
         *
         * 🔧 完成Surface设置（依赖窗口尺寸的部分）
         * 💡 必须在打开摄像头后调用
         */
        private void finishSurfaceSetup() {
            // 📐 width/height：窗口Surface的像素尺寸
            // 🔍 为什么定义：需要从成员变量复制到局部变量，方便后续多次使用
            // 💡 作用：设置视口、投影矩阵、默认位置等都需要用到窗口尺寸
            // ⏰ 使用时机：在Surface创建或尺寸变化后调用
            int width = mWindowSurfaceWidth;
            int height = mWindowSurfaceHeight;
            Log.d(TAG, "finishSurfaceSetup size=" + width + "x" + height +
                    " camera=" + mCameraPreviewWidth + "x" + mCameraPreviewHeight);

            // Use full window.
            // 🖥️ glViewport：设置OpenGL视口为整个窗口区域
            // 💡 参数(0, 0, width, height)：从左下角(0,0)到右上角(width,height)
            // 💡 作用：告诉OpenGL渲染内容应该填充整个Surface
            GLES20.glViewport(0, 0, width, height);

            // Simple orthographic projection, with (0,0) in lower-left corner.
            // 📐 orthoM：创建正交投影矩阵，将2D坐标映射到屏幕像素
            // 💡 参数说明：
            //    mDisplayProjectionMatrix：输出的4x4投影矩阵（16个float）
            //    0：矩阵偏移量
            //    0, width：X轴范围（左到右）
            //    0, height：Y轴范围（下到上）
            //    -1, 1：Z轴范围（近平面到远平面）
            // 💡 作用：建立2D渲染的坐标系统
            Matrix.orthoM(mDisplayProjectionMatrix, 0, 0, width, 0, height, -1, 1);

            // Default position is center of screen.
            // 📍 mPosX/mPosY：纹理矩形的默认位置，设为屏幕中心
            // 🔍 为什么定义：用户可以通过触摸改变位置，需要有默认值
            // 💡 作用：纹理矩形的绘制中心点坐标
            // ⏰ 使用时机：在updateGeometry()中用于设置Sprite2d的位置
            mPosX = width / 2.0f;
            mPosY = height / 2.0f;

            // 📐 updateGeometry：根据窗口尺寸和用户控件值计算纹理矩形的最终几何参数
            // 💡 包括：缩放大小、旋转角度、位置坐标
            updateGeometry();

            // Ready to go, start the camera.
            // 📷 准备就绪，启动摄像头预览
            Log.d(TAG, "starting camera preview");
            try {
                // 📷 setPreviewTexture：将摄像头预览输出绑定到SurfaceTexture
                // 💡 mCameraTexture：之前创建的SurfaceTexture，接收摄像头帧数据
                // 💡 作用：建立摄像头→纹理的数据流通道
                mCamera.setPreviewTexture(mCameraTexture);
            } catch (IOException ioe) {
                // ⚠️ IOException：设置预览纹理失败时抛出运行时异常
                throw new RuntimeException(ioe);
            }
            // 📷 startPreview：开始摄像头预览
            // 💡 作用：摄像头开始捕获帧数据，通过SurfaceTexture传递给OpenGL纹理
            mCamera.startPreview();
        }

        /**
         * Updates the geometry of mRect, based on the size of the window and the current
         * values set by the UI.
         * 
         * 📐 更新矩形的几何参数
         * 💡 根据窗口尺寸和用户设置的值计算位置、大小、缩放和旋转
         */
        private void updateGeometry() {
            // 📐 width/height：窗口Surface的像素尺寸
            // 🔍 为什么定义：需要从成员变量复制到局部变量，方便后续多次使用
            // 💡 作用：计算纹理矩形的大小和位置
            // ⏰ 使用时机：立即用于计算
            int width = mWindowSurfaceWidth;
            int height = mWindowSurfaceHeight;

            // 📐 smallDim：窗口的较小尺寸
            // 🔍 为什么定义：纹理矩形的大小基于较小的尺寸，确保不会超出屏幕
            // 💡 作用：计算纹理矩形的基准大小
            // ⏰ 使用时机：立即用于计算scaled
            int smallDim = Math.min(width, height);
            
            // 📊 scaled：缩放后的纹理矩形大小
            // 🔍 为什么计算：需要根据用户设置的百分比计算实际大小
            // 💡 作用：确定纹理矩形的最终显示大小
            // ⏰ 使用时机：立即用于计算宽度和高度
            // Max scale is a bit larger than the screen, so we can show over-size.
            // 📐 最大缩放略大于屏幕，以便显示超大尺寸
            float scaled = smallDim * (mSizePercent / 100.0f) * 1.25f;
            
            // 📊 cameraAspect：摄像头预览的宽高比
            // 🔍 为什么计算：需要保持纹理的原始比例
            // 💡 作用：确保纹理不失真
            // ⏰ 使用时机：立即用于计算宽度
            float cameraAspect = (float) mCameraPreviewWidth / mCameraPreviewHeight;
            
            // 📐 newWidth/newHeight：纹理矩形的最终宽高
            // 🔍 为什么计算：需要设置到Sprite2d对象
            // 💡 作用：确定纹理矩形的显示尺寸
            // ⏰ 使用时机：立即用于设置矩形属性
            int newWidth = Math.round(scaled * cameraAspect);
            int newHeight = Math.round(scaled);

            // 📊 zoomFactor：缩放因子
            // 🔍 为什么计算：用户设置的缩放百分比需要转换为实际因子
            // 💡 作用：1.0表示无缩放，0.0表示完全缩小
            // ⏰ 使用时机：立即用于设置矩形的缩放
            // 🔍 计算缩放因子（100%时为1.0，0%时为0.0）
            float zoomFactor = 1.0f - (mZoomPercent / 100.0f);
            
            // 🔄 rotAngle：旋转角度（度）
            // 🔍 为什么计算：用户设置的旋转百分比需要转换为角度
            // 💡 作用：0-360度，控制纹理矩形的旋转
            // ⏰ 使用时机：立即用于设置矩形的旋转
            // 🔄 计算旋转角度（0-360度）
            int rotAngle = Math.round(360 * (mRotatePercent / 100.0f));

            // 📐 setScale：设置纹理矩形的缩放
            // 💡 作用：控制纹理矩形的显示大小
            // 📐 设置矩形属性
            mRect.setScale(newWidth, newHeight);
            
            // 📍 setPosition：设置纹理矩形的位置
            // 💡 作用：控制纹理矩形的显示位置
            mRect.setPosition(mPosX, mPosY);
            
            // 🔄 setRotation：设置纹理矩形的旋转
            // 💡 作用：控制纹理矩形的旋转角度
            mRect.setRotation(rotAngle);
            
            // 🔍 setScale：设置纹理采样的缩放
            // 💡 作用：控制纹理的采样区域大小
            mRectDrawable.setScale(zoomFactor);

            // 📤 sendRectSize：发送矩形尺寸到主线程
            // 💡 作用：更新UI显示
            // 📤 发送更新的值到主线程显示
            mMainHandler.sendRectSize(newWidth, newHeight);
            
            // 📤 sendZoomArea：发送缩放区域到主线程
            // 💡 作用：更新UI显示
            mMainHandler.sendZoomArea(Math.round(mCameraPreviewWidth * zoomFactor),
                    Math.round(mCameraPreviewHeight * zoomFactor));
            
            // 📤 sendRotateDeg：发送旋转角度到主线程
            // 💡 作用：更新UI显示
            mMainHandler.sendRotateDeg(rotAngle);
        }

        /**
         * 🖼️ SurfaceTexture.OnFrameAvailableListener回调
         * 💡 当有新的摄像头帧可用时调用（在任意线程）
         */
        @Override   // SurfaceTexture.OnFrameAvailableListener; runs on arbitrary thread
        public void onFrameAvailable(SurfaceTexture surfaceTexture) {
            mHandler.sendFrameAvailable();
        }

        /**
         * Handles incoming frame of data from the camera.
         * 
         * 🖼️ 处理来自摄像头的帧数据
         * 💡 更新纹理图像并绘制
         */
        private void frameAvailable() {
            mCameraTexture.updateTexImage();
            draw();
        }

        /**
         * Draws the scene and submits the buffer.
         * 
         * 🖼️ 绘制场景并提交缓冲区
         */
        private void draw() {
            // 🔍 GlUtil.checkGlError：检查OpenGL错误
            // 💡 作用：调试时检测是否有OpenGL错误
            // ⏰ 使用时机：在绘制前后检查
            GlUtil.checkGlError("draw start");

            // 🎨 glClearColor：设置清除颜色
            // 💡 参数(0.0f, 0.0f, 0.0f, 1.0f)：黑色（不透明）
            // 💡 作用：指定背景颜色
            // ⏰ 使用时机：在清除之前设置
            // 🎨 清除为黑色背景
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            
            // 🎨 glClear：清除颜色缓冲区
            // 💡 GL_COLOR_BUFFER_BIT：只清除颜色
            // 💡 作用：用黑色填充整个屏幕
            // ⏰ 使用时机：在绘制之前清除
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            
            // 🖼️ draw：绘制纹理矩形
            // 💡 参数：纹理程序和投影矩阵
            // 💡 作用：将摄像头帧渲染到屏幕
            // 🖼️ 绘制纹理矩形
            mRect.draw(mTexProgram, mDisplayProjectionMatrix);
            
            // 🔄 swapBuffers：交换前后缓冲区
            // 💡 作用：将渲染结果显示到屏幕
            // 🔄 交换缓冲区
            mWindowSurface.swapBuffers();

            // 🔍 GlUtil.checkGlError：检查OpenGL错误
            GlUtil.checkGlError("draw done");
        }

        // 🎚️ 设置缩放百分比
        private void setZoom(int percent) {
            mZoomPercent = percent;
            updateGeometry();
        }

        // 📐 设置大小百分比
        private void setSize(int percent) {
            mSizePercent = percent;
            updateGeometry();
        }

        // 🔄 设置旋转百分比
        private void setRotate(int percent) {
            mRotatePercent = percent;
            updateGeometry();
        }

        /**
         * 📍 设置纹理位置
         * 💡 注意：GLES坐标系Y轴向上，需要翻转
         */
        private void setPosition(int x, int y) {
            mPosX = x;
            mPosY = mWindowSurfaceHeight - y;   // GLES is upside-down
            updateGeometry();
        }

        /**
         * Opens a camera, and attempts to establish preview mode at the specified width
         * and height with a fixed frame rate.
         * <p>
         * Sets mCameraPreviewWidth / mCameraPreviewHeight.
         *
         * 📷 打开摄像头并尝试以指定宽高和固定帧率建立预览模式
         * 💡 设置mCameraPreviewWidth和mCameraPreviewHeight
         *
         * @param desiredWidth  期望的预览宽度（像素），如1280 📐
         * @param desiredHeight 期望的预览高度（像素），如720 📐
         * @param desiredFps    期望的帧率，如30 🎬
         */
        private void openCamera(int desiredWidth, int desiredHeight, int desiredFps) {
            // ⚠️ 防御性检查：确保摄像头未被重复初始化
            if (mCamera != null) {
                throw new RuntimeException("camera already initialized");
            }

            // 📷 info：摄像头信息对象，用于查询摄像头的方向、类型等属性
            // 🔍 为什么定义：需要遍历所有摄像头来查找前置摄像头
            // 💡 作用：存储单个摄像头的元数据信息
            // ⏰ 使用时机：在for循环中每次获取一个摄像头的信息
            Camera.CameraInfo info = new Camera.CameraInfo();

            // Try to find a front-facing camera (e.g. for videoconferencing).
            // 🔍 numCameras：设备上可用的摄像头总数
            // 🔍 为什么定义：需要知道循环范围来遍历所有摄像头
            // 💡 作用：控制for循环的迭代次数
            // ⏰ 使用时机：立即用于for循环条件判断
            int numCameras = Camera.getNumberOfCameras();

            // 🔁 遍历所有摄像头，优先查找前置摄像头
            // 💡 为什么优先前置：适合视频通话等场景
            for (int i = 0; i < numCameras; i++) {
                // 📷 getCameraInfo：获取第i个摄像头的信息，存入info对象
                Camera.getCameraInfo(i, info);
                // 🔍 info.facing：摄像头方向，CAMERA_FACING_FRONT表示前置
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                    // 📷 Camera.open(i)：打开指定索引的摄像头
                    // 💡 成功找到前置摄像头，跳出循环
                    mCamera = Camera.open(i);
                    break;
                }
            }

            // 📷 如果没找到前置摄像头，使用默认摄像头（通常是后置）
            if (mCamera == null) {
                Log.d(TAG, "No front-facing camera found; opening default");
                // 📷 Camera.open()：打开默认摄像头（第一个后置摄像头）
                mCamera = Camera.open();    // opens first back-facing camera
            }

            // ⚠️ 最终检查：确保摄像头成功打开
            if (mCamera == null) {
                throw new RuntimeException("Unable to open camera");
            }

            // ⚙️ parms：摄像头参数对象，用于配置预览尺寸、帧率等
            // 🔍 为什么定义：需要修改摄像头的运行参数
            // 💡 作用：获取当前参数→修改→设置回去
            // ⏰ 使用时机：在Camera.open()成功后立即获取
            // ⚙️ parms：相机参数对象
            // 🔍 为什么定义：需要修改相机的预览尺寸、帧率等运行参数
            // 💡 作用：获取当前参数→修改→设置回去
            // ⏰ 使用时机：在Camera.open()成功后立即获取
            Camera.Parameters parms = mCamera.getParameters();

            // 📐 choosePreviewSize：从可用尺寸中选择最接近期望值的预览尺寸
            // 💡 参数parms：参数对象会被修改，包含选中的尺寸
            CameraUtils.choosePreviewSize(parms, desiredWidth, desiredHeight);

            // Try to set the frame rate to a constant value.
            // 🎬 thousandFps：实际设置的帧率×1000（整数表示，避免浮点误差）
            // 🔍 为什么定义：需要记录实际设置的帧率，用于后续显示和计算
            // 💡 作用：chooseFixedPreviewFps返回实际设置的帧率（毫秒单位）
            // ⏰ 使用时机：设置参数后，用于发送到主线程显示
            int thousandFps = CameraUtils.chooseFixedPreviewFps(parms, desiredFps * 1000);

            // Give the camera a hint that we're recording video.  This can have a big
            // impact on frame rate.
            // 💡 setRecordingHint(true)：提示摄像头系统我们在录制视频
            // 💡 作用：系统会优化帧率和功耗，可能显著提高帧率稳定性
            parms.setRecordingHint(true);

            // 📷 setParameters：将修改后的参数应用到摄像头
            mCamera.setParameters(parms);

            // 📊 fpsRange：存储摄像头实际的帧率范围 [min, max]（毫秒单位）
            // 🔍 为什么定义：需要获取摄像头实际支持的帧率范围，用于日志记录
            // 💡 作用：存储getPreviewFpsRange的输出结果
            // ⏰ 使用时机：在getPreviewFpsRange调用后立即读取
            int[] fpsRange = new int[2];

            // 📐 mCameraPreviewSize：摄像头实际的预览尺寸对象
            // 🔍 为什么定义：需要获取摄像头实际分配的尺寸（可能与请求值不同）
            // 💡 作用：包含width和height属性，用于后续计算
            Camera.Size mCameraPreviewSize = parms.getPreviewSize();

            // 📊 getPreviewFpsRange：获取摄像头实际的帧率范围，存入fpsRange数组
            parms.getPreviewFpsRange(fpsRange);

            // 📝 previewFacts：构建摄像头配置的描述字符串，用于日志输出
            // 🔍 为什么定义：需要格式化显示摄像头的实际配置参数
            // 💡 作用：拼接尺寸和帧率信息，便于调试
            String previewFacts = mCameraPreviewSize.width + "x" + mCameraPreviewSize.height;

            // 🎬 判断帧率范围：如果min==max表示固定帧率，否则是范围帧率
            if (fpsRange[0] == fpsRange[1]) {
                // 📊 固定帧率：直接显示fps值
                previewFacts += " @" + (fpsRange[0] / 1000.0) + "fps";
            } else {
                // 📊 范围帧率：显示[min-max]的范围
                previewFacts += " @[" + (fpsRange[0] / 1000.0) +
                        " - " + (fpsRange[1] / 1000.0) + "] fps";
            }
            // 📝 输出摄像头配置日志
            Log.i(TAG, "Camera config: " + previewFacts);

            // 📝 保存摄像头实际预览尺寸到成员变量
            // 💡 mCameraPreviewWidth/mCameraPreviewHeight：用于后续的纹理坐标计算
            mCameraPreviewWidth = mCameraPreviewSize.width;
            mCameraPreviewHeight = mCameraPreviewSize.height;

            // 📤 sendCameraParams：将摄像头参数发送到主线程，用于UI显示
            // 💡 参数：宽度、高度、帧率（thousandFps/1000.0f还原为浮点数）
            mMainHandler.sendCameraParams(mCameraPreviewWidth, mCameraPreviewHeight,
                    thousandFps / 1000.0f);
        }

        /**
         * Stops camera preview, and releases the camera to the system.
         * 
         * ⏹️ 停止摄像头预览并释放摄像头
         */
        private void releaseCamera() {
            // ⚠️ null检查：确保摄像头已打开才释放
            // 🔍 为什么检查：可能在摄像头未打开时调用此方法
            // 💡 作用：避免空指针异常
            // ⏰ 使用时机：在释放资源前检查
            if (mCamera != null) {
                // ⏹️ stopPreview：停止摄像头预览
                // 🔍 为什么调用：释放摄像头前必须先停止预览
                // 💡 作用：停止摄像头捕获帧数据
                // ⏰ 使用时机：在释放摄像头之前
                mCamera.stopPreview();
                // 🗑️ release：释放摄像头硬件资源
                // 🔍 为什么调用：将摄像头归还给系统，其他应用可以使用
                // 💡 作用：释放摄像头硬件的独占锁
                // ⏰ 使用时机：在停止预览之后
                mCamera.release();
                // 🗑️ 置null：清除摄像头引用
                // 🔍 为什么置null：标记摄像头已释放，避免重复释放
                // 💡 作用：允许垃圾回收器回收Camera对象
                // ⏰ 使用时机：在释放后立即置null
                mCamera = null;
                // 📝 日志输出：记录释放完成
                Log.d(TAG, "releaseCamera -- done");
            }
        }
    }


    /**
     * Handler for RenderThread.  Used for messages sent from the UI thread to the render thread.
     * <p>
     * The object is created on the render thread, and the various "send" methods are called
     * from the UI thread.
     */
    private static class RenderHandler extends Handler {
        private static final int MSG_SURFACE_AVAILABLE = 0;
        private static final int MSG_SURFACE_CHANGED = 1;
        private static final int MSG_SURFACE_DESTROYED = 2;
        private static final int MSG_SHUTDOWN = 3;
        private static final int MSG_FRAME_AVAILABLE = 4;
        private static final int MSG_ZOOM_VALUE = 5;
        private static final int MSG_SIZE_VALUE = 6;
        private static final int MSG_ROTATE_VALUE = 7;
        private static final int MSG_POSITION = 8;
        private static final int MSG_REDRAW = 9;

        // This shouldn't need to be a weak ref, since we'll go away when the Looper quits,
        // but no real harm in it.
        private WeakReference<RenderThread> mWeakRenderThread;

        /**
         * Call from render thread.
         */
        public RenderHandler(RenderThread rt) {
            mWeakRenderThread = new WeakReference<RenderThread>(rt);
        }

        /**
         * Sends the "surface available" message.  If the surface was newly created (i.e.
         * this is called from surfaceCreated()), set newSurface to true.  If this is
         * being called during Activity startup for a previously-existing surface, set
         * newSurface to false.
         * <p>
         * The flag tells the caller whether or not it can expect a surfaceChanged() to
         * arrive very soon.
         * <p>
         * Call from UI thread.
         */
        public void sendSurfaceAvailable(SurfaceHolder holder, boolean newSurface) {
            sendMessage(obtainMessage(MSG_SURFACE_AVAILABLE,
                    newSurface ? 1 : 0, 0, holder));
        }

        /**
         * Sends the "surface changed" message, forwarding what we got from the SurfaceHolder.
         * <p>
         * Call from UI thread.
         */
        public void sendSurfaceChanged(@SuppressWarnings("unused") int format, int width,
                int height) {
            // ignore format
            sendMessage(obtainMessage(MSG_SURFACE_CHANGED, width, height));
        }

        /**
         * Sends the "shutdown" message, which tells the render thread to halt.
         * <p>
         * Call from UI thread.
         */
        public void sendSurfaceDestroyed() {
            sendMessage(obtainMessage(MSG_SURFACE_DESTROYED));
        }

        /**
         * Sends the "shutdown" message, which tells the render thread to halt.
         * <p>
         * Call from UI thread.
         */
        public void sendShutdown() {
            sendMessage(obtainMessage(MSG_SHUTDOWN));
        }

        /**
         * Sends the "frame available" message.
         * <p>
         * Call from UI thread.
         */
        public void sendFrameAvailable() {
            sendMessage(obtainMessage(MSG_FRAME_AVAILABLE));
        }

        /**
         * Sends the "zoom value" message.  "progress" should be 0-100.
         * <p>
         * Call from UI thread.
         */
        public void sendZoomValue(int progress) {
            sendMessage(obtainMessage(MSG_ZOOM_VALUE, progress, 0));
        }

        /**
         * Sends the "size value" message.  "progress" should be 0-100.
         * <p>
         * Call from UI thread.
         */
        public void sendSizeValue(int progress) {
            sendMessage(obtainMessage(MSG_SIZE_VALUE, progress, 0));
        }

        /**
         * Sends the "rotate value" message.  "progress" should be 0-100.
         * <p>
         * Call from UI thread.
         */
        public void sendRotateValue(int progress) {
            sendMessage(obtainMessage(MSG_ROTATE_VALUE, progress, 0));
        }

        /**
         * Sends the "position" message.  Sets the position of the rect.
         * <p>
         * Call from UI thread.
         */
        public void sendPosition(int x, int y) {
            sendMessage(obtainMessage(MSG_POSITION, x, y));
        }

        /**
         * Sends the "redraw" message.  Forces an immediate redraw.
         * <p>
         * Call from UI thread.
         */
        public void sendRedraw() {
            sendMessage(obtainMessage(MSG_REDRAW));
        }

        /**
         * 📬 处理来自UI线程的消息
         * 💡 在渲染线程的Looper中执行，根据消息类型调用RenderThread的对应方法
         *
         * @param msg 从UI线程发送过来的消息对象，包含操作类型和参数数据 📨
         */
        @Override  // runs on RenderThread
        public void handleMessage(Message msg) {
            // 📋 what：消息类型标识，决定调用RenderThread的哪个方法
            // 🔍 为什么定义：需要根据不同的消息类型执行不同的渲染操作
            // 💡 作用：消息分发的依据
            // ⏰ 使用时机：立即用于switch判断
            int what = msg.what;
            //Log.d(TAG, "RenderHandler [" + this + "]: what=" + what);

            // 🧵 renderThread：从弱引用获取RenderThread实例
            // 🔍 为什么使用弱引用：防止RenderThread被Handler强引用导致无法回收
            // 💡 作用：调用RenderThread的各种方法来执行实际的渲染操作
            // ⏰ 使用时机：每次收到消息时都需要获取，可能为null
            RenderThread renderThread = mWeakRenderThread.get();

            // ⚠️ null检查：如果RenderThread已被垃圾回收，记录警告并返回
            if (renderThread == null) {
                Log.w(TAG, "RenderHandler.handleMessage: weak ref is null");
                return;
            }

            // 🎯 根据消息类型分发到RenderThread的对应方法
            switch (what) {
                case MSG_SURFACE_AVAILABLE:
                    // 🖼️ Surface可用：创建EGL窗口Surface并初始化纹理
                    // 📋 msg.obj：SurfaceHolder对象，包含可用的Surface
                    // 📋 msg.arg1：是否是新创建的Surface（1=新，0=旧）
                    renderThread.surfaceAvailable((SurfaceHolder) msg.obj, msg.arg1 != 0);
                    break;
                case MSG_SURFACE_CHANGED:
                    // 📐 Surface尺寸变化：更新窗口尺寸并重新配置投影矩阵
                    // 📋 msg.arg1：新的宽度（像素）
                    // 📋 msg.arg2：新的高度（像素）
                    renderThread.surfaceChanged(msg.arg1, msg.arg2);
                    break;
                case MSG_SURFACE_DESTROYED:
                    // 🗑️ Surface销毁：释放GL资源
                    renderThread.surfaceDestroyed();
                    break;
                case MSG_SHUTDOWN:
                    // 🛑 关闭：退出Looper循环，释放所有资源
                    renderThread.shutdown();
                    break;
                case MSG_FRAME_AVAILABLE:
                    // 🖼️ 新帧可用：更新纹理图像并触发绘制
                    // 💡 这是最频繁的消息，每帧都会触发
                    renderThread.frameAvailable();
                    break;
                case MSG_ZOOM_VALUE:
                    // 🔍 缩放值变化：更新纹理采样区域的缩放百分比
                    // 📋 msg.arg1：缩放百分比（0-100）
                    renderThread.setZoom(msg.arg1);
                    break;
                case MSG_SIZE_VALUE:
                    // 📐 大小值变化：更新纹理矩形的显示尺寸百分比
                    // 📋 msg.arg1：大小百分比（0-100）
                    renderThread.setSize(msg.arg1);
                    break;
                case MSG_ROTATE_VALUE:
                    // 🔄 旋转值变化：更新纹理矩形的旋转角度百分比
                    // 📋 msg.arg1：旋转百分比（0-100），映射到0-360度
                    renderThread.setRotate(msg.arg1);
                    break;
                case MSG_POSITION:
                    // 📍 位置变化：更新纹理矩形的显示位置
                    // 📋 msg.arg1：触摸点的X坐标（像素）
                    // 📋 msg.arg2：触摸点的Y坐标（像素）
                    renderThread.setPosition(msg.arg1, msg.arg2);
                    break;
                case MSG_REDRAW:
                    // 🎨 强制重绘：立即执行一次绘制操作
                    // 💡 用于用户拖动控件时确保及时更新显示
                    renderThread.draw();
                    break;
               default:
                    // ⚠️ 未知消息类型，抛出异常（防御性编程）
                    throw new RuntimeException("unknown message " + what);
            }
        }
    }
}
