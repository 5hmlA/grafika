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

package com.android.grafika.gles;

import android.graphics.Bitmap;
import android.opengl.EGL14;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.util.Log;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Common base class for EGL surfaces.
 * <p>
 * There can be multiple surfaces associated with a single context.
 * 
 * 🖼️ EGL Surface基类
 * 💡 一个上下文可以关联多个Surface
 */
public class EglSurfaceBase {
    protected static final String TAG = GlUtil.TAG;

    protected EglCore mEglCore;                          // 🎮 EGL核心对象
    private EGLSurface mEGLSurface = EGL14.EGL_NO_SURFACE;  // 🖼️ EGL Surface
    private int mWidth = -1;                             // 📐 宽度
    private int mHeight = -1;                            // 📐 高度

    protected EglSurfaceBase(EglCore eglCore) {
        mEglCore = eglCore;
    }

    /**
     * Creates a window surface.
     * 
     * 🖼️ 创建窗口Surface
     * @param surface Surface或SurfaceTexture
     */
    public void createWindowSurface(Object surface) {
        // ⚠️ mEGLSurface检查：验证Surface是否已经创建
        // 💡 为什么检查：一个EglSurfaceBase实例只能关联一个EGL Surface
        // 💡 作用：防止重复创建导致资源泄漏或状态混乱
        // 💡 使用时机：在创建Surface之前进行前置校验
        if (mEGLSurface != EGL14.EGL_NO_SURFACE) {
            throw new IllegalStateException("surface already created");
        }
        // 🖼️ mEGLSurface：保存EglCore创建的窗口Surface（EGLSurface类型）
        // 💡 为什么赋值：将EglCore返回的EGLSurface保存到成员变量供后续操作使用
        // 💡 作用：作为后续makeCurrent、swapBuffers等操作的目标表面
        // 💡 使用时机：创建后通过makeCurrent()绑定，然后进行渲染
        mEGLSurface = mEglCore.createWindowSurface(surface);
    }

    /**
     * Creates an off-screen surface.
     * 
     * 🖼️ 创建离屏Surface
     */
    public void createOffscreenSurface(int width, int height) {
        // ⚠️ mEGLSurface检查：验证Surface是否已经创建
        // 💡 为什么检查：一个EglSurfaceBase实例只能关联一个EGL Surface
        // 💡 作用：防止重复创建导致资源泄漏或状态混乱
        // 💡 使用时机：在创建离屏Surface之前进行前置校验
        if (mEGLSurface != EGL14.EGL_NO_SURFACE) {
            throw new IllegalStateException("surface already created");
        }
        // 🖼️ mEGLSurface：保存EglCore创建的离屏Surface（EGLSurface类型）
        // 💡 为什么赋值：将EglCore返回的Pbuffer EGLSurface保存到成员变量
        // 💡 作用：作为后续离屏渲染操作的目标表面
        // 💡 使用时机：通过makeCurrent()绑定后进行离屏渲染
        mEGLSurface = mEglCore.createOffscreenSurface(width, height);
        // 📐 mWidth：记录Surface宽度（int类型）
        // 💡 为什么赋值：离屏Surface的尺寸在创建时确定，需要缓存以供后续查询
        // 💡 作用：存储Surface的像素宽度，getWidth()会直接返回此值
        // 💡 使用时机：在getWidth()查询或渲染计算时使用
        mWidth = width;
        // 📐 mHeight：记录Surface高度（int类型）
        // 💡 为什么赋值：离屏Surface的尺寸在创建时确定，需要缓存以供后续查询
        // 💡 作用：存储Surface的像素高度，getHeight()会直接返回此值
        // 💡 使用时机：在getHeight()查询或渲染计算时使用
        mHeight = height;
    }

    /** 📐 获取Surface宽度 */
    public int getWidth() {
        // 🔍 如果mWidth未设置（<0），则向EGL查询实际宽度
        // 💡 窗口Surface的尺寸可能随时变化，需要动态查询
        if (mWidth < 0) {
            return mEglCore.querySurface(mEGLSurface, EGL14.EGL_WIDTH);
        } else {
            // ✅ 返回缓存的宽度值（离屏Surface在创建时已设置）
            return mWidth;
        }
    }

    /** 📐 获取Surface高度 */
    public int getHeight() {
        // 🔍 如果mHeight未设置（<0），则向EGL查询实际高度
        // 💡 窗口Surface的尺寸可能随时变化，需要动态查询
        if (mHeight < 0) {
            return mEglCore.querySurface(mEGLSurface, EGL14.EGL_HEIGHT);
        } else {
            // ✅ 返回缓存的高度值（离屏Surface在创建时已设置）
            return mHeight;
        }
    }

    /** 🗑️ 释放EGL Surface */
    public void releaseEglSurface() {
        mEglCore.releaseSurface(mEGLSurface);
        mEGLSurface = EGL14.EGL_NO_SURFACE;
        mWidth = mHeight = -1;
    }

