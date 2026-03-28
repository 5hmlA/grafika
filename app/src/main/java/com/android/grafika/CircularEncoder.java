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

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;

/**
 * 🔄 在固定大小的环形缓冲区中编码视频。
 * Encodes video in a fixed-size circular buffer.
 * <p>
 * The obvious way to do this would be to store each packet in its own buffer and hook it
 * into a linked list.  The trouble with this approach is that it requires constant
 * allocation, which means we'll be driving the GC to distraction as the frame rate and
 * bit rate increase.  Instead we create fixed-size pools for video data and metadata,
 * which requires a bit more work for us but avoids allocations in the steady state.
 * 显而易见的方法是为每个包分配独立缓冲区并链接成链表。
 * 但这种方法需要持续分配内存，帧率和比特率越高 GC 压力越大。
 * 因此我们创建固定大小的视频数据和元数据池，虽然更复杂但避免了稳态分配。
 * <p>
 * Video must always start with a sync frame (a/k/a key frame, a/k/a I-frame).  When the
 * circular buffer wraps around, we either need to delete all of the data between the frame at
 * the head of the list and the next sync frame, or have the file save function know that
 * it needs to scan forward for a sync frame before it can start saving data.
 * 视频必须以同步帧（关键帧 / I 帧）开头。环形缓冲区回绕时，
 * 要么删除头部帧到下一个同步帧之间的数据，要么让保存函数扫描到同步帧再开始保存。
 * <p>
 * When we're told to save a snapshot, we create a MediaMuxer, write all the frames out,
 * and then go back to what we were doing.
 * 收到保存快照指令时，创建 MediaMuxer，写出所有帧，然后继续之前的工作。
 */
public class CircularEncoder {
    private static final String TAG = MainActivity.TAG;
    private static final boolean VERBOSE = false;

    private static final String MIME_TYPE = "video/avc";    // H.264 Advanced Video Coding 🎬 H.264 编码格式
    private static final int IFRAME_INTERVAL = 1;           // sync frame every second ⏱️ 每秒一个同步帧

    private EncoderThread mEncoderThread;   // 🧵 编码器线程
    private Surface mInputSurface;          // 🖼️ 输入表面
    private MediaCodec mEncoder;            // 🎬 MediaCodec 编码器

    /**
     * Callback function definitions.  CircularEncoder caller must provide one.
     * 📞 回调接口定义。调用者必须提供实现。
     */
    public interface Callback {
        /**
         * Called some time after saveVideo(), when all data has been written to the
         * output file.
         * 💾 文件保存完成后回调。
         *
         * @param status Zero means success, nonzero indicates failure. 0 表示成功，非 0 表示失败
         */
        void fileSaveComplete(int status);

        /**
         * Called occasionally.
         * 📊 定期回调，报告缓冲区状态。
         *
         * @param totalTimeMsec Total length, in milliseconds, of buffered video. 缓冲视频总时长（毫秒）
         */
        void bufferStatus(long totalTimeMsec);
    }

    /**
     * Configures encoder, and prepares the input Surface.
     * 🔧 配置编码器并准备输入表面。
     *
     * @param width Width of encoded video, in pixels.  Should be a multiple of 16.
     *              编码视频宽度（像素），应为 16 的倍数
     * @param height Height of encoded video, in pixels.  Usually a multiple of 16 (1080 is ok).
     *               编码视频高度（像素）
     * @param bitRate Target bit rate, in bits.
     *                目标比特率
     * @param frameRate Expected frame rate.
     *                  预期帧率
     * @param desiredSpanSec How many seconds of video we want to have in our buffer at any time.
     *                       缓冲区期望的视频秒数
     * @param cb Callback interface for file save completion and buffer status.
     *           文件保存完成和缓冲区状态的回调接口
     */
    // 🔧 构造函数：初始化环形编码器
    public CircularEncoder(int width, int height, int bitRate, int frameRate, int desiredSpanSec,
            Callback cb) throws IOException {
        // The goal is to size the buffer so that we can accumulate N seconds worth of video,
        // where N is passed in as "desiredSpanSec".  If the codec generates data at roughly
        // the requested bit rate, we can compute it as time * bitRate / bitsPerByte.
        //
        // Sync frames will appear every (frameRate * IFRAME_INTERVAL) frames.  If the frame
        // rate is higher or lower than expected, various calculations may not work out right.
        //
        // Since we have to start muxing from a sync frame, we want to ensure that there's
        // room for at least one full GOP in the buffer, preferrably two.
        //
        // 🎯 目标是调整缓冲区大小以累积 N 秒的视频（N = desiredSpanSec）。
        //    如果编码器按请求的比特率生成数据，可按 time * bitRate / bitsPerByte 计算。
        //    同步帧每 (frameRate * IFRAME_INTERVAL) 帧出现一次。
        //    因为必须从同步帧开始混合，所以缓冲区至少容纳一个完整 GOP（最好两个）。

        // ⚠️ 验证时间跨度是否足够容纳至少两个同步帧间隔
        // 💡 为什么检查：环形缓冲区需要至少两个GOP才能正确回绕保存
        if (desiredSpanSec < IFRAME_INTERVAL * 2) {
            throw new RuntimeException("Requested time span is too short: " + desiredSpanSec +
                    " vs. " + (IFRAME_INTERVAL * 2));
        }
        // 🔄 encBuffer：环形编码缓冲区
        // 💡 为什么定义：在固定大小的缓冲区中循环存储编码数据，避免频繁内存分配
        // 💡 作用：存储编码后的视频帧，支持按时间跨度回绕
        // 💡 使用时机：drainEncoder()中添加数据，saveVideo()中读取数据
        CircularEncoderBuffer encBuffer = new CircularEncoderBuffer(bitRate, frameRate,
                desiredSpanSec);

        // 📊 format：视频编码格式配置
        // 💡 为什么定义：MediaCodec需要知道编码参数
        // 💡 作用：指定MIME类型、宽高、颜色格式、比特率、帧率等
        // 💡 使用时机：传入mEncoder.configure()
        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);

