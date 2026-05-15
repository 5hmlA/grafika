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

import android.graphics.SurfaceTexture;
import android.opengl.EGLContext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import com.android.grafika.gles.EglCore;
import com.android.grafika.gles.FullFrameRect;
import com.android.grafika.gles.Texture2dProgram;
import com.android.grafika.gles.WindowSurface;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;

/**
 * 🎬 从外部纹理图像渲染的帧编码生成视频。
 * Encode a movie from frames rendered from an external texture image.
 * <p>
 * The object wraps an encoder running on a dedicated thread.  The various control messages
 * may be sent from arbitrary threads (typically the app UI thread).  The encoder thread
 * manages both sides of the encoder (feeding and draining); the only external input is
 * the GL texture.
 * 该对象封装了一个在专用线程上运行的编码器。各种控制消息可以从任意线程发送
 * （通常是应用 UI 线程）。编码器线程管理编码器的两端（输入和输出）；
 * 唯一的外部输入是 GL 纹理。
 * <p>
 * The design is complicated slightly by the need to create an EGL context that shares state
 * with a view that gets restarted if (say) the device orientation changes.  When the view
 * in question is a GLSurfaceView, we don't have full control over the EGL context creation
 * on that side, so we have to bend a bit backwards here.
 * 设计上稍微复杂一些，因为需要创建一个 EGL 上下文来与可能重启的视图共享状态
 * （比如设备方向改变时）。当视图是 GLSurfaceView 时，我们无法完全控制
 * 那边的 EGL 上下文创建，所以需要一些额外的处理。
 * <p>
 * To use:
 * 📖 使用方法：
 * <ul>
 * <li>create TextureMovieEncoder object 创建 TextureMovieEncoder 对象
 * <li>create an EncoderConfig 创建 EncoderConfig 配置
 * <li>call TextureMovieEncoder#startRecording() with the config 调用 startRecording()
 * <li>call TextureMovieEncoder#setTextureId() with the texture object that receives frames 调用 setTextureId()
 * <li>for each frame, after latching it with SurfaceTexture#updateTexImage(),
 *     call TextureMovieEncoder#frameAvailable().
 *     每帧使用 SurfaceTexture#updateTexImage() 后，调用 frameAvailable()
 * </ul>
 *
 * TODO: tweak the API (esp. textureId) so it's less awkward for simple use cases.
 */
public class TextureMovieEncoder implements Runnable {
    private static final String TAG = MainActivity.TAG;
    private static final boolean VERBOSE = false;

    // 📨 消息类型常量
    private static final int MSG_START_RECORDING = 0;       // ▶️ 开始录制
    private static final int MSG_STOP_RECORDING = 1;        // ⏹️ 停止录制
    private static final int MSG_FRAME_AVAILABLE = 2;       // 🖼️ 新帧可用
    private static final int MSG_SET_TEXTURE_ID = 3;        // 🎨 设置纹理 ID
    private static final int MSG_UPDATE_SHARED_CONTEXT = 4; // 🔄 更新共享上下文
    private static final int MSG_QUIT = 5;                  // 🚪 退出
    private static final int MSG_UPDATE_CAMERA_FILTER = 6;  // 🎛️ 与预览一致的相机滤镜
    private static final int MSG_SET_CAMERA_TEXTURE_SIZE = 7; // 📐 相机外部纹理尺寸（卷积滤镜需要）

    // ----- accessed exclusively by encoder thread -----
    // 🔒 仅编码器线程访问的变量
    private WindowSurface mInputWindowSurface;  // 🖼️ 输入窗口表面
    private EglCore mEglCore;                   // 🔧 EGL 核心对象
    private FullFrameRect mFullScreen;          // 📐 全屏矩形绘制器
    private int mTextureId;                     // 🎨 纹理 ID
    private int mFrameNum;                      // 📊 帧计数器
    private VideoEncoderCore mVideoEncoder;     // 🎬 视频编码器核心

    /** 🎛️ 编码器线程：与 CameraCaptureActivity 下拉框一致的滤镜模式 */
    private int mEncoderFilterMode = CameraCaptureActivity.FILTER_NONE;
    /** 📐 相机预览纹理尺寸（与渲染线程 setCameraPreviewSize 同步） */
    private int mEncoderTexWidth = -1;
    private int mEncoderTexHeight = -1;
    /** 避免每帧重复编译 / setTexSize：仅状态变化时重配 */
    private int mEncoderAppliedFilter = Integer.MIN_VALUE;
    private int mEncoderAppliedTexW = -1;
    private int mEncoderAppliedTexH = -1;

    // ----- accessed by multiple threads -----
    // 🔀 多线程访问的变量
    private volatile EncoderHandler mHandler;   // 📨 编码器 Handler

    private Object mReadyFence = new Object();      // guards ready/running 🔒 同步锁
    private boolean mReady;     // ✅ 编码器就绪标志
    private boolean mRunning;   // 🏃 编码器运行标志


    /**
     * Encoder configuration.
     * <p>
     * Object is immutable, which means we can safely pass it between threads without
     * explicit synchronization (and don't need to worry about it getting tweaked out from
     * under us).
     * 📋 编码器配置类（不可变对象，线程安全传递）。
     * <p>
     * TODO: make frame rate and iframe interval configurable?  Maybe use builder pattern
     *       with reasonable defaults for those and bit rate.
     */
    public static class EncoderConfig {
        final File mOutputFile;       // 📁 输出文件
        final int mWidth;             // 📐 视频宽度
        final int mHeight;            // 📐 视频高度
        final int mBitRate;           // 📊 比特率
        final EGLContext mEglContext;  // 🔧 共享的 EGL 上下文

