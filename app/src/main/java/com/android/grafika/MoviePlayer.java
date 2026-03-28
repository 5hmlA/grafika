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

// 📚 导入需要的类库
import android.media.MediaCodec;               // 🎬 MediaCodec：媒体编解码器
import android.media.MediaExtractor;           // 📦 MediaExtractor：媒体提取器，用于从文件中提取媒体数据
import android.media.MediaFormat;              // 📊 MediaFormat：媒体格式信息
import android.os.Handler;                     // 🔧 Handler：消息处理器
import android.os.Message;                     // 📨 Message：消息对象
import android.util.Log;                       // 📝 Log：日志工具
import android.view.Surface;                   // 🖼️ Surface：显示表面

import java.io.File;                           // 📁 File：文件类
import java.io.FileNotFoundException;          // ⚠️ FileNotFoundException：文件未找到异常
import java.io.IOException;                    // ⚠️ IOException：IO异常
import java.nio.ByteBuffer;                    // 📦 ByteBuffer：字节缓冲区


/**
 * Plays the video track from a movie file to a Surface.
 * <p>
 * TODO: needs more advanced shuttle controls (pause/resume, skip)
 * 
 * 🎬 将视频文件的视频轨道播放到Surface
 * 💡 这是视频播放的核心类，负责解码和渲染
 * 📝 待办：需要更高级的播放控制（暂停/恢复、跳过）
 */
public class MoviePlayer {
    // 🏷️ TAG：日志标签
    private static final String TAG = MainActivity.TAG;
    // 🔇 VERBOSE：是否输出详细日志
    private static final boolean VERBOSE = false;

    // 📊 mBufferInfo：缓冲区信息对象
    // 💡 声明在这里以减少内存分配
    // Declare this here to reduce allocations.
    private MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();

    // ⏹️ mIsStopRequested：是否请求停止
    // 💡 可能被不同线程设置/读取，使用volatile保证可见性
    // May be set/read by different threads.
    private volatile boolean mIsStopRequested;

    // 📁 mSourceFile：源视频文件
    private File mSourceFile;
    // 🖼️ mOutputSurface：输出Surface
    private Surface mOutputSurface;
    // 🎯 mFrameCallback：帧回调接口
    FrameCallback mFrameCallback;
    // 🔄 mLoop：是否循环播放
    private boolean mLoop;
    // 📐 mVideoWidth：视频宽度
    private int mVideoWidth;
    // 📐 mVideoHeight：视频高度
    private int mVideoHeight;


    /**
     * Interface to be implemented by class that manages playback UI.
     * <p>
     * Callback methods will be invoked on the UI thread.
     * 
     * 🎯 播放反馈接口，由管理播放UI的类实现
     * 💡 回调方法将在UI线程上调用
     */
    public interface PlayerFeedback {
        /**
         * 播放停止时调用
         */
        void playbackStopped();
    }


    /**
     * Callback invoked when rendering video frames.  The MoviePlayer client must
     * provide one of these.
     * 
     * 🎯 渲染视频帧时调用的回调接口
     * 💡 MoviePlayer客户端必须提供一个实现
     */
    public interface FrameCallback {
        /**
         * Called immediately before the frame is rendered.
         * @param presentationTimeUsec The desired presentation time, in microseconds.
         * 
         * 🎯 在帧渲染之前立即调用
         * @param presentationTimeUsec 期望的呈现时间（微秒）
         */
        void preRender(long presentationTimeUsec);

        /**
         * Called immediately after the frame render call returns.  The frame may not have
         * actually been rendered yet.
         * TODO: is this actually useful?
         * 
         * 🎯 在帧渲染调用返回后立即调用
         * 💡 帧可能还没有实际渲染完成
         * 📝 待办：这个真的有用吗？
         */
        void postRender();

        /**
         * Called after the last frame of a looped movie has been rendered.  This allows the
         * callback to adjust its expectations of the next presentation time stamp.
         * 
         * 🎯 在循环播放的视频最后一帧渲染后调用
         * 💡 这允许回调调整对下一个呈现时间戳的期望
         */
        void loopReset();
    }


