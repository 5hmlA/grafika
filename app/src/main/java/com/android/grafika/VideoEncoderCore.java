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

// 📦 包声明
package com.android.grafika;

// 📚 导入需要的类库
import android.media.MediaCodec;               // 🎬 MediaCodec：媒体编解码器，用于视频编码
import android.media.MediaCodecInfo;            // 📋 MediaCodecInfo：编解码器信息，包含颜色格式等常量
import android.media.MediaFormat;               // 📊 MediaFormat：媒体格式配置类
import android.media.MediaMuxer;                // 📦 MediaMuxer：媒体复用器，将编码数据写入MP4文件
import android.util.Log;                        // 📝 Log：Android日志工具
import android.view.Surface;                    // 🖼️ Surface：显示表面，编码器的输入源
import java.io.File;                            // 📁 File：文件类，表示输出文件路径
import java.io.IOException;                     // ⚠️ IOException：IO异常类
import java.nio.ByteBuffer;                     // 📦 ByteBuffer：字节缓冲区，用于存储编码数据

/**
 * This class wraps up the core components used for surface-input video encoding.
 * <p>
 * Once created, frames are fed to the input surface.  Remember to provide the presentation
 * time stamp, and always call drainEncoder() before swapBuffers() to ensure that the
 * producer side doesn't get backed up.
 * <p>
 * This class is not thread-safe, with one exception: it is valid to use the input surface
 * on one thread, and drain the output on a different thread.
 *
 * 🎬 视频编码器核心类：封装Surface输入视频编码的核心组件
 * 💡 创建后，帧被送入输入Surface
 * 💡 记得提供时间戳，且总是在swapBuffers()之前调用drainEncoder()
 * ⚠️ 非线程安全，但可以在不同线程使用输入Surface和排空输出
 */
// 🏗️ VideoEncoderCore类：视频编码的核心封装
public class VideoEncoderCore {
    // 🏷️ TAG：日志标签，用于Logcat中过滤本类日志
    private static final String TAG = MainActivity.TAG;
    // 🔇 VERBOSE：是否输出详细日志，false表示关闭详细日志以提升性能
    private static final boolean VERBOSE = false;

    // 🎬 MIME_TYPE：视频编码MIME类型，H.264 (AVC)是Android广泛支持的编码格式
    private static final String MIME_TYPE = "video/avc";    // H.264 Advanced Video Coding
    // 🎞️ FRAME_RATE：视频帧率，30fps是标准视频帧率
    private static final int FRAME_RATE = 30;               // 30帧/秒
    // ⏱️ IFRAME_INTERVAL：I帧（关键帧）间隔秒数，每隔5秒生成一个关键帧
    // 💡 关键帧间隔越小，随机访问越快，但文件越大
    private static final int IFRAME_INTERVAL = 5;           // 每5秒一个I帧

