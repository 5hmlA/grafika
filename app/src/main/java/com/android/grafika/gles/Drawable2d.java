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

import java.nio.FloatBuffer;

/**
 * Base class for stuff we like to draw.
 * 
 * 🎨 2D可绘制对象的基类
 * 💡 定义了三角形、矩形和全屏矩形的顶点数据
 */
public class Drawable2d {
    // 📏 SIZEOF_FLOAT：float类型的字节大小（4字节）
    // 💡 在OpenGL中，每个float占用4个字节
    private static final int SIZEOF_FLOAT = 4;

    /**
     * Simple equilateral triangle (1.0 per side).  Centered on (0,0).
     * 
     * 🔺 简单的等边三角形（每边1.0），以(0,0)为中心
     * 💡 顶点坐标按逆时针顺序排列
     */
    private static final float TRIANGLE_COORDS[] = {
         0.0f,  0.577350269f,   // 0 top        # 🔺 顶部顶点
        -0.5f, -0.288675135f,   // 1 bottom left  # 🔺 左下顶点
         0.5f, -0.288675135f    // 2 bottom right # 🔺 右下顶点
    };
    
    // 🎨 TRIANGLE_TEX_COORDS：三角形的纹理坐标
    // 💡 纹理坐标范围是0.0到1.0
    private static final float TRIANGLE_TEX_COORDS[] = {
        0.5f, 0.0f,     // 0 top center      # 🎨 顶部中心纹理坐标
        0.0f, 1.0f,     // 1 bottom left     # 🎨 左下纹理坐标
        1.0f, 1.0f,     // 2 bottom right    # 🎨 右下纹理坐标
    };
    
    // 📦 TRIANGLE_BUF：三角形顶点数据的FloatBuffer
    private static final FloatBuffer TRIANGLE_BUF =
            GlUtil.createFloatBuffer(TRIANGLE_COORDS);
    // 📦 TRIANGLE_TEX_BUF：三角形纹理坐标数据的FloatBuffer
    private static final FloatBuffer TRIANGLE_TEX_BUF =
            GlUtil.createFloatBuffer(TRIANGLE_TEX_COORDS);

    /**
     * Simple square, specified as a triangle strip.  The square is centered on (0,0) and has
     * a size of 1x1.
     * <p>
     * Triangles are 0-1-2 and 2-1-3 (counter-clockwise winding).
     * 
     * 🟥 简单的正方形，使用三角形条带（triangle strip）方式绘制
     * 💡 以(0,0)为中心，大小为1x1
     * 💡 两个三角形：0-1-2 和 2-1-3（逆时针缠绕）
     */
    private static final float RECTANGLE_COORDS[] = {
        -0.5f, -0.5f,   // 0 bottom left    # 🟥 左下角
         0.5f, -0.5f,   // 1 bottom right   # 🟥 右下角
        -0.5f,  0.5f,   // 2 top left       # 🟥 左上角
         0.5f,  0.5f,   // 3 top right      # 🟥 右上角
    };
    
    // 🎨 RECTANGLE_TEX_COORDS：正方形的纹理坐标
    // 💡 左下角为(0,1)，右上角为(1,0)
    private static final float RECTANGLE_TEX_COORDS[] = {
        0.0f, 1.0f,     // 0 bottom left    # 🎨 左下纹理坐标
        1.0f, 1.0f,     // 1 bottom right   # 🎨 右下纹理坐标
        0.0f, 0.0f,     // 2 top left       # 🎨 左上纹理坐标
        1.0f, 0.0f      // 3 top right      # 🎨 右上纹理坐标
    };
    
    // 📦 RECTANGLE_BUF：正方形顶点数据的FloatBuffer
    private static final FloatBuffer RECTANGLE_BUF =
            GlUtil.createFloatBuffer(RECTANGLE_COORDS);
    // 📦 RECTANGLE_TEX_BUF：正方形纹理坐标数据的FloatBuffer
    private static final FloatBuffer RECTANGLE_TEX_BUF =
            GlUtil.createFloatBuffer(RECTANGLE_TEX_COORDS);

