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
import android.util.Log;

/**
 * Base class for a 2d object.  Includes position, scale, rotation, and flat-shaded color.
 * 
 * 🎨 2D精灵基类：包含位置、缩放、旋转和平面着色
 */
public class Sprite2d {
    private static final String TAG = GlUtil.TAG;

    private Drawable2d mDrawable;           // 🎨 可绘制对象
    private float mColor[];                 // 🎨 颜色 RGBA
    private int mTextureId;                 // 🖼️ 纹理ID
    private float mAngle;                   // 🔄 旋转角度
    private float mScaleX, mScaleY;         // 📐 缩放
    private float mPosX, mPosY;             // 📍 位置

    private float[] mModelViewMatrix;       // 📐 模型视图矩阵
    private boolean mMatrixReady;           // ✅ 矩阵是否需要重新计算

    private float[] mScratchMatrix = new float[16];  // 📐 临时矩阵

    /**
     * 🎨 2D精灵构造函数（初始化精灵状态）
     * 💡 初始化所有成员变量，设置默认值
     * 📌 使用时机：创建精灵对象时调用
     *
     * @param drawable 可绘制对象（Drawable2d）
     *                🎯 作用：定义精灵的几何形状（矩形、三角形等）
     */
    public Sprite2d(Drawable2d drawable) {
        // 🎨 mDrawable：可绘制对象（Drawable2d），定义精灵的几何形状
        // 💡 为什么定义：精灵需要知道绘制什么形状（矩形、三角形等）
        // 💡 作用：在draw()方法中获取顶点数据、纹理坐标等渲染所需信息
        // 💡 使用时机：构造时设置，在draw()中通过getVertexArray()等方法使用
        mDrawable = drawable;

        // 🎨 mColor：RGBA颜色数组（float[4]），存储精灵的平面着色颜色
        // 💡 为什么定义：平面着色模式下需要指定填充颜色
        // 💡 作用：在draw(FlatShadedProgram)中传递给着色器作为片段颜色
        // 💡 格式：[R, G, B, A]，每个分量范围0.0-1.0
        // 💡 使用时机：setColor()设置颜色，draw()中使用
        mColor = new float[4];

        // 🔲 mColor[3]：alpha通道值（默认1.0=完全不透明）
        // 💡 为什么设为1.0：默认情况下精灵应该完全不透明
        // 💡 作用：控制精灵的透明度，0.0=完全透明，1.0=完全不透明
        // 💡 使用时机：在draw()中传递给着色器，影响最终渲染的透明度
        mColor[3] = 1.0f;  // alpha = 1.0

        // 🖼️ mTextureId：纹理对象ID（int），标识精灵使用的纹理
        // 💡 为什么设为-1：-1表示无效/未设置纹理，避免与有效纹理ID冲突
        // 💡 作用：在draw(Texture2dProgram)中绑定纹理进行渲染
        // 💡 使用时机：setTexture()设置纹理ID，draw()中绑定纹理时使用
        mTextureId = -1;

        // 📐 mModelViewMatrix：4x4模型视图矩阵（float[16]），存储精灵的变换信息
        // 💡 为什么定义：需要组合位置、旋转、缩放变换，供渲染使用
        // 💡 作用：在recomputeMatrix()中计算完整变换，draw()中与投影矩阵相乘
        // 💡 使用时机：recomputeMatrix()更新，getModelViewMatrix()获取，draw()中使用
        mModelViewMatrix = new float[16];

        // ✅ mMatrixReady：矩阵就绪标志（boolean），标记矩阵是否需要重新计算
        // 💡 为什么设为false：构造时矩阵尚未计算，需要触发一次recomputeMatrix()
        // 💡 作用：缓存机制，避免每帧重复计算矩阵（位置/旋转/缩放未变时直接返回缓存）
        // 💡 使用时机：位置/旋转/缩放改变时设为false，recomputeMatrix()完成后设为true
        mMatrixReady = false;
    }

