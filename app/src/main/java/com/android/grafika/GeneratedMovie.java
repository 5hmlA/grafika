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

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;
import android.view.Surface;
import com.android.grafika.gles.EglCore;
import com.android.grafika.gles.WindowSurface;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Base class for generated movies.
 * 
 * 🎬 生成视频的基类
 * 💡 提供视频编码、复用的核心功能
 */
public abstract class GeneratedMovie implements Content {
    private static final String TAG = MainActivity.TAG;
    private static final boolean VERBOSE = false;

    private static final int IFRAME_INTERVAL = 5;  // ⏱️ I帧间隔（秒）

    // 🎬 子类设置此标志表示视频已生成
    protected boolean mMovieReady = false;

    // 📊 录制时的"活跃"状态
    private MediaCodec.BufferInfo mBufferInfo;  // 📊 缓冲区信息
    private MediaCodec mEncoder;                // 🎬 编码器
    private MediaMuxer mMuxer;                  // 📦 复用器
    private EglCore mEglCore;                   // 🎮 EGL核心
    private WindowSurface mInputSurface;        // 🖼️ 输入Surface
    private int mTrackIndex;                    // 🎯 轨道索引
    private boolean mMuxerStarted;              // ✅ 复用器是否已启动

    /**
     * Creates the movie content.  Usually called from an async task thread.
     * 
     * 🎬 创建视频内容，通常从异步任务线程调用
     */
    public abstract void create(File outputFile, ContentManager.ProgressUpdater prog);

    /**
     * 判断编码器是否为软件实现
     */
    private static boolean isSoftwareCodec(MediaCodec codec) {
        String codecName = codec.getCodecInfo().getName();
        return ("OMX.google.h264.encoder".equals(codecName));
    }

