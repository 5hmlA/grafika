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
 * 🎬 生成一个非常简单的视频。屏幕分为 8 个矩形，每帧高亮一个。
 * Generates a very simple movie.  The screen is divided into eight rectangles, and one
 * rectangle is highlighted in each frame.
 * <p>
 * To add a little flavor, the timing of the frames speeds up as the movie continues.
 * 为了让效果更有趣，帧的播放速度会随着视频进行逐渐加快。
 */
public class MovieEightRects extends GeneratedMovie {
    private static final String TAG = MainActivity.TAG;

    private static final String MIME_TYPE = "video/avc";    // 🎬 H.264 编码格式
    private static final int WIDTH = 320;                   // 📐 视频宽度
    private static final int HEIGHT = 240;                  // 📐 视频高度
    private static final int BIT_RATE = 2000000;            // 📊 比特率 2Mbps
    private static final int NUM_FRAMES = 32;               // 🎞️ 总帧数 32
    private static final int FRAMES_PER_SECOND = 30;        // 🎞️ 帧率 30fps

    // RGB color values for generated frames 🎨 生成帧的 RGB 颜色值
    private static final int TEST_R0 = 0;       // 背景色 R
    private static final int TEST_G0 = 136;     // 背景色 G
    private static final int TEST_B0 = 0;       // 背景色 B（深绿色）
    private static final int TEST_R1 = 236;     // 高亮色 R
    private static final int TEST_G1 = 50;      // 高亮色 G
    private static final int TEST_B1 = 186;     // 高亮色 B（粉紫色）