    /**
     * Constructs a MoviePlayer.
     *
     * @param sourceFile The video file to open.
     * @param outputSurface The Surface where frames will be sent.
     * @param frameCallback Callback object, used to pace output.
     * @throws IOException
     *
     * 🔧 构造函数：创建MoviePlayer实例
     *
     * @param sourceFile 要打开的视频文件
     * @param outputSurface 帧将被发送到的Surface
     * @param frameCallback 回调对象，用于控制输出节奏
     * @throws IOException 文件读取异常
     */
    public MoviePlayer(File sourceFile, Surface outputSurface, FrameCallback frameCallback)
            throws IOException {
        // 📁 mSourceFile：保存源文件引用
        // 💡 为什么定义：后续play()方法需要读取此文件进行解码
        // 💡 作用：持有视频文件的路径引用
        // 💡 使用时机：在play()方法中用于MediaExtractor.setDataSource()
        mSourceFile = sourceFile;
        // 🖼️ mOutputSurface：保存输出Surface引用
        // 💡 为什么定义：解码器需要将解码后的帧渲染到这个Surface上
        // 💡 作用：持有显示表面的引用，用于视频帧渲染
        // 💡 使用时机：在MediaCodec.configure()时作为输出目标传入
        mOutputSurface = outputSurface;
        // 🎯 mFrameCallback：保存帧回调引用
        // 💡 为什么定义：外部需要控制帧的渲染节奏（如同步音频）
        // 💡 作用：持有帧回调接口，在渲染前后通知调用方
        // 💡 使用时机：在doExtract()中，每帧渲染前后调用preRender/postRender
        mFrameCallback = frameCallback;

        // 📦 打开文件并提取视频特征
        // Pop the file open and pull out the video characteristics.

        /**
         * TODO: consider leaving the extractor open.  Should be able to just seek back to
         *       the start after each iteration of play.  Need to rearrange the API a bit --
         *       currently play() is taking an all-in-one open+work+release approach.
         *
         * 📝 待办：考虑保持提取器打开状态。应该可以在每次播放迭代后直接seek回开始。
         *    需要稍微重新安排API——目前play()采用的是一体化的打开+工作+释放方式。
         */

        // 📦 extractor：临时MediaExtractor实例，用于读取视频元数据
        // 💡 为什么定义：需要从视频文件中提取轨道信息和视频尺寸
        // 💡 作用：解析视频文件，获取视频轨道的格式信息（宽/高）
        // 💡 使用时机：仅在构造函数中使用，获取完元数据后立即释放
        MediaExtractor extractor = null;
        try {
            // 📦 创建MediaExtractor实例
            // 💡 必须先创建才能设置数据源
            extractor = new MediaExtractor();
            // 📁 设置数据源：将视频文件路径传给提取器
            // 💡 提取器需要知道从哪个文件读取数据
            extractor.setDataSource(sourceFile.toString());
            // 🎯 trackIndex：视频轨道索引
            // 💡 为什么定义：视频文件可能包含多个轨道（音频/视频/字幕），需要定位视频轨道
            // 💡 作用：保存视频轨道在轨道列表中的索引位置
            // 💡 使用时机：用于后续selectTrack()和getTrackFormat()调用
            int trackIndex = selectTrack(extractor);
            // ⚠️ 检查是否找到视频轨道：trackIndex < 0 表示没有视频轨道
            // 💡 为什么检查：如果文件不含视频轨道（如纯音频文件），后续操作会失败
            // 💡 作用：提前发现错误，给出清晰的错误信息
            // 💡 使用时机：selectTrack()返回后立即检查
            if (trackIndex < 0) {
                throw new RuntimeException("No video track found in " + mSourceFile);
            }
            // ✅ 选择找到的视频轨道：告诉提取器后续只操作这个轨道
            // 💡 为什么调用：提取器需要明确知道读取哪条轨道的数据
            // 💡 作用：激活指定轨道，后续readSampleData()只返回该轨道数据
            // 💡 使用时机：找到视频轨道后立即调用
            extractor.selectTrack(trackIndex);

            // 📊 format：视频轨道的格式信息对象
            // 💡 为什么定义：需要从格式中读取视频宽度和高度
            // 💡 作用：包含视频的各种参数（编码类型、宽高、帧率等）
            // 💡 使用时机：立即从中提取KEY_WIDTH和KEY_HEIGHT
            MediaFormat format = extractor.getTrackFormat(trackIndex);
            // 📐 mVideoWidth：从格式中读取视频宽度（像素）
            // 💡 为什么定义：外部UI需要知道视频尺寸来调整显示区域
            // 💡 作用：存储视频的宽度信息，供getVideoWidth()返回
            // 💡 使用时机：UI布局时通过getVideoWidth()获取
            mVideoWidth = format.getInteger(MediaFormat.KEY_WIDTH);
            // 📐 mVideoHeight：从格式中读取视频高度（像素）
            // 💡 为什么定义：外部UI需要知道视频尺寸来调整显示区域
            // 💡 作用：存储视频的高度信息，供getVideoHeight()返回
            // 💡 使用时机：UI布局时通过getVideoHeight()获取
            mVideoHeight = format.getInteger(MediaFormat.KEY_HEIGHT);
            // 📝 如果启用详细日志，记录视频尺寸信息便于调试
            if (VERBOSE) {
                Log.d(TAG, "Video size is " + mVideoWidth + "x" + mVideoHeight);
            }
        } finally {
            // 🗑️ 释放提取器：无论成功失败都要释放资源防止内存泄漏
            // 💡 finally块确保即使发生异常也能正确释放资源
            if (extractor != null) {
                // 🗑️ extractor.release()：释放提取器占用的资源
                // 💡 为什么调用：提取器持有文件句柄和解码资源，必须释放避免内存泄漏
                // 💡 作用：关闭底层文件句柄，释放Native层内存
                // 💡 使用时机：在构造函数结束时（无论成功或异常）
                extractor.release();
            }
        }
    }

    /**
     * Returns the width, in pixels, of the video.
     * 
     * 📤 获取视频宽度（像素）
     * 
     * @return 视频宽度
     */
    public int getVideoWidth() {
        return mVideoWidth;
    }

    /**
     * Returns the height, in pixels, of the video.
     * 
     * 📤 获取视频高度（像素）
     * 
     * @return 视频高度
     */
    public int getVideoHeight() {
        return mVideoHeight;
    }

    /**
     * Sets the loop mode.  If true, playback will loop forever.
     * 
     * 🔄 设置循环模式
     * 💡 如果为true，播放将永远循环
     * 
     * @param loopMode 是否循环播放
     */
    public void setLoopMode(boolean loopMode) {
        mLoop = loopMode;
    }

    /**
     * Asks the player to stop.  Returns without waiting for playback to halt.
     * <p>
     * Called from arbitrary thread.
     * 
     * ⏹️ 请求停止播放
     * 💡 不会等待播放完全停止
     * 💡 可以从任意线程调用
     */
    public void requestStop() {
        mIsStopRequested = true;
    }