        /**
         * 🔧 构造编码器配置
         * @param outputFile 输出文件路径
         * @param width 视频宽度
         * @param height 视频高度
         * @param bitRate 比特率
         * @param sharedEglContext 共享的 EGL 上下文
         */
        // 🔧 构造函数：初始化编码器配置的所有参数
        public EncoderConfig(File outputFile, int width, int height, int bitRate,
                EGLContext sharedEglContext) {
            // 📁 mOutputFile = outputFile：保存输出文件路径
            // 💡 为什么赋值：编码器需要知道将视频写入哪个文件
            // 💡 作用：编码完成时写入此文件路径
            // 💡 使用时机：传入prepareEncoder()，创建VideoEncoderCore时使用
            mOutputFile = outputFile;
            // 📐 mWidth = width：保存视频宽度
            // 💡 为什么赋值：编码器和EGL表面需要知道视频分辨率
            // 💡 作用：配置MediaFormat和创建EGL窗口Surface
            // 💡 使用时机：传入prepareEncoder()，创建编码格式时使用
            mWidth = width;
            // 📐 mHeight = height：保存视频高度
            // 💡 为什么赋值：编码器和EGL表面需要知道视频分辨率
            // 💡 作用：配置MediaFormat和创建EGL窗口Surface
            // 💡 使用时机：传入prepareEncoder()，创建编码格式时使用
            mHeight = height;
            // 📊 mBitRate = bitRate：保存目标比特率
            // 💡 为什么赋值：控制视频质量和文件大小的平衡
            // 💡 作用：编码器尽量接近此比特率输出
            // 💡 使用时机：传入prepareEncoder()，设置MediaFormat时使用
            mBitRate = bitRate;
            // 🔧 mEglContext = sharedEglContext：保存共享的EGL上下文
            // 💡 为什么赋值：编码器线程需要与主线程共享纹理和EGL状态
            // 💡 作用：创建EglCore时传入，实现纹理共享
            // 💡 使用时机：传入prepareEncoder()，创建EglCore时使用
            mEglContext = sharedEglContext;
        }

        // 📝 返回配置的字符串表示
        @Override
        public String toString() {
            return "EncoderConfig: " + mWidth + "x" + mHeight + " @" + mBitRate +
                    " to '" + mOutputFile.toString() + "' ctxt=" + mEglContext;
        }
    }

    /**
     * Tells the video recorder to start recording.  (Call from non-encoder thread.)
     * <p>
     * Creates a new thread, which will create an encoder using the provided configuration.
     * <p>
     * Returns after the recorder thread has started and is ready to accept Messages.  The
     * encoder may not yet be fully configured.
     * ▶️ 开始录制（从非编码器线程调用）。
     *    创建新线程，使用提供的配置创建编码器。
     *    在录制线程启动并准备好接收消息后返回。编码器可能尚未完全配置。
     */
    public void startRecording(EncoderConfig config) {
        Log.d(TAG, "Encoder: startRecording()");
        // 🔒 synchronized (mReadyFence)：获取同步锁，确保线程安全
        // 💡 为什么需要：mRunning和mReady被多个线程访问，需要同步保护
        // 💡 作用：防止竞态条件，确保状态一致性
        // 💡 使用时机：访问/修改共享状态（mRunning、mReady）时
        synchronized (mReadyFence) {
            // 🔍 if (mRunning)：检查编码器线程是否已在运行
            // 💡 为什么检查：避免重复启动编码器线程
            // 💡 作用：防止资源浪费和状态混乱
            // 💡 使用时机：启动新线程前检查
            if (mRunning) {
                Log.w(TAG, "Encoder thread already running");
                return;
            }
            // ✅ mRunning = true：标记编码器线程正在运行
            // 💡 为什么设置：告知其他线程编码器已启动
            // 💡 作用：防止重复启动，isRecording()可查询状态
            // 💡 使用时机：启动线程前设置
            mRunning = true;
            // 🧵 new Thread(this, "TextureMovieEncoder").start()：创建并启动编码器线程
            // 💡 为什么创建：编码器需要在独立线程上运行，避免阻塞UI线程
            // 💡 作用：启动线程执行run()方法，初始化Looper和Handler
            // 💡 使用时机：设置mRunning后立即启动
            new Thread(this, "TextureMovieEncoder").start();
            // ⏳ while (!mReady)：等待编码器线程初始化完成
            // 💡 为什么等待：线程启动后需要时间初始化Looper和Handler
            // 💡 作用：确保线程完全准备好后再发送消息
            // 💡 使用时机：启动线程后，发送MSG_START_RECORDING之前
            while (!mReady) {
                try {
                    // ⏳ mReadyFence.wait()：释放锁并等待通知
                    // 💡 为什么等待：线程初始化需要时间，不能立即使用
                    // 💡 作用：阻塞当前线程，直到编码器线程调用notify()
                    // 💡 使用时机：mReady为false时持续等待
                    mReadyFence.wait();
                } catch (InterruptedException ie) {
                    // ignore
                }
            }
        }

        mHandler.sendMessage(mHandler.obtainMessage(MSG_START_RECORDING, config));
    }

