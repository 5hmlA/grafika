package com.android.grafika;

import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import com.google.grafika.R;

/**
 * A demonstration of using Canvas to draw on a TextureView.  Based on TextureViewGLActivity.
 * <p>
 * Currently renders frames as fast as possible, without waiting for the consumer.
 * 
 * 🎨 使用Canvas在TextureView上绘制的演示
 * 💡 基于TextureViewGLActivity，但使用Canvas而不是OpenGL
 * 💡 尽可能快地渲染帧，不等待消费者
 */
public class TextureViewCanvasActivity extends Activity {
    private static final String TAG = MainActivity.TAG;

    private TextureView mTextureView;  // 🖼️ TextureView视图
    private Renderer mRenderer;        // 🧵 渲染线程

    /**
     * 🔧 Activity创建时调用
     * 💡 作用：初始化Canvas渲染线程和TextureView
     * 💡 参数savedInstanceState：Activity之前保存的状态数据
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 📝 打印创建日志
        Log.d(TAG, "onCreate");
        // 🔄 调用父类的onCreate方法
        // 💡 作用：执行标准的Activity创建流程
        super.onCreate(savedInstanceState);

        // 🧵 创建Canvas渲染线程实例
        // 💡 变量mRenderer：渲染线程对象，负责Canvas绘制
        // 💡 作用：在后台线程中执行Canvas动画渲染
        mRenderer = new Renderer();
        // ▶️ 启动渲染线程
        // 💡 作用：线程开始运行，等待SurfaceTexture可用
        mRenderer.start();

        // 🖼️ 设置Activity布局文件
        // 💡 作用：加载包含TextureView的XML布局
        setContentView(R.layout.activity_texture_view_canvas);
        // 🔍 查找TextureView控件
        // 💡 变量mTextureView：TextureView视图引用
        // 💡 作用：获取布局中的TextureView，用于Canvas渲染
        mTextureView = (TextureView) findViewById(R.id.canvasTextureView);
        // 📋 设置SurfaceTexture监听器
        // 💡 作用：监听SurfaceTexture的生命周期事件（可用/销毁等）
        mTextureView.setSurfaceTextureListener(mRenderer);
    }

    @Override
    protected void onResume() {
        // 🔄 调用父类onResume，执行标准Activity恢复流程
        super.onResume();
    }

    /**
     * 💀 Activity销毁时调用
     * 💡 作用：停止渲染线程，释放资源
     */
    @Override
    protected void onDestroy() {
        // 📝 打印销毁日志
        Log.d(TAG, "onDestroy");
        // 🔄 调用父类的onDestroy方法
        // 💡 作用：执行标准的Activity销毁流程
        super.onDestroy();
        // ⏹️ 通知渲染线程停止运行
        // 💡 作用：设置停止标志并唤醒线程，使其安全退出
        mRenderer.halt();
    }

    /**
     * 🧵 Canvas渲染线程类
     * 💡 处理Canvas渲染和SurfaceTexture回调
     */
    private static class Renderer extends Thread implements TextureView.SurfaceTextureListener {
        private Object mLock = new Object();        // 🔒 保护mSurfaceTexture和mDone
        private SurfaceTexture mSurfaceTexture;     // 🖼️ SurfaceTexture
        private boolean mDone;                      // 🔚 是否完成
        private int mWidth;                         // 📐 宽度
        private int mHeight;                        // 📐 高度

        public Renderer() {
            super("TextureViewCanvas Renderer");
        }