    /**
     * Re-computes mModelViewMatrix, based on the current values for rotation, scale, and
     * translation.
     *
     * 📐 重新计算模型视图矩阵（核心变换方法！）
     * 💡 根据当前位置、旋转、缩放值重建变换矩阵
     * 📌 使用时机：位置/旋转/缩放改变后，绘制前自动调用
     * ⚠️ 变换顺序：先平移 → 再旋转 → 最后缩放（OpenGL矩阵从右往左乘）
     */
    private void recomputeMatrix() {
        // 📐 modelView：模型视图矩阵的局部引用（临时变量）
        // 💡 为什么定义：避免重复访问成员变量mModelViewMatrix，提高性能（局部变量访问更快）
        // 💡 作用：作为后续所有矩阵变换操作的目标矩阵
        // 💡 使用时机：在本方法内作为矩阵运算的载体，方法结束后自动销毁
        float[] modelView = mModelViewMatrix;

        // 🔄 Matrix.setIdentityM：设置单位矩阵，清除之前的变换
        // 💡 作用：每次重建都从干净的单位矩阵开始，避免累积旧变换
        // 💡 参数说明：modelView(目标矩阵数组), 0(起始偏移量)
        // 💡 使用时机：矩阵重建的第一步，必须先清零
        Matrix.setIdentityM(modelView, 0);

        // 📍 Matrix.translateM：应用平移变换，移动到指定位置
        // 💡 作用：将精灵从原点移动到(mPosX, mPosY)位置
        // 💡 mPosX：精灵的X轴位置坐标，由setPosition()设置
        // 💡 mPosY：精灵的Y轴位置坐标，由setPosition()设置
        // 💡 参数说明：modelView(矩阵), 0(偏移), mPosX(X位移), mPosY(Y位移), 0.0f(Z位移，2D不需要)
        // 💡 使用时机：平移是第一个变换，因为缩放和旋转都需要围绕中心点进行
        Matrix.translateM(modelView, 0, mPosX, mPosY, 0.0f);

        // 🔄 如果有旋转角度，应用旋转变换
        // 💡 作用：绕Z轴旋转精灵（2D旋转只在Z轴方向）
        // 💡 为什么检查!=0：避免不必要的旋转计算（0度旋转是恒等变换，浪费GPU计算）
        // 💡 mAngle：旋转角度（度数），由setRotation()设置，已归一化到[-360,360]
        if (mAngle != 0.0f) {
            // 💡 Matrix.rotateM：旋转变换
            // 💡 参数说明：modelView(矩阵), 0(偏移), mAngle(旋转角度),
            //              0.0f(X轴分量), 0.0f(Y轴分量), 1.0f(Z轴分量=绕Z轴旋转)
            Matrix.rotateM(modelView, 0, mAngle, 0.0f, 0.0f, 1.0f);
        }

        // 📐 Matrix.scaleM：应用缩放变换，缩放精灵大小
        // 💡 作用：将精灵缩放到(mScaleX, mScaleY)大小
        // 💡 mScaleX：X轴缩放比例，由setScale()设置
        // 💡 mScaleY：Y轴缩放比例，由setScale()设置
        // 💡 参数说明：modelView(矩阵), 0(偏移), mScaleX(X缩放), mScaleY(Y缩放), 1.0f(Z缩放，2D保持1)
        // 💡 使用时机：缩放是最后一个变换（矩阵乘法从右往左，缩放最先执行）
        Matrix.scaleM(modelView, 0, mScaleX, mScaleY, 1.0f);

        // ✅ mMatrixReady：矩阵就绪标志（布尔类型）
        // 💡 为什么定义：避免重复计算矩阵，提高性能（缓存机制）
        // 💡 作用：标记矩阵已计算完成，下次getModelViewMatrix()直接返回缓存
        // 💡 使用时机：矩阵计算完成后立即设为true，位置/旋转/缩放改变时设为false
        mMatrixReady = true;
    }