    /** 🎯 使当前EGL上下文和Surface成为当前 */
    public void makeCurrent() {
        mEglCore.makeCurrent(mEGLSurface);
    }

    /** 🎯 使当前Surface用于绘制，指定Surface用于读取 */
    public void makeCurrentReadFrom(EglSurfaceBase readSurface) {
        mEglCore.makeCurrent(mEGLSurface, readSurface.mEGLSurface);
    }

    /**
     * Calls eglSwapBuffers.  Use this to "publish" the current frame.
     *
     * 🔄 交换缓冲区，发布当前帧
     * @return 成功返回true
     */
    public boolean swapBuffers() {
        // 🔄 result：缓冲区交换是否成功（boolean类型）
        // 💡 为什么定义：需要判断swapBuffers操作是否成功并处理失败情况
        // 💡 作用：存储mEglCore.swapBuffers()的返回值
        // 💡 使用时机：在判断是否打印警告和返回给调用者时使用
        boolean result = mEglCore.swapBuffers(mEGLSurface);
        // ⚠️ 失败检查：交换失败时打印警告日志（不抛异常）
        // 💡 为什么不抛异常：缓冲区交换失败可能是暂时的（如surface被销毁）
        // 💡 作用：记录问题但允许调用者决定如何处理（而不是强制崩溃）
        // 💡 使用时机：result为false时打印警告，便于调试
        if (!result) {
            Log.d(TAG, "WARNING: swapBuffers() failed");
        }
        // ✅ 返回交换结果给调用者
        // 💡 为什么返回：调用者可能需要根据交换结果决定后续操作
        // 💡 作用：将操作状态传递给上层，让调用者能感知渲染失败
        return result;
    }

    /**
     * Sends the presentation time stamp to EGL.
     *
     * ⏰ 设置呈现时间戳
     * @param nsecs 时间戳（纳秒）
     */
    public void setPresentationTime(long nsecs) {
        mEglCore.setPresentationTime(mEGLSurface, nsecs);
    }