    /**
     * 🎬 创建八矩形视频：32 帧，编码后输出到文件。
     */
    // 💡 @Override：重写 GeneratedMovie 抽象类的 create 方法
    // 💡 作用：实现 MovieEightRects 特有的视频生成逻辑（8矩形蛇形动画）
    @Override
    // 💡 create()：创建八矩形视频的入口方法
    // 💡 参数：outputFile=输出文件路径, prog=进度更新回调
    public void create(File outputFile, ContentManager.ProgressUpdater prog) {
        // 🔍 if (mMovieReady)：检查视频是否已经生成过
        // 💡 为什么检查：防止重复创建导致文件覆盖或编码器冲突
        if (mMovieReady) {
            // 🚨 防止重复创建：如果视频已生成，抛出异常保护数据完整性
            throw new RuntimeException("Already created");  // ⚠️ 防止重复创建
        }

        // 🔒 try：开始异常捕获块（IO 操作可能失败）
        try {
            // 🔧 初始化编码器：创建 MediaCodec、EGL 上下文、窗口表面
            prepareEncoder(MIME_TYPE, WIDTH, HEIGHT, BIT_RATE, FRAMES_PER_SECOND, outputFile);

            // 🔄 i: 当前帧索引（0~31）
            //    💡 为什么定义：视频需要逐帧生成，每帧需要单独编码
            //    💡 作用：标识当前正在生成第几帧，用于调用 generateFrame(i) 和 submitFrame()
            //    💡 使用时机：for 循环递增，drainEncoder 之后绘制和提交当前帧
            for (int i = 0; i < NUM_FRAMES; i++) {
                // Drain any data from the encoder into the muxer.
                // 📤 排空编码器数据到混合器
                //    作用：在提交新帧前先取出已编码的数据，防止编码器阻塞
                //    使用时机：每帧循环开始时调用
                drainEncoder(false);

                // Generate a frame and submit it.
                // 🎨 generateFrame(i): 用 GL 命令绘制第 i 帧
                //    作用：按蛇形顺序高亮 8 个矩形之一
                generateFrame(i);
                // ⏱️ submitFrame(): 设置呈现时间并交换缓冲区
                //    作用：将绘制好的帧提交给编码器，附带时间戳（速度逐渐加快）
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
            // 🚨 捕获 IO 异常（如文件创建失败、磁盘空间不足）
            // 💡 为什么捕获：文件写入可能因系统原因失败
            // 💡 作用：将受检异常包装为运行时异常抛出
            throw new RuntimeException(ioe);
        // 🔒 finally：无论成功或失败都执行的清理块
        } finally {
            // 🗑️ releaseEncoder()：释放编码器资源（编码器、EGL 表面、复用器）
            // 💡 为什么在 finally 中：无论成功或失败都必须释放资源，防止泄漏
            releaseEncoder();
        }

        // 📝 Log.d：记录视频生成完成日志，便于调试确认
        Log.d(TAG, "MovieEightRects complete: " + outputFile);
        // ✅ 标记视频已生成完成
        mMovieReady = true;
    }

    /**
     * Generates a frame of data using GL commands.  We have an 8-frame animation
     * sequence that wraps around.  It looks like this:
     * <pre>
     *   0 1 2 3
     *   7 6 5 4
     * </pre>
     * We draw one of the eight rectangles and leave the rest set to the clear color.
     * 🎨 使用 GL 命令生成一帧。8 帧动画序列按蛇形排列，每帧高亮一个矩形。
     *    其余区域设为清除色（深绿色）。
     */
    private void generateFrame(int frameIndex) {
        // 🔄 frameIndex %= 8：将帧索引限制在 0~7 范围内
        //    💡 为什么定义：8帧循环动画需要将帧索引限制在有效范围内
        //    💡 为什么需要：8 帧循环动画，每帧高亮一个矩形
        //    💡 作用：实现 8 帧循环播放效果
        //    💡 使用时机：方法入口处立即取模
        frameIndex %= 8;

        // 📍 startX：高亮矩形左下角的 X 坐标（像素）
        //    💡 为什么定义：每个矩形需要唯一的水平位置来区分显示
        //    💡 为什么定义：确定裁剪区域的水平位置
        //    💡 作用：传入 glScissor() 设置裁剪起始 X
        //    💡 使用时机：根据 frameIndex 计算后传入 glScissor()
        int startX;
        // 📍 startY：高亮矩形左下角的 Y 坐标（像素）
        //    💡 为什么定义：每个矩形需要唯一的垂直位置来区分显示
        //    💡 为什么定义：确定裁剪区域的垂直位置
        //    💡 作用：传入 glScissor() 设置裁剪起始 Y
        //    💡 使用时机：根据 frameIndex 计算后传入 glScissor()
        int startY;

        // 🔍 if (frameIndex < 4)：判断当前帧在蛇形排列的上行还是下行
        // 💡 为什么判断：前 4 帧在上半屏从左到右，后 4 帧在下半屏从右到左
        // 💡 作用：根据帧号计算矩形的屏幕坐标
        // 💡 使用时机：每帧绘制前判断
        if (frameIndex < 4) {
            // 📍 GL 中 (0,0) 在左下角
            // 📍 startX = frameIndex * (WIDTH / 4)：上半屏从左到右排列
            // 💡 为什么计算：每个矩形宽度 = 屏幕宽度 / 4 = 80 像素
            // 💡 作用：实现从左到右的水平排列
            // 💡 使用时机：frameIndex < 4 时使用
            startX = frameIndex * (WIDTH / 4);
            // 📍 startY = HEIGHT / 2：上半屏的起始 Y 坐标
            // 💡 为什么设置：上半屏从屏幕中点开始
            // 💡 作用：将矩形定位在上半部分
            // 💡 使用时机：frameIndex < 4 时使用
            startY = HEIGHT / 2;
        // 🔍 else：frameIndex >= 4，下半屏从右到左排列
        } else {
            // 📍 startX = (7 - frameIndex) * (WIDTH / 4)：下半屏从右到左排列
            // 💡 为什么用 (7 - frameIndex)：实现反向排列，形成蛇形走位
            // 💡 作用：frameIndex=4 时 startX=3*80=240（最右边），frameIndex=7 时 startX=0（最左边）
            // 💡 使用时机：frameIndex >= 4 时使用
            startX = (7 - frameIndex) * (WIDTH / 4);
            // 📍 startY = 0：下半屏的起始 Y 坐标
            // 💡 为什么设置：下半屏从屏幕底部开始
            // 💡 作用：将矩形定位在下半部分
            // 💡 使用时机：frameIndex >= 4 时使用
            startY = 0;
        }

        // 🎨 glClearColor(R0/255, G0/255, B0/255, 1.0)：设置背景清除色为深绿色
        // 💡 为什么设置：未高亮区域显示为深绿色背景
        // 💡 作用：将 0~255 的 RGB 值归一化到 0.0~1.0（OpenGL 要求）
        // 💡 使用时机：绘制高亮矩形之前，先设置背景色
        GLES20.glClearColor(TEST_R0 / 255.0f, TEST_G0 / 255.0f, TEST_B0 / 255.0f, 1.0f);
        // 🧹 glClear(COLOR_BUFFER_BIT)：用深绿色清除整个屏幕
        // 💡 为什么调用：清除上一帧内容，填充深绿色背景
        // 💡 作用：将整个帧缓冲区填充为深绿色
        // 💡 使用时机：设置背景色之后立即清除
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        // ✂️ glEnable(GL_SCISSOR_TEST)：启用裁剪测试
        // 💡 为什么启用：只在指定矩形区域内绘制高亮色
        // 💡 作用：后续 glClear 只影响 glScissor 定义的区域
        // 💡 使用时机：绘制高亮矩形前启用
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
        // ✂️ glScissor(startX, startY, WIDTH/4, HEIGHT/2)：设置裁剪区域为 1/8 屏幕
        // 💡 为什么设置：限制高亮色只在当前帧对应的矩形内绘制
        // 💡 作用：定义矩形位置和大小（宽度=屏幕/4，高度=屏幕/2）
        // 💡 使用时机：启用裁剪测试后设置
        GLES20.glScissor(startX, startY, WIDTH / 4, HEIGHT / 2);
        // 🎨 glClearColor(R1/255, G1/255, B1/255, 1.0)：设置高亮清除色为粉紫色
        // 💡 为什么设置：高亮区域显示为醒目的粉紫色
        // 💡 作用：定义高亮矩形的颜色（R=236, G=50, B=186）
        // 💡 使用时机：设置裁剪区域后、清除之前
        GLES20.glClearColor(TEST_R1 / 255.0f, TEST_G1 / 255.0f, TEST_B1 / 255.0f, 1.0f);
        // 🧹 glClear(COLOR_BUFFER_BIT)：用粉紫色填充裁剪区域
        // 💡 为什么调用：将 1/8 区域填充为高亮色
        // 💡 作用：只在裁剪区域内生效，绘制高亮矩形
        // 💡 使用时机：设置高亮色之后立即清除
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        // ❌ glDisable(GL_SCISSOR_TEST)：禁用裁剪测试
        // 💡 为什么禁用：恢复全屏绘制模式，避免影响后续 GL 操作
        // 💡 作用：后续绘制不再受裁剪区域限制
        // 💡 使用时机：高亮矩形绘制完成后立即禁用
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
    }


    /**
     * Generates the presentation time for frame N, in nanoseconds.
     * <p>
     * First 8 frames at 8 fps, next 8 at 16fps, rest at 30fps.
     * ⏱️ 计算第 N 帧的呈现时间（纳秒）。
     *    前 8 帧 8fps，接下来 8 帧 16fps，其余 30fps（速度逐渐加快）。
     */
    private static long computePresentationTimeNsec(int frameIndex) {
        // 💰 ONE_BILLION：1 秒对应的纳秒数（10^9）
        //    💡 为什么定义：时间计算需要以纳秒为单位，需要常量进行单位转换
        //    💡 作用：计算帧的呈现时间戳时换算
        //    💡 使用时机：每次计算时间戳时使用
        final long ONE_BILLION = 1000000000;

        // ⏱️ time：累计经过的时间（纳秒）
        //    💡 为什么定义：多段帧率计算需要累计前面各段的总耗时
        //    💡 为什么定义：记录到当前帧开始时已经经过的总时间
        //    💡 作用：作为后续帧时间计算的基准
        //    💡 使用时机：在 else 分支中累加各段耗时
        long time;

        // 🔍 if (frameIndex < 8)：判断是否在前 8 帧范围内
        // 💡 为什么判断：前 8 帧以 8fps 播放（最慢），计算方式不同
        // 💡 作用：分段计算不同帧率下的时间戳
        // 💡 使用时机：每次计算时间戳时首先判断
        if (frameIndex < 8) {
            // 🐌 前 8 帧以 8fps 播放（最慢）
            // 💡 为什么计算：frameIndex * ONE_BILLION / 8 = 第 frameIndex 帧的时间
            // 💡 作用：前 8 帧总耗时 = 8 × (1/8) = 1 秒
            // 💡 使用时机：frameIndex < 8 时直接返回
            return frameIndex * ONE_BILLION / 8;
        // 🔍 else：frameIndex >= 8，进入第二段帧率计算
        } else {
            // ⏱️ time = ONE_BILLION：前 8 帧已经占了 1 秒，基准时间设为 1 秒
            // 💡 为什么设置：后续帧的时间需要加上前 8 帧的耗时
            // 💡 作用：作为第二段帧率计算的起始时间
            // 💡 使用时机：frameIndex >= 8 时设置
            time = ONE_BILLION;
            // 🔄 frameIndex -= 8：减去已处理的 8 帧，重新从 0 开始计数
            // 💡 为什么减去：将绝对帧号转换为段内相对帧号
            // 💡 作用：简化后续计算，只需关注当前段内的帧偏移
            // 💡 使用时机：设置基准时间后立即调整
            frameIndex -= 8;
        }

        // 🔍 if (frameIndex < 8)：判断是否在第二段（第 9~16 帧）范围内
        // 💡 为什么判断：第二段以 16fps 播放（中速），第三段以 30fps 播放
        // 💡 作用：分段处理不同帧率
        // 💡 使用时机：处理完第一段后判断
        if (frameIndex < 8) {
            // 🏃 第 9~16 帧以 16fps 播放（中速）
            // 💡 为什么计算：time + frameIndex * ONE_BILLION / 16 = 基准时间 + 段内偏移
            // 💡 作用：这 8 帧总耗时 = 8 × (1/16) = 0.5 秒
            // 💡 使用时机：frameIndex 在第二段时返回
            return time + frameIndex * ONE_BILLION / 16;
        // 🔍 else：frameIndex >= 8，进入第三段帧率计算
        } else {
            // ⏱️ time += ONE_BILLION / 2：又过了 0.5 秒（16fps × 8 帧 = 0.5s）
            // 💡 为什么累加：第三段帧率的时间需要加上前两段的总耗时
            // 💡 作用：更新基准时间为 1.5 秒
            // 💡 使用时机：frameIndex >= 8 时累加
            time += ONE_BILLION / 2;
            // 🔄 frameIndex -= 8：再减去已处理的 8 帧
            // 💡 为什么减去：将绝对帧号转换为第三段内的相对帧号
            // 💡 作用：简化第三段的计算
            // 💡 使用时机：累加时间后立即调整
            frameIndex -= 8;
        }

        // 🚀 第 17+ 帧以 30fps 播放（最快）
        // 💡 为什么计算：time + frameIndex * ONE_BILLION / 30 = 基准时间 + 段内偏移
        // 💡 作用：剩余帧以标准帧率播放，实现速度逐渐加快的效果
        // 💡 使用时机：处理完前两段后，计算剩余帧的时间戳
        return time + frameIndex * ONE_BILLION / 30;
    }
}
