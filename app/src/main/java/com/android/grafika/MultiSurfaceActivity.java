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
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.os.Trace;

import com.android.grafika.gles.EglCore;
import com.android.grafika.gles.WindowSurface;
import com.google.grafika.R;

/**
 * 🎬 演示 SurfaceView 的一些不常用特性。
 * Exercises some less-commonly-used aspects of SurfaceView.  In particular:
 * <ul>
 * <li> We have three overlapping SurfaceViews.
 * 我们有三个重叠的 SurfaceView。
 * <li> One is at the default depth, one is at "media overlay" depth, and one is on top of the UI.
 * 一个在默认深度，一个在"媒体覆盖层"深度，一个在 UI 之上。
 * <li> One is marked "secure".
 * 其中一个被标记为"安全"。
 * </ul>
 * <p>
 * To watch this in systrace, use
 * <code>systrace.py --app=com.android.grafika gfx view sched dalvik</code>
 * (most interesting while bouncing).
 * 要在 systrace 中观察，请使用上述命令（弹跳动画时最有趣）。
 */
public class MultiSurfaceActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = MainActivity.TAG;

    // Number of steps in each direction.  There's actually N+1 positions because we
    // don't re-draw the same position after a rebound.
    // 🎯 每个方向的步数。实际上有 N+1 个位置，因为反弹后不会重新绘制相同位置。
    private static final int BOUNCE_STEPS = 30;

    // 🖼️ 三个 SurfaceView 引用
    private SurfaceView mSurfaceView1;
    private SurfaceView mSurfaceView2;
    private SurfaceView mSurfaceView3;
    private volatile boolean mBouncing;  // 🔀 弹跳动画开关（多线程可见）
    private Thread mBounceThread;        // 🧵 弹跳动画线程

    /**
     * 🚀 Activity 创建时初始化三个重叠的 SurfaceView（共28行）。
     *    设置不同的层级深度、透明度和安全标志。
     * 🔧 为什么：Activity生命周期入口，必须初始化所有UI组件
     * 📍 时机：系统首次创建Activity时自动调用
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState); // 📞 调用父类onCreate，完成系统级初始化
        // 🖼️ 加载布局文件，包含三个重叠的SurfaceView控件
        setContentView(R.layout.activity_multi_surface_test); // 📞 设置Activity内容视图

        // #1 is at the bottom; mark it as secure just for fun.  By default, this will use
        // the RGB565 color format.
        // 🔒 #1 在最底层；标记为安全模式（仅供演示）。默认使用 RGB565 颜色格式。
        // 🖼️ mSurfaceView1：最底层的SurfaceView，默认深度，安全标记
        // 📌 为什么：作为三层中的最底层，展示默认SurfaceView行为
        // 💡 作用：承载基础绘制内容，安全标记防止截屏/录屏
        // ⏰ 使用时机：初始化后在surfaceChanged中绘制圆圈
        mSurfaceView1 = (SurfaceView) findViewById(R.id.multiSurfaceView1); // 📞 通过ID获取最底层SurfaceView
        mSurfaceView1.getHolder().addCallback(this); // 📞 注册Surface生命周期回调（surfaceCreated/surfaceChanged等）
        mSurfaceView1.setSecure(true); // 🔒 设置安全标记，防止内容被截屏或录屏

        // #2 is above it, in the "media overlay"; must be translucent or we will totally
        // obscure #1 and it will be ignored by the compositor.  The addition of the alpha
        // plane should switch us to RGBA8888.
        // 🎭 #2 在其上方，处于"媒体覆盖层"；必须设置为半透明，否则会完全遮挡 #1。
        // 添加 alpha 通道后会自动切换到 RGBA8888 格式。
        // 🖼️ mSurfaceView2：中间层的SurfaceView，媒体覆盖层深度
        // 📌 为什么：展示媒体覆盖层特性，半透明使其与底层叠加显示
        // 💡 作用：在媒体覆盖层绘制弹跳动画，半透明允许底层内容透出
        // ⏰ 使用时机：初始化后在startBouncing中绘制弹跳圆圈
        mSurfaceView2 = (SurfaceView) findViewById(R.id.multiSurfaceView2); // 📞 通过ID获取中间层SurfaceView
        mSurfaceView2.getHolder().addCallback(this); // 📞 注册Surface生命周期回调
        mSurfaceView2.getHolder().setFormat(PixelFormat.TRANSLUCENT); // 🎭 设置像素格式为半透明（含Alpha通道）
        mSurfaceView2.setZOrderMediaOverlay(true); // ⬆️ 设置为媒体覆盖层深度（在默认层之上，UI之下）

        // #3 is above everything, including the UI.  Also translucent.
        // ⬆️ #3 在最顶层，包括 UI 之上。同样设置为半透明。
        // 🖼️ mSurfaceView3：最顶层的SurfaceView，在所有UI之上
        // 📌 为什么：展示在UI之上的SurfaceView特性
        // 💡 作用：绘制alpha条纹，覆盖在所有内容之上
        // ⏰ 使用时机：初始化后在surfaceChanged中绘制半透明矩形条纹
        mSurfaceView3 = (SurfaceView) findViewById(R.id.multiSurfaceView3); // 📞 通过ID获取最顶层SurfaceView
        mSurfaceView3.getHolder().addCallback(this); // 📞 注册Surface生命周期回调
        mSurfaceView3.getHolder().setFormat(PixelFormat.TRANSLUCENT); // 🎭 设置像素格式为半透明
        mSurfaceView3.setZOrderOnTop(true); // ⬆️ 设置为最顶层深度（在所有UI控件之上）
    }

    /**
     * ⏸️ Activity 暂停时停止弹跳动画（共6行）。
     * 🔧 为什么：Activity暂停后不应继续占用CPU绘制
     * 📍 时机：用户离开Activity或按下Home键时系统自动调用
     */
    @Override
    protected void onPause() {
        super.onPause(); // 📞 调用父类onPause，完成系统级暂停处理
        if (mBounceThread != null) { // 🔍 检查是否有弹跳线程正在运行
            stopBouncing(); // 🛑 停止弹跳动画并等待线程结束
        }
    }

    /**
     * onClick handler for "bounce" button.
     * 🎯 "弹跳"按钮的点击处理，切换弹跳动画的开关状态（共8行）。
     * 🔧 为什么：用户需要通过按钮控制弹跳动画的启动/停止
     * 📍 时机：用户点击"Bounce"按钮时由Android系统调用
     */
    public void clickBounce(@SuppressWarnings("unused") View unused) {
        Log.d(TAG, "clickBounce bouncing=" + mBouncing); // 📝 记录当前弹跳状态，便于调试
        if (mBounceThread != null) { // 🔍 检查弹跳线程是否存在（存在即表示正在运行）
            stopBouncing(); // 🛑 停止弹跳动画，释放线程资源
        } else { // ▶️ 没有运行中的线程
            startBouncing(); // 🚀 启动弹跳动画线程
        }
    }

    /**
     * 🚀 启动弹跳动画线程，在 mSurfaceView2 上绘制弹跳圆圈。（共32行，需逐行注释）
     * 启动一个新线程循环绘制弹跳圆圈，计算并记录 FPS。
     * 🔧 为什么：演示SurfaceView的动态绘制能力
     * 📍 时机：用户点击"Bounce"按钮时调用
     */
    private void startBouncing() {
        // 🖼️ surface：mSurfaceView2的绘图表面
        // 🔍 为什么：需要Surface引用来在Canvas上绘制动画
        final Surface surface = mSurfaceView2.getHolder().getSurface(); // 📞 获取SurfaceView底层的Surface对象
        if (surface == null || !surface.isValid()) { // 🔍 检查Surface是否可用
            Log.w(TAG, "mSurfaceView2 is not ready"); // ⚠️ Surface未就绪，无法绘制
            return; // 🛑 提前返回，避免空指针异常
        }
        // 🧵 创建匿名线程，执行弹跳动画循环
        mBounceThread = new Thread() {
            @Override
            public void run() { // 🏃 线程执行入口
                while (true) { // 🔄 无限循环，直到mBouncing变为false
                    // ⏱️ startWhen：记录本轮循环开始的时间戳
                    // 🔍 为什么：用于计算本轮所有帧的总耗时，从而得出FPS
                    long startWhen = System.nanoTime(); // 📊 获取当前纳秒级时间戳
                    // ➡️ 向前弹跳：从位置0移动到BOUNCE_STEPS
                    for (int i = 0; i < BOUNCE_STEPS; i++) { // 🔄 i：当前步数，决定圆圈位置
                        if (!mBouncing) return; // 🔍 检查取消标志，用户停止时立即退出
                        drawBouncingCircle(surface, i); // 🎨 根据步数i绘制当前位置的圆圈
                    }
                    // ⬅️ 向后弹跳：从BOUNCE_STEPS移动回位置0
                    for (int i = BOUNCE_STEPS; i > 0; i--) { // 🔄 i：从最大步数递减
                        if (!mBouncing) return; // 🔍 检查取消标志
                        drawBouncingCircle(surface, i); // 🎨 绘制回弹动画
                    }
                    // ⏱️ duration：本轮2*BOUNCE_STEPS帧的总耗时（纳秒）
                    long duration = System.nanoTime() - startWhen; // 📊 计算本轮循环耗时
                    // 📊 framesPerSec：计算得到的帧率
                    // 🔧 为什么：用于性能监控，观察SurfaceView绘制效率
                    double framesPerSec = 1000000000.0 / (duration / (BOUNCE_STEPS * 2.0)); // 🧮 FPS = 10^9 / (总耗时/总帧数)
                    Log.d(TAG, "Bouncing at " + framesPerSec + " fps"); // 📝 记录帧率到日志
                }
            }
        };
        mBouncing = true; // ✅ 设置运行标志为true，线程循环继续执行
        mBounceThread.setName("Bouncer"); // 🏷️ 设置线程名称，便于调试和systrace分析
        mBounceThread.start(); // ▶️ 启动弹跳动画线程
    }

    /**
     * Signals the bounce-thread to stop, and waits for it to do so.
     * 🛑 通知弹跳线程停止，并等待其结束。
     * 🔧 为什么：安全地停止动画线程，避免内存泄漏和状态不一致
     * 📍 时机：用户再次点击"Bounce"按钮或Activity暂停时调用
     */
    private void stopBouncing() {
        Log.d(TAG, "Stopping bounce thread"); // 📝 记录停止操作，便于调试追踪
        mBouncing = false;      // tell thread to stop
                               // 🚩 设置运行标志为false，通知线程循环退出
                               // 📌 为什么：线程循环中会检查此标志，为false时return退出
                               // 💡 作用：优雅地终止线程，而非强制中断
                               // ⏰ 使用时机：需要停止动画时立即设置
        try {
            mBounceThread.join(); // ⏳ 阻塞当前线程，等待弹跳线程执行完毕
                                  // 📌 为什么：确保线程完全退出后再清理引用
                                  // 💡 作用：防止线程还在运行时就置空引用导致问题
                                  // ⏰ 使用时机：设置停止标志后立即调用
        } catch (InterruptedException ignored) {} // 🔇 忽略中断异常（通常不会发生）
        mBounceThread = null; // 🧹 清空线程引用，允许GC回收
                              // 📌 为什么：避免持有已结束线程的引用
                              // 💡 作用：释放内存，标记动画已完全停止
                              // ⏰ 使用时机：join返回后（线程已结束）
    }

    /**
     * Returns an ordinal value for the SurfaceHolder, or -1 for an invalid surface.
     * 🔢 返回 SurfaceHolder 的编号标识（1/2/3），无效则返回 -1。
     * 🔧 为什么：SurfaceHolder.Callback的回调不区分是哪个SurfaceView，需要自行判断
     * 📍 时机：在surfaceCreated/surfaceChanged/surfaceDestroyed中调用
     */
    private int getSurfaceId(SurfaceHolder holder) {
        // 📦 holder：传入的SurfaceHolder对象，来自SurfaceHolder.Callback回调
        // 📌 为什么：需要知道回调来自哪个SurfaceView
        // 💡 作用：通过equals比较确定是三个SurfaceView中的哪一个
        // ⏰ 使用时机：每次Surface状态变化回调时都会用到
        if (holder.equals(mSurfaceView1.getHolder())) { // 🔍 检查是否是第一个SurfaceView
            return 1; // ✅ 返回编号1（默认层/最底层）
        } else if (holder.equals(mSurfaceView2.getHolder())) { // 🔍 检查是否是第二个SurfaceView
            return 2; // ✅ 返回编号2（媒体覆盖层/中间层）
        } else if (holder.equals(mSurfaceView3.getHolder())) { // 🔍 检查是否是第三个SurfaceView
            return 3; // ✅ 返回编号3（最顶层/UI之上）
        } else {
            return -1; // ❌ 未知的SurfaceHolder，返回-1表示无效
        }
    }

    /**
     * SurfaceHolder.Callback method
     * 🎬 Surface 创建时的回调（共9行，需逐行注释）。
     *    记录哪个SurfaceView的Surface被创建了。
     * 🔧 为什么：系统在SurfaceView的Surface首次可用时回调，需要记录以便后续绘制
     * 📍 时机：SurfaceView首次显示或从隐藏状态恢复时由系统自动调用
     */
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        // 🔢 id：SurfaceView编号（1/2/3），用于区分哪个SurfaceView
        // 📌 为什么：回调不区分来源，需要通过holder判断
        // 💡 作用：确定是三个重叠SurfaceView中的哪一个被创建
        // ⏰ 使用时机：在日志输出中标识Surface来源
        int id = getSurfaceId(holder); // 📞 根据holder判断是哪个SurfaceView
        if (id < 0) { // ❌ 未知的SurfaceHolder
            Log.w(TAG, "surfaceCreated UNKNOWN holder=" + holder); // ⚠️ 警告日志：未识别的holder
        } else { // ✅ 已识别的SurfaceHolder
            Log.d(TAG, "surfaceCreated #" + id + " holder=" + holder); // 📝 记录Surface创建信息

        }
    }

    /**
     * SurfaceHolder.Callback method
     * <p>
     * Draws when the surface changes.  Since nothing else is touching the surface, and
     * we're not animating, we just draw here and ignore it.
     * 🎨 Surface 尺寸变化时的回调（共42行，需逐行注释）。
     *    根据不同的 Surface 层级绘制不同内容：
     *   #1 默认层：在左/上位置画圆
     *   #2 媒体覆盖层：在右/下位置画圆
     *   #3 顶层：绘制 alpha 条纹
     * 🔧 为什么：Surface尺寸确定后才能正确绘制内容
     * 📍 时机：Surface创建或尺寸变化时由系统回调
     */
    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { // 📐 format：像素格式；width/height：新尺寸
        Log.d(TAG, "surfaceChanged fmt=" + format + " size=" + width + "x" + height +
                " holder=" + holder); // 📝 记录Surface变化信息

        // 🔢 id：SurfaceView编号（1/2/3），用于区分不同层级
        int id = getSurfaceId(holder); // 📞 根据holder判断是哪个SurfaceView
        // 📐 portrait：是否竖屏模式
        // 🔍 为什么：竖屏和横屏的绘制位置不同
        boolean portrait = height > width; // 🧮 高度大于宽度则为竖屏
        // 🖼️ surface：从holder获取的Surface对象，用于绘图
        Surface surface = holder.getSurface(); // 📞 获取绘图Surface

        switch (id) { // 🔀 根据SurfaceView编号执行不同的绘制逻辑
            case 1:
                // default layer: circle on left / top
                // 🔵 默认层：在左上位置绘制圆圈
                if (portrait) { // 📱 竖屏模式
                    drawCircleSurface(surface, width / 2, height / 4, width / 4); // 🎨 圆心在水平中心、高度1/4处
                } else { // 📱 横屏模式
                    drawCircleSurface(surface, width / 4, height / 2, height / 4); // 🎨 圆心在宽度1/4、垂直中心处
                }
                break; // ✅ case 1结束
            case 2:
                // media overlay layer: circle on right / bottom
                // 🟢 媒体覆盖层：在右下位置绘制圆圈
                if (portrait) { // 📱 竖屏模式
                    drawCircleSurface(surface, width / 2, height * 3 / 4, width / 4); // 🎨 圆心在水平中心、高度3/4处
                } else { // 📱 横屏模式
                    drawCircleSurface(surface, width * 3 / 4, height / 2, height / 4); // 🎨 圆心在宽度3/4、垂直中心处
                }
                break; // ✅ case 2结束
            case 3:
                // top layer: alpha stripes
                // 🔲 顶层：绘制半透明条纹
                if (portrait) { // 📱 竖屏模式
                    // 📐 halfLine：条纹半宽度，width/16 + 1确保至少1像素
                    int halfLine = width / 16 + 1; // 🧮 计算条纹半宽
                    drawRectSurface(surface, width/2 - halfLine, 0, halfLine*2, height); // 🎨 绘制垂直条纹（居中）
                } else { // 📱 横屏模式
                    // 📐 halfLine：条纹半高度
                    int halfLine = height / 16 + 1; // 🧮 计算条纹半高
                    drawRectSurface(surface, 0, height/2 - halfLine, width, halfLine*2); // 🎨 绘制水平条纹（居中）
                }
                break; // ✅ case 3结束
            default: // ❌ 未知的SurfaceView编号
                throw new RuntimeException("wha?"); // 💥 不应出现的情况
        }
    } // ✅ surfaceChanged结束

    /**
     * 💥 Surface 销毁时的回调（共6行，需逐行注释）。
     *    记录Surface销毁事件，当前不做额外清理。
     * 🔧 为什么：系统在SurfaceView的Surface即将销毁时回调，需要记录事件
     * 📍 时机：SurfaceView从窗口移除或Activity销毁时由系统自动调用
     */
    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        // 📝 ignore：忽略销毁事件，不执行额外操作
        // 🔍 为什么：本演示中不需要在销毁时清理资源（弹跳动画在onPause中停止）
        // 💡 作用：表明此处有意不做处理，非遗漏
        // ⏰ 使用时机：仅作占位，保持回调完整性

        // 📝 记录Surface销毁日志
        // 🔍 为什么：便于调试Surface生命周期，确认回调正常触发
        // 💡 作用：在Logcat输出销毁事件的holder信息
        // ⏰ 使用时机：方法入口立即打印
        Log.d(TAG, "Surface destroyed holder=" + holder);
    }

    /**
     * Clears the surface, then draws some alpha-blended rectangles with GL.
     * <p>
     * Creates a temporary EGL context just for the duration of the call.
     * 🎨 清空 Surface，然后用 GL 绘制 alpha 混合的矩形条纹（共50行，需逐行注释）。
     *    为本次调用创建临时的 EGL 上下文。
     * 🔧 为什么：演示OpenGL ES绘制半透明矩形，展示alpha混合效果
     * 📍 时机：surfaceChanged中case 3时调用
     */
    private void drawRectSurface(Surface surface, int left, int top, int width, int height) { // 📐 left/top：绘制区域左上角；width/height：区域尺寸
        // 🖥️ eglCore：临时EGL上下文管理器
        // 🔍 为什么：每次绘制需要独立的EGL上下文，避免状态污染
        EglCore eglCore = new EglCore(); // 📞 创建默认EGL上下文（无共享上下文）
        // 🪟 win：绑定到Surface的EGL窗口表面
        WindowSurface win = new WindowSurface(eglCore, surface, false); // 📞 创建窗口Surface，false表示不接管Surface生命周期
        win.makeCurrent(); // 🔧 将此窗口设为当前GL上下文，后续GL调用在此Surface上生效
        GLES20.glClearColor(0, 0, 0, 0); // 🎨 设置清屏颜色为全透明黑色（R=0, G=0, B=0, A=0）
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT); // 🧹 清除颜色缓冲区，使Surface变为全透明

        GLES20.glEnable(GLES20.GL_SCISSOR_TEST); // ✂️ 启用裁剪测试，只更新指定矩形区域
        for (int i = 0; i < 4; i++) { // 🔄 i：绘制4个不同的alpha混合矩形条纹
            // 📐 x, y, w, h：当前条纹的位置和尺寸
            int x, y, w, h; // 声明矩形位置和大小变量
            if (width < height) { // 📐 判断方向：宽度小于高度为竖向排列
                // vertical
                w = width / 4;         // 📐 每个条纹宽度为总宽度的1/4
                h = height;            // 📐 条纹高度等于总高度
                x = left + w * i;      // 📐 X坐标：从left开始，每条纹偏移1/4宽度
                y = top;               // 📐 Y坐标：从top开始
            } else { // 📐 横向排列
                // horizontal
                w = width;             // 📐 条纹宽度等于总宽度
                h = height / 4;        // 📐 每个条纹高度为总高度的1/4
                x = left;              // 📐 X坐标：从left开始
                y = top + h * i;       // 📐 Y坐标：从top开始，每条纹偏移1/4高度
            }
            GLES20.glScissor(x, y, w, h); // ✂️ 设置裁剪区域，只有此区域内的像素会被修改
            switch (i) { // 🔀 根据索引设置不同的颜色
                case 0:     // 50% blue at 25% alpha, pre-multiplied
                            // 🔵 50% 蓝色，25% alpha，预乘
                    GLES20.glClearColor(0.0f, 0.0f, 0.125f, 0.25f); // 🎨 R=0, G=0, B=0.5*0.25=0.125, A=0.25
                    break; // ✅ case 0结束
                case 1:     // 100% blue at 25% alpha, pre-multiplied
                            // 🔵 100% 蓝色，25% alpha，预乘
                    GLES20.glClearColor(0.0f, 0.0f, 0.25f, 0.25f); // 🎨 R=0, G=0, B=1.0*0.25=0.25, A=0.25
                    break; // ✅ case 1结束
                case 2:     // 200% blue at 25% alpha, pre-multiplied (should get clipped)
                            // 🔵 200% 蓝色，25% alpha，预乘（会被裁剪到 100%）
                    GLES20.glClearColor(0.0f, 0.0f, 0.5f, 0.25f); // 🎨 R=0, G=0, B=2.0*0.25=0.5, A=0.25（超出范围会被clamp）
                    break; // ✅ case 2结束
                case 3:     // 100% white at 25% alpha, pre-multiplied
                            // ⚪ 100% 白色，25% alpha，预乘
                    GLES20.glClearColor(0.25f, 0.25f, 0.25f, 0.25f); // 🎨 R=G=B=1.0*0.25=0.25, A=0.25
                    break; // ✅ case 3结束
            }
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT); // 🧹 用当前glClearColor填充裁剪区域
        }
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST); // ❌ 关闭裁剪测试，恢复全屏绘制

        win.swapBuffers(); // 🔄 交换前后缓冲区，将绘制结果提交到Surface显示
        win.release();     // 🧹 释放EGL窗口Surface资源
        eglCore.release(); // 🧹 释放EGL上下文资源
    } // ✅ drawRectSurface结束

    /**
     * Clears the surface, then draws a filled circle with a shadow.
     * <p>
     * The Canvas drawing we're doing may not be fully implemented for hardware-accelerated
     * renderers (shadow layers only supported for text).  However, Surface#lockCanvas()
     * currently only returns an unaccelerated Canvas, so it all comes out looking fine.
     * 🎨 清空 Surface，然后绘制一个带阴影的填充圆圈（共15行）。
     *    注意：Canvas 绘制可能不完全支持硬件加速渲染器（阴影层仅支持文本）。
     *    但 Surface#lockCanvas() 目前只返回软件 Canvas，所以效果正常。
     * 🔧 为什么：在SurfaceView的Surface上用Canvas 2D API绘制静态圆圈
     * 📍 时机：surfaceChanged回调中对#1和#2 SurfaceView调用
     */
    private void drawCircleSurface(Surface surface, int x, int y, int radius) { // 📐 x/y：圆心坐标；radius：半径
        // 🖌️ paint：绘制圆圈的画笔对象
        // 📌 为什么：需要配置抗锯齿、颜色、样式和阴影
        // 💡 作用：定义圆圈的视觉效果（白色填充+红色阴影）
        // ⏰ 使用时机：在canvas.drawCircle中传入
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); // 📞 创建抗锯齿画笔
        paint.setColor(Color.WHITE); // 🎨 设置画笔颜色为白色
        paint.setStyle(Paint.Style.FILL); // 🖌️ 设置填充样式（实心圆，非描边）
        paint.setShadowLayer(radius / 4 + 1, 0, 0, Color.RED); // 🌟 设置红色阴影层，偏移量为半径1/4+1

        // 🖼️ canvas：从Surface锁定的画布，用于2D绘制
        // 📌 为什么：Surface.lockCanvas()返回可绘制的Canvas对象
        // 💡 作用：在此Canvas上绘制内容后通过unlockCanvasAndPost提交显示
        // ⏰ 使用时机：在try块中进行绘制操作
        Canvas canvas = surface.lockCanvas(null); // 🔒 锁定Surface获取Canvas，null表示锁定整个区域
        try {
            Log.v(TAG, "drawCircleSurface: isHwAcc=" + canvas.isHardwareAccelerated()); // 📝 记录是否硬件加速（通常为false）
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR); // 🧹 用CLEAR模式清空画布为透明
            canvas.drawCircle(x, y, radius, paint); // ⭕ 绘制带阴影的白色填充圆圈
        } finally {
            surface.unlockCanvasAndPost(canvas); // 🔓 解锁Canvas并提交绘制结果到Surface（finally确保总执行）
        }
    }

    /**
     * Clears the surface, then draws a filled circle with a shadow.
     * <p>
     * Similar to drawCircleSurface(), but the position changes based on the value of "i".
     * 🏀 绘制弹跳的圆圈（共37行，需逐行注释），位置根据步数参数 i 动态变化。
     * 🔧 为什么：展示SurfaceView动态绘制动画的能力
     * 📍 时机：startBouncing线程中每帧调用
     */
    private void drawBouncingCircle(Surface surface, int i) { // 📊 i：当前步数(0~BOUNCE_STEPS)，决定圆圈位置
        // 🖌️ paint：绘制圆圈的画笔
        // 🔍 为什么：设置抗锯齿和白色填充
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); // 📞 创建抗锯齿画笔
        paint.setColor(Color.WHITE); // 🎨 设置画笔颜色为白色
        paint.setStyle(Paint.Style.FILL); // 🖌️ 设置填充样式（实心圆）

        // 🖼️ canvas：从Surface锁定的画布，用于2D绘制
        Canvas canvas = surface.lockCanvas(null); // 🔒 锁定Surface获取Canvas，null表示锁定整个区域
        try {
            Trace.beginSection("drawBouncingCircle"); // 📊 开始systrace标记，用于性能分析
            Trace.beginSection("drawColor"); // 📊 开始子标记：清屏操作
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR); // 🧹 用CLEAR模式清空画布为透明
            Trace.endSection(); // drawColor 📊 结束子标记

            // 📐 width/height：画布尺寸，用于计算圆圈位置
            int width = canvas.getWidth();   // 📐 获取画布宽度
            int height = canvas.getHeight(); // 📐 获取画布高度
            // 📐 radius, x, y：圆圈的半径和圆心坐标
            int radius, x, y; // 声明圆圈参数变量
            if (width < height) { // 📐 竖屏模式判断
                // portrait
                // 📱 竖屏模式：圆圈水平移动
                radius = width / 4; // 📐 半径为宽度的1/4
                // 🧮 x坐标计算：起始位置(width/4) + 移动范围(width/2) * 当前进度(i/BOUNCE_STEPS)
                x = width / 4 + ((width / 2 * i) / BOUNCE_STEPS); // 📍 水平位置随步数线性变化
                y = height * 3 / 4; // 📍 竖直位置固定在高度3/4处
            } else { // 📐 横屏模式
                // landscape
                // 📱 横屏模式：圆圈垂直移动
                radius = height / 4; // 📐 半径为高度的1/4
                x = width * 3 / 4; // 📍 水平位置固定在宽度3/4处
                // 🧮 y坐标计算：起始位置(height/4) + 移动范围(height/2) * 当前进度
                y = height / 4 + ((height / 2 * i) / BOUNCE_STEPS); // 📍 垂直位置随步数线性变化
            }

            paint.setShadowLayer(radius / 4 + 1, 0, 0, Color.RED); // 🌟 设置红色阴影层，半径为圆圈半径的1/4+1

            canvas.drawCircle(x, y, radius, paint); // ⭕ 绘制填充圆圈（带阴影）
            Trace.endSection(); // drawBouncingCircle 📊 结束systrace标记
        } finally {
            surface.unlockCanvasAndPost(canvas); // 🔓 解锁Canvas并提交绘制结果到Surface
        }
    } // ✅ drawBouncingCircle结束

}