    /**
     * A "full" square, extending from -1 to +1 in both dimensions.  When the model/view/projection
     * matrix is identity, this will exactly cover the viewport.
     * <p>
     * The texture coordinates are Y-inverted relative to RECTANGLE.  (This seems to work out
     * right with external textures from SurfaceTexture.)
     * 
     * 🟥 一个"完整"的正方形，在两个维度上从-1延伸到+1
     * 💡 当模型/视图/投影矩阵是单位矩阵时，它正好覆盖整个视口
     * 💡 纹理坐标相对于RECTANGLE是Y轴翻转的（这与SurfaceTexture的外部纹理配合得很好）
     */
    private static final float FULL_RECTANGLE_COORDS[] = {
        -1.0f, -1.0f,   // 0 bottom left    # 🟥 左下角
         1.0f, -1.0f,   // 1 bottom right   # 🟥 右下角
        -1.0f,  1.0f,   // 2 top left       # 🟥 左上角
         1.0f,  1.0f,   // 3 top right      # 🟥 右上角
    };
    
    // 🎨 FULL_RECTANGLE_TEX_COORDS：全屏矩形的纹理坐标
    // 💡 Y轴相对于RECTANGLE翻转（从上到下是0到1）
    private static final float FULL_RECTANGLE_TEX_COORDS[] = {
        0.0f, 0.0f,     // 0 bottom left    # 🎨 左下纹理坐标
        1.0f, 0.0f,     // 1 bottom right   # 🎨 右下纹理坐标
        0.0f, 1.0f,     // 2 top left       # 🎨 左上纹理坐标
        1.0f, 1.0f      // 3 top right      # 🎨 右上纹理坐标
    };
    
    // 📦 FULL_RECTANGLE_BUF：全屏矩形顶点数据的FloatBuffer
    private static final FloatBuffer FULL_RECTANGLE_BUF =
            GlUtil.createFloatBuffer(FULL_RECTANGLE_COORDS);
    // 📦 FULL_RECTANGLE_TEX_BUF：全屏矩形纹理坐标数据的FloatBuffer
    private static final FloatBuffer FULL_RECTANGLE_TEX_BUF =
            GlUtil.createFloatBuffer(FULL_RECTANGLE_TEX_COORDS);


    // 📊 成员变量
    private FloatBuffer mVertexArray;      // 📦 顶点坐标数组
    private FloatBuffer mTexCoordArray;    // 🎨 纹理坐标数组
    private int mVertexCount;              // 🔢 顶点数量
    private int mCoordsPerVertex;          // 📐 每个顶点的坐标数（2或3）
    private int mVertexStride;             // 📏 顶点数据步长（字节数）
    private int mTexCoordStride;           // 📏 纹理坐标步长（字节数）
    private Prefab mPrefab;                // 🏷️ 预制形状类型

    /**
     * Enum values for constructor.
     * 
     * 🔧 构造函数使用的枚举值
     * 💡 用于指定预制的形状类型
     */
    public enum Prefab {
        TRIANGLE,           // 🔺 三角形
        RECTANGLE,          // 🟥 正方形
        FULL_RECTANGLE      // 🟥 全屏矩形
    }