    /**
     * Saves the EGL surface to a file.
     * 
     * 💾 将EGL Surface保存为PNG文件
     */
    public void saveFrame(File file) throws IOException {
        // ⚠️ isCurrent检查：确保当前EGL上下文和Surface是活跃的
        // 💡 为什么检查：glReadPixels只能读取当前绑定的帧缓冲区
        // 💡 作用：防止在未绑定的上下文中执行读取操作导致未定义行为
        // 💡 使用时机：在调用glReadPixels之前进行前置校验
        if (!mEglCore.isCurrent(mEGLSurface)) {
            throw new RuntimeException("Expected EGL context/surface is not current");
        }

        // 📝 filename：输出文件的路径字符串（String类型）
        // 💡 为什么定义：FileOutputStream构造函数需要字符串路径
        // 💡 作用：将File对象转换为字符串路径，供后续文件写入使用
        // 💡 使用时机：在创建BufferedOutputStream时传入文件路径
        String filename = file.toString();

        // 📐 width：Surface的像素宽度（int类型）
        // 💡 为什么定义：需要知道尺寸来分配正确大小的像素缓冲区
        // 💡 作用：存储getWidth()返回值，供缓冲区分配和Bitmap创建使用
        // 💡 使用时机：在ByteBuffer.allocateDirect和Bitmap.createBitmap中使用
        int width = getWidth();
        // 📐 height：Surface的像素高度（int类型）
        // 💡 为什么定义：需要知道尺寸来分配正确大小的像素缓冲区
        // 💡 作用：存储getHeight()返回值，供缓冲区分配和Bitmap创建使用
        // 💡 使用时机：在ByteBuffer.allocateDirect和Bitmap.createBitmap中使用
        int height = getHeight();
        // 📦 buf：直接字节缓冲区，用于从GPU读取像素数据（ByteBuffer类型）
        // 💡 为什么定义：glReadPixels需要一个缓冲区来接收GPU渲染的像素数据
        // 💡 为什么用allocateDirect：直接缓冲区避免Java堆和本地内存之间的数据拷贝
        // 💡 容量计算：width * height * 4（RGBA每像素4字节）
        // 💡 作用：临时存储从GPU帧缓冲区读取的RGBA像素数据
        // 💡 使用时机：在glReadPixels读取像素和Bitmap.copyPixelsFromBuffer时使用
        ByteBuffer buf = ByteBuffer.allocateDirect(width * height * 4);
        // 🔧 ByteOrder.LITTLE_ENDIAN：设置字节序为小端序
        // 💡 为什么设置：Android的Bitmap.ARGB_8888格式使用小端字节序
        // 💡 作用：确保从缓冲区读取像素数据时字节顺序正确
        // 💡 使用时机：在glReadPixels填充缓冲区之前设置
        buf.order(ByteOrder.LITTLE_ENDIAN);
        // 🖼️ glReadPixels：从当前绑定的帧缓冲区读取所有像素到buf
        // 💡 为什么调用：将GPU渲染结果拷贝到CPU可访问的内存中
        // 💡 参数说明：
        //    - 0, 0：读取起始坐标（左下角）
        //    - width, height：读取区域尺寸
        //    - GL_RGBA：像素格式（红、绿、蓝、透明度各1字节）
        //    - GL_UNSIGNED_BYTE：每个通道的数据类型
        //    - buf：目标缓冲区
        // 💡 使用时机：在渲染完成后、保存为文件之前
        GLES20.glReadPixels(0, 0, width, height,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf);
        // ⚠️ checkGlError：检查glReadPixels是否出错
        // 💡 为什么检查：GL操作失败不会抛Java异常，需要主动检查
        // 💡 作用：确保像素读取成功，失败时抛出明确的异常信息
        GlUtil.checkGlError("glReadPixels");
        // 🔄 buf.rewind()：重置缓冲区位置到0
        // 💡 为什么调用：glReadPixels会推进缓冲区位置，需要重置后才能从头读取
        // 💡 作用：让缓冲区准备好被Bitmap.copyPixelsFromBuffer()读取
        // 💡 使用时机：在glReadPixels之后、copyPixelsFromBuffer之前
        buf.rewind();

        // 📦 bos：缓冲输出流，用于高效写入PNG文件（BufferedOutputStream类型）
        // 💡 为什么定义：BufferedOutputStream提供8KB缓冲区，减少磁盘IO次数
        // 💡 初始化为null：因为需要在finally块中关闭，所以必须在try外部声明
        // 💡 作用：包装FileOutputStream，将Bitmap的PNG数据高效写入文件
        // 💡 使用时机：在Bitmap.compress()写入PNG数据时使用
        BufferedOutputStream bos = null;
        try {
            // 📂 BufferedOutputStream(FileOutputStream)：创建缓冲文件输出流
            // 💡 为什么包装：直接使用FileOutputStream每次写入都触发磁盘IO
            // 💡 作用：提供缓冲写入能力，大幅提高文件写入性能
            // 💡 使用时机：在try块开始时打开，用于后续的Bitmap压缩写入
            bos = new BufferedOutputStream(new FileOutputStream(filename));
            // 🖼️ bmp：从像素数据创建的Bitmap对象（Bitmap类型）
            // 💡 为什么定义：需要将RGBA像素数据转换为Android可处理的Bitmap格式
            // 💡 Config.ARGB_8888：每个像素4字节（A=8位, R=8位, G=8位, B=8位）
            // 💡 作用：作为像素数据的中间载体，从GL格式转换为可压缩为PNG的格式
            // 💡 使用时机：在copyPixelsFromBuffer接收数据和compress写入文件时使用
            Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            // 📝 copyPixelsFromBuffer：将GL像素数据复制到Bitmap
            // 💡 为什么调用：glReadPixels输出的是RGBA格式字节流，需要转换为Bitmap对象
            // 💡 作用：将buf中的原始像素数据填充到Bitmap的像素数组中
            // 💡 使用时机：在Bitmap创建后、压缩为PNG之前
            bmp.copyPixelsFromBuffer(buf);
            // 💾 compress：将Bitmap压缩为PNG格式（质量90%）并写入文件流
            // 💡 为什么用PNG：PNG支持无损压缩，适合保存渲染截图
            // 💡 参数90：压缩质量（PNG是无损的，此参数被忽略但API要求传入）
            // 💡 作用：将Bitmap的像素数据编码为PNG格式并输出到文件
            // 💡 使用时机：在像素数据复制到Bitmap之后
            bmp.compress(Bitmap.CompressFormat.PNG, 90, bos);
            // 🗑️ recycle：释放Bitmap占用的原生内存
            // 💡 为什么调用：Bitmap使用原生内存，不recycle可能导致内存泄漏
            // 💡 作用：标记Bitmap不再使用，释放其占用的像素数据内存
            // 💡 使用时机：在compress完成、Bitmap不再需要时立即调用
            bmp.recycle();
        } finally {
            // 🔒 finally块：确保输出流被关闭，防止文件句柄泄漏
            // 💡 为什么在finally：无论try块是否抛异常，都必须关闭文件流
            // 💡 null检查：防止在打开文件流之前就抛异常导致bos仍为null
            // 💡 使用时机：try块结束或发生异常时自动执行
            if (bos != null) bos.close();
        }
        // 📝 Log.d：记录保存成功的日志信息
        // 💡 为什么输出：确认帧保存成功，显示文件尺寸和路径
        // 💡 作用：便于调试时确认保存操作是否执行、文件位置是否正确
        // 💡 使用时机：在文件写入完成后输出
        Log.d(TAG, "Saved " + width + "x" + height + " frame as '" + filename + "'");
    }
}