        /**
         * 🧵 线程入口点
         * 💡 作用：等待SurfaceTexture可用，然后开始Canvas渲染循环
         * 💡 与GL版本不同：使用Canvas而非OpenGL绘制
         */
        @Override
        public void run() {
            // 🔄 主循环：持续运行直到收到停止信号
            // 💡 作用：保持渲染线程活跃，处理多次SurfaceTexture生命周期
            while (true) {
                // 🖼️ 临时存储SurfaceTexture引用
                // 💡 变量surfaceTexture：局部引用，用于线程安全地获取SurfaceTexture
                // 💡 作用：从成员变量拷贝，避免在同步块外访问共享变量
                SurfaceTexture surfaceTexture = null;

                // 🔒 进入同步块，等待SurfaceTexture变为可用
                // 💡 作用：保护mSurfaceTexture和mDone的线程安全访问
                synchronized (mLock) {
                    // ⏳ 等待循环：直到SurfaceTexture可用或收到停止信号
                    // 💡 作用：避免忙等待，节省CPU资源
                    // 💡 条件：未完成(mDone=false) 且 SurfaceTexture为null时持续等待
                    while (!mDone && (surfaceTexture = mSurfaceTexture) == null) {
                        try {
                            // 💤 释放锁并等待通知
                            // 💡 作用：阻塞当前线程，直到其他线程调用notify()
                            mLock.wait();
                        } catch (InterruptedException ie) {
                            // ⚠️ 线程被中断时抛出运行时异常
                            // 💡 作用：将检查型异常转换为运行时异常
                            throw new RuntimeException(ie);
                        }
                    }
                    // 🔍 检查是否收到停止信号
                    // 💡 作用：判断是否应该退出渲染循环
                    // 💡 时机：从等待中唤醒后立即检查
                    if (mDone) {
                        // 🚪 跳出主循环，线程结束
                        break;
                    }
                }
                // 📝 记录获取到的SurfaceTexture信息
                Log.d(TAG, "Got surfaceTexture=" + surfaceTexture);

                // 🎬 执行Canvas动画渲染循环
                // 💡 作用：开始用Canvas绘制动画帧，直到SurfaceTexture被销毁
                doAnimation();
            }

            // 📝 记录线程退出
            Log.d(TAG, "Renderer thread exiting");
        }