    /**
     * Decodes the video stream, sending frames to the surface.
     * <p>
     * Does not return until video playback is complete, or we get a "stop" signal from
     * frameCallback.
     * 
     * 🎬 解码视频流，将帧发送到surface
     * 💡 直到视频播放完成或收到停止信号才会返回
     * 
     * @throws IOException 文件读取异常
     */
    public void play() throws IOException {
        // 📦 extractor：媒体提取器，用于从视频文件中读取编码数据
        // 💡 为什么定义：需要从文件中提取视频帧的编码数据送给解码器
        // 💡 作用：读取视频文件，按轨道提取编码的媒体数据包
        // 💡 使用时机：在doExtract()循环中反复调用readSampleData()读取数据
        MediaExtractor extractor = null;
        // 🎬 decoder：媒体解码器，用于将编码数据解码为可渲染的帧
        // 💡 为什么定义：视频文件中的数据是编码格式（如H.264），需要解码才能渲染
        // 💡 作用：将编码的视频数据解码为原始图像帧
        // 💡 使用时机：在doExtract()循环中接收编码数据并输出解码帧到Surface
        MediaCodec decoder = null;

        /**
         * The MediaExtractor error messages aren't very useful.  Check to see if the input
         * file exists so we can throw a better one if it's not there.
         *
         * 💡 MediaExtractor的错误信息不太有用。
         *    先检查输入文件是否存在，这样可以抛出更好的错误信息。
         */
        // 🔍 if (!mSourceFile.canRead())：检查文件是否可读
        // 💡 为什么检查：MediaExtractor的错误信息不够清晰，提前检查能给出更好的提示
        // 💡 作用：验证文件存在且可访问，否则抛出FileNotFoundException
        // 💡 使用时机：创建MediaExtractor之前检查
        if (!mSourceFile.canRead()) {
            throw new FileNotFoundException("Unable to read " + mSourceFile);
        }

        try {
            // 📦 extractor = new MediaExtractor()：创建MediaExtractor实例
            // 💡 为什么创建：需要从视频文件中提取编码数据
            // 💡 作用：初始化提取器，准备读取视频文件
            // 💡 使用时机：文件检查通过后立即创建
            extractor = new MediaExtractor();
            // 📁 extractor.setDataSource(mSourceFile.toString())：设置数据源
            // 💡 为什么调用：提取器需要知道从哪个文件读取数据
            // 💡 作用：将视频文件路径传给提取器
            // 💡 使用时机：创建提取器后立即设置
            extractor.setDataSource(mSourceFile.toString());
            // 🎯 trackIndex：视频轨道索引
            // 💡 为什么定义：文件可能包含多条轨道，需要定位视频轨道
            // 💡 作用：保存视频轨道的索引，用于后续选择和读取
            // 💡 使用时机：传给selectTrack()和getTrackFormat()以及doExtract()
            int trackIndex = selectTrack(extractor);
            // ⚠️ if (trackIndex < 0)：检查是否找到视频轨道
            // 💡 为什么检查：确保文件包含视频轨道，避免后续解码失败
            // 💡 作用：提前验证，给出有意义的错误信息
            // 💡 使用时机：selectTrack()返回后立即检查
            if (trackIndex < 0) {
                throw new RuntimeException("No video track found in " + mSourceFile);
            }
            // ✅ extractor.selectTrack(trackIndex)：选择视频轨道
            // 💡 为什么调用：必须先选择轨道才能读取该轨道的编码数据
            // 💡 作用：激活视频轨道，使readSampleData()只返回视频数据
            // 💡 使用时机：确认轨道存在后立即调用
            extractor.selectTrack(trackIndex);

            // 📊 format：视频轨道的格式信息
            // 💡 为什么定义：需要从中获取MIME类型来创建解码器
            // 💡 作用：包含编码类型、宽高、帧率等视频参数
            // 💡 使用时机：立即提取MIME类型，后续传给解码器配置
            MediaFormat format = extractor.getTrackFormat(trackIndex);

            /**
             * Create a MediaCodec decoder, and configure it with the MediaFormat from the
             * extractor.  It's very important to use the format from the extractor because
             * it contains a copy of the CSD-0/CSD-1 codec-specific data chunks.
             *
             * 🎬 创建MediaCodec解码器，并使用提取器的MediaFormat配置它
             * 💡 使用提取器的格式非常重要，因为它包含CSD-0/CSD-1编解码器特定数据块的副本
             */
            // 📊 mime：视频编码的MIME类型（如"video/avc"表示H.264）
            // 💡 为什么定义：MediaCodec需要根据编码类型创建对应的解码器
            // 💡 作用：标识视频的编码格式，用于创建正确的解码器
            // 💡 使用时机：立即传给MediaCodec.createDecoderByType()
            String mime = format.getString(MediaFormat.KEY_MIME);
            // 🎬 decoder = MediaCodec.createDecoderByType(mime)：根据MIME类型创建解码器
            // 💡 为什么创建：系统会查找支持该编码的解码器
            // 💡 作用：创建MediaCodec解码器实例
            // 💡 使用时机：获取MIME类型后立即创建
            decoder = MediaCodec.createDecoderByType(mime);
            // ⚙️ decoder.configure(format, mOutputSurface, null, 0)：配置解码器
            // 💡 为什么调用：设置输入格式、输出Surface、无加密、非编码模式
            // 💡 参数说明：format=输入格式, surface=输出表面, crypto=null无加密, flags=0解码模式
            decoder.configure(format, mOutputSurface, null, 0);
            // ▶️ decoder.start()：启动解码器
            // 💡 为什么调用：分配资源，进入可处理数据的状态
            // 💡 作用：初始化解码器，准备接收编码数据
            decoder.start();

            // 🔄 doExtract(extractor, trackIndex, decoder, mFrameCallback)：执行提取和解码的主循环
            // 💡 为什么调用：这是视频播放的核心：循环读取编码数据→送入解码器→渲染解码帧
            // 💡 作用：阻塞调用，直到视频播放完毕或收到停止信号
            doExtract(extractor, trackIndex, decoder, mFrameCallback);
        } finally {
            // 🗑️ finally块：无论成功还是异常，都必须释放资源
            // release everything we grabbed
            if (decoder != null) {
                // ⏹️ decoder.stop()：停止解码器
                // 💡 为什么调用：结束解码工作，释放编解码器资源
                decoder.stop();    // ⏹️ 停止解码器：结束解码工作
                // 🗑️ decoder.release()：释放解码器
                // 💡 为什么调用：释放底层硬件资源，避免内存泄漏
                decoder.release(); // 🗑️ 释放解码器：释放底层硬件资源
                // 🔄 decoder = null：将引用置空
                // 💡 为什么置空：帮助GC（垃圾回收器）识别可回收对象
                // 💡 作用：加速内存回收，避免悬挂引用
                // 💡 使用时机：释放资源后立即置空
                decoder = null;    // 🔄 置空引用：帮助GC回收
            }
            if (extractor != null) {
                // 🗑️ extractor.release()：释放提取器
                // 💡 为什么调用：关闭文件句柄，释放Native层资源
                extractor.release(); // 🗑️ 释放提取器：关闭文件句柄
                // 🔄 extractor = null：将引用置空
                // 💡 为什么置空：帮助GC（垃圾回收器）识别可回收对象
                // 💡 作用：加速内存回收，避免悬挂引用
                // 💡 使用时机：释放资源后立即置空
                extractor = null;    // 🔄 置空引用：帮助GC回收
            }
        }
    }

