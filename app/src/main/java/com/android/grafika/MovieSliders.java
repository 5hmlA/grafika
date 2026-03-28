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

import android.opengl.GLES20;
import android.util.Log;

import java.io.File;
import java.io.IOException;

/**
 * 🎬 生成一个简单视频，两个小矩形在屏幕上滑动。
 * Generates a simple movie, featuring two small rectangles that slide across the screen.
 */
public class MovieSliders extends GeneratedMovie {
    private static final String TAG = MainActivity.TAG;

    private static final String MIME_TYPE = "video/avc";    // 🎬 H.264 编码格式
    private static final int WIDTH = 480;       // note 480x640, not 640x480 📐 注意是竖屏 480x640
    private static final int HEIGHT = 640;
    private static final int BIT_RATE = 5000000;            // 📊 比特率 5Mbps
    private static final int FRAMES_PER_SECOND = 30;        // 🎞️ 帧率 30fps

    /**
     * 🎬 创建滑块视频：240 帧，编码后输出到文件。
     */
    // 💡 @Override：重写 GeneratedMovie 抽象类的 create 方法
    // 💡 作用：实现 MovieSliders 特有的视频生成逻辑
    @Override
    // 💡 create()：创建滑块视频的入口方法
    // 💡 参数：outputFile=输出文件路径, prog=进度更新回调
    public void create(File outputFile, ContentManager.ProgressUpdater prog) {
        // 🔍 if (mMovieReady)：检查视频是否已经生成过
        // 💡 为什么检查：防止重复创建导致文件覆盖或编码器冲突
        if (mMovieReady) {
            // 🚨 防止重复创建：如果视频已生成，抛出异常保护数据完整性
            throw new RuntimeException("Already created");  // ⚠️ 防止重复创建
        }

        // 🎞️ NUM_FRAMES: 总帧数 240 = 30fps × 8秒
        //    💡 为什么定义：需要明确指定视频的总帧数来控制生成循环
        //    💡 作用：控制视频生成的总帧数
        //    💡 使用时机：for 循环上限，进度计算
        final int NUM_FRAMES = 240;

        // 🔒 try：开始异常捕获块（IO 操作可能失败）
        try {
            // 🔧 prepareEncoder()：初始化编码器，创建 MediaCodec、EGL 上下文、窗口表面
            // 💡 为什么调用：编码器是视频生成的前提，必须在绘制帧之前就绪
            // 💡 作用：配置 H.264 编码参数，创建 EGL 渲染环境
            // 💡 使用时机：视频生成流程的第一步
            prepareEncoder(MIME_TYPE, WIDTH, HEIGHT, BIT_RATE, FRAMES_PER_SECOND, outputFile);

            // 🔄 for (int i = 0; i < NUM_FRAMES; i++)：逐帧生成视频
            // 💡 为什么循环：每帧需要单独绘制、编码、提交
            // 💡 作用：控制 240 帧的生成流程
            for (int i = 0; i < NUM_FRAMES; i++) {
                // Drain any data from the encoder into the muxer.
                // 📤 排空编码器数据到混合器
                //    作用：在提交新帧前先取出已编码的数据，防止编码器阻塞
                //    使用时机：每帧循环开始时调用
                drainEncoder(false);

                // Generate a frame and submit it.
                // 🎨 generateFrame(i): 用 GL 命令绘制第 i 帧
                //    作用：生成红/绿方块滑动的动画帧
                generateFrame(i);
                // ⏱️ submitFrame(): 设置呈现时间并交换缓冲区
                //    作用：将绘制好的帧提交给编码器，附带时间戳
                submitFrame(computePresentationTimeNsec(i));

                // 📊 更新进度条：当前进度 = 已完成帧数 / 总帧数 × 100
                prog.updateProgress(i * 100 / NUM_FRAMES);
            }

            // Send end-of-stream and drain remaining output.
            // 🏁 发送流结束信号并排空剩余输出
            //    作用：告知编码器不再有新帧，输出所有缓存的编码数据
            drainEncoder(true);
        // 🔒 catch (IOException ioe)：捕获文件写入等 IO 异常
        } catch (IOException ioe) {
            // 🚨 捕获 IO 异常（如文件创建失败）
            // 💡 为什么捕获：文件写入可能因磁盘空间不足等原因失败
            // 💡 作用：将受检异常包装为运行时异常抛出
            throw new RuntimeException(ioe);
        // 🔒 finally：无论成功或失败都执行的清理块
        } finally {
            // 🗑️ releaseEncoder()：释放编码器资源（编码器、EGL 表面、复用器）
            // 💡 为什么在 finally 中：无论成功或失败都必须释放资源
            // 💡 作用：防止资源泄漏
            releaseEncoder();
        }

        // 📝 记录视频生成完成日志，便于调试确认
        Log.d(TAG, "MovieEightRects complete: " + outputFile);
        // ✅ 标记视频已生成完成
        mMovieReady = true;
    }