        /**
         * 🎬 执行动画，尽可能快地渲染帧
         * 💡 使用Canvas绘制移动的红色方块，背景色渐变
         * 💡 支持全屏和部分更新两种模式
         */
        private void doAnimation() {
            // 📐 定义方块宽度（像素）
            // 💡 作用：控制动画方块的大小
            // 💡 时机：绘制方块和边界检测时使用
            final int BLOCK_WIDTH = 80;

            // 🚀 定义方块移动速度（像素/帧）
            // 💡 作用：控制方块移动的快慢
            // 💡 时机：更新方块位置时使用
            final int BLOCK_SPEED = 2;

            // 🎨 清除颜色值（灰度值，0-255）
            // 💡 作用：用于背景渐变效果，从黑色逐渐变亮
            // 💡 时机：每帧绘制前设置背景色
            int clearColor = 0;

            // 📍 方块X坐标（左上角）
            // 💡 作用：控制方块在屏幕上的水平位置
            // 💡 初始值：-BLOCK_WIDTH/2，使方块从屏幕左侧开始进入
            // 💡 时机：每帧更新位置
            int xpos = -BLOCK_WIDTH / 2;

            // 🚀 方块移动方向和速度
            // 💡 作用：正值向右移动，负值向左移动
            // 💡 初始值：BLOCK_SPEED，开始向右移动
            // 💡 时机：方块到达边界时反转方向
            int xdir = BLOCK_SPEED;

            // 🔧 创建Surface对象
            // 💡 作用：包装SurfaceTexture，提供Canvas绘制接口
            // 💡 时机：动画开始前创建
            Surface surface = null;

            // 🔒 同步获取SurfaceTexture并创建Surface
            // 💡 作用：线程安全地获取SurfaceTexture引用
            // 💡 时机：动画循环开始前
            synchronized (mLock) {
                // 🖼️ 获取当前SurfaceTexture引用
                SurfaceTexture surfaceTexture = mSurfaceTexture;

                // 🔍 检查SurfaceTexture是否有效
                // 💡 作用：避免在无效SurfaceTexture上创建Surface
                if (surfaceTexture == null) {
                    // 📝 记录错误信息
                    Log.d(TAG, "ST null on entry");
                    // 🚪 退出方法
                    return;
                }

                // 🏗️ 从SurfaceTexture创建Surface
                // 💡 作用：提供Canvas绘制的目标表面
                surface = new Surface(surfaceTexture);
            }

            // 🖌️ 创建画笔对象
            // 💡 作用：配置绘制样式和颜色
            // 💡 时机：Surface创建后立即创建
            Paint paint = new Paint();

            // 🔴 设置画笔颜色为红色
            // 💡 作用：定义方块的填充颜色
            paint.setColor(Color.RED);

            // 🎨 设置画笔样式为填充
            // 💡 作用：绘制实心方块而非边框
            paint.setStyle(Paint.Style.FILL);

            // 🔀 部分更新标志
            // 💡 作用：控制是否只更新屏幕的一部分
            // 💡 true：只更新方块移动区域（性能优化）
            // 💡 false：更新整个屏幕
            boolean partial = false;

            // 🔄 动画主循环
            // 💡 作用：持续渲染帧直到出错或Surface无效
            while (true) {
                // 📐 脏矩形区域（需要更新的区域）
                // 💡 作用：指定Canvas只更新该区域
                // 💡 null表示更新整个屏幕
                Rect dirty = null;

                // 🔍 根据partial标志决定是否使用脏矩形
                // 💡 作用：优化性能，只更新变化区域
                if (partial) {
                    // 📐 创建脏矩形（屏幕中间1/4区域）
                    // 💡 作用：只更新方块可能移动的区域
                    // 💡 范围：高度的3/8到5/8
                    dirty = new Rect(0, mHeight * 3 / 8, mWidth, mHeight * 5 / 8);
                }

                // 🔒 锁定Canvas用于绘制
                // 💡 作用：获取绘制上下文
                // 💡 参数：dirty指定更新区域，null表示全屏
                // 💡 时机：每帧绘制前
                Canvas canvas = surface.lockCanvas(dirty);

                // 🔍 检查Canvas是否有效
                // 💡 作用：处理lockCanvas失败的情况
                if (canvas == null) {
                    // 📝 记录错误
                    Log.d(TAG, "lockCanvas() failed");
                    // 🚪 退出循环
                    break;
                }

                // 🔒 使用try-finally确保Canvas被解锁
                try {
                    // 🔍 检查尺寸是否匹配
                    // 💡 作用：检测Surface尺寸是否与预期不符
                    // 💡 时机：调试时使用
                    if (canvas.getWidth() != mWidth || canvas.getHeight() != mHeight) {
                        Log.d(TAG, "WEIRD: width/height mismatch");
                    }

                    // 🖼️ 绘制背景（清除颜色）
                    // 💡 作用：用灰度颜色填充背景
                    // 💡 参数：R, G, B（相同值形成灰度）
                    canvas.drawRGB(clearColor, clearColor, clearColor);

                    // 🔲 绘制红色方块
                    // 💡 作用：在屏幕上绘制移动的方块
                    // 💡 参数：left, top, right, bottom（方块的四条边）
                    // 💡 位置：xpos到xpos+BLOCK_WIDTH，高度1/4到3/4
                    canvas.drawRect(xpos, mHeight / 4, xpos + BLOCK_WIDTH, mHeight * 3 / 4, paint);
                } finally {
                    // 🔓 解锁Canvas并提交绘制结果
                    // 💡 作用：无论绘制是否成功，都必须解锁
                    try {
                        surface.unlockCanvasAndPost(canvas);
                    } catch (IllegalArgumentException iae) {
                        // ⚠️ 处理解锁失败的情况
                        // 💡 作用：捕获并记录解锁异常
                        Log.d(TAG, "unlockCanvasAndPost failed: " + iae.getMessage());
                        // 🚪 退出循环
                        break;
                    }
                }

                // 🔄 更新背景色（渐变效果）
                // 💡 作用：使背景色逐渐变亮
                // 💡 增量：4，约64帧从0变到255
                clearColor += 4;

                // 🔄 背景色超过255时重置为0
                // 💡 作用：实现循环渐变效果
                if (clearColor > 255) {
                    clearColor = 0;
                    // 🔀 切换部分更新模式
                    // 💡 作用：在全屏更新和部分更新之间切换
                    partial = !partial;
                }

                // 📍 更新方块X坐标
                // 💡 作用：移动方块位置
                xpos += xdir;

                // 🔍 边界检测：检查方块是否到达屏幕边缘
                // 💡 作用：判断是否需要反转移动方向
                // 💡 条件：左边界(-BLOCK_WIDTH/2) 或 右边界(mWidth-BLOCK_WIDTH/2)
                if (xpos <= -BLOCK_WIDTH / 2 || xpos >= mWidth - BLOCK_WIDTH / 2) {
                    // 📝 记录方向变化
                    Log.d(TAG, "change direction");
                    // 🔄 反转移动方向
                    // 💡 作用：使方块在边界处反弹
                    xdir = -xdir;
                }
            }

            // 🗑️ 释放Surface资源
            // 💡 作用：清理Surface占用的资源
            // 💡 时机：动画循环结束后
            surface.release();
        }