    /**
     * Prepares a drawable from a "pre-fabricated" shape definition.
     * <p>
     * Does no EGL/GL operations, so this can be done at any time.
     *
     * 🔧 从预制形状定义准备一个可绘制对象（形状初始化方法！）
     * 💡 不执行任何EGL/GL操作，所以可以在任何时候调用（甚至在GL上下文创建前）
     * 📌 使用时机：创建2D精灵时，指定要绘制的形状类型
     *
     * @param shape 预制形状类型（TRIANGLE、RECTANGLE或FULL_RECTANGLE）
     */
    public Drawable2d(Prefab shape) {
        // 🔀 switch：根据形状类型初始化不同的顶点数据
        // 💡 作用：将预定义的静态顶点数据关联到实例成员变量
        // 💡 为什么用switch：形状类型固定且有限，switch更清晰高效
        switch (shape) {
            case TRIANGLE:
                // 🔺 TRIANGLE：等边三角形（每边1.0，以(0,0)为中心）
                // 💡 mVertexArray：顶点坐标FloatBuffer（FloatBuffer类型）
                // 💡 为什么定义：存储三角形3个顶点的(x,y)坐标
                // 💡 作用：在draw()中传递给着色器进行顶点变换
                // 💡 使用时机：在getVertexArray()返回，Sprite2d.draw()中使用
                mVertexArray = TRIANGLE_BUF;
                // 💡 mTexCoordArray：纹理坐标FloatBuffer（FloatBuffer类型）
                // 💡 为什么定义：存储三角形3个顶点的(u,v)纹理坐标
                // 💡 作用：在纹理绘制时，指定每个顶点对应的纹理位置
                // 💡 使用时机：在getTexCoordArray()返回，Sprite2d.draw()中使用
                mTexCoordArray = TRIANGLE_TEX_BUF;
                // 💡 mCoordsPerVertex：每个顶点的坐标数（int，值为2）
                // 💡 为什么定义：告诉OpenGL每个顶点有几个坐标分量
                // 💡 作用：在draw()中作为glVertexAttribPointer的size参数
                // 💡 使用时机：在getCoordsPerVertex()返回，绘制时使用
                mCoordsPerVertex = 2;
                // 💡 mVertexStride：顶点数据字节步长（int，值为8=2*4）
                // 💡 为什么定义：告诉OpenGL顶点数据之间的字节间隔
                // 💡 计算方式：mCoordsPerVertex * SIZEOF_FLOAT = 2 * 4 = 8字节
                // 💡 作用：在draw()中作为glVertexAttribPointer的stride参数
                // 💡 使用时机：在getVertexStride()返回，绘制时使用
                mVertexStride = mCoordsPerVertex * SIZEOF_FLOAT;
                // 💡 mVertexCount：顶点数量（int，值为3）
                // 💡 为什么定义：告诉OpenGL要绘制几个顶点
                // 💡 计算方式：TRIANGLE_COORDS.length / mCoordsPerVertex = 6 / 2 = 3
                // 💡 作用：在draw()中作为glDrawArrays的count参数
                // 💡 使用时机：在getVertexCount()返回，绘制时使用
                mVertexCount = TRIANGLE_COORDS.length / mCoordsPerVertex;
                break;
            case RECTANGLE:
                // 🟥 RECTANGLE：1x1正方形（以(0,0)为中心，使用三角形条带绘制）
                // 💡 mVertexArray：正方形顶点坐标（4个顶点，8个float）
                mVertexArray = RECTANGLE_BUF;
                // 💡 mTexCoordArray：正方形纹理坐标（4个顶点，8个float）
                mTexCoordArray = RECTANGLE_TEX_BUF;
                // 💡 mCoordsPerVertex：每个顶点2个坐标（x, y）
                mCoordsPerVertex = 2;
                // 💡 mVertexStride：步长 = 2 * 4 = 8字节
                mVertexStride = mCoordsPerVertex * SIZEOF_FLOAT;
                // 💡 mVertexCount：顶点数 = 8 / 2 = 4
                mVertexCount = RECTANGLE_COORDS.length / mCoordsPerVertex;
                break;
            case FULL_RECTANGLE:
                // 🟥 FULL_RECTANGLE：全屏矩形（从-1到+1，覆盖整个视口）
                // 💡 mVertexArray：全屏矩形顶点坐标（4个顶点，8个float）
                mVertexArray = FULL_RECTANGLE_BUF;
                // 💡 mTexCoordArray：全屏矩形纹理坐标（Y轴翻转，适配SurfaceTexture）
                mTexCoordArray = FULL_RECTANGLE_TEX_BUF;
                // 💡 mCoordsPerVertex：每个顶点2个坐标（x, y）
                mCoordsPerVertex = 2;
                // 💡 mVertexStride：步长 = 2 * 4 = 8字节
                mVertexStride = mCoordsPerVertex * SIZEOF_FLOAT;
                // 💡 mVertexCount：顶点数 = 8 / 2 = 4
                mVertexCount = FULL_RECTANGLE_COORDS.length / mCoordsPerVertex;
                break;
            default:
                // ❌ 未知形状类型，抛出运行时异常
                // 💡 作用：防止枚举新增值但未处理的情况
                throw new RuntimeException("Unknown shape " + shape);
        }
        // 📏 mTexCoordStride：纹理坐标字节步长（int，值为8=2*4）
        // 💡 为什么定义：告诉OpenGL纹理坐标数据之间的字节间隔
        // 💡 作用：在draw()中作为glVertexAttribPointer的stride参数
        // 💡 使用时机：在getTexCoordStride()返回，纹理绘制时使用
        mTexCoordStride = 2 * SIZEOF_FLOAT;
        // 🏷️ mPrefab：保存形状类型枚举（Prefab类型）
        // 💡 为什么定义：记录此Drawable2d实例的形状类型
        // 💡 作用：在toString()中用于调试输出
        // 💡 使用时机：调试和日志输出时使用
        mPrefab = shape;
    }