    // 🖼️ mInputSurface：编码器的输入Surface
    // 💡 为什么定义：OpenGL ES渲染的内容直接写入此Surface，编码器从这里读取帧数据
    // 💡 作用：作为编码器和渲染管线之间的桥梁
    // 💡 使用时机：在构造函数中创建，通过getInputSurface()返回给调用者
    private Surface mInputSurface;
    // 📦 mMuxer：媒体复用器
    // 💡 为什么定义：将编码后的视频数据封装成MP4文件
    // 💡 作用：接收编码器输出的H.264数据，写入.mp4容器格式
    // 💡 使用时机：在构造函数中创建，drainEncoder()中写入数据，release()中释放
    private MediaMuxer mMuxer;
    // 🎬 mEncoder：MediaCodec编码器
    // 💡 为什么定义：执行视频编码的核心组件
    // 💡 作用：将Surface中的原始帧编码为H.264格式
    // 💡 使用时机：在构造函数中创建和启动，drainEncoder()中排空输出，release()中释放
    private MediaCodec mEncoder;
    // 📊 mBufferInfo：编码输出缓冲区的元数据
    // 💡 为什么定义：存储每一帧编码数据的偏移量、大小、时间戳和标志位
    // 💡 作用：描述dequeueOutputBuffer()返回的缓冲区内容
    // 💡 使用时机：每次drainEncoder()调用时传入dequeueOutputBuffer()
    private MediaCodec.BufferInfo mBufferInfo;
    // 🎯 mTrackIndex：复用器中的视频轨道索引
    // 💡 为什么定义：标识复用器中的视频轨道，写入数据时需要指定
    // 💡 作用：调用mMuxer.writeSampleData()时指定写入哪个轨道
    // 💡 使用时机：初始化为-1，在输出格式变化时由mMuxer.addTrack()赋值
    private int mTrackIndex;
    // ✅ mMuxerStarted：复用器是否已启动
    // 💡 为什么定义：编码器输出格式变化只发生一次，需要确保复用器只启动一次
    // 💡 作用：防止重复启动复用器，也用于检查是否可以开始写入数据
    // 💡 使用时机：初始化为false，在收到INFO_OUTPUT_FORMAT_CHANGED后设为true
    private boolean mMuxerStarted;

    /**
     * Configures encoder and muxer state, and prepares the input Surface.
     *
     * 🔧 配置编码器和复用器，准备输入Surface
     *
     * @param width 编码视频宽度（像素）
     * @param height 编码视频高度（像素）
     * @param bitRate 目标比特率（比特/秒）
     * @param outputFile 输出MP4文件
     * @throws IOException 文件创建异常
     */
    // 🔧 构造函数：初始化编码器和复用器
    // 💡 为什么定义：VideoEncoderCore的核心初始化方法，配置整个编码管线
    // 💡 作用：创建编码器、复用器、输入Surface，配置编码参数
    // 💡 使用时机：外部调用者创建VideoEncoderCore实例时自动调用
    public VideoEncoderCore(int width, int height, int bitRate, File outputFile)
            throws IOException {
        // 📊 mBufferInfo：创建缓冲区信息对象
        // 💡 为什么定义：需要在每次drainEncoder()调用中存储输出缓冲区的元数据
        // 💡 作用：保存偏移量、大小、时间戳、标志位
        // 💡 使用时机：传入dequeueOutputBuffer()接收编码输出数据的描述信息
        mBufferInfo = new MediaCodec.BufferInfo();

        // 📊 format：视频格式配置对象（局部变量）
        // 💡 为什么定义：MediaCodec需要通过MediaFormat了解编码参数
        // 💡 作用：指定MIME类型、宽高、颜色格式、比特率、帧率等
        // 💡 使用时机：传入mEncoder.configure()配置编码器
        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);

        // 🎨 KEY_COLOR_FORMAT：设置颜色格式为Surface输入
        // 💡 为什么设置：使用Surface输入模式，编码器直接从Surface读取纹理数据
        // 💡 作用：告诉编码器输入来自Surface而非ByteBuffer数组，实现零拷贝编码
        // 💡 使用时机：configure前设置，决定编码器的输入数据来源方式
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        // 📊 KEY_BIT_RATE：设置目标比特率（bitRate参数传入，如2000000=2Mbps）
        // 💡 为什么设置：控制视频质量和文件大小的平衡
        // 💡 作用：编码器尽量接近此比特率输出，值越大质量越高文件越大
        // 💡 使用时机：configure前设置，影响编码器的量化参数选择
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        // 🎞️ KEY_FRAME_RATE：设置帧率（常量FRAME_RATE=30fps）
        // 💡 为什么设置：告诉编码器预期的帧率，用于时间计算和码率控制
        // 💡 作用：编码器用于帧间隔计算和自适应码率控制
        // 💡 使用时机：configure前设置，影响编码器的时间管理
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        // ⏱️ KEY_I_FRAME_INTERVAL：设置关键帧（I帧）间隔秒数
        // 💡 为什么设置：关键帧可用于随机访问（seek），间隔影响seek性能和文件大小
        // 💡 作用：每隔IFRAME_INTERVAL=5秒生成一个I帧，平衡seek速度和压缩率
        // 💡 使用时机：configure前设置，影响视频的随机访问能力
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
        // 📝 VERBOSE：如果启用详细日志，输出格式信息用于调试
        // 💡 为什么调用：开发阶段确认编码参数是否正确设置
        // 💡 作用：在Logcat中打印MediaFormat的完整内容
        // 💡 使用时机：仅VERBOSE=true时输出，生产环境关闭以提升性能
        if (VERBOSE) Log.d(TAG, "format: " + format);

