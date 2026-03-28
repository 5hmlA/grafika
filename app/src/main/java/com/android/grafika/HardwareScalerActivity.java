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

import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.Choreographer;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.CheckBox;
import android.widget.RadioButton;
import android.widget.TextView;
import android.app.Activity;
import android.graphics.Rect;

import com.android.grafika.gles.Drawable2d;
import com.android.grafika.gles.EglCore;
import com.android.grafika.gles.FlatShadedProgram;
import com.android.grafika.gles.GeneratedTexture;
import com.android.grafika.gles.GlUtil;
import com.android.grafika.gles.Sprite2d;
import com.android.grafika.gles.Texture2dProgram;
import com.android.grafika.gles.WindowSurface;

import java.lang.ref.WeakReference;
import com.google.grafika.R;


/**
 * Exercises SurfaceHolder#setFixedSize().
 * <p>
 * http://android-developers.blogspot.com/2013/09/using-hardware-scaler-for-performance.html
 * <p>
 * The purpose of the feature is to allow games to render at 720p or 1080p to get good
 * performance on displays with a large number of pixels.  It's easier (and more fun) to
 * see the effects when we crank the resolution way down.  Normally the resolution would
 * be fixed, perhaps with minor tweaks (e.g. letterboxing via AspectFrameLayout) to match
 * the device aspect ratio, but here we make it variable to match the display window.
 * <p>
 * TODO: examine effects on touch input
 *
 * 🎮 演示SurfaceHolder#setFixedSize()的使用
 * 🎯 该功能允许游戏以720p或1080p渲染以获得更好的性能
 * 📺 通过降低分辨率可以更直观地观察效果
 * 🔄 通常分辨率是固定的，但这里我们让它可变以匹配显示窗口
 */