    /**
     * Tells the video recorder to stop recording.  (Call from non-encoder thread.)
     * <p>
     * Returns immediately; the encoder/muxer may not yet be finished creating the movie.
     * ⏹️ 停止录制（从非编码器线程调用）。
     *    立即返回；编码器/混合器可能尚未完成视频创建。
     * <p>
     * TODO: have the encoder thread invoke a callback on the UI thread just before it shuts down
     * so we can provide reasonable status UI (and let the caller know that movie encoding
     * has completed).
     */
    public void stopRecording() {
        // 📨 mHandler.obtainMessage(MSG_STOP_RECORDING)：构造停止录制消息
        // 💡 为什么发送：通知编码器线程排空编码器并释放资源
        // 💡 作用：触发handleStopRecording()处理
        // 💡 使用时机：用户停止录制时从UI线程调用
        mHandler.sendMessage(mHandler.obtainMessage(MSG_STOP_RECORDING));
        // 📨 mHandler.obtainMessage(MSG_QUIT)：构造退出消息
        // 💡 为什么发送：停止录制后编码器线程应退出以释放线程资源
        // 💡 作用：触发Looper.myLooper().quit()，结束编码器线程的消息循环
        // 💡 使用时机：紧跟在STOP_RECORDING消息之后发送
        mHandler.sendMessage(mHandler.obtainMessage(MSG_QUIT));
        // We don't know when these will actually finish (or even start).  We don't want to
        // delay the UI thread though, so we return immediately.
        // 📤 发送停止和退出消息。不确定何时完成（甚至何时开始），
        //    但不想阻塞 UI 线程，所以立即返回。
    }

    /**
     * Returns true if recording has been started.
     * 🔍 检查是否正在录制。
     */
    public boolean isRecording() {
        synchronized (mReadyFence) {
            return mRunning;
        }
    }

    /**
     * Tells the video recorder to refresh its EGL surface.  (Call from non-encoder thread.)
     * 🔄 刷新编码器的 EGL 表面（从非编码器线程调用）。
     */
    public void updateSharedContext(EGLContext sharedContext) {
        mHandler.sendMessage(mHandler.obtainMessage(MSG_UPDATE_SHARED_CONTEXT, sharedContext));
    }

    /**
     * 🎛️ 设置录制时使用的相机预览滤镜（与 {@link CameraCaptureActivity} 中常量一致）。
     * 从任意线程调用；实际在编码器线程应用 {@link CameraPreviewFilter}。
     */
    public void setCameraFilterMode(int filterMode) {
        synchronized (mReadyFence) {
            if (!mReady || mHandler == null) {
                return;
            }
        }
        mHandler.sendMessage(mHandler.obtainMessage(MSG_UPDATE_CAMERA_FILTER, filterMode, 0));
    }

    /**
     * 📐 同步相机外部纹理的宽高（卷积类滤镜必须在着色器里知道纹理尺寸）。
     */
    public void setCameraTextureSize(int width, int height) {
        synchronized (mReadyFence) {
            if (!mReady || mHandler == null) {
                return;
            }
        }
        mHandler.sendMessage(mHandler.obtainMessage(MSG_SET_CAMERA_TEXTURE_SIZE, width, height));
    }

    /**
     * Tells the video recorder that a new frame is available.  (Call from non-encoder thread.)
     * <p>
     * This function sends a message and returns immediately.  This isn't sufficient -- we
     * don't want the caller to latch a new frame until we're done with this one -- but we
     * can get away with it so long as the input frame rate is reasonable and the encoder
     * thread doesn't stall.
     * 🖼️ 通知编码器新帧可用（从非编码器线程调用）。
     *    发送消息后立即返回。只要输入帧率合理且编码器线程不卡顿就没问题。
     * <p>
     * TODO: either block here until the texture has been rendered onto the encoder surface,
     * or have a separate "block if still busy" method that the caller can execute immediately
     * before it calls updateTexImage().  The latter is preferred because we don't want to
     * stall the caller while this thread does work.
     */
    public void frameAvailable(SurfaceTexture st) {
        // 🔒 synchronized (mReadyFence)：获取同步锁检查编码器是否就绪
        // 💡 为什么需要：mReady被多个线程访问，需要同步保护
        // 💡 作用：确保读取mReady时状态一致
        // 💡 使用时机：发送帧可用消息前检查
        synchronized (mReadyFence) {
            // 🔍 if (!mReady)：检查编码器是否已准备好
            // 💡 为什么检查：编码器未就绪时不能处理帧
            // 💡 作用：避免向未初始化的Handler发送消息
            // 💡 使用时机：每次帧可用时检查
            if (!mReady) {
                return;
            }
        }

        float[] transform = new float[16];      // TODO - avoid alloc every frame ⚠️ 每帧都在分配，应优化
        // 📊 st.getTransformMatrix(transform)：获取SurfaceTexture的变换矩阵
        // 💡 为什么调用：纹理坐标需要变换才能正确渲染
        // 💡 作用：填充4x4变换矩阵，用于纹理采样坐标转换
        // 💡 使用时机：每帧获取，传入handleFrameAvailable()进行渲染
        st.getTransformMatrix(transform);
        // ⏱️ st.getTimestamp()：获取帧的时间戳（纳秒）
        // 💡 为什么获取：编码器需要时间戳来同步帧的呈现时间
        // 💡 作用：返回SurfaceTexture中当前帧的presentation time
        // 💡 使用时机：传入MSG_FRAME_AVAILABLE消息，用于编码器时间同步
        long timestamp = st.getTimestamp();
        // 🔍 if (timestamp == 0)：检查时间戳是否为0
        // 💡 为什么检查：时间戳为0是异常情况，会导致编码器崩溃
        // 💡 作用：过滤无效帧，防止MPEG4Writer在native层abort()
        // 💡 使用时机：获取时间戳后立即检查
        if (timestamp == 0) {
            // Seeing this after device is toggled off/on with power button.  The
            // first frame back has a zero timestamp.
            //
            // MPEG4Writer thinks this is cause to abort() in native code, so it's very
            // important that we just ignore the frame.
            // ⚠️ 设备电源键开关后第一帧时间戳可能为 0。
            //    MPEG4Writer 会因此在 native 层 abort()，所以必须忽略此帧。
            Log.w(TAG, "HEY: got SurfaceTexture with timestamp of zero");
            return;
        }

        mHandler.sendMessage(mHandler.obtainMessage(MSG_FRAME_AVAILABLE,
                (int) (timestamp >> 32), (int) timestamp, transform));
    }

