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
import android.opengl.GLES30;
import android.opengl.Matrix;
import android.util.Log;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Some OpenGL utility functions.
 * 
 * 🔧 OpenGL工具函数类
 */
public class GlUtil {
    public static final String TAG = "Grafika";

    /** Identity matrix for general use.  Don't modify or life will get weird. */
    /** 📐 单位矩阵，不要修改 */
    public static final float[] IDENTITY_MATRIX;
    static {
        IDENTITY_MATRIX = new float[16];
        Matrix.setIdentityM(IDENTITY_MATRIX, 0);
    }

    private static final int SIZEOF_FLOAT = 4;

    private GlUtil() {}     // do not instantiate

    /**
     * Creates a new program from the supplied vertex and fragment shaders.
     *
     * 🎨 从顶点和片段着色器创建新程序
     * @return 程序句柄，失败返回0
     */
    public static int createProgram(String vertexSource, String fragmentSource) {
        // 🔨 vertexShader：编译后的顶点着色器句柄
        // 💡 为什么定义：顶点着色器负责处理顶点位置等几何数据
        // 💡 作用：存储顶点着色器的GL句柄，后续附加到程序
        // 💡 使用时机：在glAttachShader时附加到程序对象
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        // ⚠️ 顶点着色器编译失败，直接返回0表示创建失败
        if (vertexShader == 0) {
            return 0;
        }
        // 🔨 pixelShader：编译后的片段着色器句柄
        // 💡 为什么定义：片段着色器负责计算每个像素的最终颜色
        // 💡 作用：存储片段着色器的GL句柄，后续附加到程序
        // 💡 使用时机：在glAttachShader时附加到程序对象
        int pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        // ⚠️ 片段着色器编译失败，直接返回0表示创建失败
        if (pixelShader == 0) {
            return 0;
        }

        // 🎮 program：着色器程序对象的GL句柄
        // 💡 为什么定义：程序对象用于将顶点和片段着色器组合在一起
        // 💡 作用：作为着色器程序的唯一标识，用于后续的渲染操作
        // 💡 使用时机：在附加着色器、链接程序、使用程序时都需要
        int program = GLES20.glCreateProgram();
        // ⚠️ 检查程序创建是否出错
        checkGlError("glCreateProgram");
        if (program == 0) {
            Log.e(TAG, "Could not create program");
        }
        // 🔗 将顶点着色器附加到程序
        GLES20.glAttachShader(program, vertexShader);
        checkGlError("glAttachShader");
        // 🔗 将片段着色器附加到程序
        GLES20.glAttachShader(program, pixelShader);
        checkGlError("glAttachShader");
        // 🔗 链接程序，将两个着色器组合成完整的渲染管线
        GLES20.glLinkProgram(program);
        // 📊 linkStatus：存储程序链接状态的数组
        // 💡 为什么定义：需要检查链接是否成功
        // 💡 作用：GL_LINK_STATUS的结果存储位置
        // 💡 使用时机：在判断链接是否成功（GL_TRUE）时使用
        int[] linkStatus = new int[1];
        // 🔍 查询程序链接状态
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        // ⚠️ 链接失败处理：打印错误日志并清理资源
        if (linkStatus[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "Could not link program: ");
            Log.e(TAG, GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteProgram(program);
            program = 0;
        }
        // ✅ 返回程序句柄，失败时返回0
        return program;
    }

    /**
     * Compiles the provided shader source.
     *
     * 🔨 编译着色器源码
     * @return 着色器句柄，失败返回0
     */
    public static int loadShader(int shaderType, String source) {
        // 🎮 shader：着色器对象的GL句柄
        // 💡 为什么定义：需要一个句柄来操作着色器对象
        // 💡 作用：标识GPU中的着色器资源
        // 💡 使用时机：在设置源码、编译、查询状态时使用
        int shader = GLES20.glCreateShader(shaderType);
        // ⚠️ 检查着色器创建是否出错
        checkGlError("glCreateShader type=" + shaderType);
        // 📝 将GLSL源码字符串设置到着色器对象
        GLES20.glShaderSource(shader, source);
        // 🔨 编译着色器源码为GPU可执行的机器码
        GLES20.glCompileShader(shader);
        // 📊 compiled：存储编译状态的数组
        // 💡 为什么定义：需要检查编译是否成功
        // 💡 作用：GL_COMPILE_STATUS的结果存储位置
        // 💡 使用时机：在判断编译是否成功（值是否为0）时使用
        int[] compiled = new int[1];
        // 🔍 查询着色器编译状态
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        // ⚠️ 编译失败处理：打印错误日志并清理资源
        if (compiled[0] == 0) {
            Log.e(TAG, "Could not compile shader " + shaderType + ":");
            Log.e(TAG, " " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            shader = 0;
        }
        // ✅ 返回着色器句柄，失败时返回0
        return shader;
    }

    /**
     * Checks to see if a GLES error has been raised.
     * 
     * ⚠️ 检查GL错误，有错误则抛出异常
     */
    public static void checkGlError(String op) {
        // 🔍 error：获取当前GL错误码
        // 💡 为什么定义：GL操作可能出错，需要及时检测
        // 💡 作用：存储glGetError()返回的错误码
        // 💡 使用时机：在判断是否有错误（!= GL_NO_ERROR）时使用
        int error = GLES20.glGetError();
        // ⚠️ 如果有错误，构建错误信息并抛出异常
        if (error != GLES20.GL_NO_ERROR) {
            // 📝 msg：格式化的错误信息字符串
            // 💡 为什么定义：需要记录哪个操作出错及错误码
            // 💡 作用：拼接操作名称和十六进制错误码，便于调试
            // 💡 使用时机：在打印日志和抛出异常时使用
            String msg = op + ": glError 0x" + Integer.toHexString(error);
            Log.e(TAG, msg);
            throw new RuntimeException(msg);
        }
    }

    /**
     * Checks to see if the location we obtained is valid.
     * 
     * ✅ 检查着色器变量位置是否有效
     */
    public static void checkLocation(int location, String label) {
        if (location < 0) {
            throw new RuntimeException("Unable to locate '" + label + "' in program");
        }
    }

    /**
     * Creates a texture from raw data.
     *
     * 🖼️ 从原始数据创建纹理
     * @return 纹理句柄
     */
    public static int createImageTexture(ByteBuffer data, int width, int height, int format) {
        // 📦 textureHandles：存储生成的纹理句柄的数组
        // 💡 为什么定义：glGenTextures需要数组来接收生成的纹理ID
        // 💡 作用：作为glGenTextures的输出参数
        // 💡 使用时机：在获取纹理句柄textureHandles[0]时使用
        int[] textureHandles = new int[1];
        // 🎮 textureHandle：纹理对象的GL句柄
        // 💡 为什么定义：需要一个单独的变量来持有纹理句柄便于使用
        // 💡 作用：从数组中提取纹理ID，用于后续绑定和配置
        // 💡 使用时机：在glBindTexture和返回值时使用
        int textureHandle;

        // 🏭 生成一个纹理对象
        GLES20.glGenTextures(1, textureHandles, 0);
        textureHandle = textureHandles[0];
        GlUtil.checkGlError("glGenTextures");

        // 🔗 将纹理绑定到GL_TEXTURE_2D目标，后续操作都针对此纹理
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle);

        // 📐 设置纹理缩小滤波方式为线性插值（GL_LINEAR）
        // 💡 当纹理比显示区域小时，使用线性插值平滑过渡
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR);
        // 📐 设置纹理放大滤波方式为线性插值（GL_LINEAR）
        // 💡 当纹理比显示区域大时，使用线性插值平滑过渡
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR);
        GlUtil.checkGlError("loadImageTexture");

