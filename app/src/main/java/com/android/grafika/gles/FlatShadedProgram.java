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

import android.opengl.GLES20;
import android.util.Log;
import java.nio.FloatBuffer;

/**
 * GL program and supporting functions for flat-shaded rendering.
 * 
 * 🎨 平面着色程序：使用单一颜色渲染
 */
public class FlatShadedProgram {
    private static final String TAG = GlUtil.TAG;

    /**
     * 🎯 顶点着色器 GLSL 源码
     * 💡 接收每个顶点的位置，通过 MVP 矩阵变换到裁剪空间
     *    uMVPMatrix：Model-View-Projection 矩阵（投影×视图×模型的组合）
     *    aPosition：每个顶点的原始坐标（来自 FloatBuffer 顶点数据）
     *    gl_Position：输出变量，写入变换后的裁剪坐标，交给光栅化阶段
     */
    private static final String VERTEX_SHADER =
            "uniform mat4 uMVPMatrix;\n" +
            "attribute vec4 aPosition;\n" +
            "void main() {\n" +
            "    gl_Position = uMVPMatrix * aPosition;\n" +
            "}\n";

    /**
     * 🎨 片段着色器 GLSL 源码
     * 💡 对每个光栅化后的片段（像素）输出统一颜色
     *    precision mediump float：使用中精度浮点数，平衡精度和性能
     *    uColor：RGBA 颜色统一变量，由 Java 层通过 glUniform4fv 传入
     *    gl_FragColor：输出变量，写入该片段最终颜色，送入帧缓冲
     */
    private static final String FRAGMENT_SHADER =
            "precision mediump float;\n" +
            "uniform vec4 uColor;\n" +
            "void main() {\n" +
            "    gl_FragColor = uColor;\n" +
            "}\n";

    private int mProgramHandle = -1;
    private int muColorLoc = -1;
    private int muMVPMatrixLoc = -1;
    private int maPositionLoc = -1;

    /** 🔧 构造函数：创建着色程序 */
    public FlatShadedProgram() {
        // 🎮 mProgramHandle：着色器程序的GL句柄
        // 💡 为什么定义：需要持有程序句柄用于后续渲染操作
        // 💡 作用：标识GPU中的着色器程序，用于绑定和绘制
        // 💡 使用时机：在glUseProgram和获取uniform/attrib位置时使用
        mProgramHandle = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        // ⚠️ 程序创建失败，抛出异常终止运行
        if (mProgramHandle == 0) {
            throw new RuntimeException("Unable to create program");
        }
        // 📝 记录程序创建成功，便于调试
        Log.d(TAG, "Created program " + mProgramHandle);

        // 📍 maPositionLoc：顶点位置属性在着色器中的位置
        // 💡 为什么定义：需要知道aPosition在着色器中的位置才能传入顶点数据
        // 💡 作用：存储glGetAttribLocation的查询结果
        // 💡 使用时机：在glVertexAttribPointer设置顶点数据时使用
        maPositionLoc = GLES20.glGetAttribLocation(mProgramHandle, "aPosition");
        // ✅ 验证属性位置有效
        GlUtil.checkLocation(maPositionLoc, "aPosition");
        // 📍 muMVPMatrixLoc：MVP矩阵uniform在着色器中的位置
        // 💡 为什么定义：需要知道uMVPMatrix位置才能传入变换矩阵
        // 💡 作用：存储glGetUniformLocation的查询结果
        // 💡 使用时机：在glUniformMatrix4fv传入矩阵时使用
        muMVPMatrixLoc = GLES20.glGetUniformLocation(mProgramHandle, "uMVPMatrix");
        // ✅ 验证uniform位置有效
        GlUtil.checkLocation(muMVPMatrixLoc, "uMVPMatrix");
        // 📍 muColorLoc：颜色uniform在着色器中的位置
        // 💡 为什么定义：需要知道uColor位置才能传入渲染颜色
        // 💡 作用：存储glGetUniformLocation的查询结果
        // 💡 使用时机：在glUniform4fv传入颜色时使用
        muColorLoc = GLES20.glGetUniformLocation(mProgramHandle, "uColor");
        // ✅ 验证uniform位置有效
        GlUtil.checkLocation(muColorLoc, "uColor");
    }

    /** 🗑️ 释放程序 */
    public void release() {
        GLES20.glDeleteProgram(mProgramHandle);
        mProgramHandle = -1;
    }

    /**
     * 🖼️ 绘制调用
     * @param mvpMatrix MVP矩阵
     * @param color 颜色 RGBA
     */
    public void draw(float[] mvpMatrix, float[] color, FloatBuffer vertexBuffer,
            int firstVertex, int vertexCount, int coordsPerVertex, int vertexStride) {
        // ⚠️ 绘制开始前检查是否有残留的GL错误
        GlUtil.checkGlError("draw start");

        // 🎮 激活着色器程序，后续绘制操作使用此程序
        GLES20.glUseProgram(mProgramHandle);
        // 📐 传入MVP（Model-View-Projection）变换矩阵到着色器
        // 💡 参数：位置、矩阵数量、是否转置、矩阵数据、数据偏移
        GLES20.glUniformMatrix4fv(muMVPMatrixLoc, 1, false, mvpMatrix, 0);
        // 🎨 传入RGBA颜色值到着色器，用于平面着色
        GLES20.glUniform4fv(muColorLoc, 1, color, 0);
        // 🔓 启用顶点位置属性数组，准备传入顶点数据
        GLES20.glEnableVertexAttribArray(maPositionLoc);
        // 📊 设置顶点位置属性的数据格式和来源
        // 💡 参数：属性位置、每个顶点的坐标数、数据类型、是否归一化、步长、数据缓冲区
        GLES20.glVertexAttribPointer(maPositionLoc, coordsPerVertex,
            GLES20.GL_FLOAT, false, vertexStride, vertexBuffer);
        // 🖼️ 执行三角形带绘制，从firstVertex开始绘制vertexCount个顶点
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, firstVertex, vertexCount);
        // 🔒 禁用顶点位置属性数组，清理绘制状态
        GLES20.glDisableVertexAttribArray(maPositionLoc);
        // 🔄 解除程序绑定，恢复默认状态
        GLES20.glUseProgram(0);
    }
}