    /**
     * Selects the video track, if any.
     *
     * @return the track index, or -1 if no video track is found.
     *
     * 🎯 选择视频轨道（如果有的话）
     *
     * @return 轨道索引，如果没有找到视频轨道则返回-1
     */
    private static int selectTrack(MediaExtractor extractor) {
        // Select the first video track we find, ignore the rest.
        // 📝 选择找到的第一个视频轨道，忽略其他轨道

        // 📊 numTracks：媒体文件中的轨道总数
        // 💡 为什么定义：需要知道遍历多少次来查找视频轨道
        // 💡 作用：保存轨道总数，用于for循环边界
        // 💡 使用时机：在for循环条件中作为上限
        int numTracks = extractor.getTrackCount();
        // 🔄 遍历所有轨道：逐一检查是否为视频轨道
        // 💡 i：当前遍历的轨道索引
        // 💡 为什么定义：需要逐个检查每个轨道的MIME类型
        // 💡 作用：标识当前正在检查的轨道位置
        // 💡 使用时机：传给extractor.getTrackFormat(i)获取轨道格式
        for (int i = 0; i < numTracks; i++) {
            // 📊 format：当前轨道的格式信息对象
            // 💡 为什么定义：需要从格式中读取MIME类型来判断轨道类型
            // 💡 作用：包含轨道的编码类型、宽高、帧率等参数
            // 💡 使用时机：立即从中提取KEY_MIME判断是否为视频轨道
            MediaFormat format = extractor.getTrackFormat(i);
            // 📊 mime：轨道的MIME类型字符串（如"video/avc"表示H.264视频）
            // 💡 为什么定义：通过MIME前缀判断是否为视频轨道
            // 💡 作用：标识轨道的编码格式类型
            // 💡 使用时机：通过startsWith("video/")判断是否为视频轨道
            String mime = format.getString(MediaFormat.KEY_MIME);
            // 🔍 检查MIME类型是否以"video/"开头：表示这是视频轨道
            // 💡 为什么检查：音频轨道的MIME以"audio/"开头，需要区分
            // 💡 作用：筛选出视频轨道，忽略音频和字幕轨道
            // 💡 使用时机：在每次循环迭代中判断当前轨道类型
            if (mime.startsWith("video/")) {
                // 📝 如果启用详细日志，记录选择的轨道信息便于调试
                // 💡 为什么记录：帮助开发者确认选择了正确的轨道
                // 💡 作用：在logcat中输出轨道索引、MIME类型和完整格式
                // 💡 使用时机：找到视频轨道后立即记录
                if (VERBOSE) {
                    Log.d(TAG, "Extractor selected track " + i + " (" + mime + "): " + format);
                }
                // ✅ 返回视频轨道索引：找到第一个视频轨道就返回
                // 💡 为什么立即返回：只选择第一个视频轨道，忽略后续的视频轨道
                // 💡 作用：将轨道索引返回给调用者用于后续操作
                // 💡 使用时机：找到视频轨道后立即返回，结束方法执行
                return i;
            }
        }

        // ❌ 遍历完所有轨道都没找到视频轨道：返回-1表示未找到
        // 💡 为什么返回-1：负值作为无效索引的标识，调用者需检查此值
        // 💡 作用：告知调用者此媒体文件不含视频轨道（如纯音频文件）
        // 💡 使用时机：所有轨道遍历完毕且无视频轨道时
        return -1;
    }

