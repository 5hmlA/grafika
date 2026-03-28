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

package com.android.grafika.gles;

import android.opengl.Matrix;

/**
 * This class essentially represents a viewport-sized sprite that will be rendered with
 * a texture, usually from an external source like the camera or video decoder.
 * 
 * 🎨 这个类本质上代表一个视口大小的精灵（Sprite），将使用纹理进行渲染
 * 📹 纹理通常来自外部源，如摄像头或视频解码器
 * 💡 全屏矩形用于将纹理渲染到整个屏幕
 */
public class FullFrameRect {
    // 📐 mRectDrawable：全屏矩形的几何数据
    // 💡 使用预制的全屏矩形顶点数据
    private final Drawable2d mRectDrawable = new Drawable2d(Drawable2d.Prefab.FULL_RECTANGLE);
    
    // 🎨 mProgram：着色器程序，用于渲染纹理
    // 💡 包含顶点着色器和片段着色器
    private Texture2dProgram mProgram;

    /**
     * Prepares the object.
     *
     * @param program The program to use.  FullFrameRect takes ownership, and will release
     *     the program when no longer needed.
     * 
     * 🔧 构造函数：初始化全屏矩形对象
     * 
     * @param program 要使用的着色器程序
     *               💡 FullFrameRect会接管这个程序的生命周期
     *               💡 当不再需要时会自动释放
     */
    public FullFrameRect(Texture2dProgram program) {
        mProgram = program;
    }

    /**
     * Releases resources.
     * <p>
     * This must be called with the appropriate EGL context current (i.e. the one that was
     * current when the constructor was called).  If we're about to destroy the EGL context,
     * there's no value in having the caller make it current just to do this cleanup, so you
     * can pass a flag that will tell this function to skip any EGL-context-specific cleanup.
     * 
     * 🗑️ 释放资源
     * ⚠️ 必须在正确的EGL上下文中调用（即构造函数被调用时的上下文）
     * 💡 如果要销毁EGL上下文，可以传入false跳过EGL相关的清理
     * 
     * @param doEglCleanup 是否执行EGL相关清理
     */
    public void release(boolean doEglCleanup) {
        // 🔍 mProgram检查：验证着色器程序是否存在（非null）
        // 💡 为什么检查：避免对已释放的程序重复调用release()导致崩溃
        // 💡 作用：确保只在程序存在时执行清理操作
        // 💡 使用时机：在释放着色器程序之前进行前置校验
        if (mProgram != null) {
            // 🔀 doEglCleanup判断：是否需要执行EGL相关的清理操作
            // 💡 为什么判断：如果EGL上下文即将销毁，清理GL资源是无意义的
            // 💡 作用：在上下文销毁前可以跳过GL资源释放，避免不必要的API调用
            // 💡 使用时机：当EGL上下文仍有效时传true，即将销毁时传false
            if (doEglCleanup) {
                // 🗑️ mProgram.release()：释放着色器程序的GL资源
                // 💡 为什么调用：着色器程序占用GPU资源（着色器对象、程序对象）
                // 💡 作用：删除GL程序对象，释放GPU显存
                // 💡 使用时机：在不再需要该着色器程序时调用
                mProgram.release();
            }
            // 🔄 mProgram = null：置空程序引用
            // 💡 为什么置空：帮助GC回收Java对象，避免悬空引用
            // 💡 作用：标记程序已释放，后续使用会触发NPE（快速失败原则）
            // 💡 使用时机：无论是否执行EGL清理，都要置空引用
            mProgram = null;
        }
    }

    /**
     * Returns the program currently in use.
     * 
     * 📤 获取当前使用的着色器程序
     * 
     * @return 当前的Texture2dProgram对象
     */
    public Texture2dProgram getProgram() {
        return mProgram;
    }

    /**
     * Changes the program.  The previous program will be released.
     * <p>
     * The appropriate EGL context must be current.
     * 
     * 🔄 更换着色器程序，旧的程序会被释放
     * ⚠️ 必须在正确的EGL上下文中调用
     * 
     * @param program 新的着色器程序
     */
    public void changeProgram(Texture2dProgram program) {
        mProgram.release();  // 🗑️ 释放旧的程序
        mProgram = program;  // 🔄 设置新的程序
    }