    /**
     * Tells the video recorder what texture name to use.  This is the external texture that
     * we're receiving camera previews in.  (Call from non-encoder thread.)
     * 🎨 设置视频录制器使用的纹理名称（从非编码器线程调用）。
     *    这是我们接收相机预览的外部纹理。
     * <p>
     * TODO: do something less clumsy
     */
    public void setTextureId(int id) {
        // 🔒 synchronized (mReadyFence)：获取同步锁检查编码器是否就绪
        // 💡 为什么需要：mReady被多个线程访问，需要同步保护
        // 💡 作用：确保读取mReady时状态一致
        // 💡 使用时机：发送设置纹理消息前检查
        synchronized (mReadyFence) {
            // 🔍 if (!mReady)：检查编码器是否已准备好
            // 💡 为什么检查：编码器未就绪时不能设置纹理
            // 💡 作用：避免向未初始化的Handler发送消息
            // 💡 使用时机：获取同步锁后立即检查
            if (!mReady) {
                return;
            }
        }
        // 📨 mHandler.obtainMessage(MSG_SET_TEXTURE_ID, id, 0, null)：构造设置纹理消息
        // 💡 为什么发送：通知编码器线程更新纹理ID
        // 💡 作用：arg1携带纹理ID，传入handleSetTexture()
        // 💡 使用时机：编码器就绪后立即发送
        mHandler.sendMessage(mHandler.obtainMessage(MSG_SET_TEXTURE_ID, id, 0, null));
    }

    /**
     * Encoder thread entry point.  Establishes Looper/Handler and waits for messages.
     * 🧵 编码器线程入口。创建 Looper/Handler 并等待消息。
     * <p>
     * @see java.lang.Thread#run()
     */
    @Override
    public void run() {
        // Establish a Looper for this thread, and define a Handler for it.
        // 🔧 Looper.prepare()：为当前线程准备消息循环器
        // 💡 为什么调用：Handler需要绑定到Looper才能接收消息
        // 💡 作用：初始化线程的消息队列
        // 💡 使用时机：线程入口，任何Handler操作之前
        Looper.prepare();
        // 🔒 synchronized (mReadyFence)：获取同步锁，保护共享状态
        // 💡 为什么需要：mHandler和mReady被多个线程访问
        // 💡 作用：确保Handler创建和就绪标记的原子性
        // 💡 使用时机：创建Handler和设置mReady时
        synchronized (mReadyFence) {
            // 📨 mHandler = new EncoderHandler(this)：创建编码器消息处理器
            // 💡 为什么创建：需要Handler来接收和处理其他线程发送的消息
            // 💡 作用：绑定到当前线程的Looper，处理MSG_START_RECORDING等消息
            // 💡 使用时机：Looper.prepare()之后，Looper.loop()之前
            mHandler = new EncoderHandler(this);
            // ✅ mReady = true：标记编码器线程已就绪
            // 💡 为什么设置：startRecording()中while(!mReady)循环在等待此标记变为true
            // 💡 作用：告知主线程编码器线程已准备好接收消息
            // 💡 使用时机：Handler创建完成后立即设置
            mReady = true;              // ✅ 标记就绪
            // 🔔 mReadyFence.notify()：通知等待的线程
            // 💡 为什么调用：startRecording()中waitUntilReady()在等待此通知
            // 💡 作用：唤醒在mReadyFence上等待的线程
            // 💡 使用时机：mHandler创建完成且mReady设为true之后
            mReadyFence.notify();       // 🔔 通知等待的线程
        }
        // 🔄 Looper.loop()：开始消息循环
        // 💡 为什么调用：启动消息队列的无限循环，处理入队的消息
        // 💡 作用：阻塞线程，持续从队列取出消息并分发到Handler
        // 💡 使用时机：Handler创建完成之后，线程退出之前
        Looper.loop();                  // 🔄 开始消息循环

        // 📝 Log.d(TAG, "Encoder thread exiting")：日志记录：编码器线程正在退出
        // 💡 为什么记录：调试时需要知道编码器线程何时退出
        // 💡 作用：在logcat中输出线程退出信息
        // 💡 使用时机：Looper.loop()返回后，线程结束前
        Log.d(TAG, "Encoder thread exiting");  // 🚪 线程退出
        // 🔒 synchronized (mReadyFence)：获取同步锁，重置状态
        // 💡 为什么需要：mHandler、mReady、mRunning被多个线程访问
        // 💡 作用：确保状态重置的原子性
        // 💡 使用时机：Looper退出后，线程结束前
        synchronized (mReadyFence) {
            // ❌ mReady = mRunning = false：重置就绪和运行标志
            // 💡 为什么重置：线程已退出，标记为未就绪未运行
            // 💡 作用：让isRecording()返回false，防止向已退出的线程发消息
            // 💡 使用时机：Looper.loop()返回后
            mReady = mRunning = false;  // ❌ 重置状态
            // 🔄 mHandler = null：清空Handler引用
            // 💡 为什么置空：线程已退出，Handler不再可用
            // 💡 作用：防止其他线程向已退出的线程发送消息
            // 💡 使用时机：重置mReady和mRunning之后
            mHandler = null;
        }
    }