    /** 📐 获取X轴缩放 */
    public float getScaleX() { return mScaleX; }
    /** 📐 获取Y轴缩放 */
    public float getScaleY() { return mScaleY; }

    /**
     * Sets the sprite scale (size).
     *
     * 📐 设置精灵缩放比例（缩放变换配置方法！）
     * 💡 修改精灵在X轴和Y轴方向的缩放倍数
     * 📌 使用时机：需要改变精灵大小时调用
     *
     * @param scaleX X轴缩放比例（float），正值为放大，负值为翻转
     * @param scaleY Y轴缩放比例（float），正值为放大，负值为翻转
     */
    public void setScale(float scaleX, float scaleY) {
        // 📐 mScaleX：X轴缩放比例（float）
        // 💡 为什么定义：控制精灵在水平方向的缩放倍数
        // 💡 作用：在recomputeMatrix()中作为Matrix.scaleM()的X轴缩放参数
        // 💡 使用时机：在recomputeMatrix()计算变换矩阵时使用
        mScaleX = scaleX;
        // 📐 mScaleY：Y轴缩放比例（float）
        // 💡 为什么定义：控制精灵在垂直方向的缩放倍数
        // 💡 作用：在recomputeMatrix()中作为Matrix.scaleM()的Y轴缩放参数
        // 💡 使用时机：在recomputeMatrix()计算变换矩阵时使用
        mScaleY = scaleY;
        // 🔄 mMatrixReady：矩阵就绪标志（boolean）
        // 💡 为什么置false：缩放改变后，旧矩阵不再反映最新缩放状态
        // 💡 作用：标记模型视图矩阵需要重新计算
        // 💡 使用时机：下次调用getModelViewMatrix()时会触发recomputeMatrix()
        mMatrixReady = false;
    }

    /** 🔄 获取旋转角度 */
    public float getRotation() { return mAngle; }

    /**
     * Sets the sprite rotation angle, in degrees.  Sprite will rotate counter-clockwise.
     *
     * 🔄 设置旋转角度（逆时针方向旋转）
     * 💡 角度范围：任意实数，内部会自动归一化到[-360, 360]
     * 📌 使用时机：需要改变精灵朝向时调用
     *
     * @param angle 旋转角度（度数），正值逆时针旋转
     */
    public void setRotation(float angle) {
        // 🔄 角度归一化：将角度限制到[-360, 360]范围
        // 💡 angle：传入的旋转角度参数（单位：度）
        // 💡 为什么归一化：避免角度无限累积导致浮点数溢出，确保旋转计算稳定
        // 💡 第一个while：如果角度>=360，减去360（处理正角度过大）
        // 💡 第二个while：如果角度<=-360，加上360（处理负角度过大）
        // 💡 使用时机：每次设置旋转时都必须先归一化
        while (angle >= 360.0f) { angle -= 360.0f; }
        while (angle <= -360.0f) { angle += 360.0f; }
        // 📝 mAngle：存储精灵当前的旋转角度（单位：度，浮点数）
        // 💡 为什么定义：记录精灵的旋转状态，供渲染时计算旋转变换矩阵
        // 💡 作用：在recomputeMatrix()中作为Matrix.rotateM()的角度参数
        // 💡 使用时机：在recomputeMatrix()绘制变换和toString()调试输出中使用
        mAngle = angle;
        // 🔄 mMatrixReady：矩阵就绪标志（布尔类型）
        // 💡 为什么定义：缓存机制，避免每帧重复计算矩阵
        // 💡 作用：标记模型视图矩阵是否有效（true=有效可直接使用，false=需要重新计算）
        // 💡 为什么置false：角度改变后，旧矩阵不再反映最新旋转状态
        // 💡 使用时机：下次调用getModelViewMatrix()时会检测到false并触发recomputeMatrix()
        mMatrixReady = false;
    }

    /** 📍 获取X坐标 */
    public float getPositionX() { return mPosX; }
    /** 📍 获取Y坐标 */
    public float getPositionY() { return mPosY; }

