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

import android.graphics.Canvas;
import android.graphics.Paint;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.widget.TextView;
import android.app.Activity;

import com.google.grafika.R;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * ⚠️ 使用软件写入 Surface 生成短视频（非官方支持的用法）。
 * Generate a short movie using Surface input to MediaCodec, where the Surface is written to
 * from software (lock() + unlockAndPost() rather than GLES).  This is NOT A SUPPORTED USE
 * CASE, but as of API 19 the documentation says nothing to that effect.
 *
 * See also https://code.google.com/p/android/issues/detail?id=61194
 * 使用 lock() + unlockAndPost()（而非 GLES）向 MediaCodec 的 Surface 写入数据。
 * ⚠️ 这不是官方支持的用法，但 API 19 文档中并未说明。
 */
public class SoftInputSurfaceActivity extends Activity {
    private static final String TAG = MainActivity.TAG;
    private static final boolean VERBOSE = true;

    private static final String MIME_TYPE = "video/avc";    // 🎬 H.264 编码格式
    private static final int WIDTH = 640;                   // 📐 视频宽度
    private static final int HEIGHT = 480;                  // 📐 视频高度
    private static final int BIT_RATE = 4000000;            // 📊 比特率 4Mbps
    private static final int FRAMES_PER_SECOND = 4;         // 🎞️ 帧率 4fps
    private static final int IFRAME_INTERVAL = 5;           // ⏱️ I 帧间隔 5 秒

    private static final int NUM_FRAMES = 8;                // 🎞️ 总帧数 8

    // "live" state during recording
    // 📋 录制期间的实时状态
    private MediaCodec.BufferInfo mBufferInfo;  // 📊 缓冲区信息
    private MediaCodec mEncoder;                // 🎬 MediaCodec 编码器
    private MediaMuxer mMuxer;                  // 🎬 媒体混合器
    private Surface mInputSurface;              // 🖼️ 输入表面
    private int mTrackIndex;                    // 📋 轨道索引
    private boolean mMuxerStarted;              // ✅ 混合器已启动标志
    private long mFakePts;                      // ⏱️ 伪造的时间戳（软件输入无法获取真实时间戳）


    /**
     * 🔧 Activity 创建时调用
     * 💡 作用：初始化界面并直接在主线程生成视频（⚠️ 不推荐的做法，仅供演示）
     * 💡 参数savedInstanceState：Activity之前保存的状态数据
     */
    // 🚀 Activity 创建时直接在 onCreate 中生成视频（这是不好的做法，仅供演示）
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 🔄 调用父类的onCreate方法
        // 💡 作用：执行标准的Activity创建流程
        super.onCreate(savedInstanceState);
        // 🖼️ 设置Activity布局文件
        // 💡 作用：加载包含结果TextView的XML布局
        setContentView(R.layout.activity_soft_input_surface);

        // 🔍 查找结果展示的TextView
        // 💡 变量tv：结果文本控件，用于显示"成功"或"失败"
        // 💡 作用：在视频生成完成后告知用户结果
        TextView tv = (TextView) findViewById(R.id.softInputResult_text);