    /**
     * Handles encoder state change requests.  The handler is created on the encoder thread.
     * 📨 编码器消息处理器（在编码器线程上创建）。
     *    处理来自其他线程的编码器状态变更请求。
     */
    private static class EncoderHandler extends Handler {
        private WeakReference<TextureMovieEncoder> mWeakEncoder;  // 🎣 弱引用避免内存泄漏

        /**
         * 🔧 构造函数，使用弱引用持有编码器实例。
         */
        public EncoderHandler(TextureMovieEncoder encoder) {
            mWeakEncoder = new WeakReference<TextureMovieEncoder>(encoder);
        }

        @Override  // runs on encoder thread 🧵 在编码器线程上运行
        public void handleMessage(Message inputMessage) {
            // 📨 what: 消息类型标识，用于 switch 分支判断
            //    作用：区分不同的编码器操作（开始/停止/帧可用等）
            //    使用时机：switch 判断前读取
            int what = inputMessage.what;
            // 📦 obj: 消息携带的通用数据对象
            //    作用：携带 EncoderConfig / float[] 变换矩阵等数据
            //    使用时机：根据 what 类型强转后使用
            Object obj = inputMessage.obj;

            // 🎣 encoder: 通过弱引用获取编码器实例
            //    作用：避免 Handler 持有强引用导致内存泄漏
            //    使用时机：处理每个消息前获取，可能为 null（已被 GC 回收）
            TextureMovieEncoder encoder = mWeakEncoder.get();
            if (encoder == null) {
                // ⚠️ 编码器对象已被垃圾回收，无法处理消息
                Log.w(TAG, "EncoderHandler.handleMessage: encoder is null");
                return;  // 🚫 编码器已被回收
            }

            // 🔀 根据消息类型分发到不同的处理方法
            switch (what) {
                case MSG_START_RECORDING:    // ▶️ 开始录制
                    // 📋 obj 强转为 EncoderConfig，传入开始录制处理方法
                    encoder.handleStartRecording((EncoderConfig) obj);
                    break;
                case MSG_STOP_RECORDING:     // ⏹️ 停止录制
                    // ⏹️ 调用停止录制处理方法（排空编码器 + 释放资源）
                    encoder.handleStopRecording();
                    break;
                case MSG_FRAME_AVAILABLE:    // 🖼️ 新帧可用
                    // ⏱️ timestamp: 从 arg1/arg2 重建 64 位时间戳（纳秒）
                    //    作用：SurfaceTexture 的帧时间戳，用于编码器同步
                    //    使用时机：传入 handleFrameAvailable 设置呈现时间
                    //    原理：Message 的 arg 只有 32 位，需要拆分高低 32 位传输
                    long timestamp = (((long) inputMessage.arg1) << 32) |
                            (((long) inputMessage.arg2) & 0xffffffffL);
                    // 🎨 obj 强转为 float[16] 变换矩阵，传入帧处理方法
                    encoder.handleFrameAvailable((float[]) obj, timestamp);
                    break;
                case MSG_SET_TEXTURE_ID:     // 🎨 设置纹理 ID
                    // 🎨 arg1 携带纹理 ID，传入设置纹理方法
                    encoder.handleSetTexture(inputMessage.arg1);
                    break;
                case MSG_UPDATE_SHARED_CONTEXT:  // 🔄 更新共享上下文
                    // 🔄 obj 强转为新的 EGL 上下文，传入更新方法
                    encoder.handleUpdateSharedContext((EGLContext) inputMessage.obj);
                    break;
                case MSG_UPDATE_CAMERA_FILTER:
                    encoder.handleUpdateCameraFilter(inputMessage.arg1);
                    break;
                case MSG_SET_CAMERA_TEXTURE_SIZE:
                    encoder.handleSetCameraTextureSize(inputMessage.arg1, inputMessage.arg2);
                    break;
                case MSG_QUIT:               // 🚪 退出
                    // 🚪 退出当前线程的 Looper 消息循环，结束编码器线程
                    Looper.myLooper().quit();
                    break;
                default:
                    // 🚨 未识别的消息类型，抛出运行时异常
                    throw new RuntimeException("Unhandled msg what=" + what);
            }
        }
    }

    private void handleUpdateCameraFilter(int filterMode) {
        mEncoderFilterMode = filterMode;
        syncEncoderProgramIfNeeded();
    }

    private void handleSetCameraTextureSize(int width, int height) {
        mEncoderTexWidth = width;
        mEncoderTexHeight = height;
        syncEncoderProgramIfNeeded();
    }