        // 🖼️ 将像素数据上传到GPU纹理内存
        // 💡 参数：目标、mipmap级别、内部格式、宽高、边框、源格式、数据类型、像素数据
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, /*level*/ 0, format,
                width, height, /*border*/ 0, format, GLES20.GL_UNSIGNED_BYTE, data);
        GlUtil.checkGlError("loadImageTexture");

        // ✅ 返回纹理句柄，可用于后续渲染绑定
        return textureHandle;
    }

    /**
     * Allocates a direct float buffer, and populates it with the float array data.
     * 
     * 📦 创建直接浮点缓冲区
     */
    public static FloatBuffer createFloatBuffer(float[] coords) {
        // 📦 bb：直接字节缓冲区，分配float数组所需的字节数
        // 💡 为什么定义：OpenGL需要直接缓冲区来高效传输顶点数据到GPU
        // 💡 作用：作为底层字节存储，后续转换为FloatBuffer
        // 💡 使用时机：在asFloatBuffer()转换时使用
        ByteBuffer bb = ByteBuffer.allocateDirect(coords.length * SIZEOF_FLOAT);
        // 🔧 设置字节序为本地字节序，确保跨平台数据一致性
        bb.order(ByteOrder.nativeOrder());
        // 📊 fb：浮点缓冲区视图，将ByteBuffer作为FloatBuffer使用
        // 💡 为什么定义：顶点数据通常以float类型存储，需要FloatBuffer
        // 💡 作用：提供float类型的缓冲区操作接口
        // 💡 使用时机：在put()写入数据和传给GL API时使用
        FloatBuffer fb = bb.asFloatBuffer();
        // 📝 将float数组数据写入缓冲区
        fb.put(coords);
        // 🔄 重置位置到0，准备从头读取数据
        fb.position(0);
        // ✅ 返回填充好数据的FloatBuffer
        return fb;
    }

    /**
     * Writes GL version info to the log.
     * 
     * 📝 记录GL版本信息到日志
     */
    public static void logVersionInfo() {
        // 📝 打印GPU供应商信息（如Qualcomm、ARM、NVIDIA等）
        Log.i(TAG, "vendor  : " + GLES20.glGetString(GLES20.GL_VENDOR));
        // 📝 打印GPU渲染器名称（如Adreno、Mali等）
        Log.i(TAG, "renderer: " + GLES20.glGetString(GLES20.GL_RENDERER));
        // 📝 打印OpenGL ES版本号（如OpenGL ES 3.0）
        Log.i(TAG, "version : " + GLES20.glGetString(GLES20.GL_VERSION));

        // 🔒 此代码块默认禁用（if false），仅用于调试GLES3.0版本查询
        if (false) {
            // 📦 values：存储GL查询结果的数组
            // 💡 为什么定义：glGetIntegerv需要数组来接收查询结果
            // 💡 作用：临时存储主版本号或次版本号
            // 💡 使用时机：在读取values[0]获取版本号时使用
            int[] values = new int[1];
            // 🔍 查询OpenGL ES主版本号（如3.x中的3）
            GLES30.glGetIntegerv(GLES30.GL_MAJOR_VERSION, values, 0);
            // 📊 majorVersion：主版本号
            // 💡 为什么定义：需要单独存储用于格式化输出
            // 💡 作用：存储主版本号（如3）
            // 💡 使用时机：在拼接版本字符串时使用
            int majorVersion = values[0];
            // 🔍 查询OpenGL ES次版本号（如x.0中的0）
            GLES30.glGetIntegerv(GLES30.GL_MINOR_VERSION, values, 0);
            // 📊 minorVersion：次版本号
            // 💡 为什么定义：需要单独存储用于格式化输出
            // 💡 作用：存储次版本号（如0）
            // 💡 使用时机：在拼接版本字符串时使用
            int minorVersion = values[0];
            // ⚠️ 检查查询是否成功（GLES3.0可能不支持此查询）
            if (GLES30.glGetError() == GLES30.GL_NO_ERROR) {
                // 📝 打印整数格式的版本号
                Log.i(TAG, "iversion: " + majorVersion + "." + minorVersion);
            }
        }
    }
}
