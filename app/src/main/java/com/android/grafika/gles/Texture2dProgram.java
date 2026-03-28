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

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.util.Log;

import java.nio.FloatBuffer;

/**
 * GL program and supporting functions for textured 2D shapes.
 * 
 * 🎨 GL程序和2D纹理形状的支持函数
 */
public class Texture2dProgram {
    private static final String TAG = GlUtil.TAG;

    public enum ProgramType {
        TEXTURE_2D, TEXTURE_EXT, TEXTURE_EXT_BW, TEXTURE_EXT_FILT
    }

    // Simple vertex shader, used for all programs.
    // 🎯 简单的顶点着色器，所有程序都使用
    private static final String VERTEX_SHADER =
            "uniform mat4 uMVPMatrix;\n" +
            "uniform mat4 uTexMatrix;\n" +
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTextureCoord;\n" +
            "varying vec2 vTextureCoord;\n" +
            "void main() {\n" +
            "    gl_Position = uMVPMatrix * aPosition;\n" +
            "    vTextureCoord = (uTexMatrix * aTextureCoord).xy;\n" +
            "}\n";

    // Simple fragment shader for use with "normal" 2D textures.
    // 🖼️ 简单的片段着色器，用于"普通"2D纹理
    private static final String FRAGMENT_SHADER_2D =
            "precision mediump float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform sampler2D sTexture;\n" +
            "void main() {\n" +
            "    gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
            "}\n";

    // Simple fragment shader for use with external 2D textures (e.g. what we get from
    // SurfaceTexture).
    // 🖼️ 简单的片段着色器，用于外部2D纹理（例如从SurfaceTexture获取的纹理）
    private static final String FRAGMENT_SHADER_EXT =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "void main() {\n" +
            "    gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
            "}\n";

    // Fragment shader that converts color to black & white with a simple transformation.
    // 🖼️ 片段着色器，通过简单的转换将颜色转换为黑白
    private static final String FRAGMENT_SHADER_EXT_BW =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "void main() {\n" +
            "    vec4 tc = texture2D(sTexture, vTextureCoord);\n" +
            "    float color = tc.r * 0.3 + tc.g * 0.59 + tc.b * 0.11;\n" +
            "    gl_FragColor = vec4(color, color, color, 1.0);\n" +
            "}\n";

    // Fragment shader with a convolution filter.  The upper-left half will be drawn normally,
    // the lower-right half will have the filter applied, and a thin red line will be drawn
    // at the border.
    //
    // This is not optimized for performance.  Some things that might make this faster:
    // - Remove the conditionals.  They're used to present a half & half view with a red
    //   stripe across the middle, but that's only useful for a demo.
    // - Unroll the loop.  Ideally the compiler does this for you when it's beneficial.
    // - Bake the filter kernel into the shader, instead of passing it through a uniform
    //   array.  That, combined with loop unrolling, should reduce memory accesses.
    // 
    // 🖼️ 带卷积滤镜的片段着色器
    // 💡 左上半部分正常绘制，右下半部分应用滤镜，边界处绘制一条细红线
    // 💡 这个实现没有优化性能，可能的优化方法：
    //    - 移除条件判断（目前用于显示半半视图和红色分界线，仅用于演示）
    //    - 展开循环（理想情况下编译器会在有益时自动完成）
    //    - 将滤镜核烘焙到着色器中，而不是通过uniform数组传递
    public static final int KERNEL_SIZE = 9;
    private static final String FRAGMENT_SHADER_EXT_FILT =
            "#extension GL_OES_EGL_image_external : require\n" +
            "#define KERNEL_SIZE " + KERNEL_SIZE + "\n" +
            "precision highp float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "uniform float uKernel[KERNEL_SIZE];\n" +
            "uniform vec2 uTexOffset[KERNEL_SIZE];\n" +
            "uniform float uColorAdjust;\n" +
            "void main() {\n" +
            "    int i = 0;\n" +
            "    vec4 sum = vec4(0.0);\n" +
            "    if (vTextureCoord.x < vTextureCoord.y - 0.005) {\n" +
            "        for (i = 0; i < KERNEL_SIZE; i++) {\n" +
            "            vec4 texc = texture2D(sTexture, vTextureCoord + uTexOffset[i]);\n" +
            "            sum += texc * uKernel[i];\n" +
            "        }\n" +
            "    sum += uColorAdjust;\n" +
            "    } else if (vTextureCoord.x > vTextureCoord.y + 0.005) {\n" +
            "        sum = texture2D(sTexture, vTextureCoord);\n" +
            "    } else {\n" +
            "        sum.r = 1.0;\n" +
            "    }\n" +
            "    gl_FragColor = sum;\n" +
            "}\n";