    /**
     * Sets the sprite position.
     *
     * 📍 设置精灵位置坐标（平移变换配置方法！）
     * 💡 将精灵移动到指定的世界坐标位置
     * 📌 使用时机：需要改变精灵位置时调用
     *
     * @param posX X轴位置坐标（float），世界坐标系中的水平位置
     * @param posY Y轴位置坐标（float），世界坐标系中的垂直位置
     */
    public void setPosition(float posX, float posY) {
        // 📍 mPosX：精灵的X轴位置坐标（float）
        // 💡 为什么定义：记录精灵在世界坐标系中的水平位置
        // 💡 作用：在recomputeMatrix()中作为Matrix.translateM()的X位移参数
        // 💡 使用时机：在recomputeMatrix()计算变换矩阵时使用
        mPosX = posX;
        // 📍 mPosY：精灵的Y轴位置坐标（float）
        // 💡 为什么定义：记录精灵在世界坐标系中的垂直位置
        // 💡 作用：在recomputeMatrix()中作为Matrix.translateM()的Y位移参数
        // 💡 使用时机：在recomputeMatrix()计算变换矩阵时使用
        mPosY = posY;
        // 🔄 mMatrixReady：矩阵就绪标志（boolean）
        // 💡 为什么置false：位置改变后，旧矩阵不再反映最新位置状态
        // 💡 作用：标记模型视图矩阵需要重新计算
        // 💡 使用时机：下次调用getModelViewMatrix()时会触发recomputeMatrix()
        mMatrixReady = false;
    }

    /**
     * Returns the model-view matrix.
     *
     * 📐 获取模型视图矩阵（懒加载模式！）
     * 💡 如果矩阵未就绪（mMatrixReady=false），自动调用recomputeMatrix()重新计算
     * 📌 使用时机：绘制前获取变换矩阵时调用
     *
     * @return 4x4模型视图矩阵（float[16]）
     */
    public float[] getModelViewMatrix() {
        // 🔍 mMatrixReady：矩阵就绪标志检查（boolean）
        // 💡 为什么检查：缓存机制，避免重复计算矩阵（位置/旋转/缩放未变时直接返回缓存）
        // 💡 作用：true表示矩阵有效可直接使用，false表示需要重新计算
        // 💡 使用时机：每次获取矩阵前必须检查
        if (!mMatrixReady) { recomputeMatrix(); }
        // 📤 mModelViewMatrix：返回4x4模型视图矩阵（float[16]）
        // 💡 作用：包含完整的平移、旋转、缩放变换，供绘制时与投影矩阵相乘
        // 💡 使用时机：在draw()方法中与projectionMatrix相乘得到MVP矩阵
        return mModelViewMatrix;
    }

    /**
     * Sets color to use for flat-shaded rendering.
     *
     * 🎨 设置平面着色颜色（纯色渲染配置！）
     * 💡 设置FlatShadedProgram渲染时使用的填充颜色
     * 📌 使用时机：需要改变纯色精灵颜色时调用
     *
     * @param red 红色分量（float，范围0.0-1.0）
     * @param green 绿色分量（float，范围0.0-1.0）
     * @param blue 蓝色分量（float，范围0.0-1.0）
     */
    public void setColor(float red, float green, float blue) {
        // 🔴 mColor[0]：红色分量（float，范围0.0-1.0）
        // 💡 为什么设置：平面着色需要指定填充颜色的RGB分量
        // 💡 作用：在draw(FlatShadedProgram)中传递给片段着色器作为片段颜色
        // 💡 使用时机：在draw()中传递给着色器
        mColor[0] = red;
        // 🟢 mColor[1]：绿色分量（float，范围0.0-1.0）
        // 💡 作用：平面着色颜色的绿色通道值
        // 💡 使用时机：在draw()中传递给着色器
        mColor[1] = green;
        // 🔵 mColor[2]：蓝色分量（float，范围0.0-1.0）
        // 💡 作用：平面着色颜色的蓝色通道值
        // 💡 使用时机：在draw()中传递给着色器
        mColor[2] = blue;
    }