        // Set some properties.  Failing to specify some of these can cause the MediaCodec
        // configure() call to throw an unhelpful exception.
        // ⚙️ 设置编码参数。缺少某些参数可能导致 configure() 抛出无用异常。
        // 🎨 设置颜色格式为Surface输入（编码器从Surface直接读取纹理）
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        // 📊 设置目标比特率（控制视频质量和文件大小）
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        // 🎞️ 设置预期帧率（编码器用于时间计算）
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        // ⏱️ 设置I帧间隔秒数（每隔指定秒数生成关键帧）
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
        // 📝 如果启用详细日志，输出格式信息
        if (VERBOSE) Log.d(TAG, "format: " + format);

        // Create a MediaCodec encoder, and configure it with our format.  Get a Surface
        // we can use for input and wrap it with a class that handles the EGL work.
        // 🔧 创建 MediaCodec 编码器，配置格式，获取输入 Surface。
        // 🎬 根据MIME类型创建H.264编码器
        mEncoder = MediaCodec.createEncoderByType(MIME_TYPE);
        // ⚙️ 配置编码器为编码模式，不直接输出到Surface
        mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        // 🖼️ 获取编码器的输入Surface（调用者在此Surface上渲染帧）
        mInputSurface = mEncoder.createInputSurface();
        // ▶️ 启动编码器，分配硬件资源
        mEncoder.start();