        // 🎬 mEncoder：根据MIME类型创建H.264编码器实例
        // 💡 为什么创建：需要硬件编码器来高效编码视频数据
        // 💡 作用：创建MediaCodec编码器，后续configure/start使用
        // 💡 使用时机：format配置完成后创建，优先选择硬件编码器
        mEncoder = MediaCodec.createEncoderByType(MIME_TYPE);
        // ⚙️ configure：配置编码器为编码模式，输出到muxer而非Surface
        // 💡 为什么调用：第二个参数null表示不直接输出到Surface，而是通过buffer输出
        // 💡 作用：将format中的编码参数应用到编码器，准备编码工作
        // 💡 使用时机：创建编码器后、启动前调用，CONFIGURE_FLAG_ENCODE表示编码模式
        mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        // 🖼️ mInputSurface：创建编码器的输入Surface
        // 💡 为什么创建：调用者通过此Surface提交待编码的帧（OpenGL渲染目标）
        // 💡 作用：作为编码器和渲染管线之间的桥梁，编码器从此Surface读取帧
        // 💡 使用时机：configure后、start前创建，返回给调用者用于渲染
        mInputSurface = mEncoder.createInputSurface();
        // ▶️ start：启动编码器，进入编码就绪状态
        // 💡 为什么调用：必须启动后才能开始接收输入并产生编码输出
        // 💡 作用：分配编码所需的内部缓冲区和硬件资源
        // 💡 使用时机：configure和createInputSurface之后调用，启动编码管线
        mEncoder.start();