    /**
     * Work loop.  We execute here until we run out of video or are told to stop.
     * 
     * 🔄 工作循环：在这里执行，直到视频播放完毕或收到停止信号
     * 
     * 💡 这是视频解码和渲染的核心循环
     * 💡 需要在输入和输出之间取得平衡
     */
    private void doExtract(MediaExtractor extractor, int trackIndex, MediaCodec decoder,
            FrameCallback frameCallback) {
        /**
         * We need to strike a balance between providing input and reading output that
         * operates efficiently without delays on the output side.
         *
         * To avoid delays on the output side, we need to keep the codec's input buffers
         * fed.  There can be significant latency between submitting frame N to the decoder
         * and receiving frame N on the output, so we need to stay ahead of the game.
         *
         * Many video decoders seem to want several frames of video before they start
         * producing output -- one implementation wanted four before it appeared to
         * configure itself.  We need to provide a bunch of input frames up front, and try
         * to keep the queue full as we go.
         *
         * (Note it's possible for the encoded data to be written to the stream out of order,
         * so we can't generally submit a single frame and wait for it to appear.)
         *
         * We can't just fixate on the input side though.  If we spend too much time trying
         * to stuff the input, we might miss a presentation deadline.  At 60Hz we have 16.7ms
         * between frames, so sleeping for 10ms would eat up a significant fraction of the
         * time allowed.  (Most video is at 30Hz or less, so for most content we'll have
         * significantly longer.)  Waiting for output is okay, but sleeping on availability
         * of input buffers is unwise if we need to be providing output on a regular schedule.
         *
         *
         * In some situations, startup latency may be a concern.  To minimize startup time,
         * we'd want to stuff the input full as quickly as possible.  This turns out to be
         * somewhat complicated, as the codec may still be starting up and will refuse to
         * accept input.  Removing the timeout from dequeueInputBuffer() results in spinning
         * on the CPU.
         *
         * If you have tight startup latency requirements, it would probably be best to
         * "prime the pump" with a sequence of frames that aren't actually shown (e.g.
         * grab the first 10 NAL units and shove them through, then rewind to the start of
         * the first key frame).
         *
         * The actual latency seems to depend on strongly on the nature of the video (e.g.
         * resolution).
         *
         *
         * One conceptually nice approach is to loop on the input side to ensure that the codec
         * always has all the input it can handle.  After submitting a buffer, we immediately
         * check to see if it will accept another.  We can use a short timeout so we don't
         * miss a presentation deadline.  On the output side we only check once, with a longer
         * timeout, then return to the outer loop to see if the codec is hungry for more input.
         *
         * In practice, every call to check for available buffers involves a lot of message-
         * passing between threads and processes.  Setting a very brief timeout doesn't
         * exactly work because the overhead required to determine that no buffer is available
         * is substantial.  On one device, the "clever" approach caused significantly greater
         * and more highly variable startup latency.
         *
         * The code below takes a very simple-minded approach that works, but carries a risk
         * of occasionally running out of output.  A more sophisticated approach might
         * detect an output timeout and use that as a signal to try to enqueue several input
         * buffers on the next iteration.
         *
         * If you want to experiment, set the VERBOSE flag to true and watch the behavior
         * in logcat.  Use "logcat -v threadtime" to see sub-second timing.
         * 
         * 💡 需要在提供输入和读取输出之间取得平衡，以确保输出端高效运行而无延迟。
         *
         * 💡 为了避免输出端延迟，需要保持编解码器的输入缓冲区有数据。
         *    在将帧N提交给解码器和在输出端接收帧N之间可能存在显著延迟，
         *    所以需要提前准备。
         *
         * 💡 许多视频解码器似乎需要几帧视频才开始产生输出——
         *    一个实现在看起来配置好自己之前需要四帧。
         *    需要预先提供一批输入帧，并在过程中保持队列满。
         *
         * 💡（注意：编码数据可能乱序写入流，所以通常不能提交单帧然后等待它出现。）
         *
         * 💡 但不能只关注输入端。如果花太多时间尝试填满输入，
         *    可能会错过呈现截止时间。在60Hz下，帧间隔为16.7ms，
         *    睡眠10ms会占用相当大比例的时间。
         *    （大多数视频为30Hz或更低，所以对于大多数内容有更长时间。）
         *    等待输出是可以的，但如果需要定期提供输出，
         *    在输入缓冲区可用性上睡眠是不明智的。
         *
         * 💡 在某些情况下，启动延迟可能是个问题。为了最小化启动时间，
         *    希望尽快填满输入。但这变得有点复杂，因为编解码器可能仍在启动中，
         *    会拒绝接受输入。从dequeueInputBuffer()移除超时会导致CPU空转。
         *
         * 💡 如果有严格的启动延迟要求，最好用实际不显示的帧序列"启动泵"。
         *
         * 💡 实际延迟似乎很大程度上取决于视频的性质（如分辨率）。
         *
         * 💡 一种概念上好的方法是在输入端循环，确保编解码器始终有所有能处理的输入。
         *    在提交缓冲区后，立即检查是否能接受另一个。可以使用短超时以免错过呈现截止时间。
         *    在输出端只检查一次，使用较长超时，然后返回外循环看看编解码器是否需要更多输入。
         *
         * 💡 实际上，每次检查可用缓冲区都涉及大量线程和进程间的消息传递。
         *    设置非常短的超时不完全有效，因为确定没有缓冲区可用的开销很大。
         *
         * 💡 下面的代码采用非常简单的方法，但偶尔会有输出耗尽的风险。
         *    更复杂的方法可能会检测输出超时，并将其作为信号在下次迭代中尝试入队多个输入缓冲区。
         *
         * 💡 如果想实验，将VERBOSE标志设置为true并观察logcat中的行为。
         *    使用"logcat -v threadtime"查看亚秒级时序。
         */

        // ⏱️ TIMEOUT_USEC：解码器操作的超时时间（微秒）
        // 💡 为什么定义：dequeueInputBuffer和dequeueOutputBuffer需要超时参数避免无限阻塞
        // 💡 作用：设置10毫秒超时，在响应性和CPU占用之间取得平衡
        // 💡 使用时机：每次调用dequeueInputBuffer()和dequeueOutputBuffer()时传入
        // 💡 值选择原因：10ms足够短不会错过30fps的33ms帧间隔，又不会太短导致CPU空转
        final int TIMEOUT_USEC = 10000;  // ⏱️ 超时时间：10毫秒
        // 📦 decoderInputBuffers：解码器的输入缓冲区数组
        // 💡 为什么定义：需要获取可用的输入缓冲区来填入编码数据
        // 💡 作用：保存所有输入缓冲区的引用，用于向解码器提交编码数据
        // 💡 使用时机：在主循环中通过索引获取缓冲区，调用readSampleData()填入数据
        ByteBuffer[] decoderInputBuffers = decoder.getInputBuffers();
        // 📊 inputChunk：已提交给解码器的数据块计数器
        // 💡 为什么定义：用于跟踪已处理的帧数，便于日志调试
        // 💡 作用：记录送入解码器的帧数量
        // 💡 使用时机：每次成功入队数据后递增，VERBOSE模式下打印日志
        int inputChunk = 0;
        // ⏱️ firstInputTimeNsec：第一个输入数据块的时间戳（纳秒）
        // 💡 为什么定义：需要测量从开始送数据到解码出第一帧的启动延迟
        // 💡 作用：记录首个输入帧的时间点，用于计算启动延迟
        // 💡 使用时机：首次获取到输入缓冲区时记录，首帧输出时计算延迟后置0
        long firstInputTimeNsec = -1;

        // 🏁 outputDone：输出完成标志
        // 💡 为什么定义：作为主循环的退出条件，当视频播放完毕或收到EOS时设为true
        // 💡 作用：控制主while循环是否继续执行
        // 💡 使用时机：在while条件中检查，收到BUFFER_FLAG_END_OF_STREAM且非循环时设为true
        boolean outputDone = false;
        // 🏁 inputDone：输入完成标志
        // 💡 为什么定义：标记是否已将所有编码数据送入解码器（包括发送EOS）
        // 💡 作用：避免重复发送EOS，跳过已完成的输入处理阶段
        // 💡 使用时机：在输入处理块开头检查，读到文件末尾时设为true
        boolean inputDone = false;
        // 🔄 主循环：持续处理直到输出完成
        // 💡 循环条件：!outputDone，即只要输出未完成就继续处理
        while (!outputDone) {
            // 📝 详细日志：记录每次循环迭代（仅VERBOSE模式）
            if (VERBOSE) Log.d(TAG, "loop");
            // ⏹️ 检查是否请求停止：外部线程可能调用requestStop()
            // 💡 mIsStopRequested是volatile变量，保证多线程可见性
            if (mIsStopRequested) {
                Log.d(TAG, "Stop requested");
                // 🚪 退出方法：立即返回，资源释放由play()的finally块处理
                return;
            }

            // Feed more data to the decoder.
            // 📤 向解码器提供更多数据：输入处理阶段
            // 💡 只有inputDone为false时才继续读取数据
            if (!inputDone) {
                // 🔍 inputBufIndex：可用输入缓冲区的索引
                // 💡 为什么定义：需要获取一个空闲缓冲区来填入编码数据
                // 💡 作用：标识解码器中可用的输入缓冲区位置
                // 💡 使用时机：>=0时用于获取ByteBuffer并入队，<0时跳过本轮输入
                int inputBufIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC);
                if (inputBufIndex >= 0) {
                    // ✅ 获取到可用的输入缓冲区
                    if (firstInputTimeNsec == -1) {
                        // ⏱️ 记录第一个输入块的时间：用于计算启动延迟
                        firstInputTimeNsec = System.nanoTime();
                    }
                    // 📦 inputBuf：输入缓冲区的ByteBuffer引用
                    // 💡 为什么定义：需要将编码数据写入这个缓冲区
                    // 💡 作用：作为编码数据从extractor到decoder的传输容器
                    // 💡 使用时机：传给extractor.readSampleData()填充数据
                    ByteBuffer inputBuf = decoderInputBuffers[inputBufIndex];
                    // Read the sample data into the ByteBuffer.  This neither respects nor
                    // updates inputBuf's position, limit, etc.
                    // 📦 chunkSize：读取到的样本数据大小（字节）
                    // 💡 为什么定义：需要知道实际读取了多少数据，-1表示文件结束
                    // 💡 作用：记录本次读取的数据量，用于入队时指定数据长度
                    // 💡 使用时机：>=0时传给queueInputBuffer()，<0时发送EOS标志
                    // 📦 将样本数据读入ByteBuffer（不修改position和limit）
                    int chunkSize = extractor.readSampleData(inputBuf, 0);
                    if (chunkSize < 0) {
                        // 🏁 数据流结束——发送带有EOS标志的空帧
                        // End of stream -- send empty frame with EOS flag set.
                        // 💡 必须发送EOS通知解码器输入已结束，否则解码器会一直等待
                        decoder.queueInputBuffer(inputBufIndex, 0, 0, 0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;  // ✅ 标记输入完成：不再读取更多数据
                        if (VERBOSE) Log.d(TAG, "sent input EOS");
                    } else {
                        // 📤 有数据，入队到解码器
                        if (extractor.getSampleTrackIndex() != trackIndex) {
                            // ⚠️ 警告：从错误的轨道获取了样本（正常情况不应发生）
                            Log.w(TAG, "WEIRD: got sample from track " +
                                    extractor.getSampleTrackIndex() + ", expected " + trackIndex);
                        }
                        // ⏱️ presentationTimeUs：样本的呈现时间戳（微秒）
                        // 💡 为什么定义：解码器需要时间戳来按正确顺序输出帧
                        // 💡 作用：告诉解码器这一帧应该在什么时间点呈现
                        // 💡 使用时机：传给queueInputBuffer()的presentationTime参数
                        long presentationTimeUs = extractor.getSampleTime();
                        // 📤 将输入缓冲区入队到解码器
                        // 💡 参数：bufferIndex=缓冲区索引, offset=0, size=数据长度, time=时间戳, flags=0无特殊标记
                        decoder.queueInputBuffer(inputBufIndex, 0, chunkSize,
                                presentationTimeUs, 0 /*flags*/);
                        if (VERBOSE) {
                            Log.d(TAG, "submitted frame " + inputChunk + " to dec, size=" +
                                    chunkSize);
                        }
                        inputChunk++;      // 📊 递增输入块计数：记录已提交的帧数
                        extractor.advance(); // ➡️ 移动到下一个样本：准备下一次读取
                    }
                } else {
                    // ⏳ 没有可用的输入缓冲区：解码器处理速度跟不上
                    // 💡 正常情况，下一轮循环再尝试
                    // 📝 VERBOSE日志：记录无可用缓冲区的情况（仅调试时开启）
                    // 💡 为什么记录：帮助调试缓冲区耗尽问题
                    // 💡 作用：在logcat中追踪缓冲区使用情况
                    // 💡 使用时机：每次dequeueInputBuffer()返回<0时
                    if (VERBOSE) Log.d(TAG, "input buffer not available");
                }
            }

            // 📥 处理解码器输出：输出处理阶段
            // 💡 只有outputDone为false时才继续处理输出
            if (!outputDone) {
                // 🔍 decoderStatus：解码器输出缓冲区的状态
                // 💡 为什么定义：需要判断是否有解码好的帧可以渲染
                // 💡 作用：返回值可能是缓冲区索引、状态码或错误码
                // 💡 使用时机：根据返回值分支处理：超时重试、格式变更、正常输出等
                int decoderStatus = decoder.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);
                if (decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // ⏳ 暂无输出可用：解码器还在处理中
                    // no output available yet
                    // 💡 正常情况，下一轮循环再尝试
                    // 📝 VERBOSE日志：记录解码器暂无输出
                    // 💡 为什么记录：追踪解码器的输出节奏
                    // 💡 作用：在logcat中显示解码器状态
                    // 💡 使用时机：每次dequeueOutputBuffer()返回INFO_TRY_AGAIN_LATER时
                    if (VERBOSE) Log.d(TAG, "no output from decoder available");
                } else if (decoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    // 🔄 输出缓冲区已更改（使用Surface时不需要关心）
                    // not important for us, since we're using Surface
                    // 💡 使用Surface模式时，输出直接渲染到Surface，无需获取缓冲区
                    // 📝 VERBOSE日志：记录输出缓冲区数组变更事件
                    // 💡 为什么记录：Surface模式下此事件通常可忽略，但记录有助于调试
                    // 💡 作用：在logcat中显示缓冲区变更
                    // 💡 使用时机：dequeueOutputBuffer()返回INFO_OUTPUT_BUFFERS_CHANGED时
                    if (VERBOSE) Log.d(TAG, "decoder output buffers changed");
                } else if (decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // 📊 输出格式已更改：解码器可能调整了输出格式
                    // 💡 newFormat：新的输出格式
                    // 💡 为什么定义：解码器输出格式可能与输入不同（如颜色格式变化）
                    // 💡 作用：获取解码后的实际格式信息
                    // 💡 使用时机：仅日志记录，Surface模式下无需额外处理
                    MediaFormat newFormat = decoder.getOutputFormat();
                    // 📝 VERBOSE日志：记录输出格式变化详情
                    // 💡 为什么记录：格式变化可能影响渲染，记录便于调试
                    // 💡 作用：在logcat中显示新的输出格式参数
                    // 💡 使用时机：收到INFO_OUTPUT_FORMAT_CHANGED后立即记录
                    if (VERBOSE) Log.d(TAG, "decoder output format changed: " + newFormat);
                } else if (decoderStatus < 0) {
                    // ❌ 未知状态，抛出异常：不应出现的返回值
                    throw new RuntimeException(
                            "unexpected result from decoder.dequeueOutputBuffer: " +
                                    decoderStatus);
                } else { // decoderStatus >= 0
                    // ✅ 成功获取输出缓冲区：decoderStatus就是缓冲区索引
                    // 🔍 firstInputTimeNsec != 0：检查是否需要记录启动延迟
                    // 💡 为什么检查：只在首帧输出时记录一次启动延迟
                    // 💡 作用：条件判断，确保延迟只计算一次
                    // 💡 使用时机：获取到首个输出缓冲区时
                    if (firstInputTimeNsec != 0) {
                        // ⏱️ 记录从第一个输入到第一个输出的延迟（启动延迟）
                        // Log the delay from the first buffer of input to the first buffer
                        // of output.
                        // 💡 nowNsec：当前时间（纳秒）
                        // 💡 为什么定义：需要计算从首帧输入到首帧输出的时间差
                        // 💡 作用：记录当前时刻，用于计算启动延迟
                        // 💡 使用时机：与firstInputTimeNsec相减得到延迟
                        long nowNsec = System.nanoTime();
                        Log.d(TAG, "startup lag " + ((nowNsec-firstInputTimeNsec) / 1000000.0) + " ms");
                        firstInputTimeNsec = 0;  // 🔄 重置为0：只记录一次启动延迟
                    }
                    // 🔄 doLoop：是否需要循环播放
                    // 💡 为什么定义：收到EOS时，如果开启循环模式需要重置播放位置
                    // 💡 作用：标记当前帧是最后一帧且需要循环
                    // 💡 使用时机：在渲染完成后检查，true则seekTo开头并flush解码器
                    boolean doLoop = false;
                    // 📝 VERBOSE日志：记录成功获取输出缓冲区的索引和大小
                    // 💡 为什么记录：追踪解码器输出缓冲区的使用情况
                    // 💡 作用：在logcat中显示每个输出缓冲区的详细信息
                    // 💡 使用时机：每次成功dequeueOutputBuffer()后
                    if (VERBOSE) Log.d(TAG, "surface decoder given buffer " + decoderStatus +
                            " (size=" + mBufferInfo.size + ")");
                    // 🔍 检查是否为流结束标志（EOS）
                    // 💡 BUFFER_FLAG_END_OF_STREAM表示这是最后一个缓冲区
                    // 💡 为什么检查：需要区分正常帧和流结束帧
                    // 💡 作用：决定是继续播放还是结束/循环
                    // 💡 使用时机：每次获取输出缓冲区后检查flags
                    if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        // 📝 VERBOSE日志：记录到达流末尾
                        // 💡 为什么记录：标识视频播放到达结尾
                        // 💡 作用：在logcat中显示EOS事件
                        // 💡 使用时机：检测到EOS标志时
                        if (VERBOSE) Log.d(TAG, "output EOS");
                        if (mLoop) {
                            doLoop = true;  // 🔄 需要循环播放：稍后重置位置
                        } else {
                            outputDone = true;  // 🏁 输出完成：退出主循环
                        }
                    }

                    // 🔍 doRender：是否需要渲染此帧
                    // 💡 为什么定义：缓冲区大小为0的帧不需要渲染（如EOS帧）
                    // 💡 作用：决定是否将此帧显示到Surface
                    // 💡 使用时机：传给releaseOutputBuffer()的render参数
                    boolean doRender = (mBufferInfo.size != 0);

                    // As soon as we call releaseOutputBuffer, the buffer will be forwarded
                    // to SurfaceTexture to convert to a texture.  We can't control when it
                    // appears on-screen, but we can manage the pace at which we release
                    // the buffers.
                    // 💡 调用releaseOutputBuffer后，缓冲区会被转发到SurfaceTexture
                    //    我们无法控制何时显示，但可以控制释放节奏
                    if (doRender && frameCallback != null) {
                        // 🎯 渲染前回调：用于控制播放速度（如同步音频）
                        // 💡 mBufferInfo.presentationTimeUs：当前帧的呈现时间戳（微秒）
                        frameCallback.preRender(mBufferInfo.presentationTimeUs);
                    }
                    // 🖼️ 释放输出缓冲区：doRender为true时渲染到Surface
                    // 💡 decoderStatus是缓冲区索引，doRender决定是否显示
                    decoder.releaseOutputBuffer(decoderStatus, doRender);
                    if (doRender && frameCallback != null) {
                        // 🎯 渲染后回调：通知帧已释放（可能还未实际显示）
                        frameCallback.postRender();
                    }

                    // 🔄 处理循环播放
                    if (doLoop) {
                        Log.d(TAG, "Reached EOS, looping");
                        // ⏪ extractor.seekTo()：回退到视频开始位置
                        // 💡 SEEK_TO_CLOSEST_SYNC：定位到最近的同步帧（关键帧）
                        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                        inputDone = false;    // 🔄 重置输入状态：允许继续读取数据
                        decoder.flush();      // 🔄 重置解码器状态：清空内部缓冲区
                        frameCallback.loopReset(); // 🎯 通知回调循环重置：调整时间戳期望
                    }
                }
            }
        }
    }

    /**
     * Thread helper for video playback.
     * <p>
     * The PlayerFeedback callbacks will execute on the thread that creates the object,
     * assuming that thread has a looper.  Otherwise, they will execute on the main looper.
     * 
     * 🧵 视频播放的线程辅助类
     * 💡 PlayerFeedback回调会在创建对象的线程执行（如果有Looper）
     * 💡 否则会在主线程执行
     */
    public static class PlayTask implements Runnable {
        private static final int MSG_PLAY_STOPPED = 0;  // 📨 播放停止消息

        private MoviePlayer mPlayer;           // 🎬 视频播放器
        private PlayerFeedback mFeedback;      // 🎯 UI反馈接口
        private boolean mDoLoop;               // 🔄 是否循环播放
        private Thread mThread;                // 🧵 播放线程
        private LocalHandler mLocalHandler;    // 📬 本地Handler

        private final Object mStopLock = new Object();  // 🔒 停止同步锁
        private boolean mStopped = false;               // ⏹️ 是否已停止

        /**
         * Prepares new PlayTask.
         *
         * @param player The player object, configured with control and output.
         * @param feedback UI feedback object.
         * 
         * 🔧 构造函数：准备新的PlayTask
         * @param player 配置好的播放器对象
         * @param feedback UI反馈对象
         */
        public PlayTask(MoviePlayer player, PlayerFeedback feedback) {
            // 🎬 保存播放器
            mPlayer = player;
            // 🎯 保存反馈接口
            mFeedback = feedback;

            // 📬 创建本地Handler
            mLocalHandler = new LocalHandler();
        }

        /**
         * Sets the loop mode.  If true, playback will loop forever.
         * 
         * 🔄 设置循环模式
         * @param loopMode 是否循环播放
         */
        public void setLoopMode(boolean loopMode) {
            mDoLoop = loopMode;
        }

        /**
         * Creates a new thread, and starts execution of the player.
         * 
         * 🚀 创建新线程并启动播放器执行
         */
        public void execute() {
            // 🔄 设置循环模式
            mPlayer.setLoopMode(mDoLoop);
            // 🧵 创建并启动播放线程
            mThread = new Thread(this, "Movie Player");
            mThread.start();
        }

        /**
         * Requests that the player stop.
         * <p>
         * Called from arbitrary thread.
         * 
         * ⏹️ 请求停止播放（可从任意线程调用）
         */
        public void requestStop() {
            mPlayer.requestStop();
        }

        /**
         * Wait for the player to stop.
         * <p>
         * Called from any thread other than the PlayTask thread.
         * 
         * ⏳ 等待播放器停止（不能在PlayTask线程调用）
         */
        public void waitForStop() {
            // 🔒 同步等待停止信号
            synchronized (mStopLock) {
                while (!mStopped) {
                    try {
                        mStopLock.wait();  // ⏳ 等待通知
                    } catch (InterruptedException ie) {
                        // discard
                        // 🚫 忽略中断异常
                    }
                }
            }
        }

        /**
         * 🧵 线程执行入口
         * 💡 调用播放器播放，播放完成后通知等待的线程
         */
        @Override
        public void run() {
            try {
                // ▶️ 执行播放：调用MoviePlayer.play()进行视频解码和渲染
                // 💡 为什么调用：这是视频播放的核心方法
                // 💡 作用：阻塞调用，直到视频播放完毕或收到停止信号
                // 💡 使用时机：线程启动后立即执行
                mPlayer.play();
            } catch (IOException ioe) {
                // ❌ 播放失败，抛出运行时异常
                // 💡 为什么包装：将checked exception转换为unchecked exception
                // 💡 作用：避免调用方必须处理IOException
                // 💡 使用时机：play()抛出IOException时捕获并重新抛出
                throw new RuntimeException(ioe);
            } finally {
                // tell anybody waiting on us that we're done
                // 📢 通知所有等待的线程：播放已完成
                // 💡 mStopLock：同步锁对象，用于线程间协调
                // 💡 为什么需要同步：多个线程可能同时等待播放结束
                synchronized (mStopLock) {
                    // ✅ mStopped = true：标记已停止
                    // 💡 为什么设置：让waitForStop()的while循环退出
                    // 💡 作用：指示播放已结束
                    // 💡 使用时机：播放完成或异常退出后设置
                    mStopped = true;
                    // 📢 mStopLock.notifyAll()：唤醒所有等待的线程
                    // 💡 为什么调用：通知在waitForStop()中等待的线程可以继续执行
                    // 💡 作用：解除所有在mStopLock.wait()上阻塞的线程
                    // 💡 使用时机：设置mStopped为true后立即调用
                    mStopLock.notifyAll();
                }

                // Send message through Handler so it runs on the right thread.
                // 📨 通过Handler发送消息到正确的线程执行回调
                // 💡 MSG_PLAY_STOPPED：播放停止消息标识
                // 💡 为什么发送消息：UI回调必须在正确的线程执行
                // 💡 作用：确保playbackStopped()在UI线程调用
                // 💡 使用时机：finally块末尾，确保播放停止后通知UI
                mLocalHandler.sendMessage(
                        mLocalHandler.obtainMessage(MSG_PLAY_STOPPED, mFeedback));
            }
        }

        /**
         * 📬 本地Handler类
         * 💡 用于在正确的线程上执行回调
         */
        private static class LocalHandler extends Handler {
            /**
             * 📬 处理消息
             * 💡 在创建Handler的线程上执行回调
             */
            @Override
            public void handleMessage(Message msg) {
                int what = msg.what;

                // 🎯 根据消息类型处理
                switch (what) {
                    case MSG_PLAY_STOPPED:
                        // 📤 获取反馈接口并调用回调
                        PlayerFeedback fb = (PlayerFeedback) msg.obj;
                        fb.playbackStopped();  // 🎯 通知播放已停止
                        break;
                    default:
                        // ❌ 未知消息类型
                        throw new RuntimeException("Unknown msg " + what);
                }
            }
        }
    }
}
