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

import android.util.Log;

/**
 * Movie player callback.
 * <p>
 * The goal here is to play back frames at the original rate.  This is done by introducing
 * a pause before the frame is submitted to the renderer.
 * <p>
 * This is not coordinated with VSYNC.  Since we can't control the display's refresh rate, and
 * the source material has time stamps that specify when each frame should be presented,
 * we will have to drop or repeat frames occasionally.
 * <p>
 * Thread restrictions are noted in the method descriptions.  The FrameCallback overrides should
 * only be called from the MoviePlayer.
 * 
 * 🎬 视频播放速度控制回调
 * 💡 目标是以原始帧率播放视频，通过在渲染前引入暂停来实现
 * ⚠️ 未与VSYNC同步，可能需要偶尔丢帧或重复帧
 */
public class SpeedControlCallback implements MoviePlayer.FrameCallback {
    private static final String TAG = MainActivity.TAG;
    private static final boolean CHECK_SLEEP_TIME = false;

    private static final long ONE_MILLION = 1000000L;  // ⏱️ 一百万微秒 = 1秒

    private long mPrevPresentUsec;           // ⏱️ 上一帧的呈现时间（微秒）
    private long mPrevMonoUsec;              // ⏱️ 上一帧的单调时钟时间（微秒）
    private long mFixedFrameDurationUsec;    // ⏱️ 固定帧时长（微秒），0表示使用PTS
    private boolean mLoopReset;              // 🔄 是否循环重置

    /**
     * Sets a fixed playback rate.  If set, this will ignore the presentation time stamp
     * in the video file.  Must be called before playback thread starts.
     * 
     * ⏱️ 设置固定播放帧率
     * 💡 设置后将忽略视频文件中的时间戳，必须在播放线程启动前调用
     * @param fps 期望的帧率
     */
    public void setFixedPlaybackRate(int fps) {
        mFixedFrameDurationUsec = ONE_MILLION / fps;
    }