        // 📦 mMuxer：创建MP4复用器，将编码数据封装为MP4文件
        // 💡 为什么创建：编码器输出裸H.264流，需要复用器封装成标准MP4容器
        // 💡 作用：将编码数据写入outputFile指定的MP4文件
        // 💡 使用时机：编码器启动后创建，在drainEncoder()中写入编码数据
        mMuxer = new MediaMuxer(outputFile.toString(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        // 🎯 mTrackIndex：初始化轨道索引为-1
        // 💡 为什么初始化为-1：在编码器输出格式确定前不知道轨道索引
        // 💡 作用：-1表示尚未获取轨道索引，防止在格式确定前写入数据
        // 💡 使用时机：在INFO_OUTPUT_FORMAT_CHANGED时由mMuxer.addTrack()赋实际值
        mTrackIndex = -1;
        // ❌ mMuxerStarted：初始化复用器为未启动状态
        // 💡 为什么初始化为false：复用器必须在获取轨道格式后才能启动
        // 💡 作用：防止在获取格式前写入数据，确保编码流程正确
        // 💡 使用时机：在INFO_OUTPUT_FORMAT_CHANGED中addTrack/start后设为true
        mMuxerStarted = false;
    }

    /**
     * 📤 获取编码器的输入Surface
     * 💡 调用者通过此Surface渲染帧，编码器自动读取编码
     *
     * @return 输入Surface对象
     */
    public Surface getInputSurface() {
        // 📤 返回输入Surface给调用者
        return mInputSurface;
    }

    /**
     * Releases encoder resources.
     *
     * 🗑️ 释放编码器资源
     * 💡 必须在不再使用编码器时调用，防止内存泄漏
     */
    public void release() {
        // 📝 VERBOSE：如果启用详细日志，记录释放操作
        // 💡 为什么记录：方便调试资源释放时机和顺序
        // 💡 作用：在Logcat中追踪资源释放过程
        // 💡 使用时机：仅VERBOSE=true时输出
        if (VERBOSE) Log.d(TAG, "releasing encoder objects");
        // 🔍 mEncoder != null：检查编码器是否已创建
        // 💡 为什么检查：编码器可能在构造函数中创建失败，需防止空指针异常
        // 💡 作用：空指针安全检查，确保后续stop/release操作安全
        // 💡 使用时机：调用mEncoder.stop()和release()之前必须检查
        if (mEncoder != null) {
            // ⏹️ mEncoder.stop()：停止编码器，结束所有编码工作
            // 💡 为什么必须先停止：编码器可能正在处理数据，直接release会崩溃
            // 💡 作用：通知编码器结束所有编码工作，刷新内部缓冲区输出剩余数据
            // 💡 使用时机：在release()中释放编码器资源之前调用
            mEncoder.stop();
            // 🗑️ mEncoder.release()：释放编码器占用的系统资源
            // 💡 为什么调用：编码器持有硬件编解码器资源和Native层内存缓冲区
            // 💡 作用：释放Native层资源，解除硬件编解码器的占用
            // 💡 使用时机：stop()之后立即调用，释放所有编码器资源
            mEncoder.release();
            // 🔄 mEncoder = null：置空引用，便于GC回收
            // 💡 为什么置空：帮助GC（垃圾回收器）识别可回收对象
            // 💡 作用：加速内存回收，避免悬挂引用导致意外使用已释放的编码器
            // 💡 使用时机：release()之后立即置空，防止后续代码误用
            mEncoder = null;
        }
        // 🔍 mMuxer != null：检查复用器是否已创建
        // 💡 为什么检查：复用器可能在构造函数中创建失败，需防止空指针异常
        // 💡 作用：空指针安全检查，确保后续stop/release操作安全
        // 💡 使用时机：调用mMuxer.stop()和release()之前必须检查
        if (mMuxer != null) {
            // ⏹️ mMuxer.stop()：停止复用器，完成MP4文件的最终写入
            // 💡 为什么必须先停止：复用器正在写入MP4文件，必须停止才能安全关闭
            // 💡 作用：完成MP4文件的最终写入（如moov box），使生成的文件可播放
            // 💡 使用时机：在release()中释放复用器资源之前调用
            mMuxer.stop();
            // 🗑️ mMuxer.release()：释放复用器占用的系统资源
            // 💡 为什么调用：复用器持有文件句柄和Native层内存缓冲区
            // 💡 作用：关闭文件句柄，释放Native层资源，确保数据刷入磁盘
            // 💡 使用时机：stop()之后立即调用，释放所有复用器资源
            mMuxer.release();
            // 🔄 mMuxer = null：置空引用，便于GC回收
            // 💡 为什么置空：帮助GC（垃圾回收器）识别可回收对象
            // 💡 作用：加速内存回收，避免悬挂引用导致意外使用已释放的复用器
            // 💡 使用时机：release()之后立即置空，防止后续代码误用
            mMuxer = null;
        }
    }

    /**
     * Extracts all pending data from the encoder and forwards it to the muxer.
     *
     * 📤 从编码器提取所有待处理的编码数据，并转发到复用器
     * 💡 这是编码流程的核心方法，需要在每帧渲染后调用
     *
     * @param endOfStream 是否为流结束信号
     *         true表示发送EOS信号，编码器将flush剩余帧
     *         false表示正常提取待处理的编码数据
     */
    // 📤 drainEncoder：排空编码器输出缓冲区，提取编码数据并写入复用器
    // 💡 为什么定义：编码器产生编码数据后需要主动取出，否则缓冲区满会导致阻塞
    // 💡 作用：从编码器dequeue输出缓冲区，将编码数据写入MP4复用器
    // 💡 使用时机：每帧submitFrame()后调用，视频结束时调用drainEncoder(true)
    public void drainEncoder(boolean endOfStream) {
        // ⏱️ TIMEOUT_USEC：超时时间常量（微秒）= 10毫秒
        // 💡 为什么定义为10000：控制dequeueOutputBuffer()的等待时间
        // 💡 作用：平衡响应速度和CPU占用，10ms是合理的折中值
        // 💡 使用时机：传入dequeueOutputBuffer()作为超时参数
        final int TIMEOUT_USEC = 10000;
        // 📝 VERBOSE：如果启用详细日志，记录drainEncoder调用参数
        // 💡 为什么记录：追踪每次调用是正常模式还是EOS模式
        // 💡 作用：便于在Logcat中追踪编码器排空过程
        // 💡 使用时机：仅VERBOSE=true时输出
        if (VERBOSE) Log.d(TAG, "drainEncoder(" + endOfStream + ")");

        // 🔍 endOfStream：如果请求结束流，发送EOS信号给编码器
        // 💡 为什么检查：Surface输入模式下需要显式通知编码器输入结束
        // 💡 作用：区分正常排空和流结束两种模式
        // 💡 使用时机：视频录制结束或需要终止编码时endOfStream=true
        if (endOfStream) {
            // 📝 VERBOSE：记录发送EOS信号
            // 💡 为什么记录：追踪EOS信号发送时机，便于调试
            if (VERBOSE) Log.d(TAG, "sending EOS to encoder");
            // 🏁 signalEndOfInputStream()：通知编码器输入流已结束
            // 💡 为什么调用：Surface输入模式没有显式的输入结束标记，需主动通知
            // 💡 作用：编码器收到此信号后会flush剩余帧并输出EOS标志
            // 💡 使用时机：视频录制结束或需要终止编码时调用一次
            mEncoder.signalEndOfInputStream();
        }

        // 📦 encoderOutputBuffers：编码器输出缓冲区数组（局部变量）
        // 💡 为什么定义：持有编码器所有输出缓冲区的引用
        // 💡 作用：通过索引访问输出缓冲区中的编码数据
        // 💡 使用时机：每次dequeue成功后通过索引获取ByteBuffer读取编码数据
        ByteBuffer[] encoderOutputBuffers = mEncoder.getOutputBuffers();
        // 🔄 while (true)：无限循环处理所有可用的输出缓冲区
        // 💡 为什么循环：编码器可能有多个缓冲区排队等待处理
        // 💡 作用：持续消费编码器输出，直到没有更多数据或遇到EOS标志
        // 💡 退出条件：INFO_TRY_AGAIN_LATER（非EOS模式）或收到BUFFER_FLAG_END_OF_STREAM
        while (true) {
            // 🔍 encoderStatus：从编码器获取输出缓冲区的状态码
            // 💡 为什么定义：标识dequeue操作的结果类型，决定后续处理逻辑
            // 💡 作用：区分"需要重试"、"格式变化"、"缓冲区变更"、"成功获取"等情况
            // 💡 使用时机：通过if-else判断执行不同的处理逻辑分支
            int encoderStatus = mEncoder.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);
            // 🔍 INFO_TRY_AGAIN_LATER：暂时没有可用的输出缓冲区
            // 💡 为什么判断：编码器可能还在处理中，暂时没有编码数据可用
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // 🔍 !endOfStream：如果不是结束流模式，立即退出循环
                // 💡 为什么退出：非EOS模式下没有输出就返回，避免阻塞调用者
                // 💡 作用：让调用者可以继续提交新帧或做其他工作
                if (!endOfStream) {
                    break;  // 🚪 退出循环，没有更多输出数据
                } else {
                    // ⏳ endOfStream=true：EOS模式下继续等待，直到编码器flush完毕
                    // 💡 为什么等待：EOS时必须等待所有帧编码完成并输出
                    // 💡 作用：确保所有编码数据都被取出后再退出
                    if (VERBOSE) Log.d(TAG, "no output available, spinning to await EOS");
                }
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // 🔄 INFO_OUTPUT_BUFFERS_CHANGED：输出缓冲区数组已更换（API < 21）
                // 💡 为什么处理：编码器可能重新分配输出缓冲区，旧引用失效
                // 💡 作用：更新本地缓冲区引用数组，避免访问已释放的缓冲区
                // 💡 使用时机：API < 21时需要处理此情况，新版API忽略即可
                encoderOutputBuffers = mEncoder.getOutputBuffers();
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // ⚠️ mMuxerStarted：检查复用器是否已启动（格式变化应该只发生一次）
                // 💡 为什么检查：编码器输出格式只应变化一次，重复变化是编码器bug
                // 💡 作用：防御性检查，防止编码器异常行为
                if (mMuxerStarted) {
                    throw new RuntimeException("format changed twice");
                }
                // 📊 newFormat：获取编码器输出格式（包含CSD-0/CSD-1等编解码器特定数据）
                // 💡 为什么获取：格式中包含SPS/PPS等编解码器配置数据
                // 💡 作用：MediaMuxer需要此格式信息来正确封装MP4文件
                // 💡 使用时机：仅在INFO_OUTPUT_FORMAT_CHANGED时获取一次
                MediaFormat newFormat = mEncoder.getOutputFormat();
                // 📝 Log.d：记录格式变化，便于调试编码参数
                // 💡 为什么记录：确认编码器输出格式是否符合预期
                Log.d(TAG, "encoder output format changed: " + newFormat);
                // 🎯 addTrack()：将视频轨道添加到复用器，获取轨道索引
                // 💡 为什么调用：写入数据前必须先添加轨道
                // 💡 作用：返回的轨道索引mTrackIndex在writeSampleData()中使用
                // 💡 使用时机：获取输出格式后立即添加轨道
                mTrackIndex = mMuxer.addTrack(newFormat);
                // ▶️ start()：启动复用器，准备接收编码数据
                // 💡 为什么调用：必须添加轨道后才能启动复用器
                // 💡 作用：准备好接收writeSampleData()写入的编码数据
                // 💡 使用时机：addTrack()之后立即启动
                mMuxer.start();
                // ✅ mMuxerStarted = true：标记复用器已启动
                // 💡 为什么设置：后续writeSampleData()调用前需要检查此标志
                // 💡 作用：防止在复用器未启动时写入数据导致异常
                mMuxerStarted = true;
            } else if (encoderStatus < 0) {
                // ❌ 未知的负值返回值，记录警告但不处理
                // 💡 为什么记录：未知状态码可能是编码器异常，需开发者关注
                // 💡 作用：提醒开发者检查编码器行为
                Log.w(TAG, "unexpected result from encoder.dequeueOutputBuffer: " + encoderStatus);
            } else {
                // ✅ 成功获取输出缓冲区（encoderStatus >= 0是有效的缓冲区索引）
                // 📦 encodedData：指向编码器输出缓冲区中的编码数据
                // 💡 为什么定义：持有编码后的H.264数据引用，需要写入复用器
                // 💡 作用：通过position/limit设置后传入writeSampleData()
                // 💡 使用时机：调整position/limit后传入mMuxer.writeSampleData()
                ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];
                // 🔍 encodedData == null：安全检查，缓冲区不应为null
                // 💡 为什么检查：编码器内部错误可能导致缓冲区为null
                // 💡 作用：防御性编程，提前发现问题
                if (encodedData == null) {
                    throw new RuntimeException("encoderOutputBuffer " + encoderStatus + " was null");
                }

                // 🔍 BUFFER_FLAG_CODEC_CONFIG：检查是否为编解码器配置数据（SPS/PPS）
                // 💡 为什么检查：配置数据不是视频帧，不应写入复用器的数据轨道
                // 💡 作用：过滤掉配置数据，只写入实际的视频帧数据
                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    // 📝 VERBOSE：忽略配置数据（格式变化时已通过getOutputFormat获取）
                    // 💡 为什么忽略：SPS/PPS等配置信息已包含在MediaFormat中
                    if (VERBOSE) Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
                    // 🔄 mBufferInfo.size = 0：将size设为0，跳过后续写入操作
                    // 💡 为什么设为0：writeSampleData在size==0时不会写入数据
                    // 💡 作用：跳过此缓冲区的数据写入
                    mBufferInfo.size = 0;
                }