        /** 🔚 告诉线程停止运行 */
        public void halt() {
            // 🔒 进入同步块，安全修改停止标志
            // 💡 作用：保护mDone变量的线程安全访问
            synchronized (mLock) {
                // ✅ 设置停止标志为true
                // 💡 作用：通知run()方法退出主循环
                mDone = true;
                // 🔔 唤醒等待中的渲染线程
                // 💡 作用：解除run()中mLock.wait()的阻塞状态
                mLock.notify();
            }
        }

        /**
         * 🖼️ SurfaceTexture可用时调用（UI线程）
         * 💡 作用：TextureView的SurfaceTexture就绪时通知渲染线程
         * 💡 参数st：新创建的SurfaceTexture对象
         * 💡 参数width/height：SurfaceTexture的像素尺寸
         */
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
            // 📝 记录SurfaceTexture可用信息和尺寸
            Log.d(TAG, "onSurfaceTextureAvailable(" + width + "x" + height + ")");
            // 📐 保存SurfaceTexture的宽度
            // 💡 变量mWidth：渲染表面的像素宽度
            // 💡 作用：用于Canvas绘制和脏矩形区域计算
            mWidth = width;
            // 📐 保存SurfaceTexture的高度
            // 💡 变量mHeight：渲染表面的像素高度
            // 💡 作用：用于Canvas绘制和脏矩形区域计算
            mHeight = height;
            // 🔒 进入同步块，安全地将SurfaceTexture传递给渲染线程
            synchronized (mLock) {
                // 🖼️ 保存SurfaceTexture引用
                // 💡 作用：供run()方法获取并用于Canvas渲染
                mSurfaceTexture = st;
                // 🔔 唤醒等待SurfaceTexture的渲染线程
                // 💡 作用：解除run()中mLock.wait()的阻塞，开始渲染
                mLock.notify();
            }
        }

        /**
         * 📐 SurfaceTexture尺寸变化时调用（UI线程）
         * 💡 作用：更新渲染表面的尺寸参数
         * 💡 参数st：SurfaceTexture对象
         * 💡 参数width/height：新的像素尺寸
         */
        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
            // 📝 记录尺寸变化信息
            Log.d(TAG, "onSurfaceTextureSizeChanged(" + width + "x" + height + ")");
            // 📐 更新宽度
            // 💡 作用：同步新的Surface宽度，用于Canvas绘制
            mWidth = width;
            // 📐 更新高度
            // 💡 作用：同步新的Surface高度，用于Canvas绘制
            mHeight = height;
        }

        /**
         * 🖼️ SurfaceTexture销毁时调用（UI线程）
         * 💡 作用：通知渲染线程SurfaceTexture已被销毁
         * 💡 参数st：即将被销毁的SurfaceTexture
         * 💡 返回值：true表示允许TextureView释放SurfaceTexture
         */
        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
            // 📝 记录SurfaceTexture销毁事件
            Log.d(TAG, "onSurfaceTextureDestroyed");
            // 🔒 进入同步块，安全地清除SurfaceTexture引用
            synchronized (mLock) {
                // 🖼️ 将SurfaceTexture设为null
                // 💡 作用：通知run()方法SurfaceTexture已被销毁，退出动画循环
                mSurfaceTexture = null;
            }
            // 📤 返回true，允许TextureView释放SurfaceTexture
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture st) {}
    }
}