    /**
     * Prepares the video encoder, muxer, and an EGL input surface.
     * 
     * 🔧 准备视频编码器、复用器和EGL输入Surface
     */
    protected void prepareEncoder(String mimeType, int width, int height, int bitRate,
            // 💡 framesPerSecond: 帧率参数，每秒编码的帧数
            // 💡 outputFile: 输出 MP4 文件路径
            // 💡 throws IOException: 可能因文件创建失败抛出 IO 异常
            int framesPerSecond, File outputFile) throws IOException {
        // 📊 mBufferInfo: 编码器输出缓冲区的信息对象
        //    💡 为什么定义：编码器输出的数据需要元数据描述（偏移量、大小、时间戳、标志位）
        //    💡 作用：存储每帧编码数据的偏移、大小、时间戳、标志位等元信息
        //    💡 使用时机：drainEncoder() 中从编码器取出数据时填充
        mBufferInfo = new MediaCodec.BufferInfo();

        // 📋 format: 视频编码格式配置对象
        //    作用：定义视频的 MIME 类型、尺寸、编码参数
        //    使用时机：传入 mEncoder.configure() 配置编码器
        MediaFormat format = MediaFormat.createVideoFormat(mimeType, width, height);

        // ⚙️ 设置编码参数（缺少某些参数可能导致 configure() 抛出无用异常）
        // 🎨 COLOR_FormatSurface: 使用 Surface 输入（硬件加速编码）
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                // 💡 COLOR_FormatSurface: 使用 Surface 作为编码器输入（硬件加速）
                // 💡 为什么选择：允许通过 EGL Surface 渲染后直接编码，无需 CPU 拷贝
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        // 📊 bitRate: 比特率（bps），控制视频质量和文件大小
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        // 🎞️ framesPerSecond: 帧率，每秒帧数
        format.setInteger(MediaFormat.KEY_FRAME_RATE, framesPerSecond);
        // ⏱️ IFRAME_INTERVAL: I 帧（关键帧）间隔秒数，影响随机seek能力
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
        // 📝 VERBOSE 日志：打印编码格式参数，便于调试
        // 💡 为什么调用：开发阶段确认编码参数是否正确设置
        if (VERBOSE) Log.d(TAG, "format: " + format);

        // 🎬 mEncoder: MediaCodec 视频编码器实例
        //    作用：将 Surface 输入的图像编码为 H.264 等格式
        //    使用时机：创建输入 Surface、启动编码、排空编码数据
        mEncoder = MediaCodec.createEncoderByType(mimeType);
        // ⚙️ 配置编码器：传入格式，无输出 Surface（直接取编码数据），编码模式
        mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        // 📝 VERBOSE 日志：打印编码器名称（如 OMX.qcom.video.encoder.avc）
        // 💡 为什么调用：确认使用的是硬件编码器还是软件编码器
        Log.v(TAG, "encoder is " + mEncoder.getCodecInfo().getName());
        // 🖼️ surface: 编码器的输入 Surface
        //    作用：外部渲染目标，写入的图像会被编码
        //    使用时机：传入 EGL 创建窗口表面
        Surface surface;
        // 🔒 try：尝试创建编码器的输入 Surface
        // 💡 为什么使用：createInputSurface() 可能因软件编码器不支持而抛异常
        try {
            // 💡 surface = mEncoder.createInputSurface()：获取编码器的输入 Surface
            // 💡 为什么调用：硬件编码器支持 Surface 输入，实现零拷贝编码
            surface = mEncoder.createInputSurface();  // 🔧 获取编码器的输入 Surface
        // 🔒 catch (IllegalStateException)：捕获创建 Surface 失败的异常
        // 💡 为什么捕获：软件编码器不支持 createInputSurface()，需要友好处理
        } catch (IllegalStateException ise) {
            // 🔍 isSoftwareCodec(mEncoder)：判断是否为软件编码器
            if (isSoftwareCodec(mEncoder)) {
                // 🚨 软件编码器不支持 createInputSurface()，抛出更明确的异常
                throw new RuntimeException("Can't use input surface with software codec: " +
                        mEncoder.getCodecInfo().getName(), ise);
            // 🔍 else：非软件编码器也创建失败，抛出通用异常
            } else {
                // 🚨 硬件编码器创建 Surface 失败，抛出通用错误
                throw new RuntimeException("Failed to create input surface", ise);
            }
        }
        // 🎮 mEglCore: EGL 核心对象（无共享上下文）
        //    作用：管理 EGL 显示连接、上下文和表面
        //    使用时机：创建 WindowSurface，makeCurrent 切换渲染上下文
        mEglCore = new EglCore(null, EglCore.FLAG_RECORDABLE);
        // 🖼️ mInputSurface: 包装编码器 Surface 的 EGL 窗口表面
        //    💡 为什么定义：需要将 EGL 渲染上下文与编码器 Surface 连接起来
        //    💡 作用：将 EGL 渲染输出连接到编码器输入
        //    💡 使用时机：makeCurrent 后进行 GL 绘制，swapBuffers 提交帧
        mInputSurface = new WindowSurface(mEglCore, surface, true);
        // 🔧 将 EGL 上下文切换到输入表面，后续 GL 操作渲染到编码器
        mInputSurface.makeCurrent();
        // ▶️ 启动编码器，开始接收输入并产生编码输出
        mEncoder.start();

        // 📝 VERBOSE 日志：记录输出文件路径，便于调试
        if (VERBOSE) Log.d(TAG, "output will go to " + outputFile);
        // 📦 mMuxer: 媒体复用器，将编码数据封装为 MP4 文件
        //    作用：接收编码器输出的 H.264 数据并写入 .mp4 文件
        //    使用时机：drainEncoder() 中 addTrack/start/writeSampleData
        mMuxer = new MediaMuxer(outputFile.toString(),
                // 💡 MUXER_OUTPUT_MPEG_4：输出格式为标准 MP4
                // 💡 为什么选择：MP4 是最通用的视频容器格式，兼容性最好
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        // 🎯 mTrackIndex: 视频轨道索引（-1 表示尚未添加）
        //    作用：标识复用器中的视频轨道
        //    使用时机：编码器输出格式确定后 addTrack() 获取
        mTrackIndex = -1;
        // ✅ mMuxerStarted: 复用器启动标志
        //    作用：防止在 addTrack 之前调用 writeSampleData
        //    使用时机：drainEncoder() 中检查和设置
        mMuxerStarted = false;
    }

    /**
     * Releases encoder resources.  May be called after partial / failed initialization.
     * 
     * 🗑️ 释放编码器资源
     */
    protected void releaseEncoder() {
        // 📝 记录日志，标记开始释放资源
        if (VERBOSE) Log.d(TAG, "releasing encoder objects");

        // 🎬 释放编码器资源
        //    mEncoder: MediaCodec 视频编码器实例
        //    💡 为什么定义：编码器持有硬件编解码器资源和 Native 层内存，必须显式释放
        //    💡 作用：停止编码并释放底层硬件资源
        //    💡 使用时机：视频生成完成后清理，或初始化失败时回滚
        if (mEncoder != null) {
            mEncoder.stop();       // ⏹️ 停止编码器，结束所有编码工作
            mEncoder.release();    // 🗑️ 释放编码器占用的系统资源
            mEncoder = null;       // 🔒 置空防止重复释放
        }

        // 🖼️ 释放 EGL 输入表面资源
        //    mInputSurface: 包装编码器 Surface 的 EGL 窗口表面
        //    💡 为什么定义：EGL 表面持有 GPU 资源和 Surface 引用，不释放会导致资源泄漏
        //    💡 作用：断开 EGL 与编码器 Surface 的连接
        //    💡 使用时机：编码器停止后释放渲染资源
        if (mInputSurface != null) {
            mInputSurface.release();   // 🗑️ 释放 EGL 表面资源
            mInputSurface = null;      // 🔒 置空防止重复释放
        }

        // 🎮 释放 EGL 核心资源
        //    mEglCore: EGL 上下文和显示连接管理器
        //    💡 为什么定义：EGL 上下文持有 GPU 上下文资源，必须显式销毁
        //    💡 作用：销毁 EGL 上下文、断开显示连接
        //    💡 使用时机：在 Surface 释放后清理 EGL 环境
        if (mEglCore != null) {
            mEglCore.release();    // 🗑️ 释放 EGL 上下文资源
            mEglCore = null;       // 🔒 置空防止重复释放
        }

        // 📦 释放复用器资源
        //    mMuxer: 媒体复用器，将编码数据封装为 MP4 文件
        //    💡 为什么定义：复用器持有文件句柄，必须释放才能完成 MP4 文件最终写入
        //    💡 作用：停止复用并关闭输出文件，确保数据完整写入
        //    💡 使用时机：所有编码数据写入后停止复用器
        if (mMuxer != null) {
            mMuxer.stop();         // ⏹️ 停止复用器，完成文件写入
            mMuxer.release();      // 🗑️ 释放复用器占用的系统资源
            mMuxer = null;         // 🔒 置空防止重复释放
        }
    }

    /**
     * Submits a frame to the encoder.
     *
     * 🖼️ 提交一帧到编码器
     * @param presentationTimeNsec 呈现时间戳（纳秒）
     */
    protected void submitFrame(long presentationTimeNsec) {
        mInputSurface.setPresentationTime(presentationTimeNsec);
        mInputSurface.swapBuffers();
    }

    /**
     * Extracts all pending data from the encoder.
     * <p>
     * If endOfStream is not set, this returns when there is no more data to drain.  If it
     * is set, we send EOS to the encoder, and then iterate until we see EOS on the output.
     * 
     * 📤 从编码器提取所有待处理数据
     * @param endOfStream 是否为流结束
     */
    protected void drainEncoder(boolean endOfStream) {
        // ⏱️ TIMEOUT_USEC: 轮询编码器输出的超时时间（微秒）
        //    💡 为什么定义：dequeueOutputBuffer() 需要超时参数来控制等待时间
        //    💡 作用：控制 dequeueOutputBuffer 的等待时间
        //    💡 使用时机：while 循环中每次检查编码器输出
        //    💡 值选择：10ms 足够短不会卡住，又能让编码器有时间处理
        final int TIMEOUT_USEC = 10000;
        // 📝 VERBOSE 日志：记录 drainEncoder 调用及 endOfStream 状态
        // 💡 为什么调用：便于追踪编码器排空过程
        if (VERBOSE) Log.d(TAG, "drainEncoder(" + endOfStream + ")");

        // 🔍 if (endOfStream)：判断是否为流结束模式
        // 💡 为什么判断：流结束时需要通知编码器，非结束时只排空已有数据
        if (endOfStream) {
            // 📤 endOfStream=true 时，向编码器发送流结束信号
            //    作用：告知编码器不再有新输入，尽快输出所有剩余数据
            //    使用时机：视频录制结束前调用一次
            if (VERBOSE) Log.d(TAG, "sending EOS to encoder");
            // 📤 mEncoder.signalEndOfInputStream()：发送流结束信号给编码器
            // 💡 为什么调用：告知编码器没有更多输入帧，尽快输出所有缓存数据
            // 💡 作用：触发编码器输出所有剩余编码数据
            // 💡 使用时机：视频录制结束前，最后一次 drainEncoder(true) 时调用
            mEncoder.signalEndOfInputStream();
        }

        // 📦 encoderOutputBuffers: 编码器输出缓冲区数组
        //    💡 为什么定义：需要持有编码器输出缓冲区的引用才能读取编码数据
        //    💡 作用：持有编码后的视频数据（H.264 NAL 单元）
        //    💡 使用时机：dequeueOutputBuffer 返回有效索引后读取数据
        //    💡 注意：缓冲区可能在 INFO_OUTPUT_BUFFERS_CHANGED 后更新
        ByteBuffer[] encoderOutputBuffers = mEncoder.getOutputBuffers();
        // 🔄 while (true)：无限循环排空编码器输出缓冲区
        // 💡 为什么循环：编码器可能有多个缓冲区排队，需要全部取出
        // 💡 退出条件：INFO_TRY_AGAIN_LATER（非结束模式）或收到 EOS 标志
        while (true) {
            // 🔍 encoderStatus: 从编码器取出一个输出缓冲区
            //    💡 为什么定义：需要判断编码器是否有可用的输出数据
            //    💡 作用：获取编码数据的状态（可用/重试/格式变更等）
            //    💡 使用时机：while 循环核心判断依据
            int encoderStatus = mEncoder.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);
            // 🔍 if (encoderStatus == INFO_TRY_AGAIN_LATER)：暂时没有可用输出
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // ⏳ 暂时没有可用的输出数据
                if (!endOfStream) {
                    // 🚪 非结束模式：直接退出循环，不阻塞等待
                    // 💡 为什么退出：调用者只是尝试排空，不需要等待
                    break;
                // 🔍 else：endOfStream==true，必须等待 EOS 出现
                } else {
                    // 🔄 结束模式：继续轮询等待 EOS 标志
                    //    因为 signalEndOfInputStream() 后必须等到输出端出现 EOS
                    if (VERBOSE) Log.d(TAG, "no output available, spinning to await EOS");
                }
            // 🔍 else if (INFO_OUTPUT_BUFFERS_CHANGED)：输出缓冲区数组已变更
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // 🔄 输出缓冲区数组已变更（旧的不再有效）
                //    作用：重新获取编码器的输出缓冲区引用
                //    使用时机：API < 21 时需要处理此情况
                encoderOutputBuffers = mEncoder.getOutputBuffers();
            // 🔍 else if (INFO_OUTPUT_FORMAT_CHANGED)：编码器输出格式已确定
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // 📋 编码器输出格式已确定（包含 CSD-0/CSD-1 等"神奇数据"）
                //    作用：获取编码后的 MediaFormat，添加到复用器
                //    使用时机：仅发生一次，在收到第一帧编码数据之前
                if (mMuxerStarted) {
                    // 🚨 复用器已启动时格式不应再变化，这是编码器 bug
                    throw new RuntimeException("format changed twice");
                }
                // 📋 newFormat: 编码器输出的媒体格式（含 SPS/PPS 等）
                //    💡 为什么定义：编码器输出格式确定后需要获取完整格式信息
                //    💡 作用：包含编解码器配置数据，用于复用器正确封装 MP4
                //    💡 使用时机：INFO_OUTPUT_FORMAT_CHANGED 时获取并传入 addTrack()
                MediaFormat newFormat = mEncoder.getOutputFormat();
                // 📝 记录格式变更，便于调试编码参数
                Log.d(TAG, "encoder output format changed: " + newFormat);
                // 🎯 添加视频轨道到复用器，获取轨道索引
                mTrackIndex = mMuxer.addTrack(newFormat);
                // ▶️ 启动复用器，开始接受写入数据
                mMuxer.start();
                mMuxerStarted = true;  // ✅ 标记复用器已启动
            // 🔍 else if (encoderStatus < 0)：未知的负值状态码
            } else if (encoderStatus < 0) {
                // 🤷 未知状态码，记录警告并忽略
                // 💡 为什么忽略：未知状态码不影响已知流程，继续处理
                Log.w(TAG, "unexpected result from encoder.dequeueOutputBuffer: " + encoderStatus);
            // 🔍 else：成功取出一个有效的输出缓冲区（encoderStatus >= 0）
            } else {
                // ✅ 成功取出一个有效的输出缓冲区
                // 📦 encodedData: 编码后的视频数据缓冲区
                //    💡 为什么定义：需要持有编码数据的引用才能写入复用器
                //    💡 作用：包含一帧编码数据（H.264 NAL 单元）
                //    💡 使用时机：position/limit 后传入 writeSampleData
                ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];
                // 🔍 if (encodedData == null)：检查缓冲区是否有效
                if (encodedData == null) {
                    // 🚨 缓冲区不应为 null，编码器内部错误
                    throw new RuntimeException("encoderOutputBuffer " + encoderStatus + " was null");
                }

                // 🔍 if (BUFFER_FLAG_CODEC_CONFIG)：检查是否为编解码器配置数据
                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    // ⚙️ 编解码器配置数据（SPS/PPS），已在 INFO_OUTPUT_FORMAT_CHANGED 时处理
                    //    作用：标记为无效，不写入复用器
                    if (VERBOSE) Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
                    // 📊 mBufferInfo.size = 0：将大小设为 0，跳过写入
                    // 💡 为什么设为 0：writeSampleData 在 size==0 时不会写入数据
                    mBufferInfo.size = 0;
                }

