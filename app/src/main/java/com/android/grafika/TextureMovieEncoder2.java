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

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import java.lang.ref.WeakReference;

/**
 * 🎬 从外部纹理图像渲染的帧编码生成视频（第二版）。
 * Encode a movie from frames rendered from an external texture image.
 * <p>
 * The object wraps an encoder running partly on two different threads.  An external thread
 * is sending data to the encoder's input surface, and we (the encoder thread) are pulling
 * the encoded data out and feeding it into a MediaMuxer.
 * 该对象封装了一个在两个不同线程上部分运行的编码器。
 * 外部线程向编码器的输入表面发送数据，编码器线程拉取编码数据并送入 MediaMuxer。
 * <p>
 * We could block forever waiting for the encoder, but because of the thread decomposition
 * that turns out to be a little awkward (we want to call signalEndOfInputStream() from the
 * encoder thread to avoid thread-safety issues, but we can't do that if we're blocked on
 * the encoder).  If we don't pull from the encoder often enough, the producer side can back up.
 * 可以无限等待编码器，但由于线程分解的原因有些尴尬
 * （我们想从编码器线程调用 signalEndOfInputStream() 避免线程安全问题，
 * 但如果在编码器上阻塞就无法做到）。如果从编码器拉取不够频繁，生产者端会阻塞。
 * <p>
 * The solution is to have the producer trigger drainEncoder() on every frame, before it
 * submits the new frame.  drainEncoder() might run before or after the frame is submitted,
 * but it doesn't matter -- either it runs early and prevents blockage, or it runs late
 * and un-blocks the encoder.
 * 解决方案：让生产者在每帧提交前触发 drainEncoder()。
 * drainEncoder() 可能在帧提交前后运行，但不影响结果——
 * 早运行可以防止阻塞，晚运行可以解除阻塞。
 * <p>
 * TODO: reconcile this with TextureMovieEncoder.
 */
public class TextureMovieEncoder2 implements Runnable {
    private static final String TAG = MainActivity.TAG;
    private static final boolean VERBOSE = false;

    // 📨 消息类型常量
    private static final int MSG_STOP_RECORDING = 1;    // ⏹️ 停止录制
    private static final int MSG_FRAME_AVAILABLE = 2;   // 🖼️ 新帧可用

    // ----- accessed exclusively by encoder thread -----
    // 🔒 仅编码器线程访问
    private VideoEncoderCore mVideoEncoder;  // 🎬 视频编码器核心

    // ----- accessed by multiple threads -----
    // 🔀 多线程访问
    private volatile EncoderHandler mHandler;  // 📨 编码器 Handler

    private Object mReadyFence = new Object();      // guards ready/running 🔒 同步锁
    private boolean mReady;     // ✅ 编码器就绪标志
    private boolean mRunning;   // 🏃 编码器运行标志