    /**
     * 在编码器 EGL 上下文里复用与预览相同的 {@link CameraPreviewFilter} 配置。
     */
    private void syncEncoderProgramIfNeeded() {
        if (mFullScreen == null) {
            return;
        }
        if (mEncoderAppliedFilter == mEncoderFilterMode
                && mEncoderAppliedTexW == mEncoderTexWidth
                && mEncoderAppliedTexH == mEncoderTexHeight) {
            return;
        }
        CameraPreviewFilter.apply(mEncoderFilterMode, mFullScreen, mEncoderTexWidth, mEncoderTexHeight);
        mEncoderAppliedFilter = mEncoderFilterMode;
        mEncoderAppliedTexW = mEncoderTexWidth;
        mEncoderAppliedTexH = mEncoderTexHeight;
    }

    /**
     * Starts recording.
     * ▶️ 开始录制，初始化编码器。
     */
    private void handleStartRecording(EncoderConfig config) {
        Log.d(TAG, "handleStartRecording " + config);
        // 📊 mFrameNum = 0：重置帧计数器
        // 💡 为什么重置：新的一次录制应从第0帧开始计数
        // 💡 作用：为drawBox()提供帧序号，绘制移动方块
        // 💡 使用时机：每次开始录制时
        mFrameNum = 0;  // 📊 重置帧计数器
        // 🔧 prepareEncoder(...)：初始化编码器及其关联的EGL环境
        // 💡 为什么调用：需要创建VideoEncoderCore、EGL上下文、窗口表面和绘制程序
        // 💡 作用：建立完整的编码管线（编码器→EGL→输入表面→绘制器）
        // 💡 使用时机：帧计数器重置后立即初始化
        prepareEncoder(config.mEglContext, config.mWidth, config.mHeight, config.mBitRate,
                config.mOutputFile);
    }

    /**
     * Handles notification of an available frame.
     * <p>
     * The texture is rendered onto the encoder's input surface, along with a moving
     * box (just because we can).
     * 🖼️ 处理可用帧通知。将纹理渲染到编码器的输入表面，并绘制一个移动方块。
     * <p>
     * @param transform The texture transform, from SurfaceTexture. 纹理变换矩阵
     * @param timestampNanos The frame's timestamp, from SurfaceTexture. 帧时间戳（纳秒）
     */
    private void handleFrameAvailable(float[] transform, long timestampNanos) {
        if (VERBOSE) Log.d(TAG, "handleFrameAvailable tr=" + transform);
        syncEncoderProgramIfNeeded();
        mVideoEncoder.drainEncoder(false);               // 📤 先排空编码器
        // 🎨 mFullScreen.drawFrame(mTextureId, transform)：将纹理绘制到编码器输入表面
        // 💡 为什么调用：需要将外部纹理（如相机预览）渲染到编码器Surface
        // 💡 作用：使用全屏矩形和变换矩阵绘制纹理
        // 💡 使用时机：每帧调用，drainEncoder之后、swapBuffers之前
        mFullScreen.drawFrame(mTextureId, transform);    // 🎨 绘制纹理到编码器表面

        drawBox(mFrameNum++);                            // 📦 绘制移动方块

        // ⏱️ mInputWindowSurface.setPresentationTime(timestampNanos)：设置帧的呈现时间
        // 💡 为什么调用：编码器需要正确的时间戳来生成视频
        // 💡 作用：将SurfaceTexture的时间戳传递给编码器输入Surface
        // 💡 使用时机：绘制完成后、交换缓冲区之前
        mInputWindowSurface.setPresentationTime(timestampNanos);  // ⏱️ 设置呈现时间
        // 🔄 mInputWindowSurface.swapBuffers()：交换前后缓冲区
        // 💡 为什么调用：将渲染的内容提交给编码器
        // 💡 作用：触发编码器处理当前帧，使绘制结果对编码器可见
        // 💡 使用时机：设置时间戳之后，每帧最后调用
        mInputWindowSurface.swapBuffers();                        // 🔄 交换缓冲区
    }

    /**
     * Handles a request to stop encoding.
     * ⏹️ 处理停止编码请求，排空编码器并释放资源。
     */
    private void handleStopRecording() {
        Log.d(TAG, "handleStopRecording");
        // 📤 mVideoEncoder.drainEncoder(true)：排空编码器（结束模式）
        // 💡 为什么调用：停止录制前需要flush编码器中剩余的帧
        // 💡 作用：参数true表示发送EOS信号，确保所有帧被编码输出
        // 💡 使用时机：停止录制时，释放资源之前
        mVideoEncoder.drainEncoder(true);
        // 🗑️ releaseEncoder()：释放编码器相关资源
        // 💡 为什么调用：停止录制后需要释放所有GPU和编码器资源
        // 💡 作用：释放VideoEncoderCore、WindowSurface、FullFrameRect、EglCore
        // 💡 使用时机：drainEncoder(true)之后
        releaseEncoder();
    }

    /**
     * Sets the texture name that SurfaceTexture will use when frames are received.
     * 🎨 设置 SurfaceTexture 接收帧时使用的纹理名称。
     */
    private void handleSetTexture(int id) {
        //Log.d(TAG, "handleSetTexture " + id);
        mTextureId = id;
    }