    /**
     * Generates a frame of data using GL commands.
     * 🎨 使用 GL 命令生成一帧数据。
     *    两个彩色方块（红和绿）在屏幕上来回滑动，背景亮度随之变化。
     */
    private void generateFrame(int frameIndex) {
        // 📦 BOX_SIZE: 方块像素大小（80x80）
        //    💡 为什么定义：需要统一定义红绿方块的尺寸，便于维护和调整
        //    💡 作用：定义红色和绿色方块的宽高
        //    💡 使用时机：传入 glScissor() 裁剪区域
        final int BOX_SIZE = 80;

        // 🔄 frameIndex % 240: 将帧索引限制在 0~239 范围内
        //    💡 为什么定义：帧索引可能超出范围，需要取模确保有效值
        //    💡 作用：实现 240 帧循环动画
        //    💡 使用时机：计算方块位置和背景亮度的输入
        frameIndex %= 240;

        // 📍 xpos: 方块的水平位置（像素）
        //    💡 为什么定义：绿色方块需要水平滑动，需要变量存储其位置
        //    💡 作用：控制绿色方块的水平滑动位置
        //    💡 使用时机：传入 glScissor() 设置裁剪区域 X 坐标
        int xpos;
        // 📍 ypos: 方块的垂直位置（像素）
        //    💡 为什么定义：红色方块需要垂直滑动，需要变量存储其位置
        //    💡 作用：控制红色方块的垂直滑动位置
        //    💡 使用时机：传入 glScissor() 设置裁剪区域 Y 坐标
        int ypos;

        // 📐 absIndex: 对称化的帧索引（0→120→0 钟摆效果）
        //    💡 为什么定义：需要将线性增长的帧索引转换为先增后减的钟摆效果
        //    💡 作用：通过 abs(frameIndex - 120) 产生先前进后后退的运动
        //    💡 使用时机：计算位置和亮度的中间变量
        int absIndex = Math.abs(frameIndex - 120);

        // 📐 xpos = absIndex * WIDTH / 120: 将 0~120 映射到 0~480 像素
        //    作用：绿色方块水平方向来回滑动
        xpos = absIndex * WIDTH / 120;

        // 📐 ypos = absIndex * HEIGHT / 120: 将 0~120 映射到 0~640 像素
        //    作用：红色方块垂直方向来回滑动
        ypos = absIndex * HEIGHT / 120;

        // 💡 lumaf: 背景亮度系数 (0.0~1.0)
        //    💡 为什么定义：背景需要随时间变化亮度，产生动态效果
        //    💡 作用：背景从暗到亮再到暗的渐变效果
        //    💡 使用时机：传入 glClearColor 设置背景颜色
        float lumaf = absIndex / 120.0f;

        // 🎨 设置背景清除色（灰度 = lumaf）
        //    作用：用亮度系数设置 RGB 三通道相同值 → 灰色背景
        GLES20.glClearColor(lumaf, lumaf, lumaf, 1.0f);

        // 🧹 glClear: 用清除色填充整个屏幕
        //    作用：清除上一帧内容，应用新的背景色
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        // ✂️ 启用裁剪测试
        //    作用：允许只在指定矩形区域内绘制
        //    使用时机：绘制方块前启用，绘制完后禁用
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST);

        // 🔴 绘制红色方块（垂直移动）
        //    glScissor 参数：X=BOX_SIZE/2（居中偏移），Y=ypos（随帧变化），W=H=BOX_SIZE
        //    作用：在屏幕左侧竖直滑动的红色方块
        GLES20.glScissor(BOX_SIZE / 2, ypos, BOX_SIZE, BOX_SIZE);
        GLES20.glClearColor(1.0f, 0.0f, 0.0f, 1.0f);   // 🔴 纯红色
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);      // 🧹 填充红色

        // 🟢 绘制绿色方块（水平移动）
        //    glScissor 参数：X=xpos（随帧变化），Y=BOX_SIZE/2（居中偏移），W=H=BOX_SIZE
        //    作用：在屏幕底部水平滑动的绿色方块
        GLES20.glScissor(xpos, BOX_SIZE / 2, BOX_SIZE, BOX_SIZE);
        GLES20.glClearColor(0.0f, 1.0f, 0.0f, 1.0f);   // 🟢 纯绿色
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);      // 🧹 填充绿色

        // 🚫 禁用裁剪测试
        //    作用：恢复全屏绘制模式，避免影响后续 GL 操作
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
    }

    /**
     * Generates the presentation time for frame N, in nanoseconds.  Fixed frame rate.
     * ⏱️ 计算第 N 帧的呈现时间（纳秒），固定帧率。
     */
    private static long computePresentationTimeNsec(int frameIndex) {
        final long ONE_BILLION = 1000000000;
        return frameIndex * ONE_BILLION / FRAMES_PER_SECOND;
    }
}