    /**
     * Sets texture to use for textured rendering.
     *
     * 🖼️ 设置纹理对象ID（纹理渲染配置！）
     * 💡 将纹理绑定到精灵，用于Texture2dProgram绘制
     * 📌 使用时机：创建纹理后，绘制前调用
     *
     * @param textureId 纹理对象ID（int），由Texture2dProgram.createTextureObject()创建
     */
    public void setTexture(int textureId) {
        // 🖼️ mTextureId：纹理对象ID（int）
        // 💡 为什么定义：标识精灵使用的纹理对象，用于绘制时绑定纹理
        // 💡 作用：在draw(Texture2dProgram)中传递给program.draw()进行纹理绑定
        // 💡 使用时机：在纹理绘制方法draw(Texture2dProgram)中使用
        mTextureId = textureId;
    }

    /** 🎨 获取颜色 */
    public float[] getColor() { return mColor; }

    /**
     * Draws the rectangle with the supplied program and projection matrix.
     *
     * 🖼️ 使用平面着色程序绘制（纯色渲染！）
     * 💡 使用FlatShadedProgram进行单色渲染，不使用纹理
     * 📌 使用时机：需要绘制纯色精灵时（如纯色矩形、三角形）
     *
     * @param program 平面着色程序对象
     *               🎯 作用：提供顶点着色器和片段着色器，实现纯色填充
     * @param projectionMatrix 投影矩阵（4x4）
     *                        📐 作用：将世界坐标转换到屏幕坐标
     */
    public void draw(FlatShadedProgram program, float[] projectionMatrix) {
        // 📐 Matrix.multiplyMM：计算最终变换矩阵 = 投影矩阵 × 模型视图矩阵
        // 💡 mScratchMatrix：临时矩阵（float[16]），存储MVP变换结果
        // 💡 为什么用临时矩阵：避免频繁分配内存，复用已有数组提高性能
        // 💡 作用：将顶点从模型空间 → 世界空间 → 屏幕空间的完整变换
        // 💡 参数说明：mScratchMatrix(结果矩阵), 0(结果偏移),
        //              projectionMatrix(左矩阵=投影), 0(偏移),
        //              getModelViewMatrix()(右矩阵=模型视图), 0(偏移)
        // 💡 使用时机：每次绘制前必须计算，因为投影或模型矩阵可能改变
        Matrix.multiplyMM(mScratchMatrix, 0, projectionMatrix, 0, getModelViewMatrix(), 0);

        // 🖼️ program.draw：调用着色程序进行绘制
        // 💡 作用：将顶点数据和颜色传递给GPU，执行渲染
        // 💡 参数说明：
        //    - mScratchMatrix：最终MVP变换矩阵，用于顶点变换
        //    - mColor：RGBA颜色数组（4个float），用于片段着色器填充颜色
        //    - mDrawable.getVertexArray()：顶点坐标FloatBuffer，定义形状
        //    - 0：起始顶点索引（从第0个顶点开始）
        //    - mDrawable.getVertexCount()：顶点数量（三角形=3，矩形=4）
        //    - mDrawable.getCoordsPerVertex()：每顶点坐标数（2=x,y 或 3=x,y,z）
        //    - mDrawable.getVertexStride()：顶点数据步长（字节数，通常=coordsPerVertex*4）
        // 💡 使用时机：完成矩阵计算后，立即执行绘制
        program.draw(mScratchMatrix, mColor, mDrawable.getVertexArray(), 0,
                mDrawable.getVertexCount(), mDrawable.getCoordsPerVertex(),
                mDrawable.getVertexStride());
    }

