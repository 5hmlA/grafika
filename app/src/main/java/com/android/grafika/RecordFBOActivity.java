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

import android.annotation.SuppressLint;
import android.opengl.GLES20;
import android.opengl.GLES30;
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
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.TextView;
import android.app.Activity;
import android.graphics.Rect;

import com.android.grafika.gles.Drawable2d;
import com.android.grafika.gles.EglCore;
import com.android.grafika.gles.FlatShadedProgram;
import com.android.grafika.gles.FullFrameRect;
import com.android.grafika.gles.GlUtil;
import com.android.grafika.gles.Sprite2d;
import com.android.grafika.gles.Texture2dProgram;
import com.android.grafika.gles.WindowSurface;
import com.google.grafika.R;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;

/**
 * Demonstrates efficient display + recording of OpenGL rendering using an FBO.  This
 * records only the GL surface (i.e. not the app UI, nav bar, status bar, or alert dialog).
 * <p>
 * This uses a plain SurfaceView, rather than GLSurfaceView, so we have full control
 * over the EGL config and rendering.  When available, we use GLES 3, which allows us
 * to do recording with one extra copy instead of two.
 * <p>
 * We use Choreographer so our animation matches vsync, and a separate rendering
 * thread to keep the heavy lifting off of the UI thread.  Ideally we'd let the render
 * thread receive the Choreographer events directly, but that appears to be creating
 * a permanent JNI global reference to the render thread object, preventing it from
 * being garbage collected (which, in turn, causes the Activity to be retained).  So
 * instead we receive the vsync on the UI thread and forward it.
 * <p>
 * If the rendering is fairly simple, it may be more efficient to just render the scene
 * twice (i.e. configure for display, call draw(), configure for video, call draw()).  If
 * the video being created is at a lower resolution than the display, rendering at the lower
 * resolution may produce better-looking results than a downscaling blit.
 * <p>
 * To reduce the impact of recording on rendering (which is probably a fancy-looking game),
 * we want to perform the recording tasks on a separate thread.  The actual video encoding
 * is performed in a separate process by the hardware H.264 encoder, so feeding input into
 * the encoder requires little effort.  The MediaMuxer step runs on the CPU and performs
 * disk I/O, so we really want to drain the encoder on a separate thread.
 * <p>
 * Some other examples use a pair of EGL contexts, configured to share state.  We don't want
 * to do that here, because GLES3 allows us to improve performance by using glBlitFramebuffer(),
 * and framebuffer objects aren't shared.  So we use a single EGL context for rendering to
 * both the display and the video encoder.
 * <p>
 * It might appear that shifting the rendering for the encoder input to a different thread
 * would be advantageous, but in practice all of the work is done by the GPU, and submitting
 * the requests from different CPU cores isn't going to matter.
 * <p>
 * As always, we have to be careful about sharing state across threads.  By fully configuring
 * the encoder before starting the encoder thread, we ensure that the new thread sees a
 * fully-constructed object.  The encoder object then "lives" in the encoder thread.  The main
 * thread doesn't need to talk to it directly, because all of the input goes through Surface.
 * <p>
 * TODO: add another bouncing rect that uses decoded video as a texture.  Useful for
 * evaluating simultaneous video playback and recording.
 * <p>
 * TODO: show the MP4 file name somewhere in the UI so people can find it in the player
 *
 * 🎬 使用FBO演示高效的OpenGL渲染显示和录制功能
 * 只录制GL表面内容（不包括应用UI、导航栏、状态栏或对话框）
 * 使用普通SurfaceView而非GLSurfaceView，以便完全控制EGL配置和渲染
 * 支持GLES3时可用glBlitFramebuffer减少一次拷贝操作
 * 使用Choreographer同步vsync，渲染线程分离避免阻塞UI线程
 * 💡 三种录制方法：绘制两次、FBO离屏渲染、帧缓冲复制（GLES3+）
 */