                // 🔍 mBufferInfo.size != 0：检查是否有实际编码数据需要写入
                // 💡 为什么检查：size==0表示无数据（如配置数据已被跳过）
                if (mBufferInfo.size != 0) {
                    // ⚠️ mMuxerStarted：检查复用器是否已启动（未启动则无法写入）
                    // 💡 为什么检查：writeSampleData必须在start()之后调用
                    // 💡 作用：确保编码流程正确，防止数据丢失
                    if (!mMuxerStarted) {
                        throw new RuntimeException("muxer hasn't started");
                    }
                    // 📐 encodedData.position(mBufferInfo.offset)：设置读取起始位置
                    // 💡 为什么设置：BufferInfo中的offset指示有效数据的起始位置
                    // 💡 作用：writeSampleData需要position正确指向数据起始
                    encodedData.position(mBufferInfo.offset);
                    // 📐 encodedData.limit(offset + size)：设置读取结束位置
                    // 💡 为什么设置：limit标识数据的边界，防止读取垃圾数据
                    // 💡 作用：确保只读取[offset, offset+size)范围内的有效数据
                    encodedData.limit(mBufferInfo.offset + mBufferInfo.size);
                    // 📝 writeSampleData()：将编码数据写入复用器（核心写入操作）
                    // 💡 为什么调用：这是将编码帧写入MP4文件的核心操作
                    // 💡 作用：将H.264 NAL单元写入MP4容器的指定轨道
                    // 💡 参数：mTrackIndex=轨道索引, encodedData=编码数据, mBufferInfo=时间戳等元信息
                    mMuxer.writeSampleData(mTrackIndex, encodedData, mBufferInfo);
                    // 📝 VERBOSE：如果启用详细日志，记录写入的数据量和时间戳
                    // 💡 为什么记录：追踪编码数据写入过程，便于调试和性能分析
                    if (VERBOSE) {
                        Log.d(TAG, "sent " + mBufferInfo.size + " bytes to muxer, ts=" +
                                mBufferInfo.presentationTimeUs);
                    }
                }