    /**
     * Tells the video recorder to start recording.  (Call from non-encoder thread.)
     * <p>
     * Creates a new thread, which will own the provided VideoEncoderCore.  When the
     * thread exits, the VideoEncoderCore will be released.
     * <p>
     * Returns after the recorder thread has started and is ready to accept Messages.
     * ▶️ 开始录制（从非编码器线程调用）。
     *    创建新线程，线程将拥有提供的 VideoEncoderCore。线程退出时释放编码器。
     *    在线程就绪并可接收消息后返回。
     */
    public TextureMovieEncoder2(VideoEncoderCore encoderCore) {
        Log.d(TAG, "Encoder: startRecording()");

        // 🎬 mVideoEncoder = encoderCore：保存编码器核心引用
        // 💡 为什么赋值：编码器线程需要此对象来排空输出和释放资源
        // 💡 作用：持有VideoEncoderCore引用，供后续drain/release使用
        // 💡 使用时机：在handleFrameAvailable()和handleStopRecording()中使用
        mVideoEncoder = encoderCore;

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
                Log.w(TAG, "Encoder thread already running");  // ⚠️ 已在运行
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
            new Thread(this, "TextureMovieEncoder").start();  // 🚀 启动编码线程
            // ⏳ while (!mReady)：等待编码器线程初始化完成
            // 💡 为什么等待：线程启动后需要时间初始化Looper和Handler
            // 💡 作用：确保线程完全准备好后再返回
            // 💡 使用时机：启动线程后，构造函数返回之前
            while (!mReady) {
                try {
                    // ⏳ mReadyFence.wait()：释放锁并等待通知
                    // 💡 为什么等待：线程初始化需要时间，不能立即使用
                    // 💡 作用：阻塞当前线程，直到编码器线程调用notify()
                    // 💡 使用时机：mReady为false时持续等待
                    mReadyFence.wait();  // ⏳ 等待线程就绪
                } catch (InterruptedException ie) {
                    // ignore
                }
            }
        }
    }

    /**
     * Tells the video recorder to stop recording.  (Call from non-encoder thread.)
     * <p>
     * Returns immediately; the encoder/muxer may not yet be finished creating the movie.
     * ⏹️ 停止录制（从非编码器线程调用）。立即返回。
     * <p>
     * TODO: have the encoder thread invoke a callback on the UI thread just before it shuts down
     * so we can provide reasonable status UI (and let the caller know that movie encoding
     * has completed).
     */
    public void stopRecording() {
        // 📨 mHandler.obtainMessage(MSG_STOP_RECORDING)：构造停止录制消息
        // 💡 为什么发送：通知编码器线程排空编码器并释放资源
        // 💡 作用：触发handleStopRecording()处理，排空+释放编码器
        // 💡 使用时机：用户停止录制时从UI线程调用
        mHandler.sendMessage(mHandler.obtainMessage(MSG_STOP_RECORDING));
        // We don't know when these will actually finish (or even start).  We don't want to
        // delay the UI thread though, so we return immediately.
        // 📤 发送停止消息。不确定何时完成，但不想阻塞 UI 线程。
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
     * Tells the video recorder that a new frame is arriving soon.  (Call from non-encoder thread.)
     * <p>
     * This function sends a message and returns immediately.  This is fine -- the purpose is
     * to wake the encoder thread up to do work so the producer side doesn't block.
     * 🖼️ 通知编码器新帧即将到来（从非编码器线程调用）。
     *    发送消息后立即返回。目的是唤醒编码器线程，防止生产者端阻塞。
     */
    public void frameAvailableSoon() {
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

        // 📨 mHandler.obtainMessage(MSG_FRAME_AVAILABLE)：构造帧可用消息
        // 💡 为什么发送：通知编码器线程排空编码器输出缓冲区
        // 💡 作用：唤醒编码器线程消费编码器输出，防止生产者阻塞
        // 💡 使用时机：编码器就绪后立即发送
        mHandler.sendMessage(mHandler.obtainMessage(MSG_FRAME_AVAILABLE));
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
        // 🔧 为该线程创建 Looper 和 Handler
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
            // 💡 作用：绑定到当前线程的Looper，处理MSG_STOP_RECORDING等消息
            // 💡 使用时机：Looper.prepare()之后，Looper.loop()之前
            mHandler = new EncoderHandler(this);
            // ✅ mReady = true：标记编码器线程已就绪
            // 💡 为什么设置：构造函数中while(!mReady)循环在等待此标记变为true
            // 💡 作用：告知主线程编码器线程已准备好接收消息
            // 💡 使用时机：Handler创建完成后立即设置
            mReady = true;              // ✅ 标记就绪
            // 🔔 mReadyFence.notify()：通知等待的线程
            // 💡 为什么调用：构造函数中waitUntilReady()在等待此通知
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
        // 💡 为什么需要：mHandler和mRunning被多个线程访问
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
     */
    private static class EncoderHandler extends Handler {
        private WeakReference<TextureMovieEncoder2> mWeakEncoder;  // 🎣 弱引用避免内存泄漏

        /**
         * 🔧 构造函数，使用弱引用持有编码器实例。
         */
        public EncoderHandler(TextureMovieEncoder2 encoder) {
            mWeakEncoder = new WeakReference<TextureMovieEncoder2>(encoder);
        }

        @Override  // runs on encoder thread 🧵 在编码器线程上运行
        public void handleMessage(Message inputMessage) {
            // 📊 what = inputMessage.what：提取消息类型标识
            // 💡 为什么获取：需要根据消息类型执行不同的处理逻辑
            // 💡 作用：区分停止录制、帧可用等不同操作
            // 💡 使用时机：switch判断前读取
            int what = inputMessage.what;
            // 📦 obj = inputMessage.obj：提取消息携带的数据对象
            // 💡 为什么获取：某些消息携带额外数据（如配置信息）
            // 💡 作用：携带通用数据，根据消息类型强转后使用
            // 💡 使用时机：根据what类型决定是否使用
            Object obj = inputMessage.obj;

            // 🎣 encoder = mWeakEncoder.get()：通过弱引用获取编码器实例
            // 💡 为什么获取：需要调用编码器的方法处理消息
            // 💡 作用：安全获取编码器引用，可能为null（已被GC回收）
            // 💡 使用时机：处理每个消息前获取
            TextureMovieEncoder2 encoder = mWeakEncoder.get();
            // 🔍 if (encoder == null)：检查编码器是否已被回收
            // 💡 为什么检查：弱引用的对象可能已被GC回收
            // 💡 作用：避免对null对象调用方法导致崩溃
            // 💡 使用时机：获取弱引用后立即检查
            if (encoder == null) {
                Log.w(TAG, "EncoderHandler.handleMessage: encoder is null");
                return;  // 🚫 编码器已被回收
            }

            // 🔀 switch (what)：根据消息类型分发处理
            // 💡 为什么分发：不同类型的消息需要不同的处理逻辑
            // 💡 作用：消息路由，将消息分发到对应的处理方法
            // 💡 使用时机：提取消息类型后立即执行
            switch (what) {
                case MSG_STOP_RECORDING:     // ⏹️ 停止录制
                    // ⏹️ encoder.handleStopRecording()：处理停止录制
                    // 💡 为什么调用：需要排空编码器并释放资源
                    // 💡 作用：结束编码流程，释放所有资源
                    // 💡 使用时机：收到MSG_STOP_RECORDING消息时
                    encoder.handleStopRecording();
                    // 🚪 Looper.myLooper().quit()：退出消息循环
                    // 💡 为什么调用：停止录制后编码器线程应退出
                    // 💡 作用：终止Looper.loop()，线程run()方法返回
                    // 💡 使用时机：资源释放完成后退出
                    Looper.myLooper().quit();  // 🚪 处理完后退出 Looper
                    break;
                case MSG_FRAME_AVAILABLE:    // 🖼️ 新帧可用
                    // 🖼️ encoder.handleFrameAvailable()：处理新帧可用
                    // 💡 为什么调用：需要排空编码器输出，防止生产者阻塞
                    // 💡 作用：消费编码器输出缓冲区
                    // 💡 使用时机：收到MSG_FRAME_AVAILABLE消息时
                    encoder.handleFrameAvailable();
                    break;
                default:
                    // ❌ throw new RuntimeException(...)：未知消息类型异常
                    // 💡 为什么抛出：收到了未定义的消息类型，这是编程错误
                    // 💡 作用：快速失败，暴露问题
                    // 💡 使用时机：switch的default分支
                    throw new RuntimeException("Unhandled msg what=" + what);
            }
        }
    }

    /**
     * Handles notification of an available frame.
     * 🖼️ 处理可用帧通知，排空编码器输出。
     */
    private void handleFrameAvailable() {
        if (VERBOSE) Log.d(TAG, "handleFrameAvailable");
        mVideoEncoder.drainEncoder(false);  // 📤 排空编码器（非结束模式）
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
        mVideoEncoder.drainEncoder(true);   // 📤 排空编码器（结束模式）
        // 🗑️ mVideoEncoder.release()：释放编码器资源
        // 💡 为什么调用：停止录制后需要释放所有编码器和复用器资源
        // 💡 作用：释放MediaCodec编码器和MediaMuxer复用器
        // 💡 使用时机：drainEncoder(true)之后
        mVideoEncoder.release();            // 🗑️ 释放编码器资源
    }
}