public class RecordFBOActivity extends Activity implements SurfaceHolder.Callback,
        Choreographer.FrameCallback {
    private static final String TAG = MainActivity.TAG;

    // See the (lengthy) notes at the top of HardwareScalerActivity for thoughts about
    // Activity / Surface lifecycle management.
    // 📝 关于Activity/Surface生命周期管理，请参见HardwareScalerActivity的详细注释

    // 🎥 录制方法常量
    private static final int RECMETHOD_DRAW_TWICE = 0;          // 🔴 绘制两次（显示+录制各一次）
    private static final int RECMETHOD_FBO = 1;                 // 🟢 使用FBO离屏渲染
    private static final int RECMETHOD_BLIT_FRAMEBUFFER = 2;   // 🔵 使用glBlitFramebuffer复制（GLES3+）

    private boolean mRecordingEnabled = false;          // controls button state
    // 🔴 录制状态（控制按钮状态）
    private boolean mBlitFramebufferAllowed = false;    // requires GLES3
    // 🔵 是否允许使用帧缓冲复制（需要GLES3支持）
    private int mSelectedRecordMethod;                  // current radio button
    // 🎯 当前选中的录制方法

    private RenderThread mRenderThread;  // 🧵 渲染线程

    // 🎯 Activity创建时的初始化
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 📝 调用父类onCreate，恢复Activity状态
        super.onCreate(savedInstanceState);
        // 📝 设置布局文件activity_record_fbo.xml作为界面
        setContentView(R.layout.activity_record_fbo);

        // 默认使用FBO录制方法
        // 💡 mSelectedRecordMethod：当前选中的录制方法
        // 💡 作用：决定使用哪种方式将渲染结果送入编码器
        // 💡 何时用：在doFrame()中根据此值选择渲染路径
        mSelectedRecordMethod = RECMETHOD_FBO;
        // 📝 同步界面控件状态
        updateControls();

        // 💡 sv：SurfaceView实例
        // 💡 作用：获取SurfaceView并注册回调以接收Surface生命周期事件
        // 获取SurfaceView并注册回调
        SurfaceView sv = (SurfaceView) findViewById(R.id.fboActivity_surfaceView);
        // 📝 注册SurfaceHolder.Callback，接收surfaceCreated/Changed/Destroyed事件
        sv.getHolder().addCallback(this);

        // 📝 记录Activity创建完成日志
        Log.d(TAG, "RecordFBOActivity: onCreate done");
    }

    // ⏸️ Activity暂停时移除帧回调
    @Override
    protected void onPause() {
        // 📝 调用父类onPause，保存Activity状态
        super.onPause();

        // TODO: we might want to stop recording here.  As it is, we continue "recording",
        //       which is pretty boring since we're not outputting any frames (test this
        //       by blanking the screen with the power button).
        // 📝 可能需要在这里停止录制，否则会继续"录制"但不输出帧

        // If the callback was posted, remove it.  This stops the notifications.  Ideally we
        // would send a message to the thread letting it know, so when it wakes up it can
        // reset its notion of when the previous Choreographer event arrived.
        // 🔔 移除回调停止帧通知，理想情况下应通知渲染线程重置vsync时间
        // 📝 记录日志，标记正在取消Choreographer帧回调
        Log.d(TAG, "onPause unhooking choreographer");
        // 📝 移除帧回调，停止接收vsync信号
        Choreographer.getInstance().removeFrameCallback(this);
    }

    // ▶️ Activity恢复时重新注册帧回调
    @Override
    protected void onResume() {
        // 📝 调用父类onResume，恢复Activity状态
        super.onResume();

        // If we already have a Surface, we just need to resume the frame notifications.
        // 🔄 如果已有Surface，只需恢复帧通知
        // 💡 mRenderThread：渲染线程实例
        // 💡 作用：判断渲染线程是否存在，存在则恢复帧回调
        if (mRenderThread != null) {
            // 📝 记录日志，标记正在重新注册Choreographer帧回调
            Log.d(TAG, "onResume re-hooking choreographer");
            // 📝 注册帧回调，恢复vsync信号接收
            Choreographer.getInstance().postFrameCallback(this);
        }

        // 📝 同步界面控件状态
        updateControls();
    }

    // 🎨 Surface创建时启动渲染线程
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        // 📝 记录Surface创建日志
        Log.d(TAG, "surfaceCreated holder=" + holder);

        // 📁 创建输出文件路径
        // 💡 outputFile：录制的MP4文件保存路径
        // 💡 作用：指定录制视频的输出位置
        // 💡 何时用：传给RenderThread构造函数，编码器初始化时使用
        // 💡 outputFile：录制的MP4文件保存路径
        // 🔍 为什么定义：需要指定录制视频的输出位置
        // 💡 作用：传给RenderThread构造函数，编码器初始化时用于MediaMuxer输出
        // ⏰ 使用时机：在startEncoder()中传给VideoEncoderCore构造函数
        File outputFile = new File(getFilesDir(), "fbo-gl-recording.mp4");
        // 💡 sv：SurfaceView实例
        // 🔍 为什么获取：需要从SurfaceView获取SurfaceHolder传给渲染线程
        // 💡 作用：提供SurfaceHolder用于创建渲染窗口Surface
        // ⏰ 使用时机：立即使用，获取holder传给RenderThread
        SurfaceView sv = (SurfaceView) findViewById(R.id.fboActivity_surfaceView);

        // 🚀 创建并启动渲染线程
        // 💡 mRenderThread：渲染线程实例
        // 🔍 为什么创建：需要在独立线程中执行OpenGL渲染，避免阻塞UI线程
        // 💡 作用：管理OpenGL渲染循环和视频编码
        // ⏰ 使用时机：surfaceCreated()中创建，surfaceDestroyed()中销毁
        mRenderThread = new RenderThread(sv.getHolder(), new ActivityHandler(this), outputFile,
                MiscUtils.getDisplayRefreshNsec(this));
        // 📝 设置线程名，方便调试和性能分析
        mRenderThread.setName("RecordFBO GL render");
        // 📝 启动渲染线程（执行run()方法）
        mRenderThread.start();
        // ⏳ 等待渲染线程初始化完成（Looper和Handler就绪）
        // 🔍 为什么等待：必须确保渲染线程的Handler已创建，UI线程才能发送消息
        // 💡 作用：阻塞UI线程直到渲染线程通知就绪
        // ⏰ 使用时机：start()后立即调用，确保后续操作安全
        mRenderThread.waitUntilReady();
        // 📝 设置录制方法（绘制两次/FBO/帧缓冲复制）
        // 🔍 为什么调用：确保渲染线程使用用户当前选择的录制方法
        // 💡 作用：同步mSelectedRecordMethod到渲染线程的mRecordMethod
        // ⏰ 使用时机：渲染线程就绪后、发送Surface创建消息前
        mRenderThread.setRecordMethod(mSelectedRecordMethod);

        // 📨 发送Surface创建消息
        // 💡 rh：渲染线程的Handler
        // 💡 作用：获取消息处理器，用于转发Surface创建事件
        RenderHandler rh = mRenderThread.getHandler();
        if (rh != null) {
            // 📝 发送Surface创建消息，触发渲染线程的OpenGL初始化
            rh.sendSurfaceCreated();
        }

        // start the draw events
        // 🎬 开始绘制事件
        // 📝 注册帧回调，开始接收vsync信号驱动渲染循环
        Choreographer.getInstance().postFrameCallback(this);
    }

    // 📐 Surface尺寸变化时通知渲染线程
    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // 📝 记录Surface尺寸变化日志
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

    // 💥 Surface销毁时关闭渲染线程
    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        // 📝 记录Surface销毁日志
        Log.d(TAG, "surfaceDestroyed holder=" + holder);

        // We need to wait for the render thread to shut down before continuing because we
        // don't want the Surface to disappear out from under it mid-render.  The frame
        // notifications will have been stopped back in onPause(), but there might have
        // been one in progress.
        // ⚠️ 需要等待渲染线程关闭，避免Surface在渲染过程中消失
        //
        // TODO: the RenderThread doesn't currently wait for the encoder / muxer to stop,
        //       so we can't use this as an indication that the .mp4 file is complete.
        // 📝 渲染线程不会等待编码器/混合器停止，所以不能以此判断mp4文件完成

        // 💡 rh：渲染线程的Handler
        // 💡 作用：获取消息处理器，用于发送关闭指令
        RenderHandler rh = mRenderThread.getHandler();
        if (rh != null) {
            // 🛑 发送关闭消息
            // 📝 发送SHUTDOWN消息，触发渲染线程退出Looper循环
            rh.sendShutdown();
            try {
                // ⏳ 等待渲染线程结束
                // 📝 阻塞等待渲染线程完全退出
                mRenderThread.join();
            } catch (InterruptedException ie) {
                // not expected
                // 😱 不应该发生中断
                // 📝 如果等待过程中被中断，抛出运行时异常
                throw new RuntimeException("join was interrupted", ie);
            }
        }
        // 📝 清空渲染线程引用
        mRenderThread = null;
        // 📝 重置录制状态
        mRecordingEnabled = false;

        // If the callback was posted, remove it.  Without this, we could get one more
        // call on doFrame().
        // 🚫 移除帧回调，防止额外的doFrame调用
        Choreographer.getInstance().removeFrameCallback(this);
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
            // 📝 将纳秒级时间戳发送给渲染线程，用于动画计算和录制
            rh.sendDoFrame(frameTimeNanos);
        }
    }

    /**
     * Updates the GLES version string.
     * <p>
     * Called from the render thread (via ActivityHandler) after the EGL context is created.
     * 
     * 📊 更新GLES版本显示
     * 如果版本>=3则启用glBlitFramebuffer方法
     */
    void handleShowGlesVersion(int version) {
        TextView tv = (TextView) findViewById(R.id.glesVersionValue_text);
        tv.setText("" + version);
        // ✅ GLES3+支持glBlitFramebuffer，启用该选项
        if (version >= 3) {
            mBlitFramebufferAllowed = true;
            updateControls();
        }
    }

    /**
     * Updates the FPS counter.
     * <p>
     * Called periodically from the render thread (via ActivityHandler).
     * 
     * 📈 更新FPS计数器显示
     * tfps是千分之一帧率，dropped是丢帧数
     */
    void handleUpdateFps(int tfps, int dropped) {
        String str = getString(R.string.frameRateFormat, tfps / 1000.0f, dropped);
        TextView tv = (TextView) findViewById(R.id.frameRateValue_text);
        tv.setText(str);
    }

    /**
     * onClick handler for "record" button.
     * <p>
     * Ideally we'd grey out the button while in a state of transition, e.g. while the
     * MediaMuxer finishes creating the file, and in the (very brief) period before the
     * SurfaceView's surface is created.
     * 
     * 🔴 录制按钮点击处理
     * 切换录制状态并更新UI
     */
    public void clickToggleRecording(@SuppressWarnings("unused") View unused) {
        // 📝 记录录制按钮点击日志
        Log.d(TAG, "clickToggleRecording");
        // 💡 rh：渲染线程的Handler
        // 💡 作用：获取消息处理器，用于转发录制状态变更
        RenderHandler rh = mRenderThread.getHandler();
        if (rh != null) {
            // 🔄 切换录制状态
            // 📝 取反当前录制状态（开→关，关→开）
            mRecordingEnabled = !mRecordingEnabled;
            // 📝 同步界面控件状态（按钮文本、录制提示等）
            updateControls();
            // 📨 通知渲染线程更新录制状态
            // 📝 发送录制状态消息，触发编码器启动或停止
            rh.setRecordingEnabled(mRecordingEnabled);
        }
    }

    /**
     * onClick handler for radio buttons.
     * 
     * 🎛️ 录制方法单选按钮点击处理
     * 根据选择更新录制方法（绘制两次/FBO/帧缓冲复制）
     */
    @SuppressLint("NonConstantResourceId")
    public void onRadioButtonClicked(View view) {
        // 💡 rb：被点击的RadioButton控件
        // 💡 作用：获取点击事件的来源控件，判断选中状态
        RadioButton rb = (RadioButton) view;
        if (!rb.isChecked()) {
            // 📝 忽略取消选中的事件
            Log.d(TAG, "Got click on non-checked radio button");
            return;
        }

        // 💡 id：被点击RadioButton的资源ID
        // 💡 作用：通过ID判断用户选择了哪个录制方法
        int id = rb.getId();
        // 🎯 根据选中的RadioButton设置录制方法
        if (id == R.id.recDrawTwice_radio) {
            // 📝 选择绘制两次方法（显示+录制各渲染一次）
            mSelectedRecordMethod = RECMETHOD_DRAW_TWICE;
        } else if (id == R.id.recFbo_radio) {
            // 📝 选择FBO方法（离屏渲染+复制）
            mSelectedRecordMethod = RECMETHOD_FBO;
        } else if (id == R.id.recFramebuffer_radio) {
            // 📝 选择帧缓冲复制方法（GLES3+）
            mSelectedRecordMethod = RECMETHOD_BLIT_FRAMEBUFFER;
        } else {
            // 📝 未知ID，抛出异常
            throw new RuntimeException("Click from unknown id " + rb.getId());
        }

        // 📝 记录选择的录制模式日志
        Log.d(TAG, "Selected rec mode " + mSelectedRecordMethod);
        // 💡 rh：渲染线程的Handler
        // 💡 作用：获取消息处理器，用于转发录制方法变更
        RenderHandler rh = mRenderThread.getHandler();
        if (rh != null) {
            // 📨 通知渲染线程更新录制方法
            // 📝 发送录制方法消息，渲染线程会在doFrame中使用新方法
            rh.setRecordMethod(mSelectedRecordMethod);
        }
    }

    /**
     * Updates the on-screen controls to reflect the current state of the app.
     * 
     * 🎮 更新界面控件状态
     * 同步录制按钮文本、单选按钮状态和录制提示
     */
    private void updateControls() {
        // 💡 toggleRelease：录制开关按钮
        // 💡 作用：显示当前录制状态（开/关）
        Button toggleRelease = (Button) findViewById(R.id.fboRecord_button);
        // 💡 id：按钮文本资源ID
        // 💡 作用：根据录制状态选择对应的文本资源
        int id = mRecordingEnabled ?
                R.string.toggleRecordingOff : R.string.toggleRecordingOn;
        // 📝 设置按钮文本
        toggleRelease.setText(id);

        // 🔘 更新单选按钮状态
        // 💡 rb：RadioButton临时变量
        // 💡 作用：逐个设置录制方法单选按钮的选中状态
        RadioButton rb;
        // 📝 设置绘制两次单选按钮
        rb = (RadioButton) findViewById(R.id.recDrawTwice_radio);
        rb.setChecked(mSelectedRecordMethod == RECMETHOD_DRAW_TWICE);
        // 📝 设置FBO单选按钮
        rb = (RadioButton) findViewById(R.id.recFbo_radio);
        rb.setChecked(mSelectedRecordMethod == RECMETHOD_FBO);
        // 📝 设置帧缓冲复制单选按钮
        rb = (RadioButton) findViewById(R.id.recFramebuffer_radio);
        rb.setChecked(mSelectedRecordMethod == RECMETHOD_BLIT_FRAMEBUFFER);
        // 🚫 GLES3以下禁用帧缓冲复制选项
        // 💡 mBlitFramebufferAllowed：是否允许使用帧缓冲复制
        // 💡 作用：仅当GLES3+时才启用此选项
        rb.setEnabled(mBlitFramebufferAllowed);

        // 📝 更新录制状态提示
        // 💡 tv：显示录制状态的TextView
        // 💡 作用：在界面上显示当前是否正在录制
        TextView tv = (TextView) findViewById(R.id.nowRecording_text);
        if (mRecordingEnabled) {
            // 📝 正在录制，显示录制提示文本
            tv.setText(getString(R.string.nowRecording));
        } else {
            // 📝 未录制，清空文本
            tv.setText("");
        }
    }


    /**
     * Handles messages sent from the render thread to the UI thread.
     * <p>
     * The object is created on the UI thread, and all handlers run there.
     * 
     * 📬 处理从渲染线程发送到UI线程的消息
     * 使用弱引用避免内存泄漏
     */
    static class ActivityHandler extends Handler {
        private static final int MSG_GLES_VERSION = 0;
        private static final int MSG_UPDATE_FPS = 1;

        // Weak reference to the Activity; only access this from the UI thread.
        // 🔗 对Activity的弱引用，仅在UI线程访问
        private WeakReference<RecordFBOActivity> mWeakActivity;

        public ActivityHandler(RecordFBOActivity activity) {
            mWeakActivity = new WeakReference<RecordFBOActivity>(activity);
        }

        /**
         * Send the GLES version.
         * <p>
         * Call from non-UI thread.
         * 
         * 📤 发送GLES版本信息（可从非UI线程调用）
         */
        public void sendGlesVersion(int version) {
            sendMessage(obtainMessage(MSG_GLES_VERSION, version, 0));
        }

        /**
         * Send an FPS update.  "fps" should be in thousands of frames per second
         * (i.e. fps * 1000), so we can get fractional fps even though the Handler only
         * supports passing integers.
         * <p>
         * Call from non-UI thread.
         * 
         * 📤 发送FPS更新（千分之一帧率单位，支持小数精度）
         */
        public void sendFpsUpdate(int tfps, int dropped) {
            sendMessage(obtainMessage(MSG_UPDATE_FPS, tfps, dropped));
        }

        @Override  // runs on UI thread
        // 🔄 在UI线程处理消息
        public void handleMessage(Message msg) {
            // 💡 what：消息类型标识
            // 💡 作用：根据消息类型分发到不同的处理逻辑
            int what = msg.what;
            //Log.d(TAG, "ActivityHandler [" + this + "]: what=" + what);

            // 💡 activity：从弱引用获取Activity实例
            // 💡 作用：避免Handler持有强引用导致Activity内存泄漏
            RecordFBOActivity activity = mWeakActivity.get();
            if (activity == null) {
                // 📝 弱引用已被回收，记录警告并返回
                Log.w(TAG, "ActivityHandler.handleMessage: activity is null");
                return;
            }

            // 🎯 根据消息类型分发处理
            switch (what) {
                case MSG_GLES_VERSION:
                    // 📝 处理GLES版本消息，更新界面显示
                    activity.handleShowGlesVersion(msg.arg1);
                    break;
                case MSG_UPDATE_FPS:
                    // 📝 处理FPS更新消息，arg1=千分之一帧率，arg2=丢帧数
                    activity.handleUpdateFps(msg.arg1, msg.arg2);
                    break;
                default:
                    // 📝 未知消息类型，抛出异常
                    throw new RuntimeException("unknown msg " + what);
            }
        }
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

        // Handler we can send messages to if we want to update the app UI.
        // 📬 用于更新应用UI的Handler
        private ActivityHandler mActivityHandler;

        // Used to wait for the thread to start.
        // 🔒 用于等待线程启动的锁对象
        private Object mStartLock = new Object();
        private boolean mReady = false;

        private volatile SurfaceHolder mSurfaceHolder;  // may be updated by UI thread
        // 🎬 EGL核心和窗口Surface
        private EglCore mEglCore;
        private WindowSurface mWindowSurface;
        // 🖌️ 着色器程序
        private FlatShadedProgram mProgram;

        // Orthographic projection matrix.
        // 📐 正交投影矩阵（16个float）
        // 💡 作用：将3D坐标转换为2D屏幕坐标
        // 💡 何时用：在draw()方法中传给Sprite2d的draw()函数
        // 💡 哪里用：第1294行 mTri.draw()、第1296行 mRect.draw()、第1304行 mEdges[i].draw()
        // 💡 初始化：第726行 Matrix.orthoM()设置为左下角原点的正交投影
        private float[] mDisplayProjectionMatrix = new float[16];

        // 🔺 三角形和矩形可绘制对象（定义顶点数据）
        // 💡 作用：存储几何图形的顶点坐标和纹理坐标
        // 💡 何时用：创建Sprite2d时作为参数传入
        // 💡 哪里用：第572-578行构造函数中创建对应的Sprite2d
        private final Drawable2d mTriDrawable = new Drawable2d(Drawable2d.Prefab.TRIANGLE);
        private final Drawable2d mRectDrawable = new Drawable2d(Drawable2d.Prefab.RECTANGLE);

        // 🎭 动画精灵：旋转三角形、弹跳矩形和四个边框
        // 💡 mTri：旋转的三角形精灵（绿色，第735行设置颜色）
        // 💡 mRect：弹跳的矩形精灵（红色，第738行设置颜色）
        // 💡 mEdges[4]：四个边框精灵数组
        //    - mEdges[0]：左边框（第747-748行设置）
        //    - mEdges[1]：右边框（第750-751行设置）
        //    - mEdges[2]：顶部边框（第753-754行设置）
        //    - mEdges[3]：底部边框（第756-757行设置）
        // 💡 mRecordRect：录制指示器矩形（左下角小方块，颜色表示录制方法）
        // 💡 何时用：每帧绘制时（第1294-1321行draw()方法）
        private Sprite2d mTri;
        private Sprite2d mRect;
        private Sprite2d mEdges[];
        private Sprite2d mRecordRect;
        private float mRectVelX, mRectVelY;     // velocity, in viewport units per second
        // 🚀 矩形速度（视口单位/秒）
        // 💡 作用：控制弹跳矩形的移动速度
        // 💡 何时用：第1265-1266行update()方法中更新位置
        // 💡 初始化：第741-742行根据窗口尺寸设置
        private float mInnerLeft, mInnerTop, mInnerRight, mInnerBottom;
        // 📦 内部边界矩形（用于碰撞检测）
        // 💡 作用：定义弹跳矩形的活动范围
        // 💡 何时用：第1268-1274行update()方法中检测碰撞
        // 💡 初始化：第766-768行根据边框宽度计算

        // 🆔 单位矩阵（16个float）
        // 💡 作用：作为纹理变换矩阵，不做任何变换
        // 💡 何时用：FBO录制模式时传给mFullScreen.drawFrame()
        // 💡 哪里用：第1158行、第1169行绘制离屏纹理到显示/编码器Surface
        // 💡 初始化：第568-569行构造函数中设置为单位矩阵
        private final float[] mIdentityMatrix;

        // Previous frame time.
        // ⏱️ 上一帧的时间戳（纳秒）
        // 💡 作用：计算帧间时间差，用于动画更新
        // 💡 何时用：第1232行update()方法中计算intervalNanos
        // 💡 初始化：第1229行第一次调用时设为0
        private long mPrevTimeNanos;

        // FPS / drop counter.
        // 📊 FPS和丢帧计数器
        // 💡 mRefreshPeriodNanos：显示器刷新周期（纳秒）
        //    - 作用：用于判断是否需要丢帧
        //    - 何时用：第1039行检查diff > max时丢帧
        //    - 初始化：第563行构造函数中从MiscUtils.getDisplayRefreshNsec()获取
        // 💡 mFpsCountStartNanos：FPS计算开始时间
        //    - 作用：记录开始计算FPS的时间点
        //    - 何时用：第1197-1212行每120帧计算一次FPS
        // 💡 mFpsCountFrame：已渲染帧数计数器
        //    - 作用：累计渲染帧数，达到120帧时计算FPS
        // 💡 mDroppedFrames：丢帧计数器
        //    - 作用：统计丢帧次数，用于UI显示
        //    - 何时用：第1045行丢帧时递增，第1206行发送到UI
        // 💡 mPreviousWasDropped：上一帧是否被丢弃
        //    - 作用：标记上一帧状态（目前未实际使用）
        private long mRefreshPeriodNanos;
        private long mFpsCountStartNanos;
        private int mFpsCountFrame;
        private int mDroppedFrames;
        private boolean mPreviousWasDropped;

        // Used for off-screen rendering.
        // 🖼️ 离屏渲染资源
        // 💡 mOffscreenTexture：离屏纹理对象ID
        //    - 作用：作为FBO的颜色缓冲区，存储离屏渲染结果
        //    - 何时用：第789行glGenTextures()创建，第1158行绘制到屏幕
        //    - 哪里用：prepareFramebuffer()创建，doFrame()中FBO模式使用
        // 💡 mFramebuffer：帧缓冲对象ID (FBO)
        //    - 作用：将渲染重定向到离屏纹理而非屏幕
        //    - 何时用：第1150行glBindFramebuffer()绑定，第1156行解绑
        //    - 哪里用：prepareFramebuffer()创建，doFrame()中FBO模式使用
        // 💡 mDepthBuffer：深度缓冲对象ID
        //    - 作用：存储深度信息，用于深度测试（本例中仅2D所以未实际使用）
        //    - 何时用：第835行附加到FBO
        // 💡 mFullScreen：全屏矩形渲染器
        //    - 作用：将离屏纹理绘制到屏幕或编码器Surface
        //    - 何时用：第1158行、第1169行绘制纹理
        private int mOffscreenTexture;
        private int mFramebuffer;
        private int mDepthBuffer;
        private FullFrameRect mFullScreen;

        // Used for recording.
        // 🎥 录制相关资源
        // 💡 mRecordingEnabled：录制是否启用
        //    - 作用：控制是否向编码器输出帧
        //    - 何时用：第1052行判断是否进入录制分支
        // 💡 mOutputFile：输出文件路径
        //    - 作用：指定录制的MP4文件保存位置
        //    - 初始化：第562行从Activity传入
        // 💡 mInputWindowSurface：编码器输入Surface
        //    - 作用：作为编码器的输入，将渲染结果送入编码器
        //    - 何时用：第984行startEncoder()创建，第1073行makeCurrent()
        // 💡 mVideoEncoder：视频编码器
        //    - 作用：将Surface输入编码为H.264视频
        //    - 何时用：第985行startEncoder()创建，第1073行frameAvailableSoon()
        // 💡 mRecordMethod：当前录制方法
        //    - 作用：选择录制策略（绘制两次/FBO/帧缓冲复制）
        //    - 何时用：第1063行、第1109行、第1146行判断录制方法
        // 💡 mRecordedPrevious：上一帧是否已录制
        //    - 作用：实现隔帧录制（~30fps而非60fps）
        //    - 何时用：第1052行判断是否跳过录制
        // 💡 mVideoRect：视频录制区域矩形
        //    - 作用：定义编码器中的录制区域（居中显示）
        //    - 何时用：第970行startEncoder()计算，第1094行设置视口
        private boolean mRecordingEnabled;
        private File mOutputFile;
        private WindowSurface mInputWindowSurface;
        private TextureMovieEncoder2 mVideoEncoder;
        private int mRecordMethod;
        private boolean mRecordedPrevious;
        private Rect mVideoRect;


        /**
         * Pass in the SurfaceView's SurfaceHolder.  Note the Surface may not yet exist.
         * 
         * 🏗️ 构造函数，传入SurfaceHolder（Surface可能尚未存在）
         */
        public RenderThread(SurfaceHolder holder, ActivityHandler ahandler, File outputFile,
                long refreshPeriodNs) {
            mSurfaceHolder = holder;
            mActivityHandler = ahandler;
            mOutputFile = outputFile;
            mRefreshPeriodNanos = refreshPeriodNs;

            mVideoRect = new Rect();

            // 🆔 初始化单位矩阵
            mIdentityMatrix = new float[16];
            Matrix.setIdentityM(mIdentityMatrix, 0);

            // 🎭 初始化动画精灵
            mTri = new Sprite2d(mTriDrawable);
            mRect = new Sprite2d(mRectDrawable);
            mEdges = new Sprite2d[4];
            for (int i = 0; i < mEdges.length; i++) {
                mEdges[i] = new Sprite2d(mRectDrawable);
            }
            mRecordRect = new Sprite2d(mRectDrawable);
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
            // 💡 作用：接收UI线程发送的消息（Surface创建/变化/帧绘制/录制控制等）
            mHandler = new RenderHandler(this);
            // 🎬 创建EGL核心，尝试使用GLES3并启用可录制标志
            // 💡 mEglCore：EGL上下文管理器
            // 💡 作用：管理OpenGL ES与原生窗口系统的连接
            // 💡 参数：null=默认显示，FLAG_RECORDABLE=支持录制，FLAG_TRY_GLES3=尝试GLES3
            mEglCore = new EglCore(null, EglCore.FLAG_RECORDABLE | EglCore.FLAG_TRY_GLES3);
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
            // 📝 释放OpenGL资源（窗口Surface、着色器程序、FBO等）
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
            Log.d(TAG, "shutdown");
            // 🎥 先停止编码器
            stopEncoder();
            // 🔄 退出消息循环
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
            mWindowSurface = new WindowSurface(mEglCore, surface, false);
            // 📝 将此窗口Surface设为当前渲染目标
            mWindowSurface.makeCurrent();

            // Used for blitting texture to FBO.
            // 🖼️ 用于纹理到FBO复制的全屏矩形
            // 💡 mFullScreen：全屏矩形渲染器
            // 💡 作用：将离屏纹理绘制到屏幕或编码器Surface
            // 💡 何时用：FBO模式下绘制离屏纹理到显示/编码器Surface
            mFullScreen = new FullFrameRect(
                    new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_2D));

            // Program used for drawing onto the screen.
            // 🖌️ 屏幕绘制着色器程序
            // 💡 mProgram：平面着色程序
            // 💡 作用：使用纯色绘制几何图形（三角形、矩形、边框等）
            mProgram = new FlatShadedProgram();

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

            // 📤 发送GLES版本信息到UI线程
            // 📝 获取EGL核心的GL版本并发送到UI线程显示
            mActivityHandler.sendGlesVersion(mEglCore.getGlVersion());
        }

       /**
         * Handles changes to the size of the underlying surface.  Adjusts viewport as needed.
         * Must be called before we start drawing.
         * (Called from RenderHandler.)
         * 
         * 📐 处理Surface尺寸变化
         * 准备帧缓冲，设置视口和投影矩阵，初始化动画对象位置和速度
         */
        private void surfaceChanged(int width, int height) {
            // 📝 记录Surface尺寸变化日志
            Log.d(TAG, "surfaceChanged " + width + "x" + height);

            // 🖼️ 准备离屏帧缓冲
            // 📝 创建/更新FBO，尺寸与窗口一致
            prepareFramebuffer(width, height);

            // Use full window.
            // 🖥️ 设置视口为整个窗口
            // 📝 glViewport定义渲染输出在窗口中的区域（左下角为原点）
            GLES20.glViewport(0, 0, width, height);

            // Simple orthographic projection, with (0,0) in lower-left corner.
            // 📐 设置正交投影矩阵，左下角为原点
            // 💡 mDisplayProjectionMatrix：正交投影矩阵（16个float）
            // 💡 作用：将世界坐标映射到屏幕坐标，保持物体大小不变
            Matrix.orthoM(mDisplayProjectionMatrix, 0, 0, width, 0, height, -1, 1);

            // 💡 smallDim：窗口的较短边尺寸
            // 💡 作用：用于计算动画对象的缩放比例
            int smallDim = Math.min(width, height);

            // Set initial shape size / position / velocity based on window size.  Movement
            // has the same "feel" on all devices, but the actual path will vary depending
            // on the screen proportions.  We do it here, rather than defining fixed values
            // and tweaking the projection matrix, so that our squares are square.
            // 🔺 根据窗口尺寸设置动画对象的大小、位置和速度
            // 📝 设置三角形颜色为绿色
            mTri.setColor(0.1f, 0.9f, 0.1f);
            // 📝 设置三角形缩放为窗口短边的1/4
            mTri.setScale(smallDim / 4.0f, smallDim / 4.0f);
            // 📝 设置三角形位置为窗口中心
            mTri.setPosition(width / 2.0f, height / 2.0f);
            // 📝 设置矩形颜色为红色
            mRect.setColor(0.9f, 0.1f, 0.1f);
            // 📝 设置矩形缩放为窗口短边的1/8
            mRect.setScale(smallDim / 8.0f, smallDim / 8.0f);
            // 📝 设置矩形位置为窗口中心
            mRect.setPosition(width / 2.0f, height / 2.0f);
            // 💡 mRectVelX/mRectVelY：矩形的X/Y方向速度（像素/秒）
            // 💡 作用：控制弹跳矩形的移动速度
            mRectVelX = 1 + smallDim / 4.0f;
            mRectVelY = 1 + smallDim / 5.0f;

            // left edge
            // 📦 设置四个边框的位置和大小
            // 💡 edgeWidth：边框宽度
            // 💡 作用：定义边界区域的像素宽度
            float edgeWidth = 1 + width / 64.0f;
            // 📝 左边框：灰色
            mEdges[0].setScale(edgeWidth, height);
            mEdges[0].setPosition(edgeWidth / 2.0f, height / 2.0f);
            // right edge
            // 📝 右边框：灰色
            mEdges[1].setScale(edgeWidth, height);
            mEdges[1].setPosition(width - edgeWidth / 2.0f, height / 2.0f);
            // top edge
            // 📝 顶部边框：灰色
            mEdges[2].setScale(width, edgeWidth);
            mEdges[2].setPosition(width / 2.0f, height - edgeWidth / 2.0f);
            // bottom edge
            // 📝 底部边框：灰色
            mEdges[3].setScale(width, edgeWidth);
            mEdges[3].setPosition(width / 2.0f, edgeWidth / 2.0f);

            // 🔴 设置录制指示矩形
            // 💡 mRecordRect：录制指示器矩形
            // 💡 作用：在左下角显示小方块，颜色表示当前录制方法
            mRecordRect.setColor(1.0f, 1.0f, 1.0f);
            // 📝 设置录制指示器大小为边框宽度的2倍
            mRecordRect.setScale(edgeWidth * 2f, edgeWidth * 2f);
            // 📝 设置录制指示器位置在左下角
            mRecordRect.setPosition(edgeWidth / 2.0f, edgeWidth / 2.0f);

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
         * Prepares the off-screen framebuffer.
         * 
         * 🖼️ 准备离屏帧缓冲
         * 创建纹理、帧缓冲和深度缓冲，并将它们关联起来
         * 
         * @param width 帧缓冲宽度
         * @param height 帧缓冲高度
         */
        private void prepareFramebuffer(int width, int height) {
            // 🔍 GlUtil.checkGlError：检查GL错误
            // 💡 作用：标记准备帧缓冲开始，检测之前是否有错误
            GlUtil.checkGlError("prepareFramebuffer start");

            // 📦 values：临时数组，用于存储OpenGL生成的对象ID
            // 🔍 为什么定义：glGenTextures/glGenFramebuffers/glGenRenderbuffers通过数组返回生成的ID
            // 💡 作用：接收OpenGL对象创建函数的输出结果
            // ⏰ 使用时机：每次调用glGen*函数时填充，然后取出ID保存到成员变量
            // 💡 哪里用：
            //    - glGenTextures()：返回纹理ID → mOffscreenTexture
            //    - glGenFramebuffers()：返回帧缓冲ID → mFramebuffer
            //    - glGenRenderbuffers()：返回渲染缓冲ID → mDepthBuffer
            int[] values = new int[1];

            // Create a texture object and bind it.  This will be the color buffer.
            // 🎨 创建纹理对象作为颜色缓冲
            // 📝 glGenTextures：生成1个纹理对象ID
            GLES20.glGenTextures(1, values, 0);
            GlUtil.checkGlError("glGenTextures");
            // 📊 mOffscreenTexture：离屏纹理对象ID
            // 🔍 为什么赋值：需要保存纹理ID，后续绑定和绘制使用
            // 💡 作用：作为FBO的颜色附件，存储离屏渲染结果
            mOffscreenTexture = values[0];   // expected > 0
            // 📝 glBindTexture：绑定纹理对象，后续纹理操作作用于此纹理
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mOffscreenTexture);
            GlUtil.checkGlError("glBindTexture " + mOffscreenTexture);

            // Create texture storage.
            // 📦 创建纹理存储空间
            // 📝 glTexImage2D：分配width x height的RGBA纹理存储空间
            // 💡 参数null：不上传初始数据，只分配内存
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);

            // Set parameters.  We're probably using non-power-of-two dimensions, so
            // some values may not be available for use.
            // ⚙️ 设置纹理参数（可能使用非2的幂次尺寸）
            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                    GLES20.GL_NEAREST);
            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                    GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                    GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                    GLES20.GL_CLAMP_TO_EDGE);
            GlUtil.checkGlError("glTexParameter");

            // Create framebuffer object and bind it.
            // 🖼️ 创建并绑定帧缓冲对象
            // 📝 glGenFramebuffers：生成1个帧缓冲对象ID
            GLES20.glGenFramebuffers(1, values, 0);
            GlUtil.checkGlError("glGenFramebuffers");
            // 📊 mFramebuffer：帧缓冲对象ID
            // 🔍 为什么赋值：需要保存FBO ID，后续绑定和渲染使用
            // 💡 作用：将渲染重定向到离屏纹理而非屏幕
            mFramebuffer = values[0];    // expected > 0
            // 📝 glBindFramebuffer：绑定帧缓冲，后续渲染操作输出到此FBO
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mFramebuffer);
            GlUtil.checkGlError("glBindFramebuffer " + mFramebuffer);

            // Create a depth buffer and bind it.
            // 📦 创建并绑定深度缓冲
            // 📝 glGenRenderbuffers：生成1个渲染缓冲对象ID
            GLES20.glGenRenderbuffers(1, values, 0);
            GlUtil.checkGlError("glGenRenderbuffers");
            // 📊 mDepthBuffer：深度缓冲对象ID
            // 🔍 为什么赋值：需要保存渲染缓冲ID，后续附加到FBO
            // 💡 作用：存储深度信息，用于深度测试（本例中仅2D所以未实际使用）
            mDepthBuffer = values[0];    // expected > 0
            // 📝 glBindRenderbuffer：绑定渲染缓冲对象
            GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, mDepthBuffer);
            GlUtil.checkGlError("glBindrenderbuffer " + mDepthBuffer);

            // Allocate storage for the depth buffer.
            // 💾 为深度缓冲分配存储空间
            GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_DEPTH_COMPONENT16,
                    width, height);
            GlUtil.checkGlError("glRenderbufferStorage");

            // Attach the depth buffer and the texture (color buffer) to the framebuffer object.
            // 🔗 将深度缓冲和纹理附加到帧缓冲对象
            // 📝 glFramebufferRenderbuffer：将深度缓冲附加到FBO的深度附件点
            GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT,
                    GLES20.GL_RENDERBUFFER, mDepthBuffer);
            GlUtil.checkGlError("glFramebufferRenderbuffer");
            // 📝 glFramebufferTexture2D：将纹理附加到FBO的颜色附件点
            // 💡 GL_COLOR_ATTACHMENT0：颜色附件0（FBO的主要颜色输出）
            // 💡 mOffscreenTexture：离屏纹理对象ID
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, mOffscreenTexture, 0);
            GlUtil.checkGlError("glFramebufferTexture2D");

            // See if GLES is happy with all this.
            // ✅ 检查帧缓冲完整性
            // 📝 glCheckFramebufferStatus：检查FBO配置是否完整有效
            int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                // ⚠️ FBO不完整，抛出异常（纹理或深度缓冲未正确附加）
                throw new RuntimeException("Framebuffer not complete, status=" + status);
            }

            // Switch back to the default framebuffer.
            // 🔄 切换回默认帧缓冲
            // 📝 glBindFramebuffer(0)：解绑FBO，后续渲染输出到屏幕
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

            // 🔍 GlUtil.checkGlError：检查GL错误，标记准备帧缓冲完成
            GlUtil.checkGlError("prepareFramebuffer done");
        }

        /**
         * Releases most of the GL resources we currently hold.
         * <p>
         * Does not release EglCore.
         * 
         * 🧹 释放大部分GL资源（不包括EglCore）
         */
        private void releaseGl() {
            GlUtil.checkGlError("releaseGl start");

            // 📦 values：临时数组，用于传递要删除的OpenGL对象ID
            // 🔍 为什么定义：glDeleteTextures/glDeleteFramebuffers/glDeleteRenderbuffers需要数组参数
            // 💡 作用：传递要删除的OpenGL对象ID给删除函数
            // ⏰ 使用时机：释放纹理、帧缓冲、渲染缓冲时使用
            // 💡 哪里用：
            //    - glDeleteTextures()：删除离屏纹理 mOffscreenTexture
            //    - glDeleteFramebuffers()：删除帧缓冲 mFramebuffer
            //    - glDeleteRenderbuffers()：删除深度缓冲 mDepthBuffer
            int[] values = new int[1];

            // 🪟 释放窗口Surface
            if (mWindowSurface != null) {
                mWindowSurface.release();
                mWindowSurface = null;
            }
            // 🖌️ 释放着色器程序
            if (mProgram != null) {
                mProgram.release();
                mProgram = null;
            }
            // 🎨 删除离屏纹理
            if (mOffscreenTexture > 0) {
                values[0] = mOffscreenTexture;
                GLES20.glDeleteTextures(1, values, 0);
                mOffscreenTexture = -1;
            }
            // 🖼️ 删除帧缓冲
            if (mFramebuffer > 0) {
                values[0] = mFramebuffer;
                GLES20.glDeleteFramebuffers(1, values, 0);
                mFramebuffer = -1;
            }
            // 📦 删除深度缓冲
            if (mDepthBuffer > 0) {
                values[0] = mDepthBuffer;
                GLES20.glDeleteRenderbuffers(1, values, 0);
                mDepthBuffer = -1;
            }
            // 🖼️ 释放全屏矩形
            if (mFullScreen != null) {
                mFullScreen.release(false); // TODO: should be "true"; must ensure mEglCore current
                mFullScreen = null;
            }

            GlUtil.checkGlError("releaseGl done");

            // 🔌 解绑当前上下文
            mEglCore.makeNothingCurrent();
        }

        /**
         * Updates the recording state.  Stops or starts recording as needed.
         * 
         * 🎥 更新录制状态，根据需要启动或停止录制
         */
        private void setRecordingEnabled(boolean enabled) {
            // 💡 enabled：新的录制状态，true=启用录制，false=禁用录制
            // 🔍 为什么检查：避免重复启停编码器导致资源浪费
            // 💡 作用：状态相同时直接返回，不做任何操作
            // ⏰ 使用时机：在实际操作前检查
            if (enabled == mRecordingEnabled) {
                return;
            }
            // 🎯 根据新状态决定启动或停止编码器
            if (enabled) {
                // ▶️ startEncoder：启动编码器和输入Surface
                // 🔍 为什么调用：用户请求开始录制
                // 💡 作用：创建视频编码器，准备接收帧数据
                // ⏰ 使用时机：录制状态从false变为true时
                startEncoder();
            } else {
                // ⏹️ stopEncoder：停止编码器并释放资源
                // 🔍 为什么调用：用户请求停止录制
                // 💡 作用：停止编码并保存视频文件
                // ⏰ 使用时机：录制状态从true变为false时
                stopEncoder();
            }
            // 📊 mRecordingEnabled：录制状态标志
            // 🔍 为什么更新：同步状态变量与实际操作
            // 💡 作用：记录当前录制状态，供其他方法参考
            // ⏰ 使用时机：在启停操作完成后更新
            mRecordingEnabled = enabled;
        }

        /**
         * Changes the method we use to render frames to the encoder.
         * 
         * 🎛️ 更改编码器帧渲染方法
         */
        private void setRecordMethod(int recordMethod) {
            // 📝 日志输出：记录录制方法变更
            Log.d(TAG, "RT: setRecordMethod " + recordMethod);
            // 🎯 mRecordMethod：当前使用的录制方法
            // 🔍 为什么更新：用户通过RadioButton切换了录制方式
            // 💡 作用：决定doFrame()中使用哪种渲染路径（绘制两次/FBO/帧缓冲复制）
            // ⏰ 使用时机：在doFrame()的录制分支中读取
            mRecordMethod = recordMethod;
        }

        /**
         * Creates the video encoder object and starts the encoder thread.  Creates an EGL
         * surface for encoder input.
         * 
         * 🎬 创建视频编码器并启动编码线程
         * 固定录制1280x720分辨率，根据窗口宽高比计算实际录制区域
         */
        private void startEncoder() {
            Log.d(TAG, "starting to record");
            // Record at 1280x720, regardless of the window dimensions.  The encoder may
            // explode if given "strange" dimensions, e.g. a width that is not a multiple
            // of 16.  We can box it as needed to preserve dimensions.
            // 📐 固定录制1280x720，避免编码器对奇怪尺寸报错
            // 💡 BIT_RATE：视频编码比特率（4Mbps）
            // 🔍 为什么定义：控制视频质量和文件大小的平衡
            // 💡 作用：传给VideoEncoderCore，决定H.264编码的码率
            // ⏰ 使用时机：创建VideoEncoderCore时作为参数传入
            final int BIT_RATE = 4000000;   // 4Mbps
            // 💡 VIDEO_WIDTH/VIDEO_HEIGHT：录制视频的固定分辨率
            // 🔍 为什么定义：编码器需要固定分辨率，避免动态变化导致错误
            // 💡 作用：定义编码器输出视频的像素尺寸
            // ⏰ 使用时机：创建VideoEncoderCore时作为参数传入
            final int VIDEO_WIDTH = 1280;
            final int VIDEO_HEIGHT = 720;
            // 💡 windowWidth/windowHeight：当前显示窗口的像素尺寸
            // 🔍 为什么获取：需要根据窗口比例计算录制区域（letterbox）
            // 💡 作用：计算窗口宽高比，确定视频中的有效录制区域
            // ⏰ 使用时机：立即使用，计算windowAspect
            int windowWidth = mWindowSurface.getWidth();
            int windowHeight = mWindowSurface.getHeight();
            // 💡 windowAspect：窗口宽高比（高度/宽度）
            // 🔍 为什么计算：需要保持录制视频的宽高比与窗口一致
            // 💡 作用：用于计算输出尺寸，避免视频变形
            // ⏰ 使用时机：下面if-else中判断受限维度时使用
            float windowAspect = (float) windowHeight / (float) windowWidth;
            // 💡 outWidth/outHeight：实际输出的视频像素尺寸（居中区域）
            // 🔍 为什么定义：录制区域可能小于1280x720，需要居中放置
            // 💡 作用：定义视频中有效内容的大小
            // ⏰ 使用时机：设置mVideoRect时计算偏移量
            int outWidth, outHeight;
            // 📐 根据窗口宽高比计算实际输出尺寸（保持比例）
            if (VIDEO_HEIGHT > VIDEO_WIDTH * windowAspect) {
                // limited by narrow width; reduce height
                outWidth = VIDEO_WIDTH;
                outHeight = (int) (VIDEO_WIDTH * windowAspect);
            } else {
                // limited by short height; restrict width
                outHeight = VIDEO_HEIGHT;
                outWidth = (int) (VIDEO_HEIGHT / windowAspect);
            }
            // 🎯 计算居中偏移量
            int offX = (VIDEO_WIDTH - outWidth) / 2;
            int offY = (VIDEO_HEIGHT - outHeight) / 2;
            mVideoRect.set(offX, offY, offX + outWidth, offY + outHeight);
            Log.d(TAG, "Adjusting window " + windowWidth + "x" + windowHeight +
                    " to +" + offX + ",+" + offY + " " +
                    mVideoRect.width() + "x" + mVideoRect.height());

            // 🎬 encoderCore：视频编码器核心对象
            // 🔍 为什么定义：需要创建MediaCodec编码器和MediaMuxer来处理视频编码
            // 💡 作用：管理H.264视频编码和MP4文件写入
            // ⏰ 使用时机：创建后传给TextureMovieEncoder2和WindowSurface
            VideoEncoderCore encoderCore;
            try {
                // 🎬 创建视频编码器核心
                // 💡 参数：VIDEO_WIDTH=1280, VIDEO_HEIGHT=720, BIT_RATE=4Mbps, mOutputFile=输出文件
                encoderCore = new VideoEncoderCore(VIDEO_WIDTH, VIDEO_HEIGHT,
                        BIT_RATE, mOutputFile);
            } catch (IOException ioe) {
                throw new RuntimeException(ioe);
            }
            // 🖼️ mInputWindowSurface：编码器输入窗口Surface
            // 🔍 为什么创建：需要将渲染结果送入编码器
            // 💡 作用：作为编码器的输入，接收OpenGL渲染的帧数据
            // 💡 参数true：表示此Surface由编码器管理
            mInputWindowSurface = new WindowSurface(mEglCore, encoderCore.getInputSurface(), true);
            // 🎬 mVideoEncoder：视频编码器对象
            // 🔍 为什么创建：需要控制视频录制的启停
            // 💡 作用：管理视频编码和文件写入
            // 💡 参数encoderCore：编码器核心，提供编码功能
            mVideoEncoder = new TextureMovieEncoder2(encoderCore);
        }

        /**
         * Stops the video encoder if it's running.
         * 
         * ⏹️ 停止视频编码器（如果正在运行）
         */
        private void stopEncoder() {
            // 🛑 停止录制
            if (mVideoEncoder != null) {
                // 📝 记录停止编码器日志
                Log.d(TAG, "stopping recorder, mVideoEncoder=" + mVideoEncoder);
                // 📝 停止视频录制
                mVideoEncoder.stopRecording();
                // TODO: wait (briefly) until it finishes shutting down so we know file is
                //       complete, or have a callback that updates the UI
                // 📝 需要等待编码器完成或通过回调更新UI
                // 📝 清空编码器引用
                mVideoEncoder = null;
            }
            // 🪟 释放输入Surface
            if (mInputWindowSurface != null) {
                // 📝 释放编码器输入Surface
                mInputWindowSurface.release();
                mInputWindowSurface = null;
            }
        }

        /**
         * Advance state and draw frame in response to a vsync event.
         * 
         * 🎯 响应vsync事件，更新状态并绘制帧
         * 根据录制状态和方法选择不同的渲染路径
         */
        private void doFrame(long timeStampNanos) {
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
            // 可通过每帧录制（~30fps）减少开销

            // 🔄 更新动画状态
            // 📝 调用update()更新三角形旋转和矩形弹跳位置
            update(timeStampNanos);

            // 💡 diff：当前系统时间与帧时间戳的差值（纳秒）
            // 🔍 为什么计算：判断渲染是否滞后，决定是否需要丢帧
            // 💡 作用：如果差值过大说明系统繁忙，应跳过本帧
            // ⏰ 使用时机：每帧检查，超过阈值则跳过渲染
            long diff = System.nanoTime() - timeStampNanos;
            // 💡 max：最大允许延迟（刷新周期减2ms）
            // 🔍 为什么减2ms：留出2ms余量，避免边界情况频繁丢帧
            // 💡 作用：丢帧阈值，diff超过此值则跳过渲染
            // ⏰ 使用时机：与diff比较，判断是否丢帧
            long max = mRefreshPeriodNanos - 2000000;   // if we're within 2ms, don't bother
            // ⏱️ 检查是否需要丢帧（超过2ms阈值）
            if (diff > max) {
                // too much, drop a frame
                // 📝 超过阈值说明系统繁忙，跳过本帧
                Log.d(TAG, "diff is " + (diff / 1000000.0) + " ms, max " + (max / 1000000.0) +
                        ", skipping render");
                // 📝 标记上一帧未录制
                mRecordedPrevious = false;
                // 📝 标记上一帧被丢弃
                mPreviousWasDropped = true;
                // 📝 递增丢帧计数器
                mDroppedFrames++;
                return;
            }

            // 💡 swapResult：交换缓冲的结果
            // 💡 作用：判断Surface是否有效
            boolean swapResult;

            // 🎬 根据录制状态选择渲染路径
            if (!mRecordingEnabled || mRecordedPrevious) {
                // 📝 不录制或上一帧已录制（隔帧录制），使用普通渲染路径
                mRecordedPrevious = false;
                // Render the scene, swap back to front.
                // 🖼️ 普通渲染路径：绘制场景并交换缓冲
                draw();
                swapResult = mWindowSurface.swapBuffers();
            } else {
                // 📝 需要录制本帧
                mRecordedPrevious = true;

                // recording
                // 🎥 录制模式：根据方法选择不同渲染策略
                // 💡 mRecordMethod：当前录制方法（绘制两次/FBO/帧缓冲复制）
                // 💡 作用：决定使用哪种方式将渲染结果送入编码器
                if (mRecordMethod == RECMETHOD_DRAW_TWICE) {
                    //Log.d(TAG, "MODE: draw 2x");

                    // Draw for display, swap.
                    // 🖥️ 先为显示绘制
                    // 📝 渲染场景到显示Surface
                    draw();
                    swapResult = mWindowSurface.swapBuffers();

                    // Draw for recording, swap.
                    // 🎥 再为录制绘制
                    // 📝 通知编码器有新帧可用
                    mVideoEncoder.frameAvailableSoon();
                    // 📝 切换到编码器输入Surface的上下文
                    mInputWindowSurface.makeCurrent();
                    // If we don't set the scissor rect, the glClear() we use to draw the
                    // light-grey background will draw outside the viewport and muck up our
                    // letterboxing.  Might be better if we disabled the test immediately after
                    // the glClear().  Of course, if we were clearing the frame background to
                    // black it wouldn't matter.
                    //
                    // We do still need to clear the pixels outside the scissor rect, of course,
                    // or we'll get garbage at the edges of the recording.  We can either clear
                    // the whole thing and accept that there will be a lot of overdraw, or we
                    // can issue multiple scissor/clear calls.  Some GPUs may have a special
                    // optimization for zeroing out the color buffer.
                    //
                    // For now, be lazy and zero the whole thing.  At some point we need to
                    // examine the performance here.
                    // 🎨 清除背景色，使用裁剪测试确保letterbox区域正确
                    // 📝 设置清除颜色为黑色
                    GLES20.glClearColor(0f, 0f, 0f, 1f);
                    // 📝 清除颜色缓冲区
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

                    // 🖼️ 设置视口和裁剪区域
                    // 📝 设置视口为视频录制区域
                    GLES20.glViewport(mVideoRect.left, mVideoRect.top,
                            mVideoRect.width(), mVideoRect.height());
                    // 📝 启用裁剪测试，限制绘制区域
                    GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
                    // 📝 设置裁剪区域与视口一致
                    GLES20.glScissor(mVideoRect.left, mVideoRect.top,
                            mVideoRect.width(), mVideoRect.height());
                    // 📝 绘制场景到编码器Surface
                    draw();
                    // 📝 禁用裁剪测试
                    GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
                    // 📝 设置呈现时间戳
                    mInputWindowSurface.setPresentationTime(timeStampNanos);
                    // 📝 交换编码器Surface缓冲区
                    mInputWindowSurface.swapBuffers();

                    // Restore.
                    // 🔄 恢复原始视口和上下文
                    // 📝 恢复显示Surface的视口
                    GLES20.glViewport(0, 0, mWindowSurface.getWidth(), mWindowSurface.getHeight());
                    // 📝 切换回显示Surface的上下文
                    mWindowSurface.makeCurrent();

                } else if (mEglCore.getGlVersion() >= 3 &&
                        mRecordMethod == RECMETHOD_BLIT_FRAMEBUFFER) {
                    //Log.d(TAG, "MODE: blitFramebuffer");
                    // Draw the frame, but don't swap it yet.
                    // 🖼️ 使用glBlitFramebuffer复制帧（GLES3+）
                    // 💡 优势：GPU直接复制帧缓冲，无需额外绘制，效率最高
                    // 📝 渲染场景但不交换缓冲（稍后一起处理显示和编码器）
                    draw();

                    // 📤 frameAvailableSoon：通知编码器有新帧可用
                    // 💡 作用：编码器准备接收新帧
                    mVideoEncoder.frameAvailableSoon();
                    // 🖼️ makeCurrentReadFrom：设置编码器Surface从显示Surface读取
                    // 💡 作用：建立blit操作的源（显示）和目标（编码器）关系
                    mInputWindowSurface.makeCurrentReadFrom(mWindowSurface);
                    // Clear the pixels we're not going to overwrite with the blit.  Once again,
                    // this is excessive -- we don't need to clear the entire screen.
                    // 🎨 清除背景（实际只需清除blit未覆盖区域）
                    GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                    // 📝 检查GL错误
                    GlUtil.checkGlError("before glBlitFramebuffer");
                    // 📝 记录blit操作参数日志
                    Log.v(TAG, "glBlitFramebuffer: 0,0," + mWindowSurface.getWidth() + "," +
                            mWindowSurface.getHeight() + "  " + mVideoRect.left + "," +
                            mVideoRect.top + "," + mVideoRect.right + "," + mVideoRect.bottom +
                            "  COLOR_BUFFER GL_NEAREST");
                    // 📋 使用glBlitFramebuffer复制帧缓冲内容
                    // 📝 从显示Surface复制到编码器Surface（GPU直接复制，高效）
                    GLES30.glBlitFramebuffer(
                            0, 0, mWindowSurface.getWidth(), mWindowSurface.getHeight(),
                            mVideoRect.left, mVideoRect.top, mVideoRect.right, mVideoRect.bottom,
                            GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST);
                    // 💡 err：GL错误码
                    // 💡 作用：检查glBlitFramebuffer是否成功
                    int err;
                    if ((err = GLES30.glGetError()) != GLES30.GL_NO_ERROR) {
                        // 📝 glBlitFramebuffer失败，记录错误
                        Log.w(TAG, "ERROR: glBlitFramebuffer failed: 0x" +
                                Integer.toHexString(err));
                    }
                    // 📝 设置呈现时间戳
                    mInputWindowSurface.setPresentationTime(timeStampNanos);
                    // 📝 交换编码器Surface缓冲区
                    mInputWindowSurface.swapBuffers();

                    // Now swap the display buffer.
                    // 🔄 交换显示缓冲
                    // 📝 切换回显示Surface的上下文
                    mWindowSurface.makeCurrent();
                    // 📝 交换显示Surface缓冲区
                    swapResult = mWindowSurface.swapBuffers();

                } else {
                    //Log.d(TAG, "MODE: offscreen + blit 2x");
                    // Render offscreen.
                    // 🖼️ 使用离屏渲染+FBO复制（默认方法）
                    // 💡 优势：只需绘制一次场景，通过FBO复制到显示和编码器
                    // 💡 步骤：绑定FBO→绘制到离屏纹理→解绑FBO→复制到显示和编码器
                    // 📝 绑定FBO，渲染目标从屏幕切换到离屏纹理
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mFramebuffer);
                    GlUtil.checkGlError("glBindFramebuffer");
                    // 📝 绘制场景到离屏纹理（不显示到屏幕）
                    draw();

                    // Blit to display.
                    // 📋 复制到显示Surface
                    // 📝 解绑FBO，渲染目标恢复为屏幕
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                    GlUtil.checkGlError("glBindFramebuffer");
                    // 📝 使用全屏矩形将离屏纹理绘制到显示Surface
                    // 💡 mFullScreen.drawFrame：使用全屏着色器复制纹理
                    // 💡 mOffscreenTexture：离屏纹理ID（FBO的颜色附件）
                    // 💡 mIdentityMatrix：单位矩阵（不做纹理坐标变换）
                    mFullScreen.drawFrame(mOffscreenTexture, mIdentityMatrix);
                    // 📝 交换显示Surface缓冲区，将结果显示到屏幕
                    swapResult = mWindowSurface.swapBuffers();

                    // Blit to encoder.
                    // 🎥 复制到编码器Surface
                    // 📤 frameAvailableSoon：通知编码器有新帧可用
                    mVideoEncoder.frameAvailableSoon();
                    // 📝 切换到编码器Surface的上下文
                    mInputWindowSurface.makeCurrent();
                    // 📝 清除背景为黑色
                    GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);    // again, only really need to
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);     //  clear pixels outside rect
                    // 📝 设置视口为视频录制区域
                    GLES20.glViewport(mVideoRect.left, mVideoRect.top,
                            mVideoRect.width(), mVideoRect.height());
                    // 📝 使用全屏矩形将离屏纹理绘制到编码器Surface
                    mFullScreen.drawFrame(mOffscreenTexture, mIdentityMatrix);
                    // 📝 设置呈现时间戳
                    mInputWindowSurface.setPresentationTime(timeStampNanos);
                    // 📝 交换编码器Surface缓冲区
                    mInputWindowSurface.swapBuffers();

                    // Restore previous values.
                    // 🔄 恢复原始视口和上下文
                    // 📝 恢复显示Surface的视口
                    GLES20.glViewport(0, 0, mWindowSurface.getWidth(), mWindowSurface.getHeight());
                    // 📝 切换回显示Surface的上下文
                    mWindowSurface.makeCurrent();
                }
            }

            // 📝 重置丢帧标记
            mPreviousWasDropped = false;

            // ⚠️ 交换缓冲失败时关闭渲染线程
            if (!swapResult) {
                // This can happen if the Activity stops without waiting for us to halt.
                // 📝 Surface已失效，关闭渲染线程
                Log.w(TAG, "swapBuffers failed, killing renderer thread");
                shutdown();
                return;
            }

            // Update the FPS counter.
            //
            // Ideally we'd generate something approximate quickly to make the UI look
            // reasonable, then ease into longer sampling periods.
            // 📊 更新FPS计数器（每120帧计算一次）
            // 💡 NUM_FRAMES：FPS计算的采样帧数
            // 💡 作用：每渲染120帧计算一次平均帧率
            // 💡 为什么选120：平衡准确性和UI响应速度
            final int NUM_FRAMES = 120;
            // 💡 ONE_TRILLION：一万亿（用于纳秒到秒的转换）
            // 💡 作用：将纳秒时间差转换为秒，用于计算帧率
            final long ONE_TRILLION = 1000000000000L;
            if (mFpsCountStartNanos == 0) {
                // 📝 第一次调用，初始化FPS计数起点
                mFpsCountStartNanos = timeStampNanos;
                mFpsCountFrame = 0;
            } else {
                // 📝 递增帧计数
                mFpsCountFrame++;
                if (mFpsCountFrame == NUM_FRAMES) {
                    // compute thousands of frames per second
                    // 📈 计算千分之一帧率并发送到UI线程
                    // 💡 elapsed：采样期间经过的纳秒数
                    // 💡 作用：计算120帧的总耗时
                    long elapsed = timeStampNanos - mFpsCountStartNanos;
                    // 📤 sendFpsUpdate：发送FPS更新到UI线程
                    // 💡 参数1：千分之一帧率（NUM_FRAMES * ONE_TRILLION / elapsed）
                    // 💡 参数2：丢帧数（mDroppedFrames）
                    mActivityHandler.sendFpsUpdate((int)(NUM_FRAMES * ONE_TRILLION / elapsed),
                            mDroppedFrames);

                    // reset
                    // 🔄 重置计数器，开始下一轮采样
                    mFpsCountStartNanos = timeStampNanos;
                    mFpsCountFrame = 0;
                }
            }
        }

        /**
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
            // 💡 作用：用于计算动画的位移量
            long intervalNanos;
            if (mPrevTimeNanos == 0) {
                // 📝 第一帧没有上一帧时间，间隔设为0
                intervalNanos = 0;
            } else {
                // 📝 计算与上一帧的时间差
                intervalNanos = timeStampNanos - mPrevTimeNanos;

                final long ONE_SECOND_NANOS = 1000000000L;
                // ⚠️ 时间差过大时重置（可能被暂停）
                if (intervalNanos > ONE_SECOND_NANOS) {
                    // A gap this big should only happen if something paused us.  We can
                    // either cap the delta at one second, or just pretend like this is
                    // the first frame and not advance at all.
                    // 📝 超过1秒的时间差说明被暂停过，重置为0
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
            // 🔺 旋转三角形（每3秒转一圈）
            // 💡 SECS_PER_SPIN：每圈旋转秒数
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
         * 🎨 绘制场景：三角形、矩形、边框和录制指示器
         */
        private void draw() {
            // 📝 检查GL错误，标记绘制操作开始
            GlUtil.checkGlError("draw start");

            // Clear to a non-black color to make the content easily differentiable from
            // the pillar-/letter-boxing.
            // 🎨 清除为灰色背景，便于区分letterbox区域
            // 📝 设置清除颜色为灰色
            GLES20.glClearColor(0.2f, 0.2f, 0.2f, 1.0f);
            // 📝 清除颜色缓冲区
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            // 🔺 绘制三角形
            // 📝 使用平面着色程序绘制绿色旋转三角形
            mTri.draw(mProgram, mDisplayProjectionMatrix);
            // 🟥 绘制矩形
            // 📝 使用平面着色程序绘制红色弹跳矩形
            mRect.draw(mProgram, mDisplayProjectionMatrix);
            // 📦 绘制四个边框
            for (int i = 0; i < 4; i++) {
                if (false && mPreviousWasDropped) {
                    // 📝 丢帧时边框变红（调试用，当前禁用）
                    mEdges[i].setColor(1.0f, 0.0f, 0.0f);
                } else {
                    // 📝 正常情况边框为灰色
                    mEdges[i].setColor(0.5f, 0.5f, 0.5f);
                }
                // 📝 绘制边框
                mEdges[i].draw(mProgram, mDisplayProjectionMatrix);
            }

            // Give a visual indication of the recording method.
            // 🎥 根据录制方法设置不同颜色指示器
            switch (mRecordMethod) {
                case RECMETHOD_DRAW_TWICE:
                    // 🔴 红色：绘制两次方法
                    mRecordRect.setColor(1.0f, 0.0f, 0.0f);
                    break;
                case RECMETHOD_FBO:
                    // 🟢 绿色：FBO方法
                    mRecordRect.setColor(0.0f, 1.0f, 0.0f);
                    break;
                case RECMETHOD_BLIT_FRAMEBUFFER:
                    // 🔵 蓝色：帧缓冲复制方法
                    mRecordRect.setColor(0.0f, 0.0f, 1.0f);
                    break;
                default:
            }
            // 📝 绘制录制指示器
            mRecordRect.draw(mProgram, mDisplayProjectionMatrix);

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
        private static final int MSG_SURFACE_CREATED = 0;
        private static final int MSG_SURFACE_CHANGED = 1;
        private static final int MSG_DO_FRAME = 2;
        private static final int MSG_RECORDING_ENABLED = 3;
        private static final int MSG_RECORD_METHOD = 4;
        private static final int MSG_SHUTDOWN = 5;

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
            sendMessage(obtainMessage(RenderHandler.MSG_SURFACE_CREATED));
        }

        /**
         * Sends the "surface changed" message, forwarding what we got from the SurfaceHolder.
         * <p>
         * Call from UI thread.
         * 
         * 📤 发送Surface变化消息（忽略format参数）
         */
        public void sendSurfaceChanged(@SuppressWarnings("unused") int format,
                int width, int height) {
            // ignore format
            sendMessage(obtainMessage(RenderHandler.MSG_SURFACE_CHANGED, width, height));
        }

        /**
         * Sends the "do frame" message, forwarding the Choreographer event.
         * <p>
         * Call from UI thread.
         * 
         * 📤 发送帧绘制消息，转发Choreographer事件
         */
        public void sendDoFrame(long frameTimeNanos) {
            sendMessage(obtainMessage(RenderHandler.MSG_DO_FRAME,
                    (int) (frameTimeNanos >> 32), (int) frameTimeNanos));
        }

        /**
         * Enable or disable recording.
         * <p>
         * Call from non-UI thread.
         * 
         * 🎥 启用/禁用录制（可从非UI线程调用）
         */
        public void setRecordingEnabled(boolean enabled) {
            sendMessage(obtainMessage(MSG_RECORDING_ENABLED, enabled ? 1 : 0, 0));
        }

        /**
         * Set the method used to render a frame for the encoder.
         * <p>
         * Call from non-UI thread.
         * 
         * 🎛️ 设置编码器帧渲染方法
         */
        public void setRecordMethod(int recordMethod) {
            sendMessage(obtainMessage(MSG_RECORD_METHOD, recordMethod, 0));
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
                    // 💡 timestamp：帧时间戳（纳秒级）
                    // 🔍 为什么拆分：Handler只能传递int参数，64位long需要拆成两个int
                    // 💡 作用：传递给doFrame()用于动画计算和录制时间戳设置
                    // ⏰ 使用时机：立即传给renderThread.doFrame()
                    long timestamp = (((long) msg.arg1) << 32) |
                                     (((long) msg.arg2) & 0xffffffffL);
                    renderThread.doFrame(timestamp);
                    break;
                case MSG_RECORDING_ENABLED:
                    // 📝 处理录制启用/禁用消息，arg1!=0表示启用
                    renderThread.setRecordingEnabled(msg.arg1 != 0);
                    break;
                case MSG_RECORD_METHOD:
                    // 📝 处理录制方法变更消息，arg1=录制方法索引
                    renderThread.setRecordMethod(msg.arg1);
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