    // runs on decode thread
    /**
     * 帧渲染前调用，控制播放速度
     * 💡 通过睡眠来确保按原始帧率播放
     * @param presentationTimeUsec 帧的呈现时间（微秒）
     */
    @Override
    public void preRender(long presentationTimeUsec) {
        // For the first frame, we grab the presentation time from the video
        // and the current monotonic clock time.  For subsequent frames, we
        // sleep for a bit to try to ensure that we're rendering frames at the
        // pace dictated by the video stream.
        // 🎯 对于第一帧，从视频中获取呈现时间和当前单调时钟时间
        // 💡 对于后续帧，通过睡眠确保按视频流指定的节奏渲染帧

        if (mPrevMonoUsec == 0) {
            // 第一帧：记录当前单调时钟时间（微秒）
            // 📌 作用：作为后续帧时间计算的基准点
            // ⏰ 使用时机：仅在渲染第一帧时设置一次
            mPrevMonoUsec = System.nanoTime() / 1000;

            // 🎬 记录第一帧的呈现时间戳（微秒）
            // 📌 作用：用于计算相邻帧之间的时间间隔
            // ⏰ 使用时机：后续帧会用此值计算帧间隔
            mPrevPresentUsec = presentationTimeUsec;
        } else {
            // 🔄 后续帧：计算帧间隔并睡眠以保持正确播放速度

            // 📊 frameDelta - 帧间隔时间（微秒）
            // 📌 作用：表示当前帧与上一帧之间的时间差
            // 💡 决定需要睡眠多长时间再渲染下一帧
            // ⏰ 使用时机：用于计算期望唤醒时间和实际睡眠时长
            long frameDelta;

            // 🔄 检查是否循环重置
            if (mLoopReset) {
                // 循环重置时，设置上一帧时间为"当前时间 - 30fps间隔"
                // 📌 作用：避免循环播放时出现巨大的时间跳跃
                // 💡 假设30fps，每帧约33333微秒
                mPrevPresentUsec = presentationTimeUsec - ONE_MILLION / 30;

                // 🔙 重置循环标志，避免重复处理
                mLoopReset = false;
            }

            // ⏱️ 判断使用固定帧率还是动态PTS
            if (mFixedFrameDurationUsec != 0) {
                // 使用固定帧率模式：忽略视频时间戳，使用预设帧间隔
                // 📌 作用：实现恒定帧率播放，忽略视频原始时间戳
                frameDelta = mFixedFrameDurationUsec;
            } else {
                // 使用动态PTS模式：计算实际帧间隔
                // 📌 作用：按视频原始帧率播放
                frameDelta = presentationTimeUsec - mPrevPresentUsec;
            }

            // 🛡️ 帧间隔校验和修正
            if (frameDelta < 0) {
                // ⚠️ 异常情况：视频时间倒退
                Log.w(TAG, "Weird, video times went backward");
                // 🔧 修正：设为0，立即渲染
                frameDelta = 0;
            } else if (frameDelta == 0) {
                // ⚠️ 警告：当前帧和上一帧时间戳相同
                Log.i(TAG, "Warning: current frame and previous frame had same timestamp");
            } else if (frameDelta > 10 * ONE_MILLION) {
                // ⚠️ 帧间隔过长（超过10秒），可能是异常
                // 🔧 修正：限制在5秒内，避免长时间阻塞
                Log.i(TAG, "Inter-frame pause was " + (frameDelta / ONE_MILLION) +
                        "sec, capping at 5 sec");
                frameDelta = 5 * ONE_MILLION;
            }

            // ⏰ desiredUsec - 期望唤醒时间（微秒）
            // 📌 作用：计算下一帧应该在什么时间点被渲染
            // 💡 计算方式：上一次单调时间 + 帧间隔
            long desiredUsec = mPrevMonoUsec + frameDelta;

            // ⏱️ nowUsec - 当前单调时钟时间（微秒）
            // 📌 作用：用于判断是否需要睡眠以及睡眠多长时间
            // ⏰ 使用时机：每次循环迭代都会更新
            long nowUsec = System.nanoTime() / 1000;

            // 😴 睡眠循环：直到接近期望唤醒时间
            // 💡 条件：当前时间 < 期望时间 - 100微秒（留100微秒余量）
            while (nowUsec < (desiredUsec - 100)) {
                // 🛌 sleepTimeUsec - 本次睡眠时长（微秒）
                // 📌 作用：计算需要睡眠多久才能接近期望时间
                // 💡 最大限制500ms，避免单次睡眠过长
                long sleepTimeUsec = desiredUsec - nowUsec;

                // 🔧 限制单次睡眠最大500ms（500000微秒）
                // 📌 作用：避免长时间睡眠导致响应延迟
                if (sleepTimeUsec > 500000) {
                    sleepTimeUsec = 500000;
                }

                try {
                    // 🔍 CHECK_SLEEP_TIME - 调试标志
                    // 📌 作用：启用时会记录实际睡眠时间，用于调试
                    if (CHECK_SLEEP_TIME) {
                        // ⏱️ startNsec - 睡眠开始时间戳（纳秒）
                        // 📌 作用：用于计算实际睡眠时长
                        long startNsec = System.nanoTime();

                        // 😴 执行睡眠：转换微秒为毫秒和纳秒
                        Thread.sleep(sleepTimeUsec / 1000, (int) (sleepTimeUsec % 1000) * 1000);

                        // ⏱️ actualSleepNsec - 实际睡眠时长（纳秒）
                        // 📌 作用：用于调试，对比计划睡眠和实际睡眠的差异
                        long actualSleepNsec = System.nanoTime() - startNsec;

                        // 📝 输出调试日志
                        Log.d(TAG, "sleep=" + sleepTimeUsec + " actual=" + (actualSleepNsec/1000) +
                                " diff=" + (Math.abs(actualSleepNsec / 1000 - sleepTimeUsec)) +
                                " (usec)");
                    } else {
                        // 😴 正常模式：直接睡眠，不记录调试信息
                        Thread.sleep(sleepTimeUsec / 1000, (int) (sleepTimeUsec % 1000) * 1000);
                    }
                } catch (InterruptedException ie) {
                    // 🔇 忽略中断异常，继续执行
                }

                // ⏱️ 更新当前时间，检查是否还需继续睡眠
                nowUsec = System.nanoTime() / 1000;
            }

            // 📈 使用计算值推进时间，避免累积漂移
            // 📌 作用：保持时间基准稳定，不依赖系统时钟
            // 💡 使用固定增量而非实际时间，确保帧率一致性

            // ⏱️ 更新单调时钟基准时间
            mPrevMonoUsec += frameDelta;

            // 🎬 更新呈现时间基准
            mPrevPresentUsec += frameDelta;
        }
    }

    // runs on decode thread
    @Override public void postRender() {}

    /** 循环播放重置时调用 */
    @Override
    public void loopReset() {
        mLoopReset = true;
    }
}