        // Be VERY BAD and do the whole thing during onCreate().
        // ⚠️ 非常糟糕的做法：在 onCreate 中做所有事情（仅供演示）
        // 📝 开始生成视频的日志
        Log.i(TAG, "Generating movie...");
        try {
            // 🎬 调用视频生成方法，输出到soft-input-surface.mp4
            // 💡 作用：准备编码器、绘制帧、编码、封装为MP4
            generateMovie(new File(getFilesDir(), "soft-input-surface.mp4"));
            // ✅ 视频生成成功，更新TextView显示成功消息
            tv.setText(getString(R.string.succeeded));   // ✅ 成功
            // 📝 记录生成完成
            Log.i(TAG, "Movie generation complete");
        } catch (Exception ex) {
            // 🚨 视频生成失败，记录异常信息
            Log.e(TAG, "Movie generation FAILED", ex);    // 🚨 失败
            // ❌ 更新TextView显示失败消息
            tv.setText(getString(R.string.failed));
        }
    }

    /**
     * 🎬 生成视频：准备编码器，循环生成帧，最后排空编码器。
     * 💡 作用：完整流程：prepareEncoder -> 循环(generateFrame+drainEncoder) -> drainEncoder(true) -> releaseEncoder
     * 💡 时机：onCreate()中调用，注意这是阻塞主线程的做法（仅供演示）
     *
     * @param outputFile 输出MP4文件路径
     */
    private void generateMovie(File outputFile) {
        // 📝 try块：执行视频生成的完整流程
        //    为什么用try：确保即使发生异常也能释放资源
        //    使用时机：整个视频生成过程都在try块中执行
        try {
            // 🔧 prepareEncoder(): 准备编码器、混合器和输入Surface
            //    为什么调用：需要先初始化编码环境才能开始编码
            //    作用：创建MediaCodec编码器、MediaMuxer混合器、输入Surface
            //    使用时机：视频生成流程的第一步
            prepareEncoder(outputFile);

            // 🔄 for循环：生成NUM_FRAMES帧（8帧）
            //    变量i：当前帧号（0到NUM_FRAMES-1）
            //    为什么需要循环：需要逐帧生成并编码，每帧都是独立的图像
            //    使用时机：prepareEncoder()完成后开始循环
            for (int i = 0; i < NUM_FRAMES; i++) {
                // Drain any data from the encoder into the muxer.
                // 📤 drainEncoder(false): 排空编码器中已编码的数据到混合器
                //    为什么在生成新帧前调用：避免编码器输出缓冲区堆积，防止阻塞
                //    作用：取出之前已编码的数据并写入MP4文件
                //    使用时机：每次generateFrame()之前调用
                //    参数false：非结束模式，排空后立即返回
                drainEncoder(false);

                // Generate a frame and submit it.
                // 🎨 generateFrame(i): 生成第i帧（软件绘制彩色竖条+灰色横条）
                //    为什么调用：需要为编码器提供输入帧
                //    作用：通过Canvas在Surface上绘制一帧图像
                //    使用时机：drainEncoder()之后调用，确保编码器有空间接收新帧
                generateFrame(i);
//                submitFrame(computePresentationTimeNsec(i));  // ⚠️ 注释掉了：软件输入无法设置时间戳
            }

            // Send end-of-stream and drain remaining output.
            // 🏁 drainEncoder(true): 发送流结束信号并排空剩余编码数据
            //    为什么调用：告知编码器没有更多输入，需要输出所有剩余数据
            //    作用：发送EOS信号，等待编码器输出所有缓冲数据
            //    使用时机：所有帧生成完成后调用一次
            //    参数true：结束模式，会等待所有数据输出
            drainEncoder(true);
        } catch (IOException ioe) {
            // 🚨 catch块：捕获IO异常
            //    为什么捕获：文件读写或编码过程可能发生IO错误
            //    作用：将检查异常包装为运行时异常，便于上层统一处理
            //    使用时机：prepareEncoder()或编码过程发生IO错误时
            throw new RuntimeException(ioe);
        } finally {
            // 🗑️ finally块：释放编码器、混合器、Surface等资源
            //    为什么在finally中：无论成功或失败都必须释放资源，防止泄漏
            //    作用：调用releaseEncoder()清理所有编码相关资源
            //    使用时机：try块或catch块执行完成后自动执行
            releaseEncoder();  // 🗑️ 释放编码器
        }
    }

    /**
     * Prepares the video encoder, muxer, and an input surface.
     * 🔧 准备视频编码器、混合器和输入表面。
     * 💡 作用：初始化MediaCodec编码器和MediaMuxer，创建可绘制的Surface
     * 💡 时机：generateMovie()开始时调用
     */
    private void prepareEncoder(File outputFile) throws IOException {
        // 📊 mBufferInfo: 编码器输出缓冲区信息对象
        //    为什么定义：需要存储每帧编码数据的元信息（偏移、大小、时间戳、标志位）
        //    作用：drainEncoder()中从编码器取出数据时填充，传给mMuxer.writeSampleData()
        //    使用时机：每次dequeueOutputBuffer()返回有效缓冲区时更新
        mBufferInfo = new MediaCodec.BufferInfo();

        // 📋 format: 视频编码格式配置对象
        //    为什么定义：MediaCodec需要知道视频的编码参数才能正确工作
        //    作用：定义视频的MIME类型、尺寸、颜色格式、比特率等参数
        //    使用时机：传入mEncoder.configure()配置编码器
        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, WIDTH, HEIGHT);

        // Set some properties.  Failing to specify some of these can cause the MediaCodec
        // configure() call to throw an unhelpful exception.
        // ⚙️ 设置编码参数。缺少某些参数可能导致configure()抛出无用异常。
        // 🎨 KEY_COLOR_FORMAT: 颜色格式设置为Surface输入模式
        //    为什么设置：告知编码器从Surface获取帧数据（零拷贝，高效）
        //    作用：启用硬件加速编码，避免CPU到GPU的数据拷贝
        //    使用时机：编码器configure()时读取此参数
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        // 📊 KEY_BIT_RATE: 比特率4Mbps
        //    为什么设置：控制编码输出质量和文件大小的平衡
        //    作用：编码器根据此值分配压缩质量，值越高质量越好但文件越大
        //    使用时机：编码过程中动态调节码率
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        // 🎞️ KEY_FRAME_RATE: 帧率4fps
        //    为什么设置：告知编码器期望的输入帧率
        //    作用：影响编码器的帧间预测和码率分配策略
        //    使用时机：编码器内部帧率估计参考
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAMES_PER_SECOND);
        // ⏱️ KEY_I_FRAME_INTERVAL: I帧间隔5秒
        //    为什么设置：控制关键帧（完整帧）的插入频率
        //    作用：每隔N秒插入一个完整帧，用于随机访问和错误恢复
        //    使用时机：编码器决定何时编码完整帧而非差分帧
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
        if (VERBOSE) Log.d(TAG, "format: " + format);

        // Create a MediaCodec encoder, and configure it with our format.  Get a Surface
        // we can use for input and wrap it with a class that handles the EGL work.
        // 🎬 mEncoder: MediaCodec编码器实例
        //    为什么创建：需要将Surface输入的图像编码为H.264格式
        //    作用：执行视频编码，将原始帧转换为压缩的视频流
        //    使用时机：创建输入Surface、启动编码、排空编码数据
        mEncoder = MediaCodec.createEncoderByType(MIME_TYPE);
        // ⚙️ configure(): 配置编码器
        //    参数1 format：编码格式配置（上面设置的各种参数）
        //    参数2 null：无输出Surface（直接从编码器取数据）
        //    参数3 null：无加密器
        //    参数4 CONFIGURE_FLAG_ENCODE：标记为编码模式
        //    使用时机：createEncoderByType()之后、start()之前必须调用
        mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        // 🖼️ mInputSurface: 编码器的输入Surface
        //    为什么创建：需要一个可绘制的目标，外部可以往上面画帧
        //    作用：通过软件Canvas绘制帧到此Surface，编码器自动获取并编码
        //    使用时机：generateFrame()中lockCanvas()获取画布绘制
        mInputSurface = mEncoder.createInputSurface();
        // ▶️ start(): 启动编码器
        //    为什么调用：使编码器进入运行状态，开始处理输入帧
        //    作用：初始化编码器内部线程，准备接收输入并产生编码输出
        //    使用时机：configure()和createInputSurface()之后调用
        mEncoder.start();

        // Create a MediaMuxer.  We can't add the video track and start() the muxer here,
        // because our MediaFormat doesn't have the Magic Goodies.  These can only be
        // obtained from the encoder after it has started processing data.
        //
        // We're not actually interested in multiplexing audio.  We just want to convert
        // the raw H.264 elementary stream we get from MediaCodec into a .mp4 file.
        // 🎬 mMuxer: 媒体混合器，将编码数据封装为MP4文件
        //    为什么创建：需要将编码器输出的H.264裸流封装为标准MP4容器格式
        //    作用：接收编码数据并写入.mp4文件，添加时间戳和元数据
        //    使用时机：drainEncoder()中addTrack/start/writeSampleData
        //    注意：此时还不能addTrack，需要等编码器输出格式确定后才行
        if (VERBOSE) Log.d(TAG, "output will go to " + outputFile);
        mMuxer = new MediaMuxer(outputFile.toString(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        // 🎯 mTrackIndex: 视频轨道索引（-1表示尚未添加）
        //    为什么初始化为-1：表示轨道还未添加，需要等编码器输出格式确定
        //    作用：标识复用器中的视频轨道，writeSampleData()需要此索引
        //    使用时机：INFO_OUTPUT_FORMAT_CHANGED时addTrack()获取真实值
        mTrackIndex = -1;
        // ✅ mMuxerStarted: 混合器启动标志
        //    为什么需要：防止在addTrack()之前调用writeSampleData()导致崩溃
        //    作用：drainEncoder()中检查此标志，确保正确的调用顺序
        //    使用时机：addTrack()后设为true，writeSampleData()前检查
        mMuxerStarted = false;
    }

    /**
     * Releases encoder resources.  May be called after partial / failed initialization.
     * 🗑️ 释放编码器资源。可在部分/失败初始化后调用。
     * 💡 作用：依次释放编码器、输入Surface和混合器，防止资源泄漏
     * 💡 时机：generateMovie()的finally块中调用，无论成功或失败都会执行
     */
    private void releaseEncoder() {
        // 📝 VERBOSE变量：详细日志开关
        // 🔍 为什么记录：便于追踪资源释放时机和排查资源泄漏问题
        // 📍 作用：开启时输出详细的调试日志到logcat
        // ⏰ 时机：方法入口处最先记录
        if (VERBOSE) Log.d(TAG, "releasing encoder objects");

        // 🔍 mEncoder变量：MediaCodec编码器实例
        // 🔍 为什么检查null：如果prepareEncoder()中途失败（如OOM），mEncoder可能还未创建
        // 📍 作用：持有编码器引用，避免对未初始化的编码器调用stop/release导致空指针异常
        // ⏰ 时机：每次释放操作前都必须检查
        if (mEncoder != null) {
            // ⏹️ stop(): 停止编码器内部线程
            // 🔍 为什么调用：编码器运行时占用硬件资源（如OMX组件），必须先停止再释放
            // 📍 作用：停止编码循环，不再产生输出，释放内部编解码线程
            // ⏰ 时机：release()之前必须先调用stop()
            mEncoder.stop();       // ⏹️ 停止编码器
            // 🗑️ release(): 释放编码器占用的所有系统资源
            // 🔍 为什么调用：编码器持有底层硬件资源和native内存，必须显式释放
            // 📍 作用：释放所有分配的内存、硬件资源和native句柄
            // ⏰ 时机：stop()之后调用
            mEncoder.release();    // 🗑️ 释放编码器
            // 🔚 mEncoder = null: 置空引用
            // 🔍 为什么置空：解除成员变量对编码器的引用，允许GC回收Java对象
            // 📍 作用：防止重复调用release()，同时便于垃圾回收
            // ⏰ 时机：release()之后立即置空
            mEncoder = null;
        }

        // 🔍 mInputSurface变量：编码器的输入Surface
        // 🔍 为什么检查null：Surface是createInputSurface()创建的，可能未执行到那一步
        // 📍 作用：持有编码器输入Surface引用，generateFrame()通过它绘制帧
        // ⏰ 时机：编码器释放后检查并释放Surface
        if (mInputSurface != null) {
            // 🗑️ release(): 释放输入Surface占用的图形资源
            // 🔍 为什么调用：Surface持有native内存和GPU缓冲区，不释放会内存泄漏
            // 📍 作用：释放Surface绑定的图形缓冲区和native资源
            // ⏰ 时机：编码器释放之后释放（先释放编码器，再释放其输入Surface）
            mInputSurface.release();   // 🗑️ 释放输入表面
            // 🔚 mInputSurface = null: 置空引用
            // 🔍 为什么置空：防止重复释放，同时便于GC回收
            mInputSurface = null;
        }

        // 🔍 mMuxer变量：MediaMuxer媒体混合器
        // 🔍 为什么检查null：混合器在prepareEncoder()中创建，可能因IO异常未创建成功
        // 📍 作用：持有混合器引用，stop()完成MP4文件写入，release()释放资源
        // ⏰ 时机：输入Surface释放后检查并释放混合器
        if (mMuxer != null) {
            // ⏹️ stop(): 停止混合器，完成MP4文件写入
            // 🔍 为什么调用：混合器需要正确关闭文件头和索引，否则MP4文件损坏无法播放
            // 📍 作用：刷新所有缓冲数据，写入文件尾部的moov原子（包含索引信息）
            // ⏰ 时机：所有编码数据写入完成后调用（本例在finally中无条件调用）
            mMuxer.stop();         // ⏹️ 停止混合器
            // 🗑️ release(): 释放混合器占用的系统资源
            // 🔍 为什么调用：混合器持有文件句柄和内存缓冲区，必须释放
            // 📍 作用：关闭文件、释放所有分配的内存和文件句柄
            // ⏰ 时机：stop()之后调用
            mMuxer.release();      // 🗑️ 释放混合器
            // 🔚 mMuxer = null: 置空引用
            // 🔍 为什么置空：防止重复释放，同时便于GC回收
            mMuxer = null;
        }
    }

    /**
     * Extracts all pending data from the encoder.
     * <p>
     * If endOfStream is not set, this returns when there is no more data to drain.  If it
     * is set, we send EOS to the encoder, and then iterate until we see EOS on the output.
     * Calling this with endOfStream set should be done once, right before stopping the muxer.
     * 📤 提取编码器所有待处理数据。
     *    endOfStream=false时，排空后返回。endOfStream=true时，发送EOS并等待输出端EOS。
     *    应在停止混合器前调用一次endOfStream=true。
     *
     * @param endOfStream true表示这是最后一次调用，需要发送流结束信号
     */
    private void drainEncoder(boolean endOfStream) {
        // ⏱️ TIMEOUT_USEC: 轮询编码器输出的超时时间（10000微秒=10毫秒）
        //    为什么定义10ms：平衡响应速度和CPU占用，太短会频繁轮询，太长会延迟
        //    作用：控制dequeueOutputBuffer的等待时间
        //    使用时机：while循环中每次检查编码器输出时使用
        final int TIMEOUT_USEC = 10000;
        if (VERBOSE) Log.d(TAG, "drainEncoder(" + endOfStream + ")");

        if (endOfStream) {
            // 📤 signalEndOfInputStream(): 向编码器发送流结束信号
            //    为什么调用：告知编码器不再有新输入，尽快输出所有剩余数据
            //    作用：触发编码器进入排空模式，输出所有缓冲的编码数据
            //    使用时机：视频录制结束前调用一次（在generateMovie()末尾）
            if (VERBOSE) Log.d(TAG, "sending EOS to encoder");
            mEncoder.signalEndOfInputStream();
        }

        // 📦 encoderOutputBuffers: 编码器输出缓冲区数组
        //    为什么定义：需要持有编码器输出缓冲区的引用才能读取编码数据
        //    作用：每个缓冲区包含一帧或多帧编码后的视频数据（H.264 NAL单元）
        //    使用时机：dequeueOutputBuffer返回有效索引后，通过此数组获取数据
        //    注意：INFO_OUTPUT_BUFFERS_CHANGED时需要重新获取
        ByteBuffer[] encoderOutputBuffers = mEncoder.getOutputBuffers();
        // 🔄 while(true): 持续轮询编码器输出，直到没有更多数据或遇到EOS
        //    为什么用无限循环：编码器可能有多个缓冲区待处理，需要全部取出
        //    退出条件：INFO_TRY_AGAIN_LATER（非结束模式）或遇到EOS标志
        while (true) {
            // 🔍 encoderStatus: 从编码器取出一个输出缓冲区的状态
            //    为什么定义：需要判断编码器是否有可用的输出数据
            //    作用：返回值可能是：缓冲区索引、INFO_TRY_AGAIN_LATER、INFO_OUTPUT_BUFFERS_CHANGED、INFO_OUTPUT_FORMAT_CHANGED
            //    使用时机：while循环核心判断依据，决定后续处理逻辑
            int encoderStatus = mEncoder.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // no output available yet
                // ⏳ INFO_TRY_AGAIN_LATER: 暂时没有可用的输出数据
                //    为什么发生：编码器还在处理中，或者输入数据不足
                if (!endOfStream) {
                    // 🚪 非结束模式：直接退出循环，不阻塞等待
                    //    为什么退出：generateMovie()中每次drainEncoder(false)后还要生成新帧
                    //    作用：避免长时间阻塞，让出控制权给generateFrame()
                    break;      // out of while
                } else {
                    // 🔄 结束模式：继续轮询等待EOS标志
                    //    为什么继续：必须等到编码器输出端出现BUFFER_FLAG_END_OF_STREAM
                    //    作用：确保所有编码数据都被输出，不丢失任何帧
                    if (VERBOSE) Log.d(TAG, "no output available, spinning to await EOS");
                }
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // not expected for an encoder
                // 🔄 INFO_OUTPUT_BUFFERS_CHANGED: 输出缓冲区数组已变更
                //    为什么发生：编码器内部重新分配了输出缓冲区（编码器通常不会出现）
                //    作用：重新获取编码器的输出缓冲区引用
                //    使用时机：收到此状态后必须重新调用getOutputBuffers()
                encoderOutputBuffers = mEncoder.getOutputBuffers();
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // should happen before receiving buffers, and should only happen once
                // 📋 INFO_OUTPUT_FORMAT_CHANGED: 编码器输出格式已确定
                //    为什么发生：编码器需要输出SPS/PPS等"神奇数据"（CSD-0/CSD-1）
                //    作用：获取编码后的MediaFormat，包含MP4封装所需的元数据
                //    使用时机：仅发生一次，在收到第一帧编码数据之前
                if (mMuxerStarted) {
                    throw new RuntimeException("format changed twice");  // 🚨 不应发生两次
                }
                // 📋 newFormat: 编码器输出的媒体格式（含SPS/PPS等关键数据）
                //    为什么定义：需要将此格式添加到混合器，否则MP4文件无法播放
                //    作用：包含编码器生成的配置信息，是MP4文件头的必要组成部分
                //    使用时机：立即传给mMuxer.addTrack()
                MediaFormat newFormat = mEncoder.getOutputFormat();
                Log.d(TAG, "encoder output format changed: " + newFormat);

                // now that we have the Magic Goodies, start the muxer
                // 🎬 获取到"神奇数据"（SPS/PPS）后添加轨道并启动混合器
                // 🎯 addTrack(): 添加视频轨道到混合器
                //    为什么调用：混合器需要知道轨道格式才能正确封装
                //    作用：返回轨道索引，后续writeSampleData()需要此索引
                mTrackIndex = mMuxer.addTrack(newFormat);  // 🎯 添加视频轨道
                // ▶️ start(): 启动混合器
                //    为什么调用：addTrack()后必须start()才能开始写入数据
                //    作用：初始化混合器内部状态，准备接收编码数据
                mMuxer.start();                             // ▶️ 启动混合器
                // ✅ mMuxerStarted: 标记混合器已启动
                //    为什么设置：防止重复调用start()，且writeSampleData()需要此标志
                //    作用：后续检查此标志确保混合器已就绪
                mMuxerStarted = true;                       // ✅ 标记已启动
            } else if (encoderStatus < 0) {
                // 🤷 未知状态码，记录警告并忽略
                //    为什么忽略：MediaCodec可能返回未文档化的状态码，安全起见忽略
                Log.w(TAG, "unexpected result from encoder.dequeueOutputBuffer: " +
                        encoderStatus);
                // let's ignore it
            } else {
                // ✅ 成功取出一个有效的输出缓冲区（encoderStatus是缓冲区索引）
                // 📦 encodedData: 编码后的视频数据缓冲区
                //    为什么定义：需要从输出缓冲区数组中获取实际的编码数据
                //    作用：包含一帧或多帧编码数据（H.264 NAL单元）
                //    使用时机：position/limit设置后传入writeSampleData()
                ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];
                if (encodedData == null) {
                    throw new RuntimeException("encoderOutputBuffer " + encoderStatus +
                            " was null");  // 🚨 缓冲区为空，不应发生
                }

                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    // The codec config data was pulled out and fed to the muxer when we got
                    // the INFO_OUTPUT_FORMAT_CHANGED status.  Ignore it.
                    // ⚙️ BUFFER_FLAG_CODEC_CONFIG: 编解码器配置数据（SPS/PPS）
                    //    为什么忽略：这些数据已在INFO_OUTPUT_FORMAT_CHANGED时通过getOutputFormat()处理
                    //    作用：标记为无效，避免重复写入混合器
                    if (VERBOSE) Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
                    mBufferInfo.size = 0;
                }

                if (mBufferInfo.size != 0) {
                    // ✅ 有实际编码数据需要写入混合器
                    if (!mMuxerStarted) {
                        throw new RuntimeException("muxer hasn't started");  // 🚨 混合器未启动
                    }

                    // adjust the ByteBuffer values to match BufferInfo
                    // 📐 设置ByteBuffer的position和limit以匹配BufferInfo
                    //    为什么设置：编码数据可能在缓冲区中间，需要精确定位有效数据范围
                    //    作用：确保writeSampleData()只读取有效数据，不读取垃圾数据
                    encodedData.position(mBufferInfo.offset);
                    encodedData.limit(mBufferInfo.offset + mBufferInfo.size);
                    // ⏱️ mFakePts: 伪造的呈现时间戳（微秒）
                    //    为什么伪造：软件输入的Surface无法自动获取时间戳，需要手动设置
                    //    作用：告诉播放器每帧的显示时间，实现正确的播放速度
                    //    使用时机：每帧递增，模拟固定帧率的时间流逝
                    mBufferInfo.presentationTimeUs = mFakePts;
                    // ⏱️ 递增时间戳：1秒/帧率=每帧间隔微秒数
                    //    为什么递增：每帧的时间戳必须严格递增，否则播放器行为异常
                    //    计算：1000000/4=250000微秒（0.25秒/帧）
                    mFakePts += 1000000L / FRAMES_PER_SECOND;

                    // 📝 writeSampleData(): 将编码数据写入混合器（实际写入MP4文件）
                    //    参数1 mTrackIndex：视频轨道索引（addTrack()返回的）
                    //    参数2 encodedData：编码数据缓冲区（已设置position/limit）
                    //    参数3 mBufferInfo：包含时间戳、大小、标志位等元信息
                    mMuxer.writeSampleData(mTrackIndex, encodedData, mBufferInfo);
                    if (VERBOSE) Log.d(TAG, "sent " + mBufferInfo.size + " bytes to muxer");
                }

                // 🗑️ releaseOutputBuffer(): 释放输出缓冲区，归还给编码器循环使用
                //    为什么调用：不释放会导致缓冲区耗尽，编码器阻塞
                //    参数2 false：不渲染到Surface（编码模式不需要渲染）
                mEncoder.releaseOutputBuffer(encoderStatus, false);

                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    // 🏁 检测到流结束标志（BUFFER_FLAG_END_OF_STREAM）
                    //    为什么检查：这是编码器输出的最后一个缓冲区，之后不会再有数据
                    //    作用：退出while循环，结束drainEncoder()方法
                    if (!endOfStream) {
                        Log.w(TAG, "reached end of stream unexpectedly");  // ⚠️ 非预期的流结束
                    } else {
                        if (VERBOSE) Log.d(TAG, "end of stream reached");  // 🏁 正常流结束
                    }
                    break;      // out of while 🚪 退出循环
                }
            }
        }
    }

    /**
     * Generates a frame, writing to the Surface via the "software" API (lock/unlock).
     * <p>
     * There's no way to set the time stamp.
     * 🎨 通过软件API（lock/unlock）向Surface写入生成一帧。
     *    无法设置时间戳（因此使用伪造的递增时间戳）。
     *    画面为8个彩色竖条+一条灰色横条随帧号移动。
     *
     * @param frameNum 当前帧号（0到NUM_FRAMES-1），用于计算横条位置
     */
    private void generateFrame(int frameNum) {
        // 🎨 canvas变量：Surface的画布对象（Canvas类型）
        // 🔍 为什么获取：软件绘制需要先锁定Surface获取画布，绘制完成后解锁提交
        // 📍 作用：通过Canvas API绘制矩形等图形到编码器的输入Surface
        // ⏰ 时机：每生成一帧时锁定，绘制完成后解锁
        // 💡 参数null：表示绘制整个Surface区域（不指定脏区域优化）
        // 💡 注意：lockCanvas返回的画布已绑定Surface，unlock后自动提交给编码器
        Canvas canvas = mInputSurface.lockCanvas(null);  // 🔓 锁定Surface画布
        try {
            // 📐 width变量：画布的像素宽度
            // 🔍 为什么定义：需要知道宽度才能计算每个竖条的宽度（width/8）
            // 📍 作用：用于绘制8等分竖条的左右边界坐标
            // ⏰ 时机：锁定画布后立即获取，在drawRect()中使用
            int width = canvas.getWidth();
            // 📐 height变量：画布的像素高度
            // 🔍 为什么定义：需要知道高度才能绘制填满整个高度的竖条
            // 📍 作用：用于绘制竖条的上下边界坐标，以及横条的上下边界
            // ⏰ 时机：锁定画布后立即获取，在drawRect()中使用
            int height = canvas.getHeight();

            // 🎨 paint变量：Android绘制工具（Paint对象）
            // 🔍 为什么定义：Canvas绘制矩形需要Paint对象来指定颜色
            // 📍 作用：设置绘制颜色，用于drawRect()绘制彩色矩形
            // ⏰ 时机：创建后在每个竖条和横条绘制前设置颜色
            Paint paint = new Paint();
            // 🔄 for循环：绘制8个彩色竖条
            // 📌 i变量：当前竖条索引（0到7）
            // 🔍 为什么需要8个：测试不同颜色的编码效果，同时验证编码器正确性
            // 📍 作用：控制循环次数和竖条颜色计算
            // ⏰ 时机：每次迭代绘制一个竖条
            for (int i = 0; i < 8; i++) {
                // 🎨 color变量：根据i的二进制位生成8种标准颜色
                // 🔍 为什么这样设计：i的3个二进制位分别控制R/G/B分量，生成8色
                // 📍 作用：0=黑, 1=红, 2=绿, 3=黄, 4=蓝, 5=紫, 6=青, 7=白
                // ⏰ 时机：设置给paint.color()，然后绘制第i个竖条
                int color = 0xff000000;  // 🔲 黑色基础（alpha=0xff不透明）
                if ((i & 0x01) != 0) {
                    color |= 0x00ff0000;  // 🔴 红色分量（第0位为1时开启）
                }
                if ((i & 0x02) != 0) {
                    color |= 0x0000ff00;  // 🟢 绿色分量（第1位为1时开启）
                }
                if ((i & 0x04) != 0) {
                    color |= 0x000000ff;  // 🔵 蓝色分量（第2位为1时开启）
                }
                paint.setColor(color);

                // 📐 sliceWidth变量：每个竖条的像素宽度（总宽度/8）
                // 🔍 为什么定义：需要将屏幕8等分，每个竖条占1/8宽度
                // 📍 作用：计算第i个竖条的左右边界坐标
                // ⏰ 时机：在drawRect()中计算left和right参数时使用
                float sliceWidth = width / 8;
                // 🎨 drawRect(): 绘制第i个竖条
                // 📌 参数1 left：竖条左边界（sliceWidth*i）
                // 📌 参数2 top：竖条上边界（0，从顶部开始）
                // 📌 参数3 right：竖条右边界（sliceWidth*(i+1)）
                // 📌 参数4 bottom：竖条下边界（height，到底部结束）
                // 📌 参数5 paint：绘制颜色（上面设置的color）
                canvas.drawRect(sliceWidth * i, 0, sliceWidth * (i+1), height, paint);
            }

            // 📊 绘制灰色横条，位置随帧号上下移动
            // 🔍 为什么需要：让画面有动态效果，更容易观察编码是否正确
            // 📍 作用：横条在8个位置间循环移动，验证每帧都被正确编码
            paint.setColor(0x80808080);  // ⬜ 半透明灰色（ARGB: 0x80808080）
            // 📐 sliceHeight变量：横条的像素高度（总高度/8）
            // 🔍 为什么定义：将屏幕高度8等分，横条占1/8高度
            // 📍 作用：计算横条的上下边界坐标
            // ⏰ 时机：在drawRect()中计算top和bottom参数时使用
            float sliceHeight = height / 8;
            // 🔄 frameMod变量：帧号对8取模，决定横条在哪个位置（0~7）
            // 🔍 为什么取模：让横条在8个位置间循环移动，形成动画效果
            // 📍 作用：frameNum=0时横条在顶部，frameNum=7时在底部
            // ⏰ 时机：计算横条的top坐标时使用
            int frameMod = frameNum % 8;
            // 🎨 drawRect(): 绘制灰色横条
            // 📌 参数1 left：0（从左边开始）
            // 📌 参数2 top：sliceHeight*frameMod（根据帧号决定位置）
            // 📌 参数3 right：width（到右边结束）
            // 📌 参数4 bottom：sliceHeight*(frameMod+1)（横条下边界）
            // 📌 参数5 paint：半透明灰色
            canvas.drawRect(0, sliceHeight * frameMod, width, sliceHeight * (frameMod+1), paint);
        } finally {
            // 🔒 unlockCanvasAndPost(): 解锁画布并提交到Surface
            // 🔍 为什么在finally中：无论绘制成功或失败都必须解锁，否则Surface状态异常
            // 📍 作用：将绘制的内容提交给编码器，编码器即可处理此帧
            // ⏰ 时机：绘制完成后必须调用，且只能调用一次
            mInputSurface.unlockCanvasAndPost(canvas);
        }
    }
}