                // 🗑️ releaseOutputBuffer()：释放输出缓冲区，归还给编码器循环使用
                // 💡 为什么调用：必须释放，否则编码器无可用缓冲区会导致阻塞
                // 💡 作用：将缓冲区归还编码器，第二个参数false表示不需要渲染到Surface
                // 💡 使用时机：数据处理完毕（写入复用器或跳过）后立即释放
                mEncoder.releaseOutputBuffer(encoderStatus, false);

                // 🔍 BUFFER_FLAG_END_OF_STREAM：检查编码器输出是否包含EOS标志
                // 💡 为什么检查：EOS标志表示所有编码数据已输出完毕
                // 💡 作用：检测编码结束，退出循环
                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    // 🔍 !endOfStream：如果不是期望的结束，记录警告
                    // 💡 为什么警告：非预期的流结束可能是编码器异常
                    if (!endOfStream) {
                        Log.w(TAG, "reached end of stream unexpectedly");
                    } else {
                        // 📝 VERBOSE：正常到达流末尾，记录日志确认
                        if (VERBOSE) Log.d(TAG, "end of stream reached");
                    }
                    // 🚪 break：收到EOS标志，退出循环
                    // 💡 为什么退出：所有编码数据已处理完毕，无需继续
                    break;
                }
            }
        }
    }
}