    /**
     * Creates a texture object suitable for use with drawFrame().
     * 
     * 🎨 创建一个纹理对象，用于drawFrame()方法
     * 
     * @return 纹理对象的ID
     */
    public int createTextureObject() {
        // 📤 委托给着色器程序创建纹理
        return mProgram.createTextureObject();
    }

    /**
     * Draws a viewport-filling rect, texturing it with the specified texture object.
     *
     * 🖼️ 绘制一个填满视口的矩形，使用指定的纹理对象
     * 💡 这是核心渲染方法，将纹理渲染到整个屏幕
     *
     * @param textureId 纹理对象的ID
     *                 🎯 作用：指定要渲染的纹理
     *                 📌 使用时机：绘制前需要先创建纹理对象
     * @param texMatrix 纹理变换矩阵
     *                 🔄 作用：控制纹理的变换（如旋转、缩放）
     *                 📌 使用时机：通常从SurfaceTexture获取
     */
    public void drawFrame(int textureId, float[] texMatrix) {
        // 🎨 使用单位矩阵作为MVP矩阵，让2x2的全屏矩形覆盖整个视口
        // Use the identity matrix for MVP so our 2x2 FULL_RECTANGLE covers the viewport.
        // 💡 为什么用单位矩阵：因为全屏矩形坐标范围是[-1,1]，正好覆盖NDC空间
        // 💡 作用：不做任何变换，让矩形直接覆盖整个视口

        // 🖼️ mProgram.draw()：调用着色器程序执行绘制命令（核心渲染调用！）
        // 💡 为什么调用：将纹理渲染到全屏矩形上，实现纹理到屏幕的显示
        // 💡 参数详解：
        //    [0] GlUtil.IDENTITY_MATRIX：MVP变换矩阵（单位矩阵，不做3D变换）
        //    [1] mRectDrawable.getVertexArray()：全屏矩形的4个顶点坐标
        //    [2] 0：起始顶点索引（从第一个顶点开始绘制）
        //    [3] mRectDrawable.getVertexCount()：顶点数量（4个顶点组成矩形）
        //    [4] mRectDrawable.getCoordsPerVertex()：每个顶点的坐标数（2个：x和y）
        //    [5] mRectDrawable.getVertexStride()：顶点数据字节步长（8字节=2个float）
        //    [6] texMatrix：纹理变换矩阵（从SurfaceTexture.getTransformMatrix()获取）
        //    [7] mRectDrawable.getTexCoordArray()：纹理UV坐标数组（定义纹理映射）
        //    [8] textureId：要渲染的OpenGL纹理对象ID
        //    [9] mRectDrawable.getTexCoordStride()：纹理坐标字节步长（8字节=2个float）
        // 💡 使用时机：每帧渲染循环中调用，将外部纹理（如摄像头画面）绘制到屏幕
        mProgram.draw(
                GlUtil.IDENTITY_MATRIX,           // 📐 MVP矩阵：单位矩阵，不做变换
                mRectDrawable.getVertexArray(),    // 📍 顶点数组：全屏矩形的4个顶点
                0,                                 // 📍 起始索引：从第一个顶点开始
                mRectDrawable.getVertexCount(),    // 🔢 顶点数量：4个顶点组成矩形
                mRectDrawable.getCoordsPerVertex(),// 📐 每顶点坐标数：2（x,y）
                mRectDrawable.getVertexStride(),   // 📏 顶点步长：8字节（2个float）
                texMatrix,                         // 🔄 纹理变换矩阵（从SurfaceTexture获取）
                mRectDrawable.getTexCoordArray(),  // 🎨 纹理坐标数组（UV映射）
                textureId,                         // 🖼️ 纹理ID（由createTextureObject创建）
                mRectDrawable.getTexCoordStride()  // 📏 纹理坐标步长：8字节（2个float）
        );
    }
}
