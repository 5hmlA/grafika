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

import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import com.google.grafika.R;

/**
 * Show color bars.
 * 
 * 🌈 显示RGB彩条测试图案
 */
public class ColorBarActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = MainActivity.TAG;

    private SurfaceView mSurfaceView;  // 🖼️ SurfaceView

    // 🎨 颜色名称
    private static final String[] COLOR_NAMES = {
        "black", "red", "green", "yellow", "blue", "magenta", "cyan", "white"
    };

    /**
     * 🔧 Activity创建时调用
     * 💡 作用：初始化SurfaceView并注册回调监听
     * 💡 时机：Activity首次创建时由系统调用
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 🔄 调用父类onCreate，执行标准Activity创建流程
        // 💡 作用：恢复之前保存的Activity状态
        // 💡 时机：方法入口处必须首先调用
        super.onCreate(savedInstanceState);

        // 🖼️ 加载布局文件activity_color_bar.xml
        // 💡 作用：将XML布局渲染到屏幕上，包含SurfaceView控件
        // 💡 时机：父类onCreate之后立即调用
        setContentView(R.layout.activity_color_bar);

        // 🔍 从布局中查找SurfaceView控件
        // 💡 变量mSurfaceView：SurfaceView视图引用
        // 💡 作用：保存SurfaceView引用，用于后续绑定回调和绘制彩条
        // 💡 时机：布局加载完成后获取
        mSurfaceView = (SurfaceView) findViewById(R.id.colorBarSurfaceView);

        // 📋 注册Surface生命周期回调监听器
        // 💡 作用：当Surface创建/变化/销毁时通知本Activity
        // 💡 参数this：本类实现了SurfaceHolder.Callback接口
        // 💡 时机：获取SurfaceView后立即注册
        mSurfaceView.getHolder().addCallback(this);

        // 🎨 设置Surface像素格式为RGBA_8888
        // 💡 作用：确保Surface支持32位真彩色+Alpha通道
        // 💡 原因：彩条需要精确的RGB颜色还原
        // 💡 时机：注册回调后设置，确保创建Surface时使用此格式
        mSurfaceView.getHolder().setFormat(PixelFormat.RGBA_8888);
    }

    /**
     * 🖼️ Surface创建时调用
     * 💡 当SurfaceView的Surface首次创建时触发
     * 💡 作用：记录Surface创建事件，实际绘制在surfaceChanged中进行
     *
     * @param holder 创建的SurfaceHolder对象
     */
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        // 📝 记录Surface创建日志（为什么：便于调试Surface生命周期问题）
        // 💡 作用：输出"surfaceCreated"事件到Logcat
        // ⏰ 使用时机：Surface首次创建时调用
        Log.v(TAG, "surfaceCreated holder=" + holder);
    }

    /**
     * 🖼️ Surface变化时调用（绘制彩条）
     * 💡 当Surface尺寸或格式改变时触发，此时Surface已就绪可以绘制
     * 💡 作用：记录Surface变化信息并触发彩条绘制
     *
     * @param holder SurfaceHolder对象
     * @param format 像素格式
     * @param width 新的Surface宽度（像素）
     * @param height 新的Surface高度（像素）
     */
    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // 📝 记录Surface变化日志（为什么：便于调试Surface生命周期和尺寸问题）
        // 💡 作用：输出格式和尺寸信息到Logcat
        // ⏰ 使用时机：Surface尺寸或格式变化时首先记录
        Log.v(TAG, "surfaceChanged fmt=" + format + " size=" + width + "x" + height);
        // 🎨 绘制彩条（为什么：Surface变化后需要重新绘制）
        // 💡 作用：调用drawColorBars在Surface上绘制8个RGB彩条
        // ⏰ 使用时机：记录日志后立即绘制
        drawColorBars(holder.getSurface());
    }

    /**
     * 🖼️ Surface销毁时调用
     * 💡 当SurfaceView的Surface即将被销毁时触发
     * 💡 作用：记录Surface销毁事件，清理绘制资源
     *
     * @param holder 即将销毁的SurfaceHolder对象
     */
    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        // 📝 记录Surface销毁日志（为什么：便于调试Surface生命周期问题）
        // 💡 作用：输出"Surface destroyed"事件到Logcat
        // ⏰ 使用时机：Surface即将销毁时调用
        Log.v(TAG, "Surface destroyed holder=" + holder);
    }

    /**
     * 🎨 绘制彩条和文字标签
     * 💡 在Surface上绘制8个标准RGB彩条，每个彩条显示对应颜色名称
     * 💡 彩条顺序：黑、红、绿、黄、蓝、品红、青、白（标准SMPTE彩条）
     */
    private void drawColorBars(Surface surface) {
        // 🖼️ 获取Canvas用于绘制，锁定整个Surface区域
        // 💡 作用：获取绘制上下文，用于在Surface上绘制图形
        // 💡 时机：绘制前必须获取，绘制后必须解锁
        Canvas canvas = surface.lockCanvas(null);
        try {
            // 📐 获取画布宽度，用于计算彩条位置和大小
            // 💡 作用：存储Surface的像素宽度
            // 💡 时机：后续计算彩条宽度时使用
            int width = canvas.getWidth();

            // 📐 获取画布高度，用于计算彩条位置和大小
            // 💡 作用：存储Surface的像素高度
            // 💡 时机：后续计算彩条高度和文字位置时使用
            int height = canvas.getHeight();

            // 📏 取宽高中较小值，用于自适应文字大小
            // 💡 作用：确保文字在任何屏幕比例下都清晰可见
            // 💡 时机：设置文字大小时使用
            int least = Math.min(width, height);

            // 📝 记录绘制尺寸，便于调试
            Log.d(TAG, "Drawing color bars at " + width + "x" + height);

            // 🖌️ 创建文字画笔，用于绘制颜色名称标签
            // 💡 作用：配置文字绘制的字体、大小、抗锯齿等属性
            // 💡 时机：在彩条上绘制颜色名称时使用
            Paint textPaint = new Paint();

            // 🔤 获取默认字体，保持系统字体风格
            // 💡 作用：设置文字的字体类型
            // 💡 时机：画笔创建后立即设置
            Typeface typeface = Typeface.defaultFromStyle(Typeface.NORMAL);
            textPaint.setTypeface(typeface);

            // 📏 设置文字大小为屏幕短边的1/20，确保自适应
            // 💡 作用：根据屏幕尺寸动态调整文字大小
            // 💡 时机：设置字体后立即设置
            textPaint.setTextSize(least / 20);

            // ✨ 开启抗锯齿，使文字边缘更平滑
            // 💡 作用：提高文字渲染质量
            // 💡 时机：文字画笔配置的最后一步
            textPaint.setAntiAlias(true);

            // 🎨 创建矩形画笔，用于绘制彩条背景
            // 💡 作用：配置矩形填充的颜色
            // 💡 时机：在循环中为每个彩条设置不同颜色
            Paint rectPaint = new Paint();

            // 🌈 循环绘制8个彩条
            // 💡 作用：绘制标准RGB彩条图案（3位二进制编码颜色）
            // 💡 时机：获取Canvas后立即绘制
            for (int i = 0; i < 8; i++) {
                // 🎨 初始化颜色为黑色（带Alpha通道）
                // 💡 作用：彩条颜色的基础值，0xff000000表示完全不透明
                // 💡 时机：每个彩条迭代开始时
                int color = 0xff000000;

                // 🔴 如果第0位为1，添加红色分量
                // 💡 作用：通过位运算生成RGB颜色（位0=红）
                // 💡 时机：颜色计算时
                if ((i & 0x01) != 0) color |= 0x00ff0000;  // Red

                // 🟢 如果第1位为1，添加绿色分量
                // 💡 作用：通过位运算生成RGB颜色（位1=绿）
                // 💡 时机：颜色计算时
                if ((i & 0x02) != 0) color |= 0x0000ff00;  // Green

                // 🔵 如果第2位为1，添加蓝色分量
                // 💡 作用：通过位运算生成RGB颜色（位2=蓝）
                // 💡 时机：颜色计算时
                if ((i & 0x04) != 0) color |= 0x000000ff;  // Blue

                // 🎨 设置画笔颜色
                // 💡 作用：应用计算出的RGB颜色到画笔
                // 💡 时机：颜色计算完成后
                rectPaint.setColor(color);

                // 📐 计算每个彩条的宽度（总宽度的1/8）
                // 💡 作用：将屏幕宽度平均分成8份
                // 💡 时机：绘制每个彩条前计算
                float sliceWidth = width / 8;

                // 🖼️ 绘制矩形彩条
                // 💡 作用：在屏幕上绘制当前颜色的彩条
                // 💡 时机：颜色设置完成后绘制
                canvas.drawRect(sliceWidth * i, 0, sliceWidth * (i+1), height, rectPaint);
            }

            // 🩶 绘制灰色条（50%灰度），用于检查色彩还原
            // 💡 作用：添加灰色条用于显示器校准和色彩测试
            // 💡 时机：彩条绘制完成后
            rectPaint.setColor(0x80808080);

            // 📏 计算每个水平条的高度（总高度的1/8）
            // 💡 作用：用于定位灰色条的垂直位置
            // 💡 时机：绘制灰色条前计算
            float sliceHeight = height / 8;

            // 🖼️ 绘制灰色条（位于第6个水平区域）
            // 💡 作用：在彩条下方绘制灰色测试条
            // 💡 时机：灰色画笔设置完成后
            canvas.drawRect(0, sliceHeight * 6, width, sliceHeight * 7, rectPaint);

            // 📝 为每个彩条绘制颜色名称标签
            // 💡 作用：在彩条上显示对应的颜色名称
            // 💡 时机：彩条绘制完成后
            for (int i = 0; i < 8; i++) {
                // 🏷️ 绘制带轮廓的文字标签
                // 💡 作用：显示颜色名称，位置根据彩条索引计算
                // 💡 参数：颜色名称、X坐标（彩条起始+偏移）、Y坐标（交替显示在不同高度）
                drawOutlineText(canvas, textPaint, COLOR_NAMES[i],
                        (width / 8) * i + 4, (height / 8) * ((i & 1) + 1));
            }
        } finally {
            // 🔓 解锁Canvas并发布内容到Surface
            // 💡 作用：提交绘制结果到屏幕显示
            // 💡 时机：无论绘制是否成功，都必须解锁
            surface.unlockCanvasAndPost(canvas);
        }
    }

    /**
     * 🖌️ 绘制带轮廓的文字
     * 💡 通过在文字周围绘制黑色阴影，再在中心绘制白色文字实现轮廓效果
     * 💡 作用：提高文字在任何背景颜色下的可读性
     *
     * @param canvas 画布对象，用于绘制文字
     * @param textPaint 文字画笔，颜色会被方法内部修改
     * @param str 要绘制的文字内容
     * @param x 文字的X坐标（左下角基准点）
     * @param y 文字的Y坐标（左下角基准点）
     */
    private static void drawOutlineText(Canvas canvas, Paint textPaint, String str,
            float x, float y) {
        // 🖤 设置画笔颜色为黑色（用于绘制轮廓）
        // 💡 作用：黑色轮廓让白色文字在亮色和暗色背景上都清晰可见
        // 💡 时机：绘制轮廓前设置
        textPaint.setColor(0xff000000);  // 黑色轮廓

        // ↗️ 在文字左侧绘制黑色副本
        // 💡 作用：形成文字左边缘的黑色阴影
        // 💡 偏移：X-1像素
        canvas.drawText(str, x-1, y, textPaint);

        // ↗️ 在文字右侧绘制黑色副本
        // 💡 作用：形成文字右边缘的黑色阴影
        // 💡 偏移：X+1像素
        canvas.drawText(str, x+1, y, textPaint);

        // ⬆️ 在文字上方绘制黑色副本
        // 💡 作用：形成文字上边缘的黑色阴影
        // 💡 偏移：Y-1像素
        canvas.drawText(str, x, y-1, textPaint);

        // ⬇️ 在文字下方绘制黑色副本
        // 💡 作用：形成文字下边缘的黑色阴影
        // 💡 偏移：Y+1像素
        canvas.drawText(str, x, y+1, textPaint);

        // ↖️ 在文字左上角绘制黑色副本
        // 💡 作用：填充对角线方向的间隙
        // 💡 偏移：X-0.7, Y-0.7像素（约√2/2）
        canvas.drawText(str, x-0.7f, y-0.7f, textPaint);

        // ↗️ 在文字右上角绘制黑色副本
        // 💡 作用：填充对角线方向的间隙
        canvas.drawText(str, x+0.7f, y-0.7f, textPaint);

        // ↙️ 在文字左下角绘制黑色副本
        // 💡 作用：填充对角线方向的间隙
        canvas.drawText(str, x-0.7f, y+0.7f, textPaint);

        // ↘️ 在文字右下角绘制黑色副本
        // 💡 作用：填充对角线方向的间隙
        canvas.drawText(str, x+0.7f, y+0.7f, textPaint);

        // 🤍 设置画笔颜色为白色（用于绘制主文字）
        // 💡 作用：白色文字在黑色轮廓上清晰显示
        // 💡 时机：所有轮廓绘制完成后设置
        textPaint.setColor(0xffffffff);  // 白色文字

        // ✍️ 在中心位置绘制白色主文字
        // 💡 作用：叠加在黑色轮廓上，形成完整的带轮廓文字效果
        // 💡 时机：最后绘制，覆盖部分轮廓形成最终效果
        canvas.drawText(str, x, y, textPaint);
    }
}