    /**
     * Draws the rectangle with the supplied program and projection matrix.
     *
     * 🖼️ 使用纹理程序绘制（带纹理渲染！）
     * 💡 使用Texture2dProgram进行纹理渲染，支持2D纹理和外部纹理
     * 📌 使用时机：需要绘制带纹理的精灵时（如图片、视频帧）
     *
     * @param program 纹理程序对象
     *               🎯 作用：提供纹理渲染所需的着色器和纹理处理
     * @param projectionMatrix 投影矩阵（4x4）
     *                        📐 作用：将世界坐标转换到屏幕坐标
     */
    public void draw(Texture2dProgram program, float[] projectionMatrix) {
        // 📐 Matrix.multiplyMM：计算最终变换矩阵 = 投影矩阵 × 模型视图矩阵
        // 💡 mScratchMatrix：临时矩阵（float[16]），复用避免频繁分配内存
        // 💡 作用：组合投影和模型变换，得到MVP矩阵
        // 💡 使用时机：每次绘制前必须计算最新变换
        Matrix.multiplyMM(mScratchMatrix, 0, projectionMatrix, 0, getModelViewMatrix(), 0);

        // 🖼️ program.draw：调用纹理着色程序进行绘制
        // 💡 作用：将顶点数据、纹理坐标和纹理对象传递给GPU，执行纹理渲染
        // 💡 参数说明：
        //    - mScratchMatrix：MVP变换矩阵（4x4），用于顶点位置变换
        //    - mDrawable.getVertexArray()：顶点坐标FloatBuffer，定义形状位置
        //    - 0：起始顶点索引（从第0个顶点开始绘制）
        //    - mDrawable.getVertexCount()：顶点数量（三角形=3，矩形=4）
        //    - mDrawable.getCoordsPerVertex()：每顶点坐标数（2=x,y 或 3=x,y,z）
        //    - mDrawable.getVertexStride()：顶点数据字节步长
        //    - GlUtil.IDENTITY_MATRIX：纹理变换矩阵（单位矩阵=不变换纹理坐标）
        //    - mDrawable.getTexCoordArray()：纹理坐标FloatBuffer，定义纹理映射
        //    - mTextureId：纹理对象ID，由setTexture()设置，-1表示无纹理
        //    - mDrawable.getTexCoordStride()：纹理坐标字节步长
        // 💡 使用时机：完成矩阵计算后，立即执行纹理绘制
        program.draw(mScratchMatrix, mDrawable.getVertexArray(), 0,
                mDrawable.getVertexCount(), mDrawable.getCoordsPerVertex(),
                mDrawable.getVertexStride(), GlUtil.IDENTITY_MATRIX, mDrawable.getTexCoordArray(),
                mTextureId, mDrawable.getTexCoordStride());
    }

    /**
     * Returns a string representation of the object.
     *
     * 📝 返回对象的字符串表示（调试利器！）
     * 💡 用于调试时查看精灵的完整状态信息
     * 📌 使用时机：Log.d()输出或调试器中查看对象信息
     *
     * @return 包含位置、缩放、角度、颜色和drawable信息的字符串
     */
    @Override
    public String toString() {
        // 📝 字符串拼接：将精灵的所有属性格式化为可读字符串
        // 💡 包含：位置(mPosX,mPosY)、缩放(mScaleX,mScaleY)、角度(mAngle)、颜色(mColor)、drawable类型
        // 💡 mPosX, mPosY：精灵的位置坐标（float），由setPosition()设置
        // 💡 mScaleX, mScaleY：缩放比例（float），由setScale()设置
        // 💡 mAngle：旋转角度（float，度），由setRotation()设置
        // 💡 mColor[0..2]：RGB颜色分量（float），由setColor()设置
        // 💡 mDrawable：可绘制对象类型（Drawable2d），构造时传入
        return "[Sprite2d pos=" + mPosX + "," + mPosY +       // 📍 位置坐标
                " scale=" + mScaleX + "," + mScaleY +         // 📐 缩放比例
                " angle=" + mAngle +                           // 🔄 旋转角度
                " color={" + mColor[0] + "," + mColor[1] + "," + mColor[2] + // 🎨 RGB颜色
                "} drawable=" + mDrawable + "]";               // 🖼️ 可绘制对象类型
    }

}