        // Start the encoder thread last.  That way we're sure it can see all of the state
        // we've initialized.
        // 🧵 最后启动编码器线程，确保能看到所有已初始化的状态。
        // 🧵 mEncoderThread：编码器工作线程
        // 💡 为什么定义：在独立线程中排空编码器输出，避免阻塞UI线程
        // 💡 作用：处理编码器输出、管理环形缓冲区、执行文件保存
        // 💡 使用时机：构造函数中创建并启动
        mEncoderThread = new EncoderThread(mEncoder, encBuffer, cb);
        // ▶️ 启动编码器线程
        mEncoderThread.start();
        // ⏳ 等待线程初始化完成（Looper和Handler准备就绪）
        mEncoderThread.waitUntilReady();
    }

    /**
     * Returns the encoder's input surface.
     * 🖼️ 获取编码器的输入表面。
     */
    public Surface getInputSurface() {
        return mInputSurface;
    }

    /**
     * Shuts down the encoder thread, and releases encoder resources.
     * <p>
     * Does not return until the encoder thread has stopped.
     * 🛑 关闭编码器线程并释放资源。阻塞直到线程停止。
     */
    public void shutdown() {
        if (VERBOSE) Log.d(TAG, "releasing encoder objects");  // 🗑️ 释放编码器资源

        // 📨 handler = mEncoderThread.getHandler()：获取编码器线程的Handler
        // 💡 为什么获取：需要通过Handler向编码器线程发送关闭消息
        // 💡 作用：持有Handler引用，用于发送MSG_SHUTDOWN
        // 💡 使用时机：shutdown()中发送关闭消息之前
        Handler handler = mEncoderThread.getHandler();
        // 📨 handler.sendMessage(...)：发送关闭消息到编码器线程
        // 💡 为什么发送：编码器线程需要在自己的线程上执行清理操作
        // 💡 作用：通知编码器线程退出Looper消息循环
        // 💡 使用时机：获取Handler后立即发送
        handler.sendMessage(handler.obtainMessage(EncoderThread.EncoderHandler.MSG_SHUTDOWN));  // 📨 发送关闭消息
        try {
            // ⏳ mEncoderThread.join()：等待编码器线程结束
            // 💡 为什么调用：必须确保编码器线程完全退出后才能释放编码器
            // 💡 作用：阻塞当前线程，直到编码器线程的run()方法返回
            // 💡 使用时机：发送关闭消息之后，释放编码器之前
            mEncoderThread.join();  // ⏳ 等待线程结束
        } catch (InterruptedException ie) {
            Log.w(TAG, "Encoder thread join() was interrupted", ie);
        }

        // 🔍 if (mEncoder != null)：检查编码器是否已创建
        // 💡 为什么检查：避免对null对象调用方法
        // 💡 作用：安全释放资源的前提条件检查
        // 💡 使用时机：每次释放资源前检查
        if (mEncoder != null) {
            // ⏹️ mEncoder.stop()：停止编码器运行
            // 💡 为什么调用：编码器可能正在处理数据，必须先停止才能安全释放
            // 💡 作用：通知编码器结束编码工作，刷新内部缓冲区
            // 💡 使用时机：在release()之前调用
            mEncoder.stop();       // ⏹️ 停止编码器
            // 🗑️ mEncoder.release()：释放编码器占用的所有资源
            // 💡 为什么调用：编码器持有硬件编解码器资源
            // 💡 作用：释放Native层资源，解除硬件占用
            // 💡 使用时机：stop()之后立即调用
            mEncoder.release();    // 🗑️ 释放编码器
            // 🔄 mEncoder = null：将引用置空
            // 💡 为什么置空：防止重复释放，帮助GC回收
            // 💡 作用：标记资源已释放，避免悬挂引用
            // 💡 使用时机：release()之后立即置空
            mEncoder = null;
        }
    }

    /**
     * Notifies the encoder thread that a new frame will shortly be provided to the encoder.
     * <p>
     * There may or may not yet be data available from the encoder output.  The encoder
     * has a fair mount of latency due to processing, and it may want to accumulate a
     * few additional buffers before producing output.  We just need to drain it regularly
     * to avoid a situation where the producer gets wedged up because there's no room for
     * additional frames.
     * 🖼️ 通知编码器线程新帧即将到来。
     *    编码器输出可能有也可能没有数据可用。编码器有一定延迟，
     *    可能需要积累几个缓冲区才输出。需要定期排空以防止生产者阻塞。
     * <p>
     * If the caller sends the frame and then notifies us, it could get wedged up.  If it
     * notifies us first and then sends the frame, we guarantee that the output buffers
     * were emptied, and it will be impossible for a single additional frame to block
     * indefinitely.
     * 如果调用者先发送帧再通知，可能会阻塞。如果先通知再发送，
     * 可以确保输出缓冲区已清空，单帧不会无限阻塞。
     */
    public void frameAvailableSoon() {
        // 📨 handler：获取编码器线程的Handler引用
        // 💡 为什么获取：需要通过Handler向编码器线程发送消息
        // 💡 作用：持有Handler引用，用于发送帧可用通知
        // 💡 使用时机：通知编码器线程前获取
        Handler handler = mEncoderThread.getHandler();
        // 📨 handler.obtainMessage(MSG_FRAME_AVAILABLE_SOON)：构造帧可用消息
        // 💡 为什么发送：通知编码器线程排空编码器输出缓冲区
        // 💡 作用：唤醒编码器线程，触发drainEncoder()消费编码输出
        // 💡 使用时机：获取Handler后立即发送
        handler.sendMessage(handler.obtainMessage(
                EncoderThread.EncoderHandler.MSG_FRAME_AVAILABLE_SOON));
    }

    /**
     * Initiates saving the currently-buffered frames to the specified output file.  The
     * data will be written as a .mp4 file.  The call returns immediately.  When the file
     * save completes, the callback will be notified.
     * 💾 启动将当前缓冲的帧保存到指定输出文件（.mp4 格式）。
     *    立即返回。文件保存完成后通过回调通知。
     * <p>
     * The file generation is performed on the encoder thread, which means we won't be
     * draining the output buffers while this runs.  It would be wise to stop submitting
     * frames during this time.
     * 文件生成在编码器线程上执行，期间不会排空输出缓冲区。
     * 在此期间最好停止提交帧。
     */
    public void saveVideo(File outputFile) {
        // 📨 handler：获取编码器线程的Handler引用
        // 💡 为什么获取：需要通过Handler向编码器线程发送保存视频消息
        // 💡 作用：持有Handler引用，用于发送保存命令
        // 💡 使用时机：通知编码器线程保存视频前获取
        Handler handler = mEncoderThread.getHandler();
        // 📨 handler.obtainMessage(MSG_SAVE_VIDEO, outputFile)：构造保存视频消息
        // 💡 为什么发送：文件生成需要在编码器线程执行，避免同步问题
        // 💡 作用：obj携带输出文件路径，触发EncoderThread.saveVideo()
        // 💡 使用时机：获取Handler后立即发送
        handler.sendMessage(handler.obtainMessage(
                EncoderThread.EncoderHandler.MSG_SAVE_VIDEO, outputFile));
    }

    /**
     * Object that encapsulates the encoder thread.
     * <p>
     * We want to sleep until there's work to do.  We don't actually know when a new frame
     * arrives at the encoder, because the other thread is sending frames directly to the
     * input surface.  We will see data appear at the decoder output, so we can either use
     * an infinite timeout on dequeueOutputBuffer() or wait() on an object and require the
     * calling app wake us.  It's very useful to have all of the buffer management local to
     * this thread -- avoids synchronization -- so we want to do the file muxing in here.
     * So, it's best to sleep on an object and do something appropriate when awakened.
     * 🧵 封装编码器线程的类。
     *    线程在没有工作时休眠。我们不知道新帧何时到达编码器，
     *    因为其他线程直接向输入表面发送帧。
     *    可以在 dequeueOutputBuffer() 上使用无限超时，或在对象上 wait() 并要求调用方唤醒。
     *    所有缓冲区管理在本线程内完成（避免同步），文件混合也在这里做。
     * <p>
     * This class does not manage the MediaCodec encoder startup/shutdown.  The encoder
     * should be fully started before the thread is created, and not shut down until this
     * thread has been joined.
     * 本类不管理 MediaCodec 编码器的启动/关闭。
     * 编码器应在创建线程前完全启动，线程 join() 后才能关闭。
     */
    private static class EncoderThread extends Thread {
        private MediaCodec mEncoder;                  // 🎬 MediaCodec 编码器
        private MediaFormat mEncodedFormat;           // 📋 编码格式
        private MediaCodec.BufferInfo mBufferInfo;    // 📊 缓冲区信息

        private EncoderHandler mHandler;                    // 📨 消息处理器
        private CircularEncoderBuffer mEncBuffer;           // 🔄 环形编码缓冲区
        private CircularEncoder.Callback mCallback;         // 📞 回调接口
        private int mFrameNum;                              // 📊 帧计数器

        private final Object mLock = new Object();    // 🔒 同步锁
        private volatile boolean mReady = false;      // ✅ 就绪标志

        // 🔧 构造函数：初始化编码器线程的依赖项
        public EncoderThread(MediaCodec mediaCodec, CircularEncoderBuffer encBuffer,
                CircularEncoder.Callback callback) {
            // 🎬 mEncoder = mediaCodec：保存MediaCodec编码器引用
            // 💡 为什么赋值：线程需要在run()中排空编码器输出
            // 💡 作用：持有编码器引用，供drainEncoder()使用
            // 💡 使用时机：在drainEncoder()中调用dequeueOutputBuffer()
            mEncoder = mediaCodec;
            // 🔄 mEncBuffer = encBuffer：保存环形编码缓冲区引用
            // 💡 为什么赋值：编码数据需要存储到环形缓冲区
            // 💡 作用：持有缓冲区引用，供drainEncoder()和saveVideo()使用
            // 💡 使用时机：在drainEncoder()中调用mEncBuffer.add()
            mEncBuffer = encBuffer;
            // 📞 mCallback = callback：保存回调接口引用
            // 💡 为什么赋值：需要通知调用方文件保存完成和缓冲区状态
            // 💡 作用：持有回调引用，供saveVideo()和frameAvailableSoon()使用
            // 💡 使用时机：在saveVideo()结束时调用fileSaveComplete()
            mCallback = callback;

            // 📊 mBufferInfo = new MediaCodec.BufferInfo()：创建缓冲区信息对象
            // 💡 为什么创建：dequeueOutputBuffer()需要此对象来填充输出缓冲区的元数据
            // 💡 作用：存储每一帧的偏移量、大小、时间戳和标志位
            // 💡 使用时机：每次drainEncoder()调用时传入dequeueOutputBuffer()
            mBufferInfo = new MediaCodec.BufferInfo();
        }

        /**
         * Thread entry point.
         * <p>
         * Prepares the Looper, Handler, and signals anybody watching that we're ready to go.
         * 🚀 线程入口。准备 Looper 和 Handler，通知观察者线程已就绪。
         */
        @Override
        public void run() {
            // 🔧 Looper.prepare()：为当前线程准备消息循环器
            // 💡 为什么调用：Handler需要绑定到Looper才能接收消息
            // 💡 作用：初始化线程的消息队列
            // 💡 使用时机：线程入口，任何Handler操作之前
            Looper.prepare();
            // 📨 mHandler = new EncoderHandler(this)：创建编码器消息处理器
            // 💡 为什么创建：需要Handler来接收和处理其他线程发送的消息
            // 💡 作用：绑定到当前线程的Looper，处理帧可用、保存视频等消息
            // 💡 使用时机：Looper.prepare()之后，Looper.loop()之前
            mHandler = new EncoderHandler(this);    // must create on encoder thread 🔧 必须在编码器线程创建
            // 📝 Log.d(TAG, "encoder thread ready")：记录编码器线程已准备好
            // 💡 为什么记录：调试时需要知道编码器线程何时完成初始化
            // 💡 作用：在logcat中输出线程就绪信息
            // 💡 使用时机：Handler创建完成后立即记录
            Log.d(TAG, "encoder thread ready");
            // 🔒 synchronized (mLock)：获取同步锁，保护mReady状态
            // 💡 为什么需要：mReady被多个线程访问（waitUntilReady在等待）
            // 💡 作用：确保就绪标记的原子性设置和通知
            // 💡 使用时机：Handler创建完成后
            synchronized (mLock) {
                // ✅ mReady = true：标记编码器线程已就绪
                // 💡 为什么设置：构造函数中waitUntilReady()在等待此标记
                // 💡 作用：告知其他线程编码器线程可以接收消息了
                // 💡 使用时机：Handler创建完成后立即设置
                mReady = true;
                // 🔔 mLock.notify()：通知等待的线程
                // 💡 为什么调用：waitUntilReady()中在mLock上等待此通知
                // 💡 作用：唤醒在waitUntilReady()中阻塞的线程
                // 💡 使用时机：mReady设为true之后立即调用
                mLock.notify();    // signal waitUntilReady() 🔔 通知 waitUntilReady()
            }

            // 🔄 Looper.loop()：开始消息循环
            // 💡 为什么调用：启动消息队列的无限循环，处理入队的消息
            // 💡 作用：阻塞线程，持续从队列取出消息并分发到Handler
            // 💡 使用时机：Handler创建完成且通知就绪之后
            Looper.loop();  // 🔄 开始消息循环

            // 📝 Log.d(TAG, "looper quit")：记录Looper已退出
            // 💡 为什么记录：调试时需要知道消息循环何时结束
            // 💡 作用：在logcat中输出Looper退出信息
            // 💡 使用时机：Looper.loop()返回后立即记录
            Log.d(TAG, "looper quit");  // 🚪 Looper 退出
            // 🔒 synchronized (mLock)：获取同步锁，重置状态
            // 💡 为什么需要：mHandler和mReady被多个线程访问
            // 💡 作用：确保状态重置的原子性
            // 💡 使用时机：Looper退出后，线程结束前
            synchronized (mLock) {
                // ❌ mReady = false：重置就绪标志
                // 💡 为什么重置：线程已退出，标记为未就绪
                // 💡 作用：让getHandler()抛出异常，防止向已退出的线程发消息
                // 💡 使用时机：Looper.loop()返回后
                mReady = false;
                // 🔄 mHandler = null：清空Handler引用
                // 💡 为什么置空：线程已退出，Handler不再可用
                // 💡 作用：防止其他线程向已退出的线程发送消息
                // 💡 使用时机：重置mReady之后
                mHandler = null;   // 🗑️ 清理 Handler
            }
        }

        /**
         * Waits until the encoder thread is ready to receive messages.
         * <p>
         * Call from non-encoder thread.
         * ⏳ 等待编码器线程准备好接收消息（从非编码器线程调用）。
         */
        public void waitUntilReady() {
            // 🔒 synchronized (mLock)：获取同步锁，保护mReady状态
            // 💡 为什么需要：mReady被多个线程访问（本方法等待，run()设置）
            // 💡 作用：确保读取mReady时状态一致
            // 💡 使用时机：检查和等待就绪状态时
            synchronized (mLock) {
                // 🔄 while (!mReady)：循环检查就绪标志
                // 💡 为什么循环：可能被虚假唤醒，需要反复检查
                // 💡 作用：阻塞直到编码器线程的Handler创建完成
                // 💡 使用时机：mReady为false时持续等待
                while (!mReady) {
                    try {
                        // ⏳ mLock.wait()：释放锁并等待通知
                        // 💡 为什么等待：编码器线程需要时间创建Handler
                        // 💡 作用：阻塞当前线程，直到run()中notify()被调用
                        // 💡 使用时机：mReady为false时
                        mLock.wait();
                    } catch (InterruptedException ie) { /* not expected */ }
                }
            }
        }

        /**
         * Returns the Handler used to send messages to the encoder thread.
         * 📨 获取用于向编码器线程发送消息的 Handler。
         */
        public EncoderHandler getHandler() {
            // 🔒 synchronized (mLock)：获取同步锁，保护mReady状态
            // 💡 为什么需要：mReady被多个线程访问
            // 💡 作用：确保读取mReady时状态一致
            // 💡 使用时机：检查就绪状态前获取锁
            synchronized (mLock) {
                // Confirm ready state.
                // 🔍 if (!mReady)：检查编码器线程是否已就绪
                // 💡 为什么检查：未就绪的Handler无法处理消息
                // 💡 作用：防止向未初始化的Handler发送消息
                // 💡 使用时机：获取Handler前检查
                if (!mReady) {
                    throw new RuntimeException("not ready");
                }
            }
            // 📤 mHandler：返回编码器线程的Handler引用
            // 💡 为什么返回：调用方需要通过Handler向编码器线程发送消息
            // 💡 作用：提供发送帧通知、保存视频、关闭等消息的通道
            // 💡 使用时机：确认就绪后返回
            return mHandler;
        }

        /**
         * Drains all pending output from the encoder, and adds it to the circular buffer.
         * 📤 排空编码器所有待处理的输出，添加到环形缓冲区。
         * 💡 这是编码数据从编码器转移到环形缓冲区的核心方法
         */
        // 📤 drainEncoder：排空编码器输出缓冲区
        public void drainEncoder() {
            // ⏱️ TIMEOUT_USEC：超时时间（微秒）
            // 💡 为什么定义：控制dequeueOutputBuffer()的等待时间
            // 💡 作用：0表示不等待，立即检查是否有输出（非阻塞模式）
            // 💡 使用时机：传入dequeueOutputBuffer()作为超时参数
            final int TIMEOUT_USEC = 0;     // no timeout -- check for buffers, bail if none ⚡ 无超时，立即检查

            // 📦 encoderOutputBuffers：编码器输出缓冲区数组
            // 💡 为什么定义：持有编码器所有输出缓冲区的引用
            // 💡 作用：通过索引访问输出缓冲区中的编码数据
            // 💡 使用时机：每次dequeue成功后通过索引获取编码数据
            ByteBuffer[] encoderOutputBuffers = mEncoder.getOutputBuffers();
            // 🔄 循环处理所有可用的输出缓冲区
            while (true) {
                // 🔍 encoderStatus：从编码器获取输出缓冲区的状态码
                // 💡 为什么定义：标识dequeue操作的结果类型
                // 💡 作用：区分"需要重试"、"格式变化"、"成功获取"等情况
                // 💡 使用时机：通过条件判断执行不同的处理逻辑
                int encoderStatus = mEncoder.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);
                if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // no output available yet
                    // ⏳ 暂无输出可用，立即退出循环
                    // 💡 为什么break：超时为0，没有数据就返回，不阻塞
                    break;
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    // not expected for an encoder
                    // 🔄 输出缓冲区数组已更换（编码器通常不会出现此情况）
                    // 💡 为什么更新：缓冲区数组可能被重新分配
                    encoderOutputBuffers = mEncoder.getOutputBuffers();
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Should happen before receiving buffers, and should only happen once.
                    // The MediaFormat contains the csd-0 and csd-1 keys, which we'll need
                    // for MediaMuxer.  It's unclear what else MediaMuxer might want, so
                    // rather than extract the codec-specific data and reconstruct a new
                    // MediaFormat later, we just grab it here and keep it around.
                    // 📋 输出格式变更（应在接收缓冲区前发生，仅发生一次）。
                    //    包含 csd-0/csd-1 等 MediaMuxer 需要的键值。
                    // 📊 mEncodedFormat：保存编码器输出格式
                    // 💡 为什么赋值：包含CSD-0/CSD-1编解码器配置数据，saveVideo()中创建Muxer需要
                    // 💡 作用：后续saveVideo()中添加轨道时使用
                    mEncodedFormat = mEncoder.getOutputFormat();
                    // 📝 记录格式变化
                    Log.d(TAG, "encoder output format changed: " + mEncodedFormat);
                } else if (encoderStatus < 0) {
                    // ❌ 未知的返回值，记录警告但继续运行
                    Log.w(TAG, "unexpected result from encoder.dequeueOutputBuffer: " +
                            encoderStatus);
                    // let's ignore it
                    // 🤷 忽略未知状态，继续循环
                } else {
                    // ✅ 成功获取输出缓冲区
                    // 📦 encodedData：指向编码器输出缓冲区中的编码数据
                    // 💡 为什么定义：持有编码后的H.264数据引用
                    // 💡 作用：将数据添加到环形缓冲区
                    // 💡 使用时机：调整position/limit后传入mEncBuffer.add()
                    ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];
                    // 🔍 安全检查：缓冲区不应为null
                    if (encodedData == null) {
                        throw new RuntimeException("encoderOutputBuffer " + encoderStatus +
                                " was null");  // 🚨 缓冲区为空异常
                    }

                    // 🔍 检查是否为编解码器配置数据
                    // 💡 BUFFER_FLAG_CODEC_CONFIG表示SPS/PPS等编解码器配置信息
                    if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        // The codec config data was pulled out when we got the
                        // INFO_OUTPUT_FORMAT_CHANGED status.  The MediaMuxer won't accept
                        // a single big blob -- it wants separate csd-0/csd-1 chunks --
                        // so simply saving this off won't work.
                        // ⚙️ 编解码器配置数据在 INFO_OUTPUT_FORMAT_CHANGED 时已提取。
                        //    MediaMuxer 需要分离的 csd-0/csd-1 块，不能接受大 blob。
                        if (VERBOSE) Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
                        // 🔄 将size设为0，跳过后续写入环形缓冲区
                        mBufferInfo.size = 0;
                    }

                    // 🔍 检查是否有实际数据需要存储
                    if (mBufferInfo.size != 0) {
                        // adjust the ByteBuffer values to match BufferInfo (not needed?)
                        // 📐 调整 ByteBuffer 的 position 和 limit 以匹配 BufferInfo
                        // 💡 为什么：环形缓冲区需要正确的position和limit来读取数据
                        encodedData.position(mBufferInfo.offset);
                        encodedData.limit(mBufferInfo.offset + mBufferInfo.size);

                        // 📦 将编码数据添加到环形缓冲区
                        // 💡 为什么：环形缓冲区管理所有编码帧，供后续保存使用
                        // 💡 参数：编码数据、标志位（是否关键帧）、呈现时间戳
                        mEncBuffer.add(encodedData, mBufferInfo.flags,
                                mBufferInfo.presentationTimeUs);  // 📦 添加到环形缓冲区

                        // 📝 如果启用详细日志，记录写入的数据量和时间戳
                        // 📝 VERBOSE日志：记录发送给环形缓冲区的数据量和时间戳
                        // 💡 为什么记录：追踪编码数据的输出情况
                        // 💡 作用：在logcat中显示每帧的数据量和时间戳
                        // 💡 使用时机：每次成功添加数据到环形缓冲区后
                        if (VERBOSE) {
                            Log.d(TAG, "sent " + mBufferInfo.size + " bytes to muxer, ts=" +
                                    mBufferInfo.presentationTimeUs);
                        }
                    }

                    // 🗑️ 释放输出缓冲区，返回给编码器（false表示不需要渲染到Surface）
                    mEncoder.releaseOutputBuffer(encoderStatus, false);  // 🗑️ 释放输出缓冲区

                    // 🔍 检查是否到达流末尾
                    if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.w(TAG, "reached end of stream unexpectedly");
                        break;      // out of while 🚪 意外到达流末尾，退出循环
                    }
                }
            }
        }

        /**
         * Drains the encoder output.
         * <p>
         * See notes for {@link CircularEncoder#frameAvailableSoon()}.
         * 🖼️ 帧可用时调用，排空编码器输出并报告缓冲区状态。
         */
        void frameAvailableSoon() {
            // 📝 VERBOSE日志：记录帧可用通知
            // 💡 为什么记录：追踪帧通知的频率
            // 💡 作用：在logcat中显示帧可用事件
            // 💡 使用时机：每次frameAvailableSoon()调用时
            if (VERBOSE) Log.d(TAG, "frameAvailableSoon");
            // 📤 drainEncoder()：排空编码器输出缓冲区
            // 💡 为什么调用：需要定期消费编码器输出，避免生产者阻塞
            // 💡 作用：将编码数据从编码器转移到环形缓冲区
            // 💡 使用时机：每帧通知时调用
            drainEncoder();  // 📤 排空编码器

            // 📊 mFrameNum++：递增帧计数器
            // 💡 为什么递增：用于统计已处理的帧数
            // 💡 作用：记录帧计数，用于定期报告缓冲区状态
            // 💡 使用时机：drainEncoder()之后递增
            mFrameNum++;
            // 🔍 if ((mFrameNum % 10) == 0)：每10帧报告一次缓冲区状态
            // 💡 为什么检查：不需要每帧都报告，减少回调频率
            // 💡 作用：定期通知调用方缓冲区的视频时长
            // 💡 使用时机：帧计数是10的倍数时
            if ((mFrameNum % 10) == 0) {        // TODO: should base off frame rate or clock? 📊 每 10 帧报告一次
                // 📞 mCallback.bufferStatus(...)：回调报告缓冲区状态
                // 💡 为什么调用：UI需要知道环形缓冲区中视频的总时长
                // 💡 作用：传递缓冲区视频时长（微秒）给调用方
                // 💡 使用时机：每10帧调用一次
                mCallback.bufferStatus(mEncBuffer.computeTimeSpanUsec());
            }
        }

        /**
         * Saves the encoder output to a .mp4 file.
         * <p>
         * We'll drain the encoder to get any lingering data, but we're not going to shut
         * the encoder down or use other tricks to try to "flush" the encoder.  This may
         * mean we miss the last couple of submitted frames if they're still working their
         * way through.
         * 💾 将编码器输出保存为 .mp4 文件。
         *    会排空编码器获取残留数据，但不会关闭编码器或使用其他"刷新"手段。
         *    这意味着如果最后几帧还在处理中，可能会丢失。
         * <p>
         * We may want to reset the buffer after this -- if they hit "capture" again right
         * away they'll end up saving video with a gap where we paused to write the file.
         * 可能需要在此之后重置缓冲区——如果用户立即再次点击"捕获"，
         * 视频中会有因暂停写入文件而产生的间隙。
         *
         * @param outputFile 输出MP4文件路径
         */
        // 💾 saveVideo：将环形缓冲区中的编码数据保存为MP4文件
        void saveVideo(File outputFile) {
            // 📝 如果启用详细日志，记录保存操作和目标文件
            // 📝 VERBOSE日志：记录saveVideo调用和目标文件路径
            // 💡 为什么记录：追踪文件保存操作的触发
            // 💡 作用：在logcat中显示保存操作详情
            // 💡 使用时机：每次saveVideo()调用时
            if (VERBOSE) Log.d(TAG, "saveVideo " + outputFile);

            // 🔍 index：环形缓冲区中第一个可读取的数据块索引
            // 💡 为什么定义：需要从同步帧（关键帧）开始保存
            // 💡 作用：作为遍历环形缓冲区的起始位置
            // 💡 使用时机：传入mEncBuffer.getChunk()和getNextIndex()
            int index = mEncBuffer.getFirstIndex();
            // 🔍 检查是否成功获取首个索引
            if (index < 0) {
                // ⚠️ 无法获取首个索引（可能缓冲区为空）
                Log.w(TAG, "Unable to get first index");
                // 📞 回调通知调用者保存失败（状态码1表示失败）
                mCallback.fileSaveComplete(1);
                return;  // 🚪 退出方法
            }

            // 📊 info：缓冲区信息对象
            // 💡 为什么定义：getChunk()需要此对象来填充帧的元数据（大小、时间戳、标志）
            // 💡 作用：存储每一帧的偏移量、大小、时间戳和标志位
            // 💡 使用时机：传入mEncBuffer.getChunk()和muxer.writeSampleData()
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            // 📦 muxer：媒体复用器引用
            // 💡 为什么定义：将编码数据封装成MP4文件
            // 💡 作用：写入编码数据到.mp4容器
            // 💡 使用时机：在try块中创建和使用，finally块中释放
            MediaMuxer muxer = null;
            // 📊 result：操作结果码
            // 💡 为什么定义：保存操作的结果状态
            // 💡 作用：0=成功，1=获取索引失败，2=IO异常
            // 💡 使用时机：最后传入mCallback.fileSaveComplete()
            int result = -1;
            try {
                // 🎬 创建MP4复用器
                // 💡 为什么：需要将编码数据写入标准MP4容器格式
                muxer = new MediaMuxer(outputFile.getPath(),
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                // ➕ videoTrack：将编码格式添加为视频轨道，返回轨道索引
                // 💡 为什么定义：writeSampleData()需要指定轨道索引
                // 💡 作用：标识复用器中的视频轨道
                // 💡 使用时机：传入muxer.writeSampleData()
                int videoTrack = muxer.addTrack(mEncodedFormat);
                // ▶️ muxer.start()：启动复用器
                // 💡 为什么调用：添加轨道后必须启动才能写入数据
                // 💡 作用：初始化复用器内部状态，准备接收编码数据
                // 💡 使用时机：addTrack()之后，writeSampleData()之前
                muxer.start();

                // 🔄 遍历环形缓冲区中的所有数据块
                // 🔄 do-while循环：遍历环形缓冲区并写入所有帧
                // 💡 为什么循环：需要将环形缓冲区中的所有帧写入MP4
                // 💡 作用：逐帧读取编码数据并写入复用器
                // 💡 使用时机：从getFirstIndex()开始，直到getNextIndex()返回<0
                do {
                    // 📦 buf：从环形缓冲区获取的编码数据块
                    // 💡 为什么定义：持有当前帧的编码数据引用
                    // 💡 作用：传入muxer.writeSampleData()写入MP4文件
                    // 💡 使用时机：每次循环获取一帧数据并写入
                    ByteBuffer buf = mEncBuffer.getChunk(index, info);
                    // 📝 如果启用详细日志，记录当前保存的帧索引和标志
                    // 📝 VERBOSE日志：记录当前保存的帧索引和标志位
                    // 💡 为什么记录：追踪每帧的写入进度
                    // 💡 作用：在logcat中显示帧索引和是否为关键帧
                    // 💡 使用时机：每次getChunk()后
                    if (VERBOSE) {
                        Log.d(TAG, "SAVE " + index + " flags=0x" + Integer.toHexString(info.flags));
                    }
                    // 📝 将编码数据写入复用器
                    // 💡 muxer.writeSampleData(videoTrack, buf, info)：将编码帧写入MP4
                    // 💡 为什么调用：这是将编码数据写入MP4文件的核心操作
                    // 💡 作用：将一帧H.264数据写入指定轨道
                    // 💡 使用时机：获取编码数据后立即写入
                    muxer.writeSampleData(videoTrack, buf, info);
                    // ➡️ 获取环形缓冲区中下一个数据块的索引
                    // 💡 index = mEncBuffer.getNextIndex(index)：获取下一帧索引
                    // 💡 为什么调用：需要遍历环形缓冲区中的所有帧
                    // 💡 作用：返回下一个可读数据块的索引，<0表示已遍历完
                    // 💡 使用时机：每帧写入后调用，继续下一帧
                    index = mEncBuffer.getNextIndex(index);
                } while (index >= 0);  // 🔄 直到遍历完所有数据块
                // ✅ 保存成功
                result = 0;
            } catch (IOException ioe) {
                // 🚨 混合器写入失败，记录异常
                Log.w(TAG, "muxer failed", ioe);
                // 📊 设置结果码为2（IO异常）
                result = 2;
            } finally {
                // 🔍 if (muxer != null)：检查复用器是否已创建
                // 💡 为什么检查：muxer可能在创建前就发生异常
                // 💡 作用：确保只释放已创建的资源
                // 💡 使用时机：finally块中释放资源前检查
                if (muxer != null) {
                    // ⏹️ muxer.stop()：停止复用器
                    // 💡 为什么调用：必须停止才能完成MP4文件的最终写入
                    // 💡 作用：写入MP4文件的尾部元数据（moov box），使文件完整
                    // 💡 使用时机：所有帧写入完成或发生异常时
                    muxer.stop();
                    // 🗑️ muxer.release()：释放复用器资源
                    // 💡 为什么调用：复用器持有文件句柄和内存缓冲区
                    // 💡 作用：关闭文件句柄，释放Native层资源
                    // 💡 使用时机：stop()之后立即调用
                    muxer.release();
                }
            }

            // 📝 如果启用详细日志，记录最终结果
            // 📝 VERBOSE日志：记录复用器停止和最终结果码
            // 💡 为什么记录：追踪保存操作的最终结果
            // 💡 作用：在logcat中显示保存操作是否成功
            // 💡 使用时机：finally块之后，回调之前
            if (VERBOSE) {
                Log.d(TAG, "muxer stopped, result=" + result);
            }
            // 📞 mCallback.fileSaveComplete(result)：通知调用者文件保存完成
            // 💡 为什么调用：调用方需要知道保存操作的结果
            // 💡 作用：传递结果码（0=成功，1=索引失败，2=IO异常）
            // 💡 使用时机：保存操作完成后（无论成功或失败）
            mCallback.fileSaveComplete(result);
        }

        /**
         * Tells the Looper to quit.
         * 🚪 通知 Looper 退出。
         */
        void shutdown() {
            if (VERBOSE) Log.d(TAG, "shutdown");
            Looper.myLooper().quit();
        }

        /**
         * Handler for EncoderThread.  Used for messages sent from the UI thread (or whatever
         * is driving the encoder) to the encoder thread.
         * <p>
         * The object is created on the encoder thread.
         * 📨 编码器线程的消息处理器（在编码器线程上创建）。
         *    用于从 UI 线程向编码器线程发送消息。
         */
        private static class EncoderHandler extends Handler {
            public static final int MSG_FRAME_AVAILABLE_SOON = 1;  // 🖼️ 新帧即将可用
            public static final int MSG_SAVE_VIDEO = 2;            // 💾 保存视频
            public static final int MSG_SHUTDOWN = 3;              // 🛑 关闭

            // This shouldn't need to be a weak ref, since we'll go away when the Looper quits,
            // but no real harm in it.
            // 🎣 弱引用（虽然 Looper 退出时会释放，但无害）
            private WeakReference<EncoderThread> mWeakEncoderThread;

            /**
             * Constructor.  Instantiate object from encoder thread.
             * 🔧 构造函数，在编码器线程上实例化。
             */
            public EncoderHandler(EncoderThread et) {
                mWeakEncoderThread = new WeakReference<EncoderThread>(et);
            }

            @Override  // runs on encoder thread 🧵 在编码器线程上运行
            public void handleMessage(Message msg) {
                // 📊 what = msg.what：提取消息类型标识
                // 💡 为什么获取：需要根据消息类型执行不同的处理逻辑
                // 💡 作用：区分帧可用、保存视频、关闭等不同操作
                // 💡 使用时机：switch判断前读取
                int what = msg.what;
                // 📝 VERBOSE日志：记录接收到的消息类型
                // 💡 为什么记录：追踪编码器线程的消息处理流程
                // 💡 作用：在logcat中显示每条消息的类型
                // 💡 使用时机：每条消息处理前记录
                if (VERBOSE) {
                    Log.v(TAG, "EncoderHandler: what=" + what);
                }

                // 🎣 encoderThread = mWeakEncoderThread.get()：通过弱引用获取编码器线程
                // 💡 为什么获取：需要调用编码器线程的方法处理消息
                // 💡 作用：安全获取编码器线程引用，可能为null（已被GC回收）
                // 💡 使用时机：处理每个消息前获取
                EncoderThread encoderThread = mWeakEncoderThread.get();
                // 🔍 if (encoderThread == null)：检查编码器线程是否已被回收
                // 💡 为什么检查：弱引用的对象可能已被GC回收
                // 💡 作用：避免对null对象调用方法
                // 💡 使用时机：获取弱引用后立即检查
                if (encoderThread == null) {
                    Log.w(TAG, "EncoderHandler.handleMessage: weak ref is null");
                    return;  // 🚫 编码器线程已被回收
                }

                // 🔀 switch (what)：根据消息类型分发处理
                // 💡 为什么分发：不同类型的消息需要不同的处理逻辑
                // 💡 作用：消息路由，将消息分发到对应的处理方法
                // 💡 使用时机：提取消息类型后立即执行
                switch (what) {
                    case MSG_FRAME_AVAILABLE_SOON:       // 🖼️ 新帧即将可用
                        encoderThread.frameAvailableSoon();
                        break;
                    case MSG_SAVE_VIDEO:                 // 💾 保存视频
                        encoderThread.saveVideo((File) msg.obj);
                        break;
                    case MSG_SHUTDOWN:                   // 🛑 关闭
                        encoderThread.shutdown();
                        break;
                    default:
                        throw new RuntimeException("unknown message " + what);
                }
            }
        }
    }
}