    /**
     * Returns the array of vertices.
     * <p>
     * To avoid allocations, this returns internal state.  The caller must not modify it.
     * 
     * 📤 获取顶点坐标数组
     * 💡 为了避免内存分配，返回的是内部状态，调用者不能修改
     * 
     * @return 顶点坐标FloatBuffer
     */
    public FloatBuffer getVertexArray() {
        return mVertexArray;
    }

    /**
     * Returns the array of texture coordinates.
     * <p>
     * To avoid allocations, this returns internal state.  The caller must not modify it.
     * 
     * 📤 获取纹理坐标数组
     * 💡 为了避免内存分配，返回的是内部状态，调用者不能修改
     * 
     * @return 纹理坐标FloatBuffer
     */
    public FloatBuffer getTexCoordArray() {
        return mTexCoordArray;
    }

    /**
     * Returns the number of vertices stored in the vertex array.
     * 
     * 📤 获取顶点数组中存储的顶点数量
     * 
     * @return 顶点数量
     */
    public int getVertexCount() {
        return mVertexCount;
    }

    /**
     * Returns the width, in bytes, of the data for each vertex.
     * 
     * 📤 获取每个顶点数据的宽度（字节数）
     * 💡 例如：2个float * 4字节 = 8字节
     * 
     * @return 顶点步长（字节数）
     */
    public int getVertexStride() {
        return mVertexStride;
    }

    /**
     * Returns the width, in bytes, of the data for each texture coordinate.
     * 
     * 📤 获取每个纹理坐标数据的宽度（字节数）
     * 💡 例如：2个float * 4字节 = 8字节
     * 
     * @return 纹理坐标步长（字节数）
     */
    public int getTexCoordStride() {
        return mTexCoordStride;
    }

    /**
     * Returns the number of position coordinates per vertex.  This will be 2 or 3.
     * 
     * 📤 获取每个顶点的位置坐标数量
     * 💡 返回2（x, y）或3（x, y, z）
     * 
     * @return 每个顶点的坐标数
     */
    public int getCoordsPerVertex() {
        return mCoordsPerVertex;
    }

    /**
     * 📝 转换为字符串表示（调试辅助方法！）
     * 💡 用于调试时显示Drawable2d对象的形状类型信息
     * 📌 使用时机：Log.d()输出或调试器中查看对象信息
     *
     * @return 包含形状类型的字符串，如"[Drawable2d: TRIANGLE]"
     */
    @Override
    public String toString() {
        // 🔍 mPrefab：形状类型枚举（Prefab），可能为null
        // 💡 为什么检查：如果Drawable2d不是通过预制形状创建的，mPrefab可能为null
        if (mPrefab != null) {
            // 📝 返回包含形状类型的字符串
            // 💡 mPrefab.toString()：枚举的字符串表示（如"TRIANGLE"）
            return "[Drawable2d: " + mPrefab + "]";
        } else {
            // 📝 mPrefab为null时返回占位字符串
            // 💡 作用：避免返回null，保持输出一致性
            return "[Drawable2d: ...]";
        }
    }
}