public class HardwareScalerActivity extends Activity implements SurfaceHolder.Callback,
        Choreographer.FrameCallback {
    private static final String TAG = MainActivity.TAG;

    // [ This used to have "a few thoughts about app life cycle and SurfaceView".  These
    //   are now at http://source.android.com/devices/graphics/architecture.html in
    //   Appendix B. ]
    //
    // This Activity uses approach #2 (Surface-driven).
    // [ 这里曾经有"关于应用生命周期和SurfaceView的一些思考"，现已移到上述链接 ]
    // [ 该Activity使用方法#2（Surface驱动） ]

    // Indexes into the data arrays.
    // 📊 Surface尺寸索引常量
    private static final int SURFACE_SIZE_TINY = 0;   // 🤏 极小尺寸
    private static final int SURFACE_SIZE_SMALL = 1;   // 🔹 小尺寸
    private static final int SURFACE_SIZE_MEDIUM = 2;  // 🔸 中等尺寸
    private static final int SURFACE_SIZE_FULL = 3;    // 🔶 全尺寸

    // 📐 Surface尺寸配置数组（单位：像素），-1表示使用完整尺寸
    private static final int[] SURFACE_DIM = new int[] { 64, 240, 480, -1 };
    // 🏷️ Surface尺寸标签
    private static final String[] SURFACE_LABEL = new String[] {
        "tiny", "small", "medium", "full"
    };

    private int mSelectedSize;           // 🎯 当前选中的尺寸索引
    private int mFullViewWidth;          // 📐 完整视图宽度
    private int mFullViewHeight;         // 📐 完整视图高度
    private int[][] mWindowWidthHeight;  // 📦 各尺寸对应的宽高数组
    private boolean mFlatShadingChecked; // ☑️ 是否启用平面着色

    // Rendering code runs on this thread.  The thread's life span is tied to the Surface.
    // 🧵 渲染线程，生命周期与Surface绑定
    private RenderThread mRenderThread;

    // 🎯 Activity创建时初始化界面和SurfaceView回调
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 📝 记录Activity创建日志，方便调试生命周期
        Log.d(TAG, "HardwareScalerActivity: onCreate");
        // 📝 调用父类onCreate，恢复Activity状态
        super.onCreate(savedInstanceState);
        // 📝 设置布局文件activity_hardware_scaler.xml作为界面
        setContentView(R.layout.activity_hardware_scaler);

        // 💡 mSelectedSize：当前选中的Surface尺寸索引
        // 🔍 为什么定义：需要记录用户选择的分辨率级别
        // 💡 作用：控制RadioButton选中状态和setFixedSize()的尺寸选择
        // ⏰ 使用时机：RadioButton点击时更新，surfaceCreated()和configureRadioButton()中读取
        mSelectedSize = SURFACE_SIZE_FULL;                          // 默认全尺寸
        // 💡 mFullViewWidth/mFullViewHeight：SurfaceView的实际像素尺寸
        // 🔍 为什么定义：需要存储视图的真实宽高用于计算各分辨率配置
        // 💡 作用：在surfaceCreated()中更新真实值，用于计算windowAspect和各尺寸
        // ⏰ 使用时机：onCreate()中初始化默认值512，surfaceCreated()中更新真实值
        mFullViewWidth = mFullViewHeight = 512;     // want actual view size, but it's not avail
        // 💡 mWindowWidthHeight：各尺寸对应的[width, height]数组
        // 🔍 为什么定义：需要预计算tiny/small/medium/full四种预设的实际像素尺寸
        // 💡 作用：RadioButton点击时直接查找对应尺寸，避免重复计算
        // ⏰ 使用时机：surfaceCreated()中填充数据，onRadioButtonClicked()中读取
        // 📊 初始化各尺寸的宽高数组
        mWindowWidthHeight = new int[SURFACE_DIM.length][2];
        // 📝 同步界面控件状态（单选按钮、尺寸文本等）
        updateControls();

        // 📝 获取布局中的SurfaceView控件
        // 🖥️ 获取SurfaceView并注册回调监听
        SurfaceView sv = (SurfaceView) findViewById(R.id.hardwareScaler_surfaceView);
        // 📝 注册SurfaceHolder.Callback，接收surfaceCreated/Changed/Destroyed事件
        sv.getHolder().addCallback(this);
    }

    // ⏸️ Activity暂停时移除帧回调，停止vsync通知
    @Override
    protected void onPause() {
        // 📝 调用父类onPause，保存Activity状态
        super.onPause();

        // If the callback was posted, remove it.  This stops the notifications.  Ideally we
        // would send a message to the thread letting it know, so when it wakes up it can
        // reset its notion of when the previous Choreographer event arrived.
        // 🔔 移除回调停止帧通知，理想情况下应通知渲染线程重置vsync时间
        // 📝 记录日志，标记正在取消Choreographer帧回调
        Log.d(TAG, "onPause unhooking choreographer");
        // 💡 Choreographer.getInstance()：获取系统Choreographer单例
        // 💡 作用：移除之前注册的帧回调，停止接收vsync信号
        // 💡 何时用：Activity暂停时，避免后台继续渲染浪费资源
        Choreographer.getInstance().removeFrameCallback(this);
    }

    // ▶️ Activity恢复时重新注册帧回调
    @Override
    protected void onResume() {
        // 📝 调用父类onResume，恢复Activity状态
        super.onResume();

        // If we already have a Surface, we just need to resume the frame notifications.
        // 🔄 如果已有Surface，只需恢复帧通知
        // 💡 mRenderThread：渲染线程实例（volatile确保多线程可见性）
        // 💡 作用：判断渲染线程是否存在，存在则恢复帧回调
        // 💡 何时用：仅在Surface已创建（渲染线程已启动）时才恢复回调
        if (mRenderThread != null) {
            // 📝 记录日志，标记正在重新注册Choreographer帧回调
            Log.d(TAG, "onResume re-hooking choreographer");
            // 📝 注册帧回调，恢复vsync信号接收
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    // 🎨 Surface创建回调：获取视图尺寸，计算各分辨率配置，启动渲染线程
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        // 📝 记录Surface创建日志，包含holder信息用于调试
        Log.d(TAG, "surfaceCreated holder=" + holder);

        // Grab the view's width.  It's not available before now.
        // 📐 获取视图的实际尺寸（在此之前不可用）
        // 💡 size：Surface的帧矩形，包含实际可用的宽高
        // 💡 作用：获取SurfaceView的真实像素尺寸
        // 💡 何时用：仅在surfaceCreated后才能获取到有效值
        Rect size = holder.getSurfaceFrame();
        // 💡 mFullViewWidth/mFullViewHeight：存储获取到的真实尺寸
        // 💡 作用：替换onCreate中的默认值512，用于后续计算
        mFullViewWidth = size.width();
        mFullViewHeight = size.height();

        // Configure our fixed-size values.  We want to configure it so that the narrowest
        // dimension (e.g. width when device is in portrait orientation) is equal to the
        // value in SURFACE_DIM, and the other dimension is sized to maintain the same
        // aspect ratio.
        // 📐 根据窗口宽高比计算各尺寸配置，保持相同的宽高比
        // 💡 windowAspect：窗口宽高比（高度/宽度）
        // 🔍 为什么计算：需要根据宽高比计算各分辨率配置中非固定维度的尺寸
        // 💡 作用：竖屏时用height=width*aspect，横屏时用width=height/aspect
        // ⏰ 使用时机：在下面的for循环中为tiny/small/medium计算另一维度
        float windowAspect = (float) mFullViewHeight / (float) mFullViewWidth;
        // 📝 遍历所有预设尺寸（tiny=64, small=240, medium=480, full=-1）
        for (int i = 0; i < SURFACE_DIM.length; i++) {
            if (i == SURFACE_SIZE_FULL) {
                // special-case for full size
                // 🔶 全尺寸特殊处理：直接使用视图完整尺寸
                mWindowWidthHeight[i][0] = mFullViewWidth;
                mWindowWidthHeight[i][1] = mFullViewHeight;
            } else if (mFullViewWidth < mFullViewHeight) {
                // portrait
                // 📱 竖屏模式：宽度固定为SURFACE_DIM[i]，高度按比例计算
                mWindowWidthHeight[i][0] = SURFACE_DIM[i];
                mWindowWidthHeight[i][1] = (int) (SURFACE_DIM[i] * windowAspect);
            } else {
                // landscape
                // 📱 横屏模式：高度固定为SURFACE_DIM[i]，宽度按比例计算
                mWindowWidthHeight[i][0] = (int) (SURFACE_DIM[i] / windowAspect);
                mWindowWidthHeight[i][1] = SURFACE_DIM[i];
            }
        }

        // Some controls include text based on the view dimensions, so update now.
        // 🔄 更新控件显示（尺寸文本依赖视图尺寸）
        updateControls();

        // 🚀 创建并启动渲染线程
        // 💡 sv：SurfaceView实例，用于获取SurfaceHolder
        // 💡 作用：将SurfaceHolder传给渲染线程，使其可以渲染到Surface
        SurfaceView sv = (SurfaceView) findViewById(R.id.hardwareScaler_surfaceView);
        // 💡 mRenderThread：新建的渲染线程实例
        // 💡 作用：在独立线程中执行OpenGL渲染，避免阻塞UI线程
        mRenderThread = new RenderThread(sv.getHolder());
        // 📝 设置线程名，方便在调试器中识别
        mRenderThread.setName("HardwareScaler GL render");
        // 📝 启动渲染线程（会执行run()方法）
        mRenderThread.start();
        // 📝 等待渲染线程完成初始化（Looper和Handler就绪）
        mRenderThread.waitUntilReady();

        // 📨 发送初始化消息到渲染线程
        // 💡 rh：渲染线程的Handler，用于从UI线程向渲染线程发送消息
        // 💡 作用：获取渲染线程的消息处理器
        RenderHandler rh = mRenderThread.getHandler();
        if (rh != null) {
            // 📝 发送平面着色设置（同步UI状态到渲染线程）
            rh.sendSetFlatShading(mFlatShadingChecked);
            // 📝 发送Surface创建消息（触发渲染线程的OpenGL初始化）
            rh.sendSurfaceCreated();
        }

        // start the draw events
        // 🎬 开始绘制事件
        // 📝 注册帧回调，开始接收vsync信号驱动渲染循环
        Choreographer.getInstance().postFrameCallback(this);
    }

    // 📐 Surface尺寸变化回调：通知渲染线程更新视口
    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // 📝 记录Surface尺寸变化日志，包含格式、尺寸和holder信息
        Log.d(TAG, "surfaceChanged fmt=" + format + " size=" + width + "x" + height +
                " holder=" + holder);

        // 💡 rh：渲染线程的Handler
        // 💡 作用：获取消息处理器，用于转发尺寸变化事件
        RenderHandler rh = mRenderThread.getHandler();
        if (rh != null) {
            // 📝 发送Surface变化消息，渲染线程会更新视口和投影矩阵
            rh.sendSurfaceChanged(format, width, height);
        }
    }

    // 💥 Surface销毁回调：等待渲染线程安全关闭
    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        // 📝 记录Surface销毁日志
        Log.d(TAG, "surfaceDestroyed holder=" + holder);

        // We need to wait for the render thread to shut down before continuing because we
        // don't want the Surface to disappear out from under it mid-render.  The frame
        // notifications will have been stopped back in onPause(), but there might have
        // been one in progress.
        // ⚠️ 需要等待渲染线程关闭，避免Surface在渲染过程中消失

        // 💡 rh：渲染线程的Handler
        // 💡 作用：获取消息处理器，用于发送关闭指令
        RenderHandler rh = mRenderThread.getHandler();
        if (rh != null) {
            // 🛑 发送关闭消息并等待线程结束
            // 📝 发送SHUTDOWN消息，触发渲染线程退出Looper循环
            rh.sendShutdown();
            try {
                // 📝 阻塞等待渲染线程完全退出（join会阻塞当前线程）
                mRenderThread.join();
            } catch (InterruptedException ie) {
                // not expected
                // 😱 不应该发生中断
                // 📝 如果等待过程中被中断，抛出运行时异常
                throw new RuntimeException("join was interrupted", ie);
            }
        }
        // 📝 清空渲染线程引用，允许GC回收
        mRenderThread = null;

        // 📝 记录Surface销毁完成日志
        Log.d(TAG, "surfaceDestroyed complete");
    }

    /*
     * Choreographer callback, called near vsync.
     *
     * @see android.view.Choreographer.FrameCallback#doFrame(long)
     *
     * 🎯 Choreographer回调，在vsync附近调用
     * 重新注册回调并转发帧时间到渲染线程
     */
    @Override
    public void doFrame(long frameTimeNanos) {
        // 💡 rh：渲染线程的Handler
        // 💡 作用：获取消息处理器，用于转发帧时间戳
        RenderHandler rh = mRenderThread.getHandler();
        if (rh != null) {
            // 🔄 重新注册回调以持续接收帧事件
            // 📝 再次注册帧回调，确保下一帧也能收到vsync信号
            Choreographer.getInstance().postFrameCallback(this);
            // 📨 发送帧时间到渲染线程
            // 📝 将纳秒级时间戳发送给渲染线程，用于动画计算
            rh.sendDoFrame(frameTimeNanos);
        }
    }

    /**
     * onClick handler for radio buttons.
     *
     * 🎛️ 尺寸单选按钮点击处理
     * 根据选择更新Surface尺寸
     */
    public void onRadioButtonClicked(View view) {
        // 💡 newSize：新选中的尺寸索引
        // 💡 作用：存储用户选择的分辨率级别
        // 💡 何时用：确定后更新mSelectedSize并应用新尺寸
        int newSize;

        // 💡 rb：被点击的RadioButton控件
        // 💡 作用：获取点击事件的来源控件，判断选中状态
        RadioButton rb = (RadioButton) view;
        if (!rb.isChecked()) {
            // 📝 忽略取消选中的事件（只处理选中事件）
            Log.d(TAG, "Got click on non-checked radio button");
            return;
        }

        // 🎯 根据选中的RadioButton确定新的尺寸
        // 💡 id：被点击RadioButton的资源ID
        // 💡 作用：通过ID判断用户选择了哪个尺寸选项
        int id = rb.getId();
        if (id == R.id.surfaceSizeTiny_radio) {
            // 📝 用户选择了极小尺寸（64像素）
            newSize = SURFACE_SIZE_TINY;
        } else if (id == R.id.surfaceSizeSmall_radio) {
            // 📝 用户选择了小尺寸（240像素）
            newSize = SURFACE_SIZE_SMALL;
        } else if (id == R.id.surfaceSizeMedium_radio) {
            // 📝 用户选择了中等尺寸（480像素）
            newSize = SURFACE_SIZE_MEDIUM;
        } else if (id == R.id.surfaceSizeFull_radio) {
            // 📝 用户选择了全尺寸（完整窗口）
            newSize = SURFACE_SIZE_FULL;
        } else {
            // 📝 未知ID，抛出异常
            throw new RuntimeException("Click from unknown id " + rb.getId());
        }
        // 📝 更新当前选中的尺寸索引
        mSelectedSize = newSize;

        // 💡 wh：选中尺寸对应的[width, height]数组
        // 💡 作用：获取目标分辨率的像素值
        // 💡 何时用：传给setFixedSize()设置Surface的新尺寸
        int[] wh = mWindowWidthHeight[newSize];

        // Update the Surface size.  This causes a "surface changed" event, but does not
        // destroy and re-create the Surface.
        // 📐 更新Surface尺寸（触发surfaceChanged事件，但不会销毁重建Surface）
        // 💡 sv：SurfaceView实例
        // 💡 作用：获取SurfaceHolder用于设置固定尺寸
        SurfaceView sv = (SurfaceView) findViewById(R.id.hardwareScaler_surfaceView);
        // 💡 sh：SurfaceHolder，控制Surface的属性
        // 💡 作用：调用setFixedSize()改变Surface的像素尺寸
        SurfaceHolder sh = sv.getHolder();
        // 📝 记录新尺寸日志
        Log.d(TAG, "setting size to " + wh[0] + "x" + wh[1]);
        // 📝 设置Surface固定尺寸，会触发surfaceChanged回调
        sh.setFixedSize(wh[0], wh[1]);
    }

    // ☑️ 平面着色复选框点击处理
    public void onFlatShadingClicked(@SuppressWarnings("unused") View unused) {
        // 💡 cb：平面着色复选框控件
        // 💡 作用：获取用户是否启用了平面着色
        CheckBox cb = (CheckBox) findViewById(R.id.flatShading_checkbox);
        // 💡 mFlatShadingChecked：平面着色开关状态
        // 💡 作用：记录用户选择，在surfaceCreated时同步给渲染线程
        // 💡 何时用：第198行 sendSetFlatShading() 和 第336行 cb.setChecked()
        mFlatShadingChecked = cb.isChecked();

        // 💡 rh：渲染线程的Handler
        // 💡 作用：获取消息处理器，用于转发着色模式变更
        RenderHandler rh = mRenderThread.getHandler();
        if (rh != null) {
            // 📝 发送平面着色设置消息，渲染线程会立即切换着色模式
            rh.sendSetFlatShading(mFlatShadingChecked);
        }
    }

    /**
     * Updates the on-screen controls to reflect the current state of the app.
     *
     * 🎮 更新界面控件状态
     * 同步单选按钮、视图尺寸文本和平面着色复选框
     */
    private void updateControls() {
        // 📝 配置四个RadioButton的文本和选中状态
        configureRadioButton(R.id.surfaceSizeTiny_radio, SURFACE_SIZE_TINY);
        configureRadioButton(R.id.surfaceSizeSmall_radio, SURFACE_SIZE_SMALL);
        configureRadioButton(R.id.surfaceSizeMedium_radio, SURFACE_SIZE_MEDIUM);
        configureRadioButton(R.id.surfaceSizeFull_radio, SURFACE_SIZE_FULL);

        // 📝 更新视图尺寸显示文本
        // 💡 tv：显示视图尺寸的TextView
        // 💡 作用：向用户展示SurfaceView的实际像素尺寸
        TextView tv = (TextView) findViewById(R.id.viewSizeValue_text);
        // 📝 设置文本为"宽x高"格式
        tv.setText(mFullViewWidth + "x" + mFullViewHeight);

        // ☑️ 更新平面着色复选框状态
        // 💡 cb：平面着色复选框
        // 💡 作用：同步复选框状态与当前设置
        CheckBox cb = (CheckBox) findViewById(R.id.flatShading_checkbox);
        // 📝 根据mFlatShadingChecked设置复选框选中状态
        cb.setChecked(mFlatShadingChecked);
    }

    /**
     * Generates the radio button text.
     *
     * 🏷️ 配置单选按钮的文本和选中状态
     * @param id 按钮资源ID
     * @param index 尺寸索引
     */
    private void configureRadioButton(int id, int index) {
        // 💡 rb：RadioButton控件
        // 💡 作用：通过资源ID获取对应的单选按钮
        RadioButton rb;
        rb = (RadioButton) findViewById(id);
        // 📝 根据当前选中的尺寸设置按钮选中状态
        rb.setChecked(mSelectedSize == index);
        // 📝 设置按钮文本为"标签 (宽x高)"格式
        rb.setText(SURFACE_LABEL[index] + " (" + mWindowWidthHeight[index][0] + "x" +
                mWindowWidthHeight[index][1] + ")");
    }

    /**
     * This class handles all OpenGL rendering.
     * <p>
     * We use Choreographer to coordinate with the device vsync.  We deliver one frame
     * per vsync.  We can't actually know when the frame we render will be drawn, but at
     * least we get a consistent frame interval.
     * <p>
     * Start the render thread after the Surface has been created.
     *
     * 🎨 处理所有OpenGL渲染的线程
     * 使用Choreographer同步vsync，每vsync交付一帧
     * 在Surface创建后启动渲染线程
     */
    private static class RenderThread extends Thread {
        // Object must be created on render thread to get correct Looper, but is used from
        // UI thread, so we need to declare it volatile to ensure the UI thread sees a fully
        // constructed object.
        // 🧵 渲染线程的Handler，必须声明为volatile确保UI线程看到完整对象
        private volatile RenderHandler mHandler;

        // Used to wait for the thread to start.
        // 🔒 用于等待线程启动的锁对象
        private Object mStartLock = new Object();
        private boolean mReady = false;

        private volatile SurfaceHolder mSurfaceHolder;  // contents may be updated by UI thread
        // 🎬 EGL核心和窗口Surface
        private EglCore mEglCore;
        private WindowSurface mWindowSurface;
        // 🖌️ 着色器程序
        private FlatShadedProgram mFlatProgram;
        private Texture2dProgram mTexProgram;
        private int mCoarseTexture;    // 🖼️ 粗糙纹理
        private int mFineTexture;      // 🖼️ 精细纹理
        private boolean mUseFlatShading; // ☑️ 是否使用平面着色

        // Orthographic projection matrix.
        // 📐 正交投影矩阵
        private float[] mDisplayProjectionMatrix = new float[16];

        // 🔺 三角形和矩形可绘制对象
        private final Drawable2d mTriDrawable = new Drawable2d(Drawable2d.Prefab.TRIANGLE);
        private final Drawable2d mRectDrawable = new Drawable2d(Drawable2d.Prefab.RECTANGLE);

        // One spinning triangle, one bouncing rectangle, and four edge-boxes.
        // 🎭 动画精灵：旋转三角形、弹跳矩形和四个边框
        private Sprite2d mTri;
        private Sprite2d mRect;
        private Sprite2d mEdges[];
        private float mRectVelX, mRectVelY;     // velocity, in viewport units per second
        // 🚀 矩形速度（视口单位/秒）
        private float mInnerLeft, mInnerTop, mInnerRight, mInnerBottom;
        // 📦 内部边界矩形（用于弹跳检测）

        private final float[] mIdentityMatrix;
        // 🆔 单位矩阵

        // Previous frame time.
        // ⏱️ 上一帧时间
        private long mPrevTimeNanos;


        /**
         * Pass in the SurfaceView's SurfaceHolder.  Note the Surface may not yet exist.
         *
         * 🏗️ 构造函数，传入SurfaceHolder（Surface可能尚未存在）
         */
        public RenderThread(SurfaceHolder holder) {
            mSurfaceHolder = holder;

            // 🆔 初始化单位矩阵
            mIdentityMatrix = new float[16];
            Matrix.setIdentityM(mIdentityMatrix, 0);

            // 🎭 初始化动画精灵对象
            mTri = new Sprite2d(mTriDrawable);
            mRect = new Sprite2d(mRectDrawable);
            mEdges = new Sprite2d[4];
            for (int i = 0; i < mEdges.length; i++) {
                mEdges[i] = new Sprite2d(mRectDrawable);
            }
        }

        /**
         * Thread entry point.
         * <p>
         * The thread should not be started until the Surface associated with the SurfaceHolder
         * has been created.  That way we don't have to wait for a separate "surface created"
         * message to arrive.
         *
         * 🚀 线程入口点
         * 准备Looper，创建Handler，初始化EGL核心
         * 循环处理消息直到退出
         */
        @Override
        public void run() {
            // 🔄 准备Looper循环
            // 📝 为当前线程创建Looper，使其能够处理Handler消息
            Looper.prepare();
            // 💡 mHandler：渲染线程的Handler实例
            // 🔍 为什么创建：需要接收UI线程发送的消息（Surface创建/变化/帧绘制等）
            // 💡 作用：消息分发中心，将UI线程指令转发给渲染线程方法
            // ⏰ 使用时机：创建后通过getHandler()供UI线程获取，消息循环中处理消息
            mHandler = new RenderHandler(this);
            // 🎬 创建EGL核心
            // 💡 mEglCore：EGL上下文管理器
            // 🔍 为什么创建：需要管理OpenGL ES与原生窗口系统的连接
            // 💡 作用：创建和管理EGLContext，控制渲染上下文的生命周期
            // ⏰ 使用时机：run()中创建，releaseGl()后release()
            // 💡 参数：null表示使用默认显示，0表示无特殊标志（无录制需求）
            mEglCore = new EglCore(null, 0);
            // 🔔 通知UI线程渲染线程已就绪
            // 📝 使用synchronized和notify通知等待中的UI线程
            synchronized (mStartLock) {
                // 📝 设置就绪标志
                mReady = true;
                mStartLock.notify();    // signal waitUntilReady()
            }

            // 🔄 开始消息循环
            // 📝 进入Looper消息循环，阻塞等待消息到来
            Looper.loop();

            // 🧹 循环结束后清理资源
            // 📝 Looper.quit()被调用后，loop()会返回，执行清理
            Log.d(TAG, "looper quit");
            // 📝 释放OpenGL资源（窗口Surface、着色器程序等）
            releaseGl();
            // 📝 释放EGL核心资源
            mEglCore.release();

            // 📝 重置就绪标志
            synchronized (mStartLock) {
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
                // 🔍 为什么循环检查：防止虚假唤醒（spurious wakeup）
                // 💡 作用：确保渲染线程确实已就绪
                // ⏰ 使用时机：在等待前检查
                while (!mReady) {
                    try {
                        // ⏳ wait：等待渲染线程通知
                        // 🔍 为什么调用：释放锁并进入等待状态
                        // 💡 作用：阻塞当前线程，直到渲染线程调用notify()
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
            // 📝 日志输出：记录正在关闭渲染线程
            Log.d(TAG, "shutdown");
            // 🔄 Looper.myLooper().quit()：退出消息循环
            // 🔍 为什么调用：需要停止渲染线程的消息处理
            // 💡 作用：终止Looper.loop()的阻塞，使线程继续执行清理
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
         * Prepares the surface.
         *
         * 🎨 Surface创建时准备OpenGL环境
         */
        private void surfaceCreated() {
            Surface surface = mSurfaceHolder.getSurface();
            prepareGl(surface);
        }

        /**
         * Prepares window surface and GL state.
         *
         * 🖼️ 准备窗口Surface和OpenGL状态
         * 创建窗口Surface，设置着色器程序，配置GL参数
         */
        private void prepareGl(Surface surface) {
            // 📝 记录OpenGL环境准备日志
            Log.d(TAG, "prepareGl");

            // 🎬 创建窗口Surface并设置为当前上下文
            // 💡 mWindowSurface：窗口Surface包装器
            // 💡 作用：将EGL渲染输出连接到Android Surface
            // 💡 参数：mEglCore=EGL核心，surface=目标Surface，false=不采用窗口管理
            mWindowSurface = new WindowSurface(mEglCore, surface, false);
            // 📝 将此窗口Surface设为当前渲染目标
            mWindowSurface.makeCurrent();

            // Programs used for drawing onto the screen.
            // 🖌️ 屏幕绘制着色器程序
            // 💡 mFlatProgram：平面着色程序
            // 💡 作用：使用纯色绘制几何图形（无纹理）
            // 💡 何时用：当mUseFlatShading=true时绘制三角形和矩形
            mFlatProgram = new FlatShadedProgram();
            // 💡 mTexProgram：纹理着色程序
            // 💡 作用：使用纹理绘制几何图形
            // 💡 何时用：当mUseFlatShading=false时绘制三角形和矩形
            mTexProgram = new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_2D);
            // 🖼️ 生成测试纹理
            // 💡 mCoarseTexture：粗糙纹理ID
            // 💡 作用：存储GeneratedTexture生成的粗糙测试纹理
            // 💡 何时用：在surfaceChanged()中设置给mRect精灵
            mCoarseTexture = GeneratedTexture.createTestTexture(GeneratedTexture.Image.COARSE);
            // 💡 mFineTexture：精细纹理ID
            // 💡 作用：存储GeneratedTexture生成的精细测试纹理
            // 💡 何时用：在surfaceChanged()中设置给mTri精灵
            mFineTexture = GeneratedTexture.createTestTexture(GeneratedTexture.Image.FINE);

            // Set the background color.
            // 🎨 设置背景色为黑色
            // 📝 RGBA全0表示黑色，alpha=1.0表示完全不透明
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);

            // Disable depth testing -- we're 2D only.
            // 🚫 禁用深度测试（仅2D渲染）
            // 📝 2D渲染不需要Z轴排序，禁用可提升性能
            GLES20.glDisable(GLES20.GL_DEPTH_TEST);

            // Don't need backface culling.  (If you're feeling pedantic, you can turn it on to
            // make sure we're defining our shapes correctly.)
            // 🚫 禁用背面剔除
            // 📝 2D图形没有"背面"概念，禁用可避免渲染问题
            GLES20.glDisable(GLES20.GL_CULL_FACE);
        }

        /**
         * Handles changes to the size of the underlying surface.  Adjusts viewport as needed.
         * Must be called before we start drawing.
         * (Called from RenderHandler.)
         *
         * 📐 处理Surface尺寸变化
         * 设置视口和投影矩阵，初始化动画对象位置和速度
         */
        private void surfaceChanged(int width, int height) {
            // This method is called when the surface is first created, and shortly after the
            // call to setFixedSize().  The tricky part is that this is called when the
            // drawing surface is *about* to change size, not when it has *already* changed
            // size.  A query on the EGL surface will confirm that the surface dimensions
            // haven't yet changed.  If you re-query after the next swapBuffers() call,
            // you will see the new dimensions.
            //
            // To have a smooth transition, we should continue to draw at the old size until the
            // surface query tells us that the size of the underlying buffers has actually
            // changed.  I don't really expect a "normal" app will want to call setFixedSize()
            // dynamically though, so in practice this situation shouldn't arise, and it's
            // just not worth the hassle of doing it right.
            // ⚠️ 此方法在Surface即将变化时调用，而非变化后
            // 实际中通常不需要动态调用setFixedSize()

            // 📝 记录Surface尺寸变化日志
            Log.d(TAG, "surfaceChanged " + width + "x" + height);

            // Use full window.
            // 🖥️ 设置视口为整个窗口
            // 📝 glViewport定义渲染输出在窗口中的区域（左下角为原点）
            GLES20.glViewport(0, 0, width, height);

            // Simple orthographic projection, with (0,0) in lower-left corner.
            // 📐 设置正交投影矩阵，左下角为原点
            // 💡 mDisplayProjectionMatrix：正交投影矩阵（16个float）
            // 💡 作用：将世界坐标映射到屏幕坐标，保持物体大小不变
            // 💡 参数：左=0, 右=width, 下=0, 上=height, 近=-1, 远=1
            Matrix.orthoM(mDisplayProjectionMatrix, 0, 0, width, 0, height, -1, 1);

            // 💡 smallDim：窗口的较短边尺寸
            // 🔍 为什么计算：需要以较短边为基准计算缩放，保证任何屏幕比例下效果一致
            // 💡 作用：用于计算三角形和矩形的缩放比例
            // ⏰ 使用时机：设置mTri.setScale()和mRect.setScale()时
            int smallDim = Math.min(width, height);

            // Set initial shape size / position / velocity based on window size.  Movement
            // has the same "feel" on all devices, but the actual path will vary depending
            // on the screen proportions.  We do it here, rather than defining fixed values
            // and tweaking the projection matrix, so that our squares are square.
            // 🔺 根据窗口尺寸设置动画对象的大小、位置和速度
            // 📝 设置三角形颜色为绿色
            mTri.setColor(0.1f, 0.9f, 0.1f);
            // 📝 设置三角形纹理为精细纹理
            mTri.setTexture(mFineTexture);
            // 📝 设置三角形缩放为窗口短边的1/3
            mTri.setScale(smallDim / 3.0f, smallDim / 3.0f);
            // 📝 设置三角形位置为窗口中心
            mTri.setPosition(width / 2.0f, height / 2.0f);
            // 📝 设置矩形颜色为红色
            mRect.setColor(0.9f, 0.1f, 0.1f);
            // 📝 设置矩形纹理为粗糙纹理
            mRect.setTexture(mCoarseTexture);
            // 📝 设置矩形缩放为窗口短边的1/5
            mRect.setScale(smallDim / 5.0f, smallDim / 5.0f);
            // 📝 设置矩形位置为窗口中心
            mRect.setPosition(width / 2.0f, height / 2.0f);
            // 💡 mRectVelX/mRectVelY：矩形的X/Y方向速度（像素/秒）
            // 🔍 为什么定义：控制弹跳矩形的移动方向和速度
            // 💡 作用：基于窗口短边计算，确保在不同屏幕尺寸下动画效果一致
            // ⏰ 使用时机：在update()方法中每帧更新位置
            mRectVelX = 1 + smallDim / 4.0f;
            mRectVelY = 1 + smallDim / 5.0f;

            // 📦 设置四个边框的位置和大小
            // 💡 edgeWidth：边框宽度（像素）
            // 🔍 为什么定义：需要定义弹跳区域的边界宽度
            // 💡 作用：设置边框精灵的大小，同时用于计算mInnerLeft/Right/Top/Bottom
            // ⏰ 使用时机：设置四个mEdges[]精灵的大小和位置，以及内部边界
            float edgeWidth = 1 + width / 64.0f;
            // 📝 左边框：灰色，宽度edgeWidth，高度等于窗口高度
            mEdges[0].setColor(0.5f, 0.5f, 0.5f);
            mEdges[0].setScale(edgeWidth, height);
            mEdges[0].setPosition(edgeWidth / 2.0f, height / 2.0f);
            // right edge
            // 📝 右边框：灰色，位置在窗口右边缘
            mEdges[1].setColor(0.5f, 0.5f, 0.5f);
            mEdges[1].setScale(edgeWidth, height);
            mEdges[1].setPosition(width - edgeWidth / 2.0f, height / 2.0f);
            // top edge
            // 📝 顶部边框：灰色，宽度等于窗口宽度
            mEdges[2].setColor(0.5f, 0.5f, 0.5f);
            mEdges[2].setScale(width, edgeWidth);
            mEdges[2].setPosition(width / 2.0f, height - edgeWidth / 2.0f);
            // bottom edge
            // 📝 底部边框：灰色，位置在窗口底边缘
            mEdges[3].setColor(0.5f, 0.5f, 0.5f);
            mEdges[3].setScale(width, edgeWidth);
            mEdges[3].setPosition(width / 2.0f, edgeWidth / 2.0f);

            // Inner bounding rect, used to bounce objects off the walls.
            // 📦 内部边界矩形，用于弹跳检测
            // 💡 mInnerLeft/mInnerBottom：内部区域左下角坐标
            // 💡 作用：定义矩形可以活动的最小边界
            mInnerLeft = mInnerBottom = edgeWidth;
            // 💡 mInnerRight/mInnerTop：内部区域右上角坐标
            // 💡 作用：定义矩形可以活动的最大边界
            mInnerRight = width - 1 - edgeWidth;
            mInnerTop = height - 1 - edgeWidth;

            // 📝 打印动画对象状态用于调试
            Log.d(TAG, "mTri: " + mTri);
            Log.d(TAG, "mRect: " + mRect);
        }

        /**
         * Releases most of the GL resources we currently hold.
         * <p>
         * Does not release EglCore.
         *
         * 🧹 释放大部分GL资源（不包括EglCore）
         */
        private void releaseGl() {
            // 📝 检查GL错误，标记释放操作开始
            GlUtil.checkGlError("releaseGl start");

            // 🪟 释放窗口Surface
            if (mWindowSurface != null) {
                // 📝 释放EGL窗口Surface资源
                mWindowSurface.release();
                mWindowSurface = null;
            }
            // 🖌️ 释放着色器程序
            if (mFlatProgram != null) {
                // 📝 释放平面着色程序资源
                mFlatProgram.release();
                mFlatProgram = null;
            }
            if (mTexProgram != null) {
                // 📝 释放纹理着色程序资源
                mTexProgram.release();
                mTexProgram = null;
            }
            // 📝 再次检查GL错误，确认释放完成
            GlUtil.checkGlError("releaseGl done");

            // 🔌 解绑当前上下文
            // 📝 取消EGL上下文绑定，确保资源完全释放
            mEglCore.makeNothingCurrent();
        }

        /**
         * Sets whether we use textures or flat shading.
         *
         * ☑️ 设置是否使用平面着色
         */
        private void setFlatShading(boolean useFlatShading) {
            // ☑️ mUseFlatShading：是否使用平面着色模式
            // 🔍 为什么更新：用户通过复选框切换了着色模式
            // 💡 作用：决定draw()中使用FlatShadedProgram还是Texture2dProgram
            // ⏰ 使用时机：在draw()方法中根据此值选择着色程序
            mUseFlatShading = useFlatShading;
        }

        /**
         * Handles the frame update.  Runs when Choreographer signals.
         *
         * 🎯 处理帧更新（在Choreographer触发时运行）
         * 更新动画状态，检查是否需要丢帧，绘制并交换缓冲
         */
        private void doFrame(long timeStampNanos) {
            //Log.d(TAG, "doFrame " + timeStampNanos);

            // If we're not keeping up 60fps -- maybe something in the system is busy, maybe
            // recording is too expensive, maybe the CPU frequency governor thinks we're
            // not doing and wants to drop the clock frequencies -- we need to drop frames
            // to catch up.  The "timeStampNanos" value is based on the system monotonic
            // clock, as is System.nanoTime(), so we can compare the values directly.
            //
            // Our clumsy collision detection isn't sophisticated enough to deal with large
            // time gaps, but it's nearly cost-free, so we go ahead and do the computation
            // either way.
            //
            // We can reduce the overhead of recording, as well as the size of the movie,
            // by recording at ~30fps instead of the display refresh rate.  As a quick hack
            // we just record every-other frame, using a "recorded previous" flag.
            // ⚡ 如果跟不上60fps，需要丢帧追赶

            // 🔄 更新动画状态
            // 📝 调用update()更新三角形旋转和矩形弹跳位置
            update(timeStampNanos);

            // 💡 diff：当前系统时间与帧时间戳的差值（毫秒）
            // 🔍 为什么计算：判断渲染是否滞后于vsync，决定是否需要丢帧
            // 💡 作用：如果超过15ms说明系统繁忙，应跳过本帧追赶进度
            // ⏰ 使用时机：每帧检查，超过阈值则直接return跳过渲染
            long diff = (System.nanoTime() - timeStampNanos) / 1000000;
            if (diff > 15) {
                // too much, drop a frame
                // ⏱️ 超过15ms阈值，丢帧
                // 📝 超过15ms说明系统繁忙，跳过本帧以追赶进度
                Log.d(TAG, "diff is " + diff + ", skipping render");
                return;
            }

            // 🎨 绘制场景并交换缓冲
            // 📝 调用draw()渲染三角形、矩形和边框
            draw();
            // 📝 交换前后缓冲区，将渲染结果显示到屏幕
            mWindowSurface.swapBuffers();
        }

        /**
         * Advances animation state.
         *
         * We use the time delta from the previous event to determine how far everything
         * moves.  Ideally this will yield identical animation sequences regardless of
         * the device's actual refresh rate.
         *
         * 🔄 根据时间差更新动画状态
         * 使用时间差确保不同刷新率设备动画效果一致
         */
        private void update(long timeStampNanos) {
            // Compute time from previous frame.
            // ⏱️ 计算与上一帧的时间差
            // 💡 intervalNanos：当前帧与上一帧的时间间隔（纳秒）
            // 🔍 为什么计算：需要基于时间差而非帧数来更新动画，确保不同刷新率下效果一致
            // 💡 作用：用于计算旋转角度增量和矩形位移量
            // ⏰ 使用时机：在计算angleDelta和矩形位移时使用
            long intervalNanos;
            if (mPrevTimeNanos == 0) {
                // 📝 第一帧没有上一帧时间，间隔设为0
                intervalNanos = 0;
            } else {
                // 📝 计算与上一帧的时间差
                intervalNanos = timeStampNanos - mPrevTimeNanos;

                final long ONE_SECOND_NANOS = 1000000000L;
                if (intervalNanos > ONE_SECOND_NANOS) {
                    // A gap this big should only happen if something paused us.  We can
                    // either cap the delta at one second, or just pretend like this is
                    // the first frame and not advance at all.
                    // ⚠️ 时间差过大时重置（可能被暂停）
                    // 📝 超过1秒的时间差说明被暂停过，重置为0避免动画跳跃
                    Log.d(TAG, "Time delta too large: " +
                            (double) intervalNanos / ONE_SECOND_NANOS + " sec");
                    intervalNanos = 0;
                }
            }
            // 📝 保存当前时间戳，供下一帧计算间隔
            mPrevTimeNanos = timeStampNanos;

            // 💡 ONE_BILLION_F：十亿（1秒的纳秒数）
            // 🔍 为什么定义：需要将纳秒转换为秒来计算位移
            // 💡 作用：作为除数将纳秒时间差转换为秒
            // ⏰ 使用时机：计算elapsedSeconds时使用
            final float ONE_BILLION_F = 1000000000.0f;
            // 💡 elapsedSeconds：经过的秒数
            // 🔍 为什么计算：动画需要基于秒数计算位移（速度单位是像素/秒）
            // 💡 作用：用于计算三角形旋转角度和矩形位移
            // ⏰ 使用时机：计算angleDelta和xpos/ypos位移时使用
            final float elapsedSeconds = intervalNanos / ONE_BILLION_F;

            // Spin the triangle.  We want one full 360-degree rotation every 3 seconds,
            // or 120 degrees per second.
            // 🔺 旋转三角形（每3秒转一圈，120度/秒）
            // 💡 SECS_PER_SPIN：每圈旋转秒数
            // 💡 作用：控制三角形旋转速度
            final int SECS_PER_SPIN = 3;
            // 💡 angleDelta：本帧应旋转的角度增量（度）
            // 🔍 为什么计算：需要基于时间差旋转，确保不同刷新率下速度一致
            // 💡 作用：累加到当前旋转角度上，实现每3秒转一圈
            // ⏰ 使用时机：立即用于mTri.setRotation()
            float angleDelta = (360.0f / SECS_PER_SPIN) * elapsedSeconds;
            // 📝 累加旋转角度
            mTri.setRotation(mTri.getRotation() + angleDelta);

            // Bounce the rect around the screen.  The rect is a 1x1 square scaled up to NxN.
            // We don't do fancy collision detection, so it's possible for the box to slightly
            // overlap the edges.  We draw the edges last, so it's not noticeable.
            // 🟥 更新矩形位置并处理边界碰撞
            // 💡 xpos/ypos：矩形当前的X/Y坐标
            // 🔍 为什么获取：需要基于当前位置计算新位置并检测碰撞
            // 💡 作用：存储矩形当前位置，用于位移计算和碰撞检测
            // ⏰ 使用时机：立即用于计算新位置和碰撞判断
            float xpos = mRect.getPositionX();
            float ypos = mRect.getPositionY();
            // 💡 xscale/yscale：矩形的X/Y缩放值（实际像素尺寸）
            // 🔍 为什么获取：碰撞检测需要知道矩形的实际半宽/半高
            // 💡 作用：用于计算矩形的边缘位置（xpos ± xscale/2）
            // ⏰ 使用时机：在碰撞检测if条件中判断是否越界
            float xscale = mRect.getScaleX();
            float yscale = mRect.getScaleY();
            // 📝 根据速度和时间差计算新位置
            xpos += mRectVelX * elapsedSeconds;
            ypos += mRectVelY * elapsedSeconds;
            // 🔄 碰撞检测并反弹
            // 📝 检测左右边界碰撞，反转X速度
            if ((mRectVelX < 0 && xpos - xscale/2 < mInnerLeft) ||
                    (mRectVelX > 0 && xpos + xscale/2 > mInnerRight+1)) {
                mRectVelX = -mRectVelX;
            }
            // 📝 检测上下边界碰撞，反转Y速度
            if ((mRectVelY < 0 && ypos - yscale/2 < mInnerBottom) ||
                    (mRectVelY > 0 && ypos + yscale/2 > mInnerTop+1)) {
                mRectVelY = -mRectVelY;
            }
            // 📝 更新矩形位置
            mRect.setPosition(xpos, ypos);
        }

        /**
         * Draws the scene.
         *
         * 🎨 绘制场景：清除背景，绘制三角形、矩形和边框
         */
        private void draw() {
            // 📝 检查GL错误，标记绘制操作开始
            GlUtil.checkGlError("draw start");

            // Clear to a non-black color to make the content easily differentiable from
            // the pillar-/letter-boxing.
            // 🎨 清除为灰色背景，便于区分letterbox区域
            // 📝 设置清除颜色为灰色（0.2, 0.2, 0.2）
            GLES20.glClearColor(0.2f, 0.2f, 0.2f, 1.0f);
            // 📝 清除颜色缓冲区
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            // Textures may include alpha, so turn blending on.
            // 🔀 启用混合（纹理可能包含alpha通道）
            // 📝 启用alpha混合，支持半透明纹理渲染
            GLES20.glEnable(GLES20.GL_BLEND);
            // 📝 设置混合函数：源alpha预乘，目标为1-alpha
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            // 🔺 根据着色模式绘制三角形和矩形
            // 💡 mUseFlatShading：是否使用平面着色模式
            // 💡 true：使用FlatShadedProgram（纯色绘制，无纹理）
            // 💡 false：使用Texture2dProgram（带纹理绘制）
            if (mUseFlatShading) {
                // 📝 使用平面着色程序绘制（纯色，无纹理）
                mTri.draw(mFlatProgram, mDisplayProjectionMatrix);
                mRect.draw(mFlatProgram, mDisplayProjectionMatrix);
            } else {
                // 📝 使用纹理着色程序绘制（带纹理）
                mTri.draw(mTexProgram, mDisplayProjectionMatrix);
                mRect.draw(mTexProgram, mDisplayProjectionMatrix);
            }
            // 📝 禁用混合（边框不需要透明效果）
            GLES20.glDisable(GLES20.GL_BLEND);

            // 📦 绘制四个边框
            // 📝 循环绘制左右上下四个边框精灵
            for (int i = 0; i < 4; i++) {
                mEdges[i].draw(mFlatProgram, mDisplayProjectionMatrix);
            }

            // 📝 检查GL错误，标记绘制操作完成
            GlUtil.checkGlError("draw done");
        }
    }


    /**
     * Handler for RenderThread.  Used for messages sent from the UI thread to the render thread.
     * <p>
     * The object is created on the render thread, and the various "send" methods are called
     * from the UI thread.
     *
     * 📬 渲染线程Handler，处理从UI线程发送的消息
     * 在渲染线程创建，send方法在UI线程调用
     */
    private static class RenderHandler extends Handler {
        // 📨 消息类型常量
        private static final int MSG_SURFACE_CREATED = 0;   // Surface创建
        private static final int MSG_SURFACE_CHANGED = 1;   // Surface变化
        private static final int MSG_DO_FRAME = 2;          // 帧绘制
        private static final int MSG_FLAT_SHADING = 3;      // 平面着色设置
        private static final int MSG_SHUTDOWN = 5;          // 关闭

        // This shouldn't need to be a weak ref, since we'll go away when the Looper quits,
        // but no real harm in it.
        // 🔗 对渲染线程的弱引用
        private WeakReference<RenderThread> mWeakRenderThread;

        /**
         * Call from render thread.
         *
         * 🏗️ 构造函数（在渲染线程调用）
         */
        public RenderHandler(RenderThread rt) {
            mWeakRenderThread = new WeakReference<RenderThread>(rt);
        }

        /**
         * Sends the "surface created" message.
         * <p>
         * Call from UI thread.
         *
         * 📤 发送Surface创建消息（UI线程调用）
         */
        public void sendSurfaceCreated() {
            sendMessage(obtainMessage(MSG_SURFACE_CREATED));
        }

        /**
         * Sends the "surface changed" message, forwarding what we got from the SurfaceHolder.
         * <p>
         * Call from UI thread.
         *
         * 📤 发送Surface变化消息（忽略format参数）
         */
        public void sendSurfaceChanged(@SuppressWarnings("unused") int format, int width,
                int height) {
            // ignore format
            sendMessage(obtainMessage(MSG_SURFACE_CHANGED, width, height));
        }

        /**
         * Sends the "do frame" message, forwarding the Choreographer event.
         * <p>
         * Call from UI thread.
         *
         * 📤 发送帧绘制消息，转发Choreographer事件
         */
        public void sendDoFrame(long frameTimeNanos) {
            sendMessage(obtainMessage(MSG_DO_FRAME,
                    (int) (frameTimeNanos >> 32), (int) frameTimeNanos));
        }

        /**
         * Sends a new value for the "flat shaded" boolean.
         *
         * 📤 发送平面着色设置消息
         */
        public void sendSetFlatShading(boolean useFlatShading) {
            // ignore format
            sendMessage(obtainMessage(MSG_FLAT_SHADING, useFlatShading ? 1:0, 0));
        }

        /**
         * Sends the "shutdown" message, which tells the render thread to halt.
         * <p>
         * Call from UI thread.
         *
         * 📤 发送关闭消息（UI线程调用）
         */
        public void sendShutdown() {
            sendMessage(obtainMessage(RenderHandler.MSG_SHUTDOWN));
        }

        @Override  // runs on RenderThread
        // 🔄 在渲染线程处理消息
        public void handleMessage(Message msg) {
            // 💡 what：消息类型标识
            // 💡 作用：根据消息类型分发到不同的处理逻辑
            int what = msg.what;
            //Log.d(TAG, "RenderHandler [" + this + "]: what=" + what);

            // 💡 renderThread：从弱引用获取渲染线程实例
            // 💡 作用：避免Handler持有强引用导致内存泄漏
            RenderThread renderThread = mWeakRenderThread.get();
            if (renderThread == null) {
                // 📝 弱引用已被回收，记录警告并返回
                Log.w(TAG, "RenderHandler.handleMessage: weak ref is null");
                return;
            }

            // 🎯 根据消息类型分发处理
            switch (what) {
                case MSG_SURFACE_CREATED:
                    // 📝 处理Surface创建消息
                    renderThread.surfaceCreated();
                    break;
                case MSG_SURFACE_CHANGED:
                    // 📝 处理Surface变化消息，arg1=width, arg2=height
                    renderThread.surfaceChanged(msg.arg1, msg.arg2);
                    break;
                case MSG_DO_FRAME:
                    // 🔢 从消息参数重建时间戳
                    // 📝 将拆分的高低32位重新组合成64位时间戳
                    // 💡 timestamp：帧时间戳（纳秒级），由msg.arg1（高32位）和msg.arg2（低32位）组成
                    // 🔍 为什么拆分：Handler只能传递int参数，64位long需要拆成两个int
                    // 💡 作用：传递给doFrame()用于动画时间计算
                    // ⏰ 使用时机：立即传给renderThread.doFrame()
                    long timestamp = (((long) msg.arg1) << 32) |
                                     (((long) msg.arg2) & 0xffffffffL);
                    renderThread.doFrame(timestamp);
                    break;
                case MSG_FLAT_SHADING:
                    // 📝 处理平面着色设置，arg1!=0表示启用
                    renderThread.setFlatShading(msg.arg1 != 0);
                    break;
                case MSG_SHUTDOWN:
                    // 📝 处理关闭消息，退出Looper循环
                    renderThread.shutdown();
                    break;
               default:
                    // 📝 未知消息类型，抛出异常
                    throw new RuntimeException("unknown message " + what);
            }
        }
    }
}