                // 🔍 if (mBufferInfo.size != 0)：检查是否有实际编码数据
                if (mBufferInfo.size != 0) {
                    // 🔍 if (!mMuxerStarted)：检查复用器是否已启动
                    if (!mMuxerStarted) {
                        // 🚨 复用器未启动就尝试写入数据，编码流程错误
                        throw new RuntimeException("muxer hasn't started");
                    }
                    // 📐 encodedData.position(mBufferInfo.offset)：设置读取起始位置
                    // 💡 为什么设置：BufferInfo 中的 offset 指示有效数据的起始位置
                    encodedData.position(mBufferInfo.offset);
                    // 📐 encodedData.limit(offset + size)：设置读取结束位置
                    // 💡 为什么设置：确保只读取有效数据范围，不读取垃圾数据
                    encodedData.limit(mBufferInfo.offset + mBufferInfo.size);
                    // 📝 将编码数据写入复用器（实际写入 MP4 文件）
                    mMuxer.writeSampleData(mTrackIndex, encodedData, mBufferInfo);
                    // 📝 VERBOSE 日志：记录写入复用器的字节数
                    if (VERBOSE) Log.d(TAG, "sent " + mBufferInfo.size + " bytes to muxer");
                }

                // 🗑️ 释放输出缓冲区，归还给编码器循环使用
                mEncoder.releaseOutputBuffer(encoderStatus, false);

                // 🔍 if (BUFFER_FLAG_END_OF_STREAM)：检查编码器输出是否包含 EOS 标志
                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    // 🏁 检测到流结束标志
                    if (!endOfStream) {
                        // ⚠️ 非预期的流结束（调用者没有设置 endOfStream）
                        // 💡 为什么记录：可能是编码器异常，需要开发者关注
                        Log.w(TAG, "reached end of stream unexpectedly");
                    // 🔍 else：endOfStream==true 且收到 EOS，符合预期
                    } else {
                        // ✅ 预期的流结束，VERBOSE 日志确认
                        if (VERBOSE) Log.d(TAG, "end of stream reached");
                    }
                    // 🚪 收到 EOS，退出循环
                    break;
                }
            }
        }
    }
}