    private ProgramType mProgramType;

    // Handles to the GL program and various components of it.
    // 🎮 GL程序句柄及其各个组件的句柄
    private int mProgramHandle;
    private int muMVPMatrixLoc;
    private int muTexMatrixLoc;
    private int muKernelLoc;
    private int muTexOffsetLoc;
    private int muColorAdjustLoc;
    private int maPositionLoc;
    private int maTextureCoordLoc;

    private int mTextureTarget;

    private float[] mKernel = new float[KERNEL_SIZE];
    private float[] mTexOffset;
    private float mColorAdjust;


    /**
     * Prepares the program in the current EGL context.
     *
     * 🔧 在当前EGL上下文中准备着色程序（核心初始化方法！）
     * 💡 根据程序类型创建对应的着色器程序，获取所有uniform/attribute位置
     * 📌 使用时机：渲染前初始化，必须在正确的EGL上下文中调用
     *
     * @param programType 程序类型，决定使用哪种片段着色器
     */
    public Texture2dProgram(ProgramType programType) {
        // 📝 mProgramType：程序类型枚举（TEXTURE_2D/TEXTURE_EXT/TEXTURE_EXT_BW/TEXTURE_EXT_FILT）
        // 💡 为什么定义：记录程序类型，供getProgramType()查询和后续逻辑判断
        // 💡 作用：决定纹理目标类型和使用的片段着色器
        // 💡 使用时机：构造时设置，后续只读查询
        mProgramType = programType;

        // 🔄 switch：根据程序类型创建对应的着色程序
        // 💡 不同类型使用不同的片段着色器，实现不同视觉效果
        // 💡 为什么用switch：程序类型固定且有限，switch比if-else更清晰高效
        switch (programType) {
            case TEXTURE_2D:
                // 🎯 TEXTURE_2D：普通2D纹理类型（如PNG/JPG图片）
                // 💡 mTextureTarget：纹理目标类型（int），决定glBindTexture时的纹理类型
                // 💡 为什么用GL_TEXTURE_2D：普通图片纹理的标准目标类型
                // 💡 使用时机：在createTextureObject()和draw()中绑定纹理时使用
                mTextureTarget = GLES20.GL_TEXTURE_2D;
                // 🎨 mProgramHandle：着色程序句柄（int），由顶点着色器+片段着色器编译链接而成
                // 💡 为什么定义：OpenGL需要通过句柄引用着色程序
                // 💡 作用：后续所有着色器操作（glUseProgram、glGetAttribLocation等）都需要此句柄
                // 💡 使用时机：在draw()中激活着色程序，在获取uniform/attribute位置时使用
                mProgramHandle = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER_2D);
                break;
            case TEXTURE_EXT:
                // 🎯 TEXTURE_EXT：外部纹理类型（如SurfaceTexture相机预览）
                // 💡 GL_TEXTURE_EXTERNAL_OES：Android扩展纹理类型，用于相机/视频
                mTextureTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES;
                mProgramHandle = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER_EXT);
                break;
            case TEXTURE_EXT_BW:
                // 🎯 TEXTURE_EXT_BW：外部纹理黑白转换类型
                // 💡 将彩色纹理实时转换为黑白效果（灰度滤镜）
                mTextureTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES;
                mProgramHandle = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER_EXT_BW);
                break;
            case TEXTURE_EXT_FILT:
                // 🎯 TEXTURE_EXT_FILT：外部纹理卷积滤镜类型
                // 💡 应用卷积滤镜效果（模糊、锐化、边缘检测等）
                mTextureTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES;
                mProgramHandle = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER_EXT_FILT);
                break;
            default:
                // ❌ 未知类型，抛出运行时异常（理论上不会触发，枚举已覆盖所有情况）
                throw new RuntimeException("Unhandled type " + programType);
        }
        // ⚠️ 检查着色程序是否创建成功
        // 💡 mProgramHandle == 0 表示编译或链接失败
        // 💡 使用时机：创建程序后立即验证，避免后续操作无效句柄
        if (mProgramHandle == 0) {
            throw new RuntimeException("Unable to create program");
        }
        // 📝 Log.d：记录创建的程序信息，用于调试
        // 💡 TAG：日志标签，便于过滤
        // 💡 使用时机：排查着色程序创建问题时查看日志
        Log.d(TAG, "Created program " + mProgramHandle + " (" + programType + ")");

        // get locations of attributes and uniforms
        // 📍 获取属性和统一变量的位置（attribute和uniform在着色器中的位置索引）
        // 💡 这些位置用于后续设置着色器变量值（glUniform*, glVertexAttribPointer等）

        // 🎯 maPositionLoc：顶点位置属性位置（int）
        // 💡 为什么定义：OpenGL需要通过位置索引设置顶点位置数据
        // 💡 作用：在draw()中通过glVertexAttribPointer绑定顶点坐标缓冲区
        // 💡 使用时机：draw()中启用属性数组和绑定顶点数据时
        maPositionLoc = GLES20.glGetAttribLocation(mProgramHandle, "aPosition");
        GlUtil.checkLocation(maPositionLoc, "aPosition");
        // 🎯 maTextureCoordLoc：纹理坐标属性位置（int）
        // 💡 为什么定义：OpenGL需要通过位置索引设置纹理坐标数据
        // 💡 作用：在draw()中通过glVertexAttribPointer绑定纹理坐标缓冲区
        // 💡 使用时机：draw()中启用属性数组和绑定纹理坐标数据时
        maTextureCoordLoc = GLES20.glGetAttribLocation(mProgramHandle, "aTextureCoord");
        GlUtil.checkLocation(maTextureCoordLoc, "aTextureCoord");
        // 🎯 muMVPMatrixLoc：MVP矩阵统一变量位置（int）
        // 💡 为什么定义：需要将模型-视图-投影矩阵传递给顶点着色器
        // 💡 作用：在draw()中通过glUniformMatrix4fv设置顶点变换矩阵
        // 💡 使用时机：draw()中设置顶点着色器的uMVPMatrix变量时
        muMVPMatrixLoc = GLES20.glGetUniformLocation(mProgramHandle, "uMVPMatrix");
        GlUtil.checkLocation(muMVPMatrixLoc, "uMVPMatrix");
        // 🎯 muTexMatrixLoc：纹理变换矩阵统一变量位置（int）
        // 💡 为什么定义：需要将纹理变换矩阵传递给顶点着色器（如SurfaceTexture的变换）
        // 💡 作用：在draw()中通过glUniformMatrix4fv设置纹理坐标变换
        // 💡 使用时机：draw()中设置顶点着色器的uTexMatrix变量时
        muTexMatrixLoc = GLES20.glGetUniformLocation(mProgramHandle, "uTexMatrix");
        GlUtil.checkLocation(muTexMatrixLoc, "uTexMatrix");
        // 🎯 muKernelLoc：卷积核统一变量位置（int）
        // 💡 为什么定义：卷积滤镜需要传递滤镜核权重数组给片段着色器
        // 💡 作用：在draw()中通过glUniform1fv设置卷积核值
        // 💡 使用时机：仅当程序类型为TEXTURE_EXT_FILT时有效
        muKernelLoc = GLES20.glGetUniformLocation(mProgramHandle, "uKernel");
        if (muKernelLoc < 0) {
            // no kernel in this one
            // 📝 这个程序没有卷积核（muKernelLoc < 0 表示着色器中没有uKernel变量）
            // 💡 为什么检查：非滤镜类型的程序不需要卷积核相关参数
            // 💡 使用时机：判断是否需要设置卷积核相关参数
            muKernelLoc = -1;           // 🚫 muKernelLoc = -1：无效位置，表示不使用卷积核
            muTexOffsetLoc = -1;        // 🚫 muTexOffsetLoc = -1：无效位置，不使用纹理偏移
            muColorAdjustLoc = -1;      // 🚫 muColorAdjustLoc = -1：无效位置，不使用颜色调整
        } else {
            // has kernel, must also have tex offset and color adj
            // 📝 有卷积核，必须同时有纹理偏移和颜色调整变量（三个变量配合使用）
            // 💡 为什么必须同时存在：卷积滤镜需要采样相邻纹素，需要偏移量和亮度调整
            // 🎯 muTexOffsetLoc：纹理偏移数组统一变量位置（int）
            // 💡 为什么定义：卷积采样需要指定每个采样点相对于中心的UV偏移
            // 💡 作用：在draw()中通过glUniform2fv设置采样偏移
            // 💡 使用时机：draw()中设置片段着色器的uTexOffset数组时
            muTexOffsetLoc = GLES20.glGetUniformLocation(mProgramHandle, "uTexOffset");
            GlUtil.checkLocation(muTexOffsetLoc, "uTexOffset");
            // 🎯 muColorAdjustLoc：颜色调整统一变量位置（int）
            // 💡 为什么定义：卷积滤镜后可能需要整体亮度调整
            // 💡 作用：在draw()中通过glUniform1f设置亮度偏移值
            // 💡 使用时机：draw()中设置片段着色器的uColorAdjust变量时
            muColorAdjustLoc = GLES20.glGetUniformLocation(mProgramHandle, "uColorAdjust");
            GlUtil.checkLocation(muColorAdjustLoc, "uColorAdjust");

            // initialize default values
            // 📝 初始化默认值（设置恒等变换卷积核和默认纹理尺寸）
            // 💡 为什么初始化：确保滤镜程序在未设置参数时也能正常工作
            // 💡 setKernel：设置默认的恒等卷积核[0,0,0,0,1,0,0,0,0]（中心权重1，其他0=无效果）
            // 💡 setTexSize：设置默认纹理尺寸256x256（用于计算纹素偏移）
            // 💡 使用时机：程序创建后立即设置默认值
            setKernel(new float[] {0f, 0f, 0f,  0f, 1f, 0f,  0f, 0f, 0f}, 0f);
            setTexSize(256, 256);
        }
    }

    /**
     * Releases the program.
     * <p>
     * The appropriate EGL context must be current (i.e. the one that was used to create
     * the program).
     * 
     * 🗑️ 释放着色程序
     * 💡 必须在正确的EGL上下文中调用（即创建程序时使用的上下文）
     */
    public void release() {
        Log.d(TAG, "deleting program " + mProgramHandle);
        GLES20.glDeleteProgram(mProgramHandle);
        mProgramHandle = -1;
    }

    /**
     * Returns the program type.
     * 
     * 📤 返回程序类型
     */
    public ProgramType getProgramType() {
        return mProgramType;
    }

    /**
     * Creates a texture object suitable for use with this program.
     * <p>
     * On exit, the texture will be bound.
     *
     * 🖼️ 创建适合此程序使用的纹理对象（纹理初始化核心方法！）
     * 💡 函数返回时，纹理将被绑定到当前纹理单元
     * 📌 使用时机：初始化时创建纹理，用于后续渲染
     *
     * @return 创建的纹理对象ID（int），供后续绑定和渲染使用
     */
    public int createTextureObject() {
        // 📦 textures：存储生成的纹理ID数组（int[1]）
        // 💡 为什么定义：glGenTextures要求传入数组参数来接收生成的纹理ID
        // 💡 作用：OpenGL生成纹理后将ID存入此数组
        // 💡 使用时机：调用glGenTextures时作为输出参数
        int[] textures = new int[1];

        // 🎨 glGenTextures：生成纹理对象
        // 💡 作用：在GPU中分配纹理资源，返回唯一标识符
        // 💡 参数说明：1(生成数量), textures(存储数组), 0(数组偏移)
        // 💡 使用时机：纹理初始化的第一步
        GLES20.glGenTextures(1, textures, 0);
        GlUtil.checkGlError("glGenTextures");

        // 🆔 texId：获取生成的纹理ID（int）
        // 💡 为什么定义：方便后续代码引用，避免每次都从数组取值
        // 💡 作用：后续所有纹理操作（绑定、设置参数、绘制）都通过此ID引用
        // 💡 使用时机：在glBindTexture和draw()中使用
        int texId = textures[0];

        // 🔗 glBindTexture：绑定纹理到目标
        // 💡 作用：将纹理激活，准备设置参数和加载数据
        // 💡 mTextureTarget：纹理目标类型（GL_TEXTURE_2D或GL_TEXTURE_EXTERNAL_OES）
        // 💡 texId：要绑定的纹理对象ID
        // 💡 使用时机：纹理参数设置和渲染前必须先绑定
        GLES20.glBindTexture(mTextureTarget, texId);
        GlUtil.checkGlError("glBindTexture " + texId);

        // ⚙️ glTexParameterf：设置纹理缩小过滤参数
        // 💡 GL_TEXTURE_MIN_FILTER：当纹理像素比显示区域小时的采样方式
        // 💡 GL_NEAREST：最近邻采样，性能好但可能有锯齿
        // 💡 为什么用GL_NEAREST：外部纹理通常不需要高质量缩放，优先性能
        // 💡 使用时机：纹理需要缩小时（如缩小显示到屏幕）
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_NEAREST);

        // ⚙️ glTexParameterf：设置纹理放大过滤参数
        // 💡 GL_TEXTURE_MAG_FILTER：当纹理像素比显示区域大时的采样方式
        // 💡 GL_LINEAR：线性插值，平滑但稍慢于GL_NEAREST
        // 💡 为什么用GL_LINEAR：放大时需要平滑过渡，避免像素化
        // 💡 使用时机：纹理需要放大时（如放大显示）
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR);

        // ⚙️ glTexParameteri：设置纹理S轴（水平）环绕模式
        // 💡 GL_TEXTURE_WRAP_S：纹理U坐标超出[0,1]范围时的处理方式
        // 💡 GL_CLAMP_TO_EDGE：夹紧到边缘像素，避免重复或镜像
        // 💡 为什么用CLAMP_TO_EDGE：外部纹理通常不希望重复
        // 💡 使用时机：纹理坐标可能超出[0,1]范围时
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE);

        // ⚙️ glTexParameteri：设置纹理T轴（垂直）环绕模式
        // 💡 GL_TEXTURE_WRAP_T：纹理V坐标超出[0,1]范围时的处理方式
        // 💡 GL_CLAMP_TO_EDGE：夹紧到边缘像素，避免重复或镜像
        // 💡 为什么用CLAMP_TO_EDGE：与S轴保持一致，避免边缘异常
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE);

        // ✅ checkGlError：检查纹理参数设置是否出错
        // 💡 作用：捕获glTexParameter调用中的OpenGL错误
        // 💡 使用时机：纹理参数设置完成后立即检查
        GlUtil.checkGlError("glTexParameter");

        // 📤 return texId：返回纹理ID
        // 💡 作用：供调用者后续绑定和渲染使用
        // 💡 使用时机：调用者将此ID传给setTexture()或draw()方法
        return texId;
    }

    /**
     * Configures the convolution filter values.
     *
     * ⚙️ 配置卷积滤镜值（滤镜核心配置方法！）
     * 💡 设置3x3卷积核的权重值，实现模糊、锐化、边缘检测等效果
     * 📌 使用时机：需要应用图像滤镜效果时调用
     *
     * @param values 归一化的滤镜值，必须有KERNEL_SIZE（9）个元素
     *              🎯 作用：定义3x3卷积核的权重
     *              💡 例如：[0,0,0, 0,1,0, 0,0,0] 是恒等变换（无效果）
     *              💡 例如：高斯模糊核会将周围像素加权平均
     * @param colorAdj 颜色调整值（float）
     *               🎨 作用：整体亮度偏移，正值变亮，负值变暗
     *               💡 在卷积计算结果上叠加的亮度值
     */
    public void setKernel(float[] values, float colorAdj) {
        // ⚠️ 验证卷积核大小是否正确
        // 💡 values.length：传入数组的长度
        // 💡 为什么检查：卷积核必须是KERNEL_SIZE（9）个元素，对应3x3矩阵
        // 💡 使用时机：设置卷积核前立即验证，防止数组越界
        if (values.length != KERNEL_SIZE) {
            throw new IllegalArgumentException("Kernel size is " + values.length +
                    " vs. " + KERNEL_SIZE);
        }

        // 📋 System.arraycopy：高效数组复制方法
        // 💡 作用：将传入的卷积核值复制到成员变量mKernel中
        // 💡 为什么复制：避免外部修改影响内部状态（深拷贝保护）
        // 💡 参数说明：values(源数组), 0(源起始位置), mKernel(目标数组), 0(目标起始), KERNEL_SIZE(复制9个元素)
        // 💡 使用时机：验证通过后立即复制
        System.arraycopy(values, 0, mKernel, 0, KERNEL_SIZE);

        // 🎨 mColorAdjust：颜色调整值（float）
        // 💡 为什么定义：卷积滤镜后可能需要整体亮度调整
        // 💡 作用：在片段着色器中加到卷积计算结果上，用于亮度/对比度调整
        // 💡 使用时机：在draw()中通过glUniform1f传递给着色器的uColorAdjust变量
        mColorAdjust = colorAdj;
        //Log.d(TAG, "filt kernel: " + Arrays.toString(mKernel) + ", adj=" + colorAdj);
    }

    /**
     * Sets the size of the texture.  This is used to find adjacent texels when filtering.
     *
     * 📐 设置纹理尺寸（卷积采样偏移计算！）
     * 💡 根据纹理尺寸计算采样相邻纹素时的UV偏移量
     * 📌 使用时机：纹理尺寸改变后，或应用卷积滤镜前必须调用
     *
     * @param width 纹理宽度（像素）
     *             📏 作用：计算水平方向一个纹素的UV偏移量
     * @param height 纹理高度（像素）
     *              📏 作用：计算垂直方向一个纹素的UV偏移量
     */
    public void setTexSize(int width, int height) {
        // 📐 rw：水平方向一个纹素的UV坐标宽度（float）
        // 💡 为什么定义：卷积采样需要知道相邻纹素的UV间隔
        // 💡 计算方式：1.0f / 宽度（UV范围是0~1，除以像素数得到每个像素的UV大小）
        // 💡 作用：构建纹理偏移数组时，水平方向的基础偏移量
        // 💡 使用时机：在下方构建mTexOffset数组时使用
        float rw = 1.0f / width;

        // 📐 rh：垂直方向一个纹素的UV坐标高度（float）
        // 💡 为什么定义：卷积采样需要知道相邻纹素的UV间隔
        // 💡 计算方式：1.0f / 高度
        // 💡 作用：构建纹理偏移数组时，垂直方向的基础偏移量
        // 💡 使用时机：在下方构建mTexOffset数组时使用
        float rh = 1.0f / height;

        // Don't need to create a new array here, but it's syntactically convenient.
        // 📝 这里不需要创建新数组，但语法上更方便（每次创建新数组简化代码）
        // 🔄 mTexOffset：纹理偏移数组（float[18]），存储3x3网格的UV偏移
        // 💡 为什么定义：卷积采样时，需要指定每个采样点相对于中心的UV偏移
        // 💡 作用：在片段着色器中，通过vTextureCoord + uTexOffset[i]计算每个采样点的坐标
        // 💡 格式：每两个元素为一对(u偏移, v偏移)，共9对=18个元素
        // 💡 布局：3x3网格，从左上到右下排列
        //    [-rw,-rh] [0,-rh] [rw,-rh]   ← 第一行（上排3个采样点）
        //    [-rw, 0 ] [0, 0 ] [rw, 0 ]   ← 第二行（中排3个采样点，中心点在[0,0]）
        //    [-rw, rh] [0, rh] [rw, rh]   ← 第三行（下排3个采样点）
        // 💡 使用时机：在draw()中通过glUniform2fv传递给着色器
        mTexOffset = new float[] {
            -rw, -rh,   0f, -rh,    rw, -rh,  // 🔝 上排：左上、上、右上
            -rw, 0f,    0f, 0f,     rw, 0f,   // ⬜ 中排：左、中心、右
            -rw, rh,    0f, rh,     rw, rh    // 🔽 下排：左下、下、右下
        };
        //Log.d(TAG, "filt size: " + width + "x" + height + ": " + Arrays.toString(mTexOffset));
    }

    /**
     * Issues the draw call.  Does the full setup on every call.
     *
     * @param mvpMatrix The 4x4 projection matrix.
     * @param vertexBuffer Buffer with vertex position data.
     * @param firstVertex Index of first vertex to use in vertexBuffer.
     * @param vertexCount Number of vertices in vertexBuffer.
     * @param coordsPerVertex The number of coordinates per vertex (e.g. x,y is 2).
     * @param vertexStride Width, in bytes, of the position data for each vertex (often
     *        vertexCount * sizeof(float)).
     * @param texMatrix A 4x4 transformation matrix for texture coords.  (Primarily intended
     *        for use with SurfaceTexture.)
     * @param texBuffer Buffer with vertex texture data.
     * @param texStride Width, in bytes, of the texture data for each vertex.
     *
     * 🖼️ 发起绘制调用（纹理渲染核心方法！）
     * 💡 每次调用都进行完整的着色器设置、数据绑定、绘制和清理
     * 📌 使用时机：需要绘制纹理图元时调用
     *
     * @param mvpMatrix 4x4 MVP变换矩阵
     * @param vertexBuffer 包含顶点位置数据的FloatBuffer
     * @param firstVertex 在vertexBuffer中使用的第一个顶点索引
     * @param vertexCount vertexBuffer中的顶点数量
     * @param coordsPerVertex 每个顶点的坐标数（例如x,y为2）
     * @param vertexStride 每个顶点位置数据的宽度（字节数）
     * @param texMatrix 4x4纹理坐标变换矩阵（主要用于SurfaceTexture）
     * @param texBuffer 包含顶点纹理坐标数据的FloatBuffer
     * @param textureId 纹理对象ID
     * @param texStride 每个顶点纹理数据的宽度（字节数）
     */
    public void draw(float[] mvpMatrix, FloatBuffer vertexBuffer, int firstVertex,
            int vertexCount, int coordsPerVertex, int vertexStride,
            float[] texMatrix, FloatBuffer texBuffer, int textureId, int texStride) {
        // ⚠️ checkGlError：检查绘制开始前的GL错误
        // 💡 作用：调试时捕获之前操作遗留的OpenGL错误
        // 💡 使用时机：绘制方法入口处，确保GL状态正常
        GlUtil.checkGlError("draw start");

        // Select the program.
        // 🎯 glUseProgram：选择/激活着色程序
        // 💡 mProgramHandle：着色程序句柄（int），在构造函数中创建
        // 💡 作用：告诉GPU后续绘制使用哪套着色器
        // 💡 使用时机：绘制前必须激活着色程序
        GLES20.glUseProgram(mProgramHandle);
        GlUtil.checkGlError("glUseProgram");

        // Set the texture.
        // 🖼️ glActiveTexture：激活纹理单元0
        // 💡 GL_TEXTURE0：第一个纹理单元（OpenGL支持多个纹理单元）
        // 💡 作用：指定后续纹理操作的目标纹理单元
        // 💡 使用时机：绑定纹理前必须先激活纹理单元
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        // 💡 glBindTexture：绑定纹理到当前纹理单元
        // 💡 mTextureTarget：纹理目标类型（GL_TEXTURE_2D或GL_TEXTURE_EXTERNAL_OES）
        // 💡 textureId：要绑定的纹理对象ID，由createTextureObject()创建
        // 💡 作用：将指定纹理设置为当前绘制使用的纹理
        // 💡 使用时机：激活着色程序后，绘制前绑定
        GLES20.glBindTexture(mTextureTarget, textureId);

        // Copy the model / view / projection matrix over.
        // 📋 glUniformMatrix4fv：复制MVP矩阵到着色器
        // 💡 muMVPMatrixLoc：MVP矩阵在着色器中的位置（int），在构造函数中获取
        // 💡 mvpMatrix：4x4变换矩阵（float[16]），用于顶点位置变换
        // 💡 参数说明：位置, 1(矩阵数量), false(不转置), mvpMatrix(数据), 0(偏移)
        // 💡 作用：设置顶点着色器中的uMVPMatrix变量
        // 💡 使用时机：设置顶点变换矩阵
        GLES20.glUniformMatrix4fv(muMVPMatrixLoc, 1, false, mvpMatrix, 0);
        GlUtil.checkGlError("glUniformMatrix4fv");

        // Copy the texture transformation matrix over.
        // 📋 glUniformMatrix4fv：复制纹理变换矩阵到着色器
        // 💡 muTexMatrixLoc：纹理矩阵在着色器中的位置（int）
        // 💡 texMatrix：4x4纹理变换矩阵（float[16]），用于纹理坐标变换
        // 💡 作用：设置顶点着色器中的uTexMatrix变量（如SurfaceTexture的变换）
        // 💡 使用时机：设置纹理坐标变换矩阵
        GLES20.glUniformMatrix4fv(muTexMatrixLoc, 1, false, texMatrix, 0);
        GlUtil.checkGlError("glUniformMatrix4fv");

        // Enable the "aPosition" vertex attribute.
        // ✅ glEnableVertexAttribArray：启用顶点位置属性数组
        // 💡 maPositionLoc：顶点位置属性位置（int）
        // 💡 作用：告诉GPU该属性需要从缓冲区读取数据
        // 💡 使用时机：绘制前必须启用，绘制后禁用
        GLES20.glEnableVertexAttribArray(maPositionLoc);
        GlUtil.checkGlError("glEnableVertexAttribArray");

        // Connect vertexBuffer to "aPosition".
        // 🔗 glVertexAttribPointer：将顶点缓冲区连接到aPosition属性
        // 💡 maPositionLoc：属性位置
        // 💡 coordsPerVertex：每个顶点的坐标数（2或3）
        // 💡 GL_FLOAT：数据类型为浮点数
        // 💡 false：不归一化
        // 💡 vertexStride：顶点数据字节间隔
        // 💡 vertexBuffer：包含顶点位置数据的FloatBuffer
        // 💡 作用：指定顶点位置数据的来源和格式
        // 💡 使用时机：启用属性数组后，绑定数据源
        GLES20.glVertexAttribPointer(maPositionLoc, coordsPerVertex,
            GLES20.GL_FLOAT, false, vertexStride, vertexBuffer);
        GlUtil.checkGlError("glVertexAttribPointer");

        // Enable the "aTextureCoord" vertex attribute.
        // ✅ glEnableVertexAttribArray：启用纹理坐标属性数组
        // 💡 maTextureCoordLoc：纹理坐标属性位置（int）
        // 💡 作用：告诉GPU纹理坐标需要从缓冲区读取
        // 💡 使用时机：绘制纹理前必须启用
        GLES20.glEnableVertexAttribArray(maTextureCoordLoc);
        GlUtil.checkGlError("glEnableVertexAttribArray");

        // Connect texBuffer to "aTextureCoord".
        // 🔗 glVertexAttribPointer：将纹理坐标缓冲区连接到aTextureCoord属性
        // 💡 maTextureCoordLoc：纹理坐标属性位置
        // 💡 2：纹理坐标维度（u, v两个分量）
        // 💡 GL_FLOAT：数据类型为浮点数
        // 💡 false：不归一化
        // 💡 texStride：纹理坐标数据字节间隔
        // 💡 texBuffer：包含纹理坐标数据的FloatBuffer
        // 💡 作用：指定纹理坐标数据的来源和格式
        // 💡 使用时机：启用纹理坐标属性后，绑定数据源
        GLES20.glVertexAttribPointer(maTextureCoordLoc, 2,
                GLES20.GL_FLOAT, false, texStride, texBuffer);
            GlUtil.checkGlError("glVertexAttribPointer");

        // Populate the convolution kernel, if present.
        // 📝 如果存在卷积核（muKernelLoc >= 0），则填充卷积相关参数
        // 💡 muKernelLoc >= 0 表示着色器包含卷积核（即TEXTURE_EXT_FILT类型）
        // 💡 作用：仅当使用卷积滤镜时，传递滤镜参数给片段着色器
        // 💡 使用时机：仅TEXTURE_EXT_FILT类型的程序会执行此分支
        if (muKernelLoc >= 0) {
            // 💡 glUniform1fv：设置卷积核数组
            // 💡 muKernelLoc：卷积核数组位置（int）
            // 💡 KERNEL_SIZE：数组大小（9个元素，3x3卷积核）
            // 💡 mKernel：卷积核数值数组（float[9]），由setKernel()设置
            // 💡 作用：传递卷积核权重给片段着色器的uKernel数组
            GLES20.glUniform1fv(muKernelLoc, KERNEL_SIZE, mKernel, 0);
            // 💡 glUniform2fv：设置纹理偏移数组
            // 💡 muTexOffsetLoc：纹理偏移数组位置（int）
            // 💡 KERNEL_SIZE：数组大小（9个偏移对）
            // 💡 mTexOffset：纹理偏移数组（float[18]），由setTexSize()设置
            // 💡 作用：传递采样点UV偏移给片段着色器的uTexOffset数组
            GLES20.glUniform2fv(muTexOffsetLoc, KERNEL_SIZE, mTexOffset, 0);
            // 💡 glUniform1f：设置颜色调整值
            // 💡 muColorAdjustLoc：颜色调整值位置（int）
            // 💡 mColorAdjust：颜色调整值（float），由setKernel()设置
            // 💡 作用：传递亮度偏移给片段着色器的uColorAdjust变量
            GLES20.glUniform1f(muColorAdjustLoc, mColorAdjust);
        }

        // Draw the rect.
        // 🖼️ glDrawArrays：绘制矩形（实际执行绘制命令！）
        // 💡 GL_TRIANGLE_STRIP：三角形带绘制模式（4个顶点组成2个三角形）
        // 💡 firstVertex：起始顶点索引（通常为0）
        // 💡 vertexCount：要绘制的顶点数量（矩形=4）
        // 💡 作用：通知GPU执行渲染管线，将图元绘制到帧缓冲区
        // 💡 使用时机：所有数据绑定完成后，执行实际绘制
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, firstVertex, vertexCount);
        GlUtil.checkGlError("glDrawArrays");

        // ✅ 完成 - 禁用顶点数组、纹理和程序（资源清理！）
        // 💡 作用：清理绘制状态，避免影响后续其他绘制操作
        // 💡 为什么清理：OpenGL是状态机，不清理会影响后续绘制
        // 💡 使用时机：绘制完成后立即执行
        GLES20.glDisableVertexAttribArray(maPositionLoc);   // 🚫 禁用顶点位置属性数组，释放GPU资源
        GLES20.glDisableVertexAttribArray(maTextureCoordLoc); // 🚫 禁用纹理坐标属性数组，释放GPU资源
        GLES20.glBindTexture(mTextureTarget, 0);            // 🚫 解绑纹理（0=无纹理），恢复默认纹理状态
        GLES20.glUseProgram(0);                             // 🚫 停用着色程序（0=无程序），恢复默认程序状态
    }
}