    /**
     * Tears down the EGL surface and context we've been using to feed the MediaCodec input
     * surface, and replaces it with a new one that shares with the new context.
     * <p>
     * This is useful if the old context we were sharing with went away (maybe a GLSurfaceView
     * that got torn down) and we need to hook up with the new one.
     * 🔄 销毁旧的 EGL 表面和上下文，用新的共享上下文重建。
     *    当旧的共享上下文消失时（如 GLSurfaceView 被销毁）很有用。
     */
    private void handleUpdateSharedContext(EGLContext newSharedContext) {
        Log.d(TAG, "handleUpdatedSharedContext " + newSharedContext);

        // Release the EGLSurface and EGLContext.
        // 🗑️ 释放旧的 EGL 资源
        // 🗑️ mInputWindowSurface.releaseEglSurface()：释放窗口Surface的EGL表面
        // 💡 为什么调用：旧的共享上下文已失效，需要释放相关资源
        // 💡 作用：解除EGL表面与窗口的关联，释放图形资源
        // 💡 使用时机：更新共享上下文时，先释放旧资源
        mInputWindowSurface.releaseEglSurface();
        // 🗑️ mFullScreen.release(false)：释放全屏矩形绘制器
        // 💡 为什么调用：绘制器关联旧的EGL上下文，必须释放
        // 💡 作用：释放着色器程序和纹理资源
        // 💡 使用时机：释放EGL表面之后
        mFullScreen.release(false);
        // 🗑️ mEglCore.release()：释放EGL核心对象
        // 💡 为什么调用：旧的EGL上下文已失效
        // 💡 作用：释放EGL显示连接和上下文资源
        // 💡 使用时机：释放Surface和绘制器之后
        mEglCore.release();

        // Create a new EGLContext and recreate the window surface.
        // 🔧 用新的共享上下文创建 EGL 资源
        // 🔧 mEglCore = new EglCore(...)：用新共享上下文创建EGL核心
        // 💡 为什么创建：需要与新的GLSurfaceView共享纹理和状态
        // 💡 作用：建立新的EGL上下文，FLAG_RECORDABLE表示可用于视频录制
        // 💡 使用时机：释放旧资源后立即创建
        mEglCore = new EglCore(newSharedContext, EglCore.FLAG_RECORDABLE);
        // 🔧 mInputWindowSurface.recreate(mEglCore)：用新EGL核心重建窗口Surface
        // 💡 为什么调用：旧Surface已释放，需要用新上下文重建
        // 💡 作用：创建新的EGL表面，关联编码器输入Surface
        // 💡 使用时机：创建新EglCore后立即重建
        mInputWindowSurface.recreate(mEglCore);
        // ▶️ mInputWindowSurface.makeCurrent()：将新Surface设为当前渲染目标
        // 💡 为什么调用：后续OpenGL调用需要知道渲染到哪个Surface
        // 💡 作用：激活新的EGL上下文和表面
        // 💡 使用时机：重建Surface后立即调用
        mInputWindowSurface.makeCurrent();

        // Create new programs and such for the new context.
        // 🎨 为新上下文创建全屏矩形绘制程序
        mFullScreen = new FullFrameRect(
                new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_EXT));
        mEncoderAppliedFilter = Integer.MIN_VALUE;
        mEncoderAppliedTexW = mEncoderAppliedTexH = -1;
        syncEncoderProgramIfNeeded();
    }

    /**
     * 🔧 初始化编码器：创建 VideoEncoderCore、EGL 上下文、窗口表面和绘制程序。
     */
    private void prepareEncoder(EGLContext sharedContext, int width, int height, int bitRate,
            File outputFile) {
        try {
            // 🎬 mVideoEncoder = new VideoEncoderCore(...)：创建视频编码器核心
            // 💡 为什么创建：需要编码器来将渲染的帧编码为H.264格式
            // 💡 作用：初始化MediaCodec编码器和MediaMuxer复用器
            // 💡 使用时机：开始录制时，初始化编码管线
            mVideoEncoder = new VideoEncoderCore(width, height, bitRate, outputFile);
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
        // 🔧 mEglCore = new EglCore(...)：创建EGL核心对象
        // 💡 为什么创建：需要EGL上下文来进行离屏渲染
        // 💡 作用：建立与共享上下文共享纹理的EGL环境
        // 💡 使用时机：VideoEncoderCore创建之后
        mEglCore = new EglCore(sharedContext, EglCore.FLAG_RECORDABLE);
        // 🖼️ mInputWindowSurface = new WindowSurface(...)：创建窗口Surface
        // 💡 为什么创建：需要将渲染结果输出到编码器的输入Surface
        // 💡 作用：包装编码器输入Surface为EGL可渲染的WindowSurface
        // 💡 使用时机：EglCore创建之后
        mInputWindowSurface = new WindowSurface(mEglCore, mVideoEncoder.getInputSurface(), true);
        // ▶️ mInputWindowSurface.makeCurrent()：将新Surface设为当前渲染目标
        // 💡 为什么调用：后续OpenGL调用需要知道渲染到哪个Surface
        // 💡 作用：激活EGL上下文和表面
        // 💡 使用时机：WindowSurface创建之后
        mInputWindowSurface.makeCurrent();

        // 🎨 mFullScreen = new FullFrameRect(...)：创建全屏矩形绘制器
        // 💡 为什么创建：需要绘制器将外部纹理渲染到编码器Surface
        // 💡 作用：封装OpenGL绘制逻辑，使用OES_external纹理
        // 💡 使用时机：WindowSurface激活之后
        mFullScreen = new FullFrameRect(
                new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_EXT));
        mEncoderAppliedFilter = Integer.MIN_VALUE;
        mEncoderAppliedTexW = mEncoderAppliedTexH = -1;
        syncEncoderProgramIfNeeded();
    }

    /**
     * 🗑️ 释放编码器资源（视频编码器、窗口表面、全屏绘制器、EGL 核心）。
     */
    private void releaseEncoder() {
        mVideoEncoder.release();
        // 🔍 if (mInputWindowSurface != null)：检查窗口Surface是否存在
        // 💡 为什么检查：避免对null对象调用方法导致NullPointerException
        // 💡 作用：安全释放资源的前提条件检查
        // 💡 使用时机：每次释放资源前检查
        if (mInputWindowSurface != null) {
            // 🗑️ mInputWindowSurface.release()：释放窗口Surface
            // 💡 为什么调用：Surface持有EGL资源和Native内存
            // 💡 作用：释放EGL表面和关联的图形资源
            // 💡 使用时机：编码器释放之后
            mInputWindowSurface.release();
            // 🔄 mInputWindowSurface = null：将引用置空
            // 💡 为什么置空：防止重复释放，帮助GC回收
            // 💡 作用：标记资源已释放，避免悬挂引用
            // 💡 使用时机：release()之后立即置空
            mInputWindowSurface = null;
        }
        // 🔍 if (mFullScreen != null)：检查全屏绘制器是否存在
        // 💡 为什么检查：避免对null对象调用方法导致NullPointerException
        // 💡 作用：安全释放资源的前提条件检查
        // 💡 使用时机：每次释放资源前检查
        if (mFullScreen != null) {
            // 🗑️ mFullScreen.release(false)：释放全屏绘制器
            // 💡 为什么调用：绘制器持有OpenGL资源（着色器、纹理）
            // 💡 作用：释放GPU资源，参数false表示不释放外部纹理
            // 💡 使用时机：窗口Surface释放之后
            mFullScreen.release(false);
            // 🔄 mFullScreen = null：将引用置空
            // 💡 为什么置空：防止重复释放，帮助GC回收
            // 💡 作用：标记资源已释放，避免悬挂引用
            // 💡 使用时机：release()之后立即置空
            mFullScreen = null;
        }
        // 🔍 if (mEglCore != null)：检查EGL核心对象是否存在
        // 💡 为什么检查：避免对null对象调用方法导致NullPointerException
        // 💡 作用：安全释放资源的前提条件检查
        // 💡 使用时机：每次释放资源前检查
        if (mEglCore != null) {
            // 🗑️ mEglCore.release()：释放EGL核心对象
            // 💡 为什么调用：EGL核心持有显示连接和上下文资源
            // 💡 作用：释放EGL显示连接、上下文等系统资源
            // 💡 使用时机：全屏绘制器释放之后
            mEglCore.release();
            // 🔄 mEglCore = null：将引用置空
            // 💡 为什么置空：防止重复释放，帮助GC回收
            // 💡 作用：标记资源已释放，避免悬挂引用
            // 💡 使用时机：release()之后立即置空
            mEglCore = null;
        }
    }

    /**
     * Draws a box, with position offset.
     * 📦 绘制一个位置随帧数变化的紫色方块（用于可视化效果）。
     */
    private void drawBox(int posn) {
        // 📐 width：获取输入窗口表面的宽度
        // 💡 为什么获取：用于计算方块的X坐标范围
        // 💡 作用：确定方块可以移动的最大宽度
        // 💡 使用时机：计算xpos时使用
        final int width = mInputWindowSurface.getWidth();
        // 📍 xpos：方块的X坐标位置，随帧数增加而右移，到达边界后回弹
        // 💡 为什么计算：每帧移动4像素，在窗口宽度内循环
        // 💡 作用：产生方块从左到右的平移动画效果
        // 💡 使用时机：传入glScissor()设置裁剪区域
        int xpos = (posn * 4) % (width - 50);  // 📍 计算方块 x 坐标
        // ✂️ glEnable(GL_SCISSOR_TEST)：启用裁剪测试
        // 💡 为什么启用：只想清除指定区域而非整个画面
        // 💡 作用：后续glClear只影响glScissor定义的区域
        // 💡 使用时机：绘制方块前启用
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
        // ✂️ glScissor(xpos, 0, 100, 100)：设置裁剪区域为100x100像素的方块
        // 💡 为什么设置：定义方块的位置和大小
        // 💡 作用：限制后续绘制操作只在此矩形区域内生效
        // 💡 使用时机：启用裁剪测试后设置
        GLES20.glScissor(xpos, 0, 100, 100);   // ✂️ 设置裁剪区域（100x100 方块）
        // 🟣 glClearColor(1.0f, 0.0f, 1.0f, 1.0f)：设置清屏颜色为紫色
        // 💡 为什么设置：紫色方块作为可视化标记，易于辨识
        // 💡 作用：定义清除颜色（R=1, G=0, B=1 → 紫色）
        // 💡 使用时机：设置裁剪区域后、清除之前
        GLES20.glClearColor(1.0f, 0.0f, 1.0f, 1.0f);  // 🟣 紫色
        // 🧹 glClear(GL_COLOR_BUFFER_BIT)：用紫色清除裁剪区域
        // 💡 为什么调用：将裁剪区域内的像素设置为紫色
        // 💡 作用：绘制紫色方块（实际就是清除为紫色）
        // 💡 使用时机：设置颜色后立即清除
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        // ❌ glDisable(GL_SCISSOR_TEST)：关闭裁剪测试
        // 💡 为什么关闭：绘制完方块后恢复正常绘制
        // 💡 作用：后续绘制操作不再受裁剪区域限制
        // 💡 使用时机：方块绘制完成后立即关闭
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
    }
}
